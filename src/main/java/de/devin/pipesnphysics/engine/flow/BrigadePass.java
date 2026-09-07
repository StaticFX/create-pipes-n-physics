package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.store.PipeWindow;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One fluid pass of the brigade: every edge the solver flowed this fluid over becomes a
 * {@link FlowingRun}, and they tick CONSUMERS-FIRST (reverse-topological along the flow) so a
 * chain moves one step everywhere in the same tick — a run past a junction pulls from its
 * feeders' tail cells before the feeders themselves advance. Cycles (a pump ring through
 * junctions) fall out of the order and are appended as-is; the seam edge lags one tick, which a
 * primed ring never notices.
 *
 * The pass also installs each source reservoir's LIP drain cap (it knows the out-flowing
 * openings), which then also bounds the settle phase — same physical opening, same rule.
 */
public final class BrigadePass {
    private final FlowNetwork network;
    private final FlowLedger ledger;
    private final FluidStack fluid;
    private final Map<Integer, FlowingRun> runs = new HashMap<>();
    /** The runs flowing INTO each pass-through node — the feeders a consumer may pull from. */
    private final Map<Integer, List<FlowingRun>> feedersInto = new HashMap<>();
    /**
     * Each junction/gate slot's contents as this pass FOUND them, before any run drew on it — the
     * quantity the depth gate in {@link #pullArrivingAt} asks about. See {@link #snapshotSlots}.
     */
    private final Map<Integer, Integer> slotArrival = new HashMap<>();

    public BrigadePass(FlowNetwork network, FlowLedger ledger, Solution.FlowPass pass) {
        this.network = network;
        this.ledger = ledger;
        this.fluid = pass.fluid();
        for (Edge edge : network.graph.edges()) {
            double flow = pass.edgeFlow()[edge.index()];
            int solvedRateMb = (int) Math.round(Math.abs(flow));
            // A sub-1 mB/t trickle carries no whole millibucket this tick; the edge settles
            // instead, so equalization still finishes.
            if (solvedRateMb < 1) continue;
            boolean flowsAToB = flow > 0;
            FlowingRun run = new FlowingRun(this, network, edge, fluid, solvedRateMb, flowsAToB);
            runs.put(edge.index(), run);
            feedersInto.computeIfAbsent(run.downstreamNode(), k -> new ArrayList<>()).add(run);
        }
    }

    /** The edge indices that carry solved flow in this pass; the settle phase skips them. */
    public Set<Integer> flowingEdges() {
        return runs.keySet();
    }

    FlowLedger ledger() {
        return ledger;
    }

    /** One brigade tick: install the source lip caps, then tick every run consumers-first. */
    public void execute() {
        if (runs.isEmpty()) return;
        installLipCaps();
        snapshotSlots();
        for (FlowingRun run : consumersFirst()) {
            run.tick();
        }
    }

    /**
     * Record every junction/gate slot's contents BEFORE any run draws on it, because the depth gate
     * asks whether the column has ARRIVED at that junction — a state earlier ticks established, not
     * a live level that the first consumer of this tick can revoke for its siblings.
     *
     * Reading the live slot starved every branch of a MANIFOLD but one. A slot holds exactly one
     * cell, and a run's flow depth clamps to a full cell at any rate past a quarter cell per tick,
     * so two branches off one junction each demanded the WHOLE slot: whichever ticked first dropped
     * it below depth and every sibling's gate then failed — the same branch winning every tick
     * (consumer order is stable), the others permanently dry with the trunk stuck at a fraction of
     * its solved rate. Gating on arrival makes the split supply-limited instead of order-limited.
     * A slot that never reached depth still passes nothing, so plug flow is unchanged.
     *
     * The DIVISION past the open gate stays first-come, deliberately. A proportional share of the
     * solved rates was built and reverted: it could not be shown to change any outcome, because
     * {@link FlowingRun#intake} bounds a run's demand by its head cell's ROOM — a branch that just
     * took from the slot must deliver downstream before it can ask again, which costs it a tick and
     * hands its sibling the turn. The manifold round-robins on its own; measured on the
     * {@code manifold_split} rig, greedy split 2604/2587.
     */
    private void snapshotSlots() {
        for (Node node : network.graph.nodes()) {
            PipeStore.Store slot = network.slotAt(node.index());
            if (slot != null) slotArrival.put(node.index(), slot.amount());
        }
    }

    /**
     * Fluid arriving at a node this tick, for a consumer pulling through it: a reservoir drains
     * on demand; a junction/gate node yields its SLOT — plug flow: the slot passes fluid on only
     * once it has pooled the pulling run's flow depth ({@code depthMb}), so the junction cell
     * visibly fills before anything continues past it (feeders top it up in their own ticks); a
     * slot-less pass-through (a pump) pulls straight from its feeders' tails, recursing through
     * wires. The visited set breaks pull cycles.
     *
     * A junction/gate slot answers on the ARRIVAL snapshot, never its live level, so a manifold
     * serves every branch instead of only whichever ticks first. See {@link #snapshotSlots}.
     */
    int pullArrivingAt(FlowingRun puller, FluidStack wanted, int amount, Set<Integer> visited) {
        int nodeIndex = puller.upstreamNode();
        if (amount <= 0 || !visited.add(nodeIndex)) return 0;
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null) return reservoir.drain(wanted, amount);

        PipeStore.Store slot = network.slotAt(nodeIndex);
        if (slot != null) {
            if (slot.amount() > 0 && !FluidStack.isSameFluidSameComponents(slot.fluid(), wanted)) {
                return 0;
            }
            if (slotArrival.getOrDefault(nodeIndex, 0) < puller.flowDepth()
                    && !nothingMoreArrivesAt(nodeIndex, wanted, puller.flowDepth())) {
                return 0;
            }
            return slot.extract(amount).getAmount();
        }
        int got = 0;
        for (FlowingRun feeder : feedersInto.getOrDefault(nodeIndex, List.of())) {
            if (got >= amount) break;
            got += feeder.pullFromTail(wanted, amount - got, visited);
        }
        return got;
    }

    /**
     * Whether a node's pooled column is all that will EVER gather there, so a depth gate demanding
     * more would wait forever — the junction twin of {@link FlowingRun#columnFullyArrived}, and the
     * same rule: a gate must never ask for a column the line cannot build.
     *
     * A MANIFOLD of small sources feeding one fast run is the shape that needs it. The depth is
     * sized from the SOLVED rate, which is a hydraulic capability the supply need not be able to
     * back: three engines giving 15 mB/t each into a junction whose outgoing run solved 224 mB/t
     * were asked for a 250 mB slot, so nothing ever crossed and the line sat dead with a full
     * solve on it. Reservoir-fed runs have been relieved of exactly this since the arrived-column
     * rule shipped; this is that rule finally reaching the junction case it always excluded.
     */
    private boolean nothingMoreArrivesAt(int nodeIndex, FluidStack wanted, int depth) {
        return arrivingSupply(nodeIndex, wanted, depth + 1, freshVisitSet()) <= depth;
    }

    /**
     * What could still gather at a node — the non-mutating twin of {@link #pullArrivingAt}, and the
     * answer every "can this line ever build the depth" question needs. A reservoir answers what it
     * would give; a junction/gate slot answers its pooled contents PLUS whatever can still reach it;
     * a slot-less pass-through forwards to its feeders, each of which answers its own stored column
     * plus its own upstream. The visited set breaks cycles exactly as the pull does.
     */
    int arrivingSupply(int nodeIndex, FluidStack wanted, int want, Set<Integer> visited) {
        if (want <= 0 || !visited.add(nodeIndex)) return 0;
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null) return reservoir.probeSupply(wanted, want);

        int got = 0;
        PipeStore.Store slot = network.slotAt(nodeIndex);
        if (slot != null) {
            if (slot.amount() > 0 && !FluidStack.isSameFluidSameComponents(slot.fluid(), wanted)) {
                return 0;
            }
            got = Math.min(want, slot.amount());
        }
        for (FlowingRun feeder : feedersInto.getOrDefault(nodeIndex, List.of())) {
            if (got >= want) break;
            got += feeder.availableToPass(wanted, want - got, visited);
        }
        return got;
    }

    /**
     * The dual of {@link #pullArrivingAt}: return fluid a consumer could not place after all to
     * where a pull at this node takes it from — the reservoir, the junction/gate slot, or the
     * feeders' tails, recursing through wires. The pull that just emptied these stores guarantees
     * the room, so nothing is voided short of a handler refusing its own fluid back.
     */
    int refundArrivingAt(int nodeIndex, FluidStack fluid, int amount, Set<Integer> visited) {
        if (amount <= 0 || !visited.add(nodeIndex)) return 0;
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null) return reservoir.refund(fluid, amount);

        PipeStore.Store slot = network.slotAt(nodeIndex);
        if (slot != null) return slot.insert(fluid, amount);
        int returned = 0;
        for (FlowingRun feeder : feedersInto.getOrDefault(nodeIndex, List.of())) {
            if (returned >= amount) break;
            returned += feeder.refundToTail(fluid, amount - returned, visited);
        }
        return returned;
    }

    Set<Integer> freshVisitSet() {
        return new HashSet<>();
    }

    /**
     * A run ticks only after every run OUT of its downstream PASS-THROUGH node has ticked (a
     * reservoir buffers, so it breaks the dependency chain). A junction slot buffers too, but it
     * only conducts once it pools the consumer's flow depth (and does not exist in wire mode), so
     * runs into a pass-through still order behind the runs out of it — only a reservoir truly
     * decouples. Kahn's algorithm over that relation; cycle members that never free up are
     * appended in discovery order.
     */
    private List<FlowingRun> consumersFirst() {
        Map<Integer, Integer> waitingOn = new HashMap<>();
        for (FlowingRun run : runs.values()) {
            int down = run.downstreamNode();
            waitingOn.put(run.edge.index(),
                    network.reservoirAt(down) != null ? 0 : runsOutOf(down));
        }
        ArrayDeque<FlowingRun> ready = new ArrayDeque<>();
        for (FlowingRun run : runs.values()) {
            if (waitingOn.get(run.edge.index()) == 0) ready.add(run);
        }
        List<FlowingRun> order = new ArrayList<>(runs.size());
        Set<Integer> done = new HashSet<>();
        while (!ready.isEmpty()) {
            FlowingRun run = ready.poll();
            if (!done.add(run.edge.index())) continue;
            order.add(run);
            for (FlowingRun feeder : feedersInto.getOrDefault(run.upstreamNode(), List.of())) {
                int left = waitingOn.merge(feeder.edge.index(), -1, Integer::sum);
                if (left == 0) ready.add(feeder);
            }
        }
        for (FlowingRun run : runs.values()) {
            if (!done.contains(run.edge.index())) order.add(run);
        }
        return order;
    }

    private int runsOutOf(int nodeIndex) {
        int count = 0;
        for (FlowingRun run : runs.values()) {
            if (run.upstreamNode() == nodeIndex) count++;
        }
        return count;
    }

    /**
     * The lip drain cap per source reservoir: at most half its volume above the LOWEST opening it
     * flows out of this tick. The lip of an opening is its cell's LIP (the pipe's outer shell
     * bottom, {@code PipeWindow.lipY}) for gravity flow, the block floor when a pump actively
     * pulls the reservoir (suction reaches the puddle under the pipe), and gone entirely when
     * {@code pumpDrainAnyLevel} is on (the dip-tube rule) — mirroring {@code FluidPass.openingLip}.
     * Gases and non-finite endpoints (open mouths, hose pulleys, relays — no surface to flap
     * across a lip) are exempt. The cap's formula and consumption live on
     * {@link Reservoir#capDrawAtLip}.
     */
    private void installLipCaps() {
        boolean drainAny = PipesNPhysicsConfig.PUMP_DRAIN_ANY_LEVEL.get();
        Map<Reservoir, Double> lowestLip = new HashMap<>();
        for (Node node : network.graph.nodes()) {
            Reservoir reservoir = network.reservoirAt(node.index());
            if (reservoir == null || !reservoir.isFiniteReservoir() || reservoir.isInfiniteSource()) continue;
            // A buoyant column gets the MIRRORED lip rather than being exempted: its gate is the
            // aperture's top and everything is negated, so the same "most permissive opening"
            // merge picks the HIGHEST one — the first a ceiling pocket grows down to.
            boolean gas = SettlingRun.lighterThanAir(reservoir.contents());
            for (Edge edge : network.graph.edgesOf(node.index())) {
                FlowingRun run = runs.get(edge.index());
                if (run == null || run.upstreamNode() != node.index()) continue;
                boolean pumpPulls = pumpPullsFrom(edge, node.index());
                if (drainAny && pumpPulls) continue;
                BlockPos opening = PipeGeometry.adjacentCell(network.graph, edge, node.index());
                lowestLip.merge(reservoir,
                        PipeWindow.drawLipY(network.level, opening, pumpPulls, gas), Math::min);
            }
        }
        lowestLip.forEach(Reservoir::capDrawAtLip);
    }

    /** Whether the far end of {@code edge} is a pump whose PULL side faces this endpoint. */
    private boolean pumpPullsFrom(Edge edge, int nodeIndex) {
        Node far = network.graph.node(edge.other(nodeIndex));
        if (!far.isPump()) return false;
        BlockPos toward = PipeGeometry.adjacentCell(network.graph, edge, far.index());
        return toward != null && toward.equals(far.pullCell());
    }
}
