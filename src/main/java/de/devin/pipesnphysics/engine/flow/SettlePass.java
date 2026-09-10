package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeGates;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.Set;

/**
 * Settles every edge the brigade did NOT flow this tick (see {@link SettlingRun} for the physics)
 * plus the junction/shut-valve buffer slots, and clears their scroll stamps. A held or backed-up
 * run — a pump pressing a shut gate or a full sink, a dead conduit against a full tank — settles
 * FILL-ONLY: pressure keeps packing the line toward its reachable ceiling, but never lets it
 * drain back out.
 */
public final class SettlePass {
    private final FlowNetwork network;
    private final FlowLedger ledger;
    private final Solution solution;

    public SettlePass(FlowNetwork network, FlowLedger ledger, Solution solution) {
        this.network = network;
        this.ledger = ledger;
        this.solution = solution;
    }

    public void execute(Set<Integer> flowedEdges) {
        if (network.cellCapacity <= 0) return;
        for (Edge edge : network.graph.edges()) {
            if (flowedEdges.contains(edge.index())) {
                // A flowing run still PRESSURIZES: its submerged cells top up from the end
                // reservoirs toward the waterline alongside the flow (fill-only, no
                // redistribution — see SettlingRun.topUp), source-side-first. Stamps stay.
                // Read the brigade's movement BEFORE topUp adds settle moves to the ledger:
                // only a run that REALLY carried fluid also sheds toward the grade line — a
                // stalled or dead-headed line keeps its packed column.
                boolean carried = ledger.edgeMovedMb()[edge.index()] > 0;
                ledger.note(edge, Solution.SettleNote.FLOWING);
                SettlingRun run = new SettlingRun(network, ledger, solution, edge, false);
                boolean moved = run.topUp();
                if (carried) {
                    moved |= run.shed(FlowNetwork.flowDepthMb(
                            solvedRate(edge.index()), network.cellCapacity));
                }
                if (moved) ledger.markSettling();
                continue;
            }
            if (new SettlingRun(network, ledger, solution, edge, solution.isBackedUp(edge.index())).settle()) {
                ledger.markSettling();
            }
            network.clearFlowStamps(edge);
        }
        for (Node node : network.graph.nodes()) {
            if (node.isJunction() || node.isClosedGate()) settleSlot(node, flowedEdges);
        }
    }

    /** The strongest solved rate over this edge across the tick's fluid passes. */
    private int solvedRate(int edgeIndex) {
        double strongest = 0;
        for (Solution.FlowPass pass : solution.passes()) {
            strongest = Math.max(strongest, Math.abs(pass.edgeFlow()[edgeIndex]));
        }
        return (int) Math.round(strongest);
    }

    /**
     * A junction/gate buffer settles against its own node head, exchanging with the adjacent edge
     * end cells — this is what fills (and renders) a dead-end cell pressed against a solid block.
     */
    private void settleSlot(Node node, Set<Integer> flowedEdges) {
        PipeStore.Store slot = network.slotAt(node.index());
        if (slot == null) return;
        slot.clearFlow();
        // A gas slot BUBBLES UP instead of taking the waterline target below: that target mixes
        // the node head with world Y, and a gas's INVERTED head reads "drain to 0" — the slot
        // then bled its gas into an idle edge every settle tick while the brigade pushed it back,
        // an endless churn the player saw as the pipe constantly refilling from the top. Buoyant
        // exchange is monotone (gas only ever moves up), so it cannot churn.
        boolean gasFrame = solution.gasHeadNodes().contains(node.index());
        // An EMPTY slot has no fluid of its own to classify by, so the FRAME of its head decides:
        // on a gas network it must still bubble rather than pool, or the lateral spread in
        // poolHeadless bleeds gas into a level stub — the very churn buoyant exchange prevents.
        if (SettlingRun.lighterThanAir(slot.fluid()) || (slot.amount() <= 0 && gasFrame)) {
            bubbleUp(node, slot, flowedEdges);
            return;
        }
        Double head = solution.nodeHeads().get(node.index());
        // A head published by a GAS pass is a buoyancy quantity, not a world elevation (§4), and on
        // a network carrying both fluids the last pass to write wins. Mapped through windowFill it
        // reads far below the cell — "drain to 0" — so a LIQUID slot handed one bleeds its contents
        // into an idle edge every tick while the run pushes them back: an endless slosh, the mirror
        // of the churn bubbleUp guards against, which cannot catch this because it keys on the
        // slot's own fluid. With no usable head the slot pools by plain gravity instead.
        if (head == null || gasFrame) {
            poolHeadless(node, slot, flowedEdges);
            return;
        }
        int target = (int) Math.round(
                network.windowFill(node.pos(), head) * network.cellCapacity);
        int rate = SettlingRun.settleRate(network.cellCapacity);
        for (Edge edge : network.graph.edgesOf(node.index())) {
            // The brigade owns the cells of edges it flowed this tick: exchanging with them here
            // would move fluid outside their exit budgets and trim the slot below its pooled
            // depth, breaking the "a slot conducts only once at flow depth" plug gate next tick.
            if (flowedEdges.contains(edge.index())) continue;
            BlockPos adjacent = PipeGeometry.adjacentCell(network.graph, edge, node.index());
            if (adjacent == null) continue;
            PipeStore.Store cell = neighbourStore(node, edge, adjacent);
            if (cell == null || !crosses(node, adjacent, slot, cell)) continue;
            // A one-way valve slot exchanges only ALONG its direction: pour toward the arrow,
            // pull from behind it — this is the settle's only cross-node path. Mostly shadowed
            // by the display-head sign discipline (a head never spreads backward through the
            // gate, so the slot rarely gets a wrong-side target), but the settle must not lean
            // on a DISPLAY-layer rule for a no-backward-transport property — this guard owns it.
            boolean pourAllowed = node.gateFlow() == null
                    || adjacent.equals(node.pos().relative(node.gateFlow()));
            boolean pullAllowed = node.gateFlow() == null
                    || adjacent.equals(node.pos().relative(node.gateFlow().getOpposite()));
            if (slot.amount() > target && pourAllowed) {
                exchange(edge, slot, cell, Math.min(slot.amount() - target, rate));
            } else if (slot.amount() < target && pullAllowed && cell.amount() > 0
                    && !SettlingRun.lighterThanAir(cell.fluid())) {
                // The mirror guard: never pull a neighbouring cell's GAS toward a liquid target.
                exchange(edge, cell, slot, Math.min(target - slot.amount(), rate));
            }
        }
    }

    /**
     * The fallback for a slot the solve never reached (no node head at all — every endpoint that
     * could name this network's fluid has stopped participating): plain gravity against each
     * adjacent cell, in the MIRRORED frame for a gas. Fluid pours into a neighbour below, takes
     * what drains in from one above, and equalizes with one on the LEVEL — the same trickle and
     * anti-slosh spread a headless RUN already gets ({@link SettlingRun#gravityPool}, "fluid is
     * never frozen in a pipe that physics says should drain").
     *
     * Without it a headless junction is a WALL: this method bailed on the missing head, and a
     * settling run only ever moves within its own cells and its END RESERVOIRS — it never pushes
     * into a slot — so fluid resting in a stub beside such a junction was sealed in with no path
     * out at all. Liquids mostly hide it (some reservoir usually still anchors a head, and if
     * everything empties the run drains downhill anyway); a GAS manifold whose source stops loses
     * its head AND sits at one elevation, so a stopped exhaust line's residue stayed in the pipes
     * for good — the reported TFMG engine rig, whose CO2 sat in the two stubs either side of an
     * empty junction while the engines were starved of air and making none.
     */
    private void poolHeadless(Node node, PipeStore.Store slot, Set<Integer> flowedEdges) {
        int rate = SettlingRun.settleRate(network.cellCapacity);
        double slotY = network.cellCenterY(node.pos());
        for (Edge edge : network.graph.edgesOf(node.index())) {
            if (flowedEdges.contains(edge.index())) continue;
            BlockPos adjacent = PipeGeometry.adjacentCell(network.graph, edge, node.index());
            if (adjacent == null) continue;
            PipeStore.Store cell = neighbourStore(node, edge, adjacent);
            if (cell == null || !crosses(node, adjacent, slot, cell)) continue;
            // Only one of the two holds the fluid that would cross; buoyancy is gravity upside
            // down, so a gas's "downhill" is up.
            FluidStack moving = slot.amount() > 0 ? slot.fluid() : cell.fluid();
            if (moving.isEmpty()) continue;
            double drop = (slotY - network.cellCenterY(adjacent))
                    * (SettlingRun.lighterThanAir(moving) ? -1 : 1);
            boolean pourAllowed = node.gateFlow() == null
                    || adjacent.equals(node.pos().relative(node.gateFlow()));
            boolean pullAllowed = node.gateFlow() == null
                    || adjacent.equals(node.pos().relative(node.gateFlow().getOpposite()));
            if (drop > SettlingRun.SURFACE_EPS) {
                if (pourAllowed) exchange(edge, slot, cell, Math.min(slot.amount(), rate));
            } else if (drop < -SettlingRun.SURFACE_EPS) {
                if (pullAllowed) exchange(edge, cell, slot, Math.min(cell.amount(), rate));
            } else {
                spreadLevel(edge, slot, cell, rate, pourAllowed, pullAllowed);
            }
        }
        // A vessel flush against this junction is no store, so the loop above cannot reach it.
        pourIntoNeighbourReservoirs(node, slot, flowedEdges);
    }

    /**
     * Equalize a slot with a neighbour standing at its own elevation, leaving the shared dregs
     * margin at the boundary so a level pair cannot ping-pong a millibucket back and forth every
     * tick (the run's own {@code spreadLevel} rule, applied across the node).
     */
    private void spreadLevel(Edge edge, PipeStore.Store slot, PipeStore.Store cell, int rate,
                             boolean pourAllowed, boolean pullAllowed) {
        int diff = slot.amount() - cell.amount();
        if (Math.abs(diff) <= Reservoir.DREGS_MB) return;
        if (diff > 0) {
            if (pourAllowed) exchange(edge, slot, cell, Math.min(diff / 2, rate));
        } else if (pullAllowed) {
            exchange(edge, cell, slot, Math.min(-diff / 2, rate));
        }
    }

    /**
     * A junction/gate slot holding a lighter-than-air gas exchanges by BUOYANCY: it pours into
     * the adjacent cell ABOVE it (room permitting) and pulls same-gas up from the cell BELOW —
     * gas bubbles up through a junction toward the vessel over it. Same-height neighbours are
     * left alone (no lateral slosh — the guard the old full freeze provided stays), the one-way
     * gate's direction rules ride along, and edges the brigade flowed this tick stay its.
     */
    private void bubbleUp(Node node, PipeStore.Store slot, Set<Integer> flowedEdges) {
        int rate = SettlingRun.settleRate(network.cellCapacity);
        double slotY = network.cellCenterY(node.pos());
        for (Edge edge : network.graph.edgesOf(node.index())) {
            if (flowedEdges.contains(edge.index())) continue;
            BlockPos adjacent = PipeGeometry.adjacentCell(network.graph, edge, node.index());
            if (adjacent == null) continue;
            PipeStore.Store cell = neighbourStore(node, edge, adjacent);
            if (cell == null || !crosses(node, adjacent, slot, cell)) continue;
            boolean pourAllowed = node.gateFlow() == null
                    || adjacent.equals(node.pos().relative(node.gateFlow()));
            boolean pullAllowed = node.gateFlow() == null
                    || adjacent.equals(node.pos().relative(node.gateFlow().getOpposite()));
            double cellY = network.cellCenterY(adjacent);
            if (cellY > slotY + SettlingRun.SURFACE_EPS && pourAllowed && slot.amount() > 0) {
                exchange(edge, slot, cell, Math.min(slot.amount(), rate));
            } else if (cellY < slotY - SettlingRun.SURFACE_EPS && pullAllowed
                    && SettlingRun.lighterThanAir(cell.fluid())) {
                exchange(edge, cell, slot, Math.min(cell.amount(), rate));
            }
        }
        // Buoyant exchange with a vessel flush against this junction: the gas rises INTO the tank
        // above it, the mirror of the pour below. Monotone like the rest of this method.
        pourIntoNeighbourReservoirs(node, slot, flowedEdges);
    }

    /**
     * The store a node's slot exchanges with across one incident edge, or null where there is none.
     *
     * On an edge WITH cells that is its end cell. On a ZERO-CELL edge {@code adjacentCell} answers
     * the far NODE's position instead, and only a junction or shut valve there is a store of its
     * own — so the neighbour is that node's SLOT, exactly the predicate the brigade already applies
     * ({@code BrigadePass.pullArrivingAt}). A reservoir or an open mouth has none and is reached by
     * {@link #pourIntoNeighbourReservoirs} instead.
     *
     * A PUMP is the one that bit. It stores nothing by design, yet its block entity carries a live
     * {@code PipeStore} with a full cell of room — the content mixin sits on the base
     * {@code FluidTransportBehaviour} its own behaviour extends — so the raw {@code cellAt} these
     * loops used happily poured into it, and NOTHING drains a pump again: no run owns it (a pump is
     * a node, not a cell of any edge), the brigade never touches it, and it renders nowhere. Only
     * the same junction pulling back could recover it. Measured at 150 mB of 250 standing inside
     * the Mechanical Pump of the {@code pumpAgainstJunctionSlotStillDelivers} rig, invisible on
     * every node and edge line of {@code /pipegraph} while the Fluids total still counted it.
     */
    private PipeStore.Store neighbourStore(Node node, Edge edge, BlockPos adjacent) {
        if (adjacent.equals(node.pos())) return null; // a run looping back to its own node
        if (!edge.pipes().isEmpty()) return network.cellAt(adjacent);
        return network.slotAt(edge.other(node.index()));
    }

    /**
     * A slot pours into a reservoir or open mouth sitting directly against it — the slot twin of a
     * headless run's {@code SettlingRun.equalizeWithReservoir} and {@code pourOutOpenEnd}.
     *
     * Across a ZERO-CELL edge the neighbour IS that vessel (a tank flush against a junction, a tee
     * with one arm open to the air), and every exchange above moves between STORES, so such a slot
     * had no outlet in the settle at all: while the solve drives the edge the brigade empties it,
     * but the moment it stops the contents stood there for good — the slot twin of the headless-run
     * hole, and the same "fluid frozen in a pipe that physics says should drain".
     *
     * POUR ONLY, like the run's headless equalize: gravity may empty a slot into a vessel it stands
     * above, but drawing the other way is the solve's business and would fight it. Called from the
     * headless and buoyant paths only — never where a node head is driving the slot — so a slot the
     * solve is steering keeps its target.
     */
    private void pourIntoNeighbourReservoirs(Node node, PipeStore.Store slot, Set<Integer> flowed) {
        if (slot.amount() <= 0) return;
        boolean gas = SettlingRun.lighterThanAir(slot.fluid());
        int rate = SettlingRun.settleRate(network.cellCapacity);
        for (Edge edge : network.graph.edgesOf(node.index())) {
            if (flowed.contains(edge.index()) || !edge.pipes().isEmpty()) continue;
            Reservoir reservoir = network.reservoirAt(edge.other(node.index()));
            if (reservoir == null) continue;
            BlockPos far = network.graph.node(edge.other(node.index())).pos();
            // A one-way gate pours only along its arrow, and a filter walls the slot off exactly
            // as it walls a run.
            if (node.gateFlow() != null && !far.equals(node.pos().relative(node.gateFlow()))) continue;
            if (!PipeGates.conducts(network.level, node.pos(), far, slot.fluid())) continue;
            if (!standsAbove(node.pos(), slot, reservoir, far, gas)) continue;
            int poured = reservoir.fill(slot.fluid(), Math.min(slot.amount(), rate));
            if (poured > 0) {
                slot.extract(poured);
                ledger.moved(edge, poured);
                ledger.markSettling();
            }
            if (slot.amount() <= 0) return;
        }
    }

    /**
     * Whether the slot's fluid stands above the vessel it would pour into, IN THE FLUID'S OWN
     * frame — a buoyant gas pours UP, so every elevation reads negated (§5a gas hydrostatics). A
     * finite reservoir is compared surface to surface; an open MOUTH is a spill threshold rather
     * than a surface, so it takes the mid-height test the run's mouth pour uses.
     */
    private boolean standsAbove(BlockPos slotPos, PipeStore.Store slot, Reservoir reservoir,
                                BlockPos far, boolean gas) {
        if (reservoir.isOpenMouth()) {
            double slotMid = network.cellCenterY(slotPos);
            double mouthMid = network.cellCenterY(far);
            return (gas ? -mouthMid : mouthMid) <= (gas ? -slotMid : slotMid) + SettlingRun.SURFACE_EPS;
        }
        if (!reservoir.isFiniteReservoir()) return false;
        double height = network.windowHeight(slotPos);
        double low = gas ? -(network.windowBottomY(slotPos) + height) : network.windowBottomY(slotPos);
        double surface = low + slot.amount() / (double) network.cellCapacity * height;
        double vessel = gas ? -reservoir.gasSurface() : reservoir.surface();
        return surface > vessel + SettlingRun.SURFACE_EPS;
    }

    /**
     * Whether the fluid that would move between a node's slot and one adjacent cell may cross that
     * boundary at all — a smart pipe's filter (or any pipe gate) walls the slot off from it, just
     * as it walls a run in the solve. Only one of the two ever holds the crossing fluid: the slot
     * when it pours, the cell when the slot pulls.
     */
    private boolean crosses(Node node, BlockPos adjacent, PipeStore.Store slot, PipeStore.Store cell) {
        FluidStack crossing = slot.amount() > 0 ? slot.fluid() : cell.fluid();
        return PipeGates.conducts(network.level, node.pos(), adjacent, crossing);
    }

    private void exchange(Edge edge, PipeStore.Store from, PipeStore.Store to, int amount) {
        int moved = from.moveInto(to, amount);
        if (moved > 0) {
            ledger.moved(edge, moved);
            ledger.markSettling();
        }
    }
}
