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
        if (SettlingRun.lighterThanAir(slot.fluid())) {
            bubbleUp(node, slot, flowedEdges);
            return;
        }
        Double head = solution.nodeHeads().get(node.index());
        if (head == null) return;
        int target = (int) Math.round(
                network.windowFill(node.pos(), head) * network.cellCapacity);
        int rate = SettlingRun.settleRate(network.cellCapacity);
        for (Edge edge : network.graph.edgesOf(node.index())) {
            // The brigade owns the cells of edges it flowed this tick: exchanging with them here
            // would move fluid outside their exit budgets and trim the slot below its pooled
            // depth, breaking the "a slot conducts only once at flow depth" plug gate next tick.
            if (flowedEdges.contains(edge.index())) continue;
            BlockPos adjacent = PipeGeometry.adjacentCell(network.graph, edge, node.index());
            if (adjacent == null || adjacent.equals(node.pos())) continue;
            PipeStore.Store cell = network.cellAt(adjacent);
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
            if (adjacent == null || adjacent.equals(node.pos())) continue;
            PipeStore.Store cell = network.cellAt(adjacent);
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
