package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.Set;

/**
 * One edge carrying solved flow in one fluid pass: the cells oriented upstream→downstream, the
 * solved rate, and the per-tick EXIT budget (how much may still leave its downstream
 * end — consumers past a junction pull against it before this run itself ticks).
 *
 * {@link #tick()} is the whole brigade for this run, in the order that makes a chain move one
 * step everywhere in the same tick: deliver at the downstream end (or top up the junction slot),
 * shift every internal boundary forward by at most the solved rate, then take intake at the
 * upstream end. Movement is PLUG FLOW at the run's {@linkplain FlowNetwork#flowDepthMb flow
 * depth}: fluid entering a dry cell parks until the feeding cell carries the depth, so the
 * visible front is a coherent column — never a smear — and a sink only receives once the column
 * has actually arrived (a tail cell at depth). A fast run's depth is a full cell (the old FULL
 * gates); a trickle runs as a shallow stream that still primes cell by cell.
 */
final class FlowingRun {
    private final BrigadePass pass;
    private final FlowNetwork network;
    final Edge edge;
    private final FluidStack fluid;
    /** The solved rate: the most any single boundary of this run moves this tick, in mB. */
    private final int solvedRateMb;
    /** The plug depth this run flows at — what every gate below requires instead of a full cell. */
    private final int flowDepthMb;
    private final boolean flowsAToB;
    /** Cells upstream→downstream; empty when the endpoints touch directly (or cells hold nothing). */
    private final List<BlockPos> cells;
    private int exitBudget;

    FlowingRun(BrigadePass pass, FlowNetwork network, Edge edge, FluidStack fluid,
               int solvedRateMb, boolean flowsAToB) {
        this.pass = pass;
        this.network = network;
        this.edge = edge;
        this.fluid = fluid;
        this.solvedRateMb = solvedRateMb;
        this.flowDepthMb = FlowNetwork.flowDepthMb(solvedRateMb, network.cellCapacity);
        this.flowsAToB = flowsAToB;
        this.cells = network.cellCapacity <= 0 ? List.of()
                : flowsAToB ? edge.pipes() : edge.pipes().reversed();
        this.exitBudget = solvedRateMb;
    }

    int upstreamNode() {
        return flowsAToB ? edge.a() : edge.b();
    }

    /** The plug depth every gate on this run requires, in mB. */
    int flowDepth() {
        return flowDepthMb;
    }

    int downstreamNode() {
        return flowsAToB ? edge.b() : edge.a();
    }

    void tick() {
        // A run driven for one fluid whose cells hold a DIFFERENT one is crossing the streams: react
        // at the boundary (Create breaks the pipe) instead of pistoning the resident fluid downstream
        // into the wrong sink. Skip the rest of the run this tick — the colliding cell breaks after
        // the flush and the network re-solves next tick.
        if (reactToForeignFluid()) return;
        deliver();
        shiftForward();
        intake();
        stampFlowAnimation();
    }

    /**
     * The first cell (from the source side) holding a fluid different from this run's pass fluid —
     * where this flow's front meets a foreign fluid. Records the collision and reports it so the run
     * moves nothing this tick; the resting-adjacent case (no driven flow) is left to the settle,
     * which just blocks, exactly as two idle fluids do.
     *
     * With mixed contents ALLOWED the recording is a no-op and this is simply the front stopping
     * behind the batch already in the pipe — the run still stands down for the tick, since a cell
     * holds one fluid and nothing of ours can pass the resident plug anyway. (Deliberately not the
     * old plug-piston that shoved the resident fluid onward: that delivered it into the wrong sink.)
     */
    private boolean reactToForeignFluid() {
        for (BlockPos pos : cells) {
            PipeStore.Store cell = network.cellAt(pos);
            if (cell == null || cell.amount() <= 0) continue;
            if (!FluidStack.isSameFluidSameComponents(cell.fluid(), fluid)) {
                network.collide(pos, cell.fluid(), fluid);
                return true;
            }
        }
        return false;
    }

    /**
     * The downstream end: pour an arrived column into a sink reservoir, pull straight through a
     * zero-cell wire, or top up a junction/gate slot. Pass-through consumers were already served
     * (they ran first and PULLED via {@link #pullFromTail}); what they didn't take backs up here.
     */
    private void deliver() {
        Reservoir sink = network.reservoirAt(downstreamNode());
        if (sink != null) {
            if (cells.isEmpty()) deliverThroughWire(sink);
            else deliverFromTail(sink);
            return;
        }
        PipeStore.Store slot = network.slotAt(downstreamNode());
        if (slot == null) return;
        BlockPos slotPos = network.graph.node(downstreamNode()).pos();
        // Two fluids converging at a junction: this run's fluid meets a different one already pooled
        // in the slot — crossing the streams at the junction cell.
        if (network.collides(slotPos, slot, fluid)) return;
        if (cells.isEmpty()) {
            topUpSlotThroughWire(slot);
            return;
        }
        PipeStore.Store tail = network.cellAt(cells.getLast());
        if (tail != null) {
            int moved = plugMove(tail, slot, exitBudget);
            exitBudget -= moved;
            pass.ledger().moved(edge, moved);
        }
    }

    /**
     * A zero-cell run into a junction/gate slot has no tail cell to conduct with, so it tops the
     * slot up by pulling straight through the wire — exactly what {@link #deliverThroughWire}
     * does for a reservoir sink. Without this a pump wedged flush against a junction never moves
     * anything: the slot stays empty, the consumer past the junction stays gated on it (a slot
     * passes fluid only once at the consumer's flow depth), and the whole line reads solved flow
     * with zero actual.
     */
    private void topUpSlotThroughWire(PipeStore.Store slot) {
        int want = Math.min(exitBudget, slot.room(fluid));
        if (want <= 0) return;
        Reservoir source = network.reservoirAt(upstreamNode());
        int got = source != null
                ? source.drain(fluid, want)
                : pass.pullArrivingAt(this, fluid, want, pass.freshVisitSet());
        if (got <= 0) return;
        slot.insert(fluid, got);
        exitBudget -= got;
        pass.ledger().moved(edge, got);
    }

    /** Every internal boundary moves at most the solved rate, downstream-first (one step per tick). */
    private void shiftForward() {
        for (int i = cells.size() - 2; i >= 0; i--) {
            PipeStore.Store from = network.cellAt(cells.get(i));
            PipeStore.Store to = network.cellAt(cells.get(i + 1));
            if (from == null || to == null) continue;
            pass.ledger().moved(edge, plugMove(from, to, solvedRateMb));
        }
    }

    /** The upstream end: refill the head cell from a source reservoir or through the node. */
    private void intake() {
        if (cells.isEmpty()) return;
        PipeStore.Store head = network.cellAt(cells.getFirst());
        if (head == null) return;
        int want = Math.min(solvedRateMb, head.room(fluid));
        if (want <= 0) return;
        Reservoir source = network.reservoirAt(upstreamNode());
        int got = source != null
                ? source.drain(fluid, want)
                : pass.pullArrivingAt(this, fluid, want, pass.freshVisitSet());
        if (got > 0) {
            head.insert(fluid, got);
            pass.ledger().moved(edge, got);
        }
    }

    /**
     * Deliver the tail cell's own fluid (plug flow may carry a different fluid than the pass)
     * into the sink — but the column must ARRIVE first: a tail cell still filling toward the flow
     * depth delivers nothing, unless the whole column has already arrived ({@link
     * #columnFullyArrived}) and drains forward.
     */
    private void deliverFromTail(Reservoir sink) {
        PipeStore.Store tail = network.cellAt(cells.getLast());
        if (tail == null) return;
        boolean arrived = tail.amount() < flowDepthMb && columnFullyArrived();
        if (tail.amount() < flowDepthMb && !arrived) return;
        int budget = Math.min(exitBudget, tail.amount());
        if (budget > 0) {
            int filled = sink.fill(tail.fluid(), budget);
            if (filled > 0) {
                tail.extract(filled);
                exitBudget -= filled;
                pass.ledger().moved(edge, filled);
            }
        }
        // A fully-arrived run conducts THROUGH to its source for the remainder — the sub-depth
        // endgame degenerates to a wire, so the sink still receives the source's last dregs
        // (which sit a consumers-first tick behind the tail and would otherwise orbit).
        if (arrived) deliverThroughWire(sink);
    }

    /** A zero-cell edge is a wire: pull straight through from the upstream side into the sink. */
    private void deliverThroughWire(Reservoir sink) {
        int budget = exitBudget;
        int want = sink.probeFill(fluid, budget);
        if (want <= 0) return;
        Reservoir source = network.reservoirAt(upstreamNode());
        int got = source != null
                ? source.drain(fluid, want)
                : pass.pullArrivingAt(this, fluid, want, pass.freshVisitSet());
        if (got <= 0) return;
        int filled = sink.fill(fluid, got);
        if (filled < got) reinsertLeftover(got - filled);
        exitBudget -= filled;
        pass.ledger().moved(edge, filled);
    }

    /**
     * Let a downstream consumer take up to {@code amount} out of this run's downstream end —
     * from a tail cell at this run's flow depth (the column must have arrived, plug flow), or
     * straight through a wire — bounded by the remaining exit budget.
     */
    int pullFromTail(FluidStack wanted, int amount, Set<Integer> visited) {
        int budget = Math.min(amount, exitBudget);
        if (budget <= 0) return 0;
        int got;
        if (cells.isEmpty()) {
            got = pass.pullArrivingAt(this, wanted, budget, visited);
        } else {
            PipeStore.Store tail = network.cellAt(cells.getLast());
            if (tail == null) return 0;
            if (tail.amount() > 0 && !FluidStack.isSameFluidSameComponents(tail.fluid(), wanted)) {
                return 0;
            }
            boolean arrived = tail.amount() < flowDepthMb && columnFullyArrived();
            if (tail.amount() < flowDepthMb && !arrived) return 0;
            got = tail.amount() > 0 ? tail.extract(budget).getAmount() : 0;
            // The wire-remnant pull-through: the consumer reaches past the arrived column into
            // the source's last dregs, which a consumers-first tick could never catch in the tail.
            if (arrived && got < budget) {
                Reservoir source = network.reservoirAt(upstreamNode());
                if (source != null) got += source.drain(wanted, budget - got);
            }
        }
        exitBudget -= got;
        pass.ledger().moved(edge, got);
        return got;
    }

    /**
     * Whether the tail is the end of everything this run will ever carry: the whole stored column
     * plus what the source reservoir can still give no longer reaches the flow depth, so the
     * depth gate could never open again — the column has fully ARRIVED and drains forward (the
     * "dry source lets the column drain forward" rule). Without this, the last sub-depth residual
     * strands in the pipe once the source runs dry — and beside an open bowl it ORBITS: the
     * settle pours it back into the empty basin, the pump lifts it out again, forever (the
     * separation-rig oscillation). A run fed through a junction/pump keeps the plain gate (its
     * remaining supply is unknowable); a self-refilling source always answers in full, so the
     * gate holds there.
     *
     * The boundary is INCLUSIVE — a total of EXACTLY the depth is arrived too: its tail can only
     * meet the gate on the tick the source gives its last mB, and by the next solve the pass is
     * dead (an empty source assembles no flow), so no consumer ever observes it — the total
     * orbited basin↔pipe forever at precisely one value ("still oscillates at around 60 mB").
     * The probe therefore asks one mB PAST the shortfall: a live supply answers beyond the
     * depth, an exhausted one cannot.
     */
    private boolean columnFullyArrived() {
        Reservoir source = network.reservoirAt(upstreamNode());
        if (source == null) return false;
        int stored = 0;
        for (BlockPos pos : cells) {
            PipeStore.Store cell = network.cellAt(pos);
            if (cell != null) stored += cell.amount();
        }
        if (stored >= flowDepthMb) return false;
        return stored + source.probeSupply(fluid, flowDepthMb - stored + 1) <= flowDepthMb;
    }

    /** The dual of {@link #pullFromTail}: put refused fluid back into this run's downstream end. */
    int refundToTail(FluidStack refused, int amount, Set<Integer> visited) {
        if (cells.isEmpty()) return pass.refundArrivingAt(upstreamNode(), refused, amount, visited);
        PipeStore.Store tail = network.cellAt(cells.getLast());
        return tail == null ? 0 : tail.insert(refused, amount);
    }

    /**
     * Plug flow, not a smear: fluid entering a DRY cell parks there until the feeding cell
     * carries the flow depth. A wet destination (the front itself, or a draining column) moves
     * freely.
     */
    private int plugMove(PipeStore.Store from, PipeStore.Store to, int amount) {
        if (to.amount() <= 0 && from.amount() < flowDepthMb) return 0;
        return from.moveInto(to, amount);
    }

    /**
     * Fluid a two-phase wire move could not place after all is refunded to where the pull took it
     * from — the upstream reservoir, junction slot, or feeder tails — never voided silently. The
     * SIMULATE probe makes any leftover a foreign sink's contract violation, and the pull just made
     * the room, so the warn is a genuinely unreachable last resort.
     */
    private void reinsertLeftover(int leftover) {
        leftover -= pass.refundArrivingAt(upstreamNode(), fluid, leftover, pass.freshVisitSet());
        if (leftover > 0) {
            PipesNPhysics.LOGGER.warn("Voided {} mB of {} at {} (sink accepted less than simulated)",
                    leftover, fluid.getFluid(), network.graph.node(upstreamNode()).pos());
        }
    }

    /** Stamp the scroll direction + rate on the wet cells; dry cells ahead of the front stay still. */
    private void stampFlowAnimation() {
        double rate = network.cellCapacity > 0 ? solvedRateMb / (double) network.cellCapacity : 0;
        BlockPos downstream = network.graph.node(downstreamNode()).pos();
        for (int i = 0; i < cells.size(); i++) {
            PipeStore.Store cell = network.cellAt(cells.get(i));
            if (cell == null) continue;
            if (cell.amount() <= 0) {
                cell.clearFlow();
                continue;
            }
            BlockPos next = i < cells.size() - 1 ? cells.get(i + 1) : downstream;
            cell.setFlow(PipeGeometry.between(cells.get(i), next), rate);
        }
    }
}
