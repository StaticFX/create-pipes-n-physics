package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Answers "what is this pipe doing right now" for the goggle overlay: builds the
 * network at the queried position, runs the read-only solve, and condenses the
 * cell-local picture (status, fluid, rate, direction, gauge pressure) into one
 * {@link PipeStatusPayload}.
 *
 * Gauge pressure is the solved hydraulic head at the cell minus the cell's own
 * elevation — how many blocks of fluid column sit (or pull, when negative) on
 * this pipe. It is the number that makes towers, pumps, and suction readable.
 */
public final class PipeProbe {
    private PipeProbe() {}

    public static PipeStatusPayload probe(ServerLevel level, BlockPos pos) {
        Graph graph = GraphBuilder.build(level, pos);
        if (graph.isEmpty()) return PipeStatusPayload.notConnected(pos);

        Solution solution = FlowSolver.solve(level, graph);

        for (Edge edge : graph.edges()) {
            int cell = edge.pipes().indexOf(pos);
            if (cell >= 0) return probeEdgeCell(level, graph, solution, edge, cell, pos);
        }

        Node node = graph.nodeAt(pos);
        if (node != null && !node.isHandler()) {
            return probeNode(level, graph, solution, node, pos);
        }
        return PipeStatusPayload.notConnected(pos);
    }

    private static PipeStatusPayload probeEdgeCell(ServerLevel level, Graph graph,
                                                   Solution solution, Edge edge, int cell,
                                                   BlockPos pos) {
        EdgeFlow flow = solution.edgeFlows().get(edge.index());
        // The honest "mB/t through this pipe" — the fluid actually moved by the executed
        // transfers, NOT the solver's hydraulic flow, which the lip cap / max-flow cap can
        // throttle well below. They diverge on a near-empty source: the pipe would read a
        // brisk flow yet barely a trickle leaves the tank.
        int actualFlow = actualEdgeFlow(graph, solution, edge);
        FluidStack fluid = solution.edgeFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (fluid.isEmpty()) {
            // An idle run can still be full of RESTING fluid (equalized/settled). Surfacing
            // it lets the goggle distinguish "settled, levels balanced" from "dry, nothing
            // reaching this pipe" — otherwise both read as a bare "No flow" and the player
            // can't tell a healthy balance from a starved run.
            fluid = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        }

        Direction direction = null;
        if (actualFlow > 0 && flow.direction() != EdgeFlow.Direction.NONE) {
            boolean towardB = flow.direction() == EdgeFlow.Direction.A_TO_B;
            BlockPos next = towardB
                    ? (cell + 1 < edge.pipes().size() ? edge.pipes().get(cell + 1) : graph.node(edge.b()).pos())
                    : (cell > 0 ? edge.pipes().get(cell - 1) : graph.node(edge.a()).pos());
            direction = directionBetween(pos, next);
        }

        Double headA = solution.nodeHeads().get(edge.a());
        Double headB = solution.nodeHeads().get(edge.b());
        float pressure = 0;
        float runWorstPressure = 0;
        boolean hasPressure = headA != null && headB != null;
        if (hasPressure) {
            double frac = (cell + 1.0) / (edge.length() + 1);
            double headHere = headA + (headB - headA) * frac;
            pressure = (float) (headHere - SableCompat.getWorldY(level, pos));
            runWorstPressure = runWorstPressure(level, solution, edge, headA, headB, pressure);
        }

        Double ceilingA = solution.nodeCeilings().get(edge.a());
        Double ceilingB = solution.nodeCeilings().get(edge.b());
        // A gas ceiling lives in buoyancy units, not world elevation, so "ceiling − cellY"
        // is meaningless for it — suppress the whole lift/reach line, as budget() and the
        // suction margin already are, instead of painting a false "Reach limit" on a gas
        // pipe that is flowing fine.
        boolean hasHeadroom = (ceilingA != null || ceilingB != null) && !isGas(fluid);
        float headroom = 0;
        float headTotal = 0;
        if (hasHeadroom) {
            int winner = ceilingB != null && (ceilingA == null || ceilingB > ceilingA)
                    ? edge.b() : edge.a();
            double ceiling = solution.nodeCeilings().get(winner);
            double cellY = SableCompat.getWorldY(level, pos);
            headroom = (float) (ceiling - cellY);
            headTotal = budget(solution, winner, ceiling, cellY, fluid);
        }

        byte status = status(solution, edge.index(), actualFlow);
        byte detail = detail(solution, edge.index(), status);
        if (status == PipeStatusPayload.STATUS_NO_FLOW && fluid.isEmpty()
                && starvedDryEdges(level, graph, solution).contains(edge.index())) {
            detail = PipeStatusPayload.DETAIL_PUMP_STARVED;
        }
        boolean hasSuction = hasPressure && runWorstPressure < -0.05f && !isGas(fluid);
        float suctionMargin = hasSuction
                ? (float) (PipesNPhysicsConfig.SUCTION_LIMIT.get() + runWorstPressure) : 0;
        return new PipeStatusPayload(pos, status, actualFlow, direction,
                fluid.copyWithAmount(1), hasPressure, pressure, hasHeadroom, headroom, headTotal,
                detail, hasSuction, suctionMargin, false, 0, 0);
    }

    /**
     * The cavitation gate acts at the run's crest, not at the probed cell, so the
     * margin shown anywhere on a run is taken from its worst point — otherwise a
     * cell below the crest would read more safety than the run actually has.
     */
    private static float runWorstPressure(ServerLevel level, Solution solution, Edge edge,
                                          double headA, double headB, float cellPressure) {
        float worst = cellPressure;
        for (int i = 0; i < edge.pipes().size(); i++) {
            double frac = (i + 1.0) / (edge.length() + 1);
            double head = headA + (headB - headA) * frac;
            double y = SableCompat.getWorldY(level, edge.pipes().get(i));
            worst = Math.min(worst, (float) (head - y));
        }
        return worst;
    }

    /**
     * The head budget at a cell: everything between its ceiling and the supply
     * surface the ceiling was seeded from. Below the surface nothing is spent
     * yet, so the budget shrinks to exactly the remaining headroom (a full bar).
     * Gas heads live in their own pressure units that cannot be compared with a
     * world elevation, so gases claim no budget and get no bar.
     */
    private static float budget(Solution solution, int nodeIndex, double ceiling, double cellY,
                                FluidStack fluid) {
        if (isGas(fluid)) return 0;
        Double anchor = solution.nodeAnchors().get(nodeIndex);
        double base = anchor != null ? Math.min(anchor, cellY) : cellY;
        return (float) (ceiling - base);
    }

    private static boolean isGas(FluidStack fluid) {
        return !fluid.isEmpty() && fluid.getFluid().getFluidType().isLighterThanAir();
    }

    /**
     * The culprit behind a blocked/stalled status, when the solver recorded one.
     * An edge can be blocked for one fluid and stalled for another within a tick;
     * a reason is only shown when it belongs to the status actually displayed.
     */
    private static byte detail(Solution solution, int edgeIndex, byte status) {
        if (status != PipeStatusPayload.STATUS_BLOCKED
                && status != PipeStatusPayload.STATUS_STALLED) {
            return PipeStatusPayload.DETAIL_NONE;
        }
        Solution.Reason reason = solution.edgeReasons().get(edgeIndex);
        if (reason == null) return PipeStatusPayload.DETAIL_NONE;
        boolean stallReason = reason == Solution.Reason.SINK_FULL
                || reason == Solution.Reason.SOURCE_DRY;
        if (stallReason != (status == PipeStatusPayload.STATUS_STALLED)) {
            return PipeStatusPayload.DETAIL_NONE;
        }
        return switch (reason) {
            case VALVE -> PipeStatusPayload.DETAIL_VALVE;
            case PUMP_OFF -> PipeStatusPayload.DETAIL_PUMP_OFF;
            case CREST -> PipeStatusPayload.DETAIL_CREST;
            case SINK_FULL -> PipeStatusPayload.DETAIL_SINK_FULL;
            case SOURCE_DRY -> PipeStatusPayload.DETAIL_SOURCE_DRY;
        };
    }

    /**
     * The edges left dry because a powered pump can't pull a supply — used to attach the
     * "pump can't pull its supply" message to ONLY the pipes a starved pump is actually
     * starving, not every dry pipe sharing the graph. A pump is starved when it is running
     * (past the speed deadband with a real facing — mirrors {@code FlowSolver}'s open-pump
     * test) yet moves nothing on all its branches: a pump pressing a FULL sink stalls, one
     * that can't lift is NO_HEAD, a valved one is blocked — so all-idle-and-unflagged is the
     * starvation signature. From each starved pump we flood the contiguous DRY region (edges
     * with no fluid and no flow): those are the pipes its failure to pull leaves empty. A pipe
     * dry for an UNRELATED reason — a lip-gated connector boxed in by full runs, or a branch
     * behind a closed valve that holds resting fluid — is never reached, so it keeps the
     * neutral "dry" message instead of being blamed on a source it has nothing to do with.
     */
    private static Set<Integer> starvedDryEdges(ServerLevel level, Graph graph, Solution solution) {
        Set<Integer> dry = new HashSet<>();
        for (Node pump : graph.pumps()) {
            float speed = level.getBlockEntity(pump.pos()) instanceof KineticBlockEntity kinetic
                    ? kinetic.getSpeed() : 0;
            if (Math.abs(speed) <= 0.01f || pump.pumpFacing() == null) continue;
            boolean movesNothing = true;
            for (Edge edge : graph.edgesOf(pump.index())) {
                int idx = edge.index();
                if (solution.edgeFlows().get(idx).mbPerTick() > 0
                        || solution.stalledEdges().contains(idx)
                        || solution.noHeadEdges().contains(idx)
                        || solution.blockedEdges().contains(idx)) {
                    movesNothing = false;
                    break;
                }
            }
            if (movesNothing) floodDryRegion(graph, solution, pump.index(), dry);
        }
        return dry;
    }

    /** Flood the dry (no fluid, no flow) edges reachable from a starved pump's node. */
    private static void floodDryRegion(Graph graph, Solution solution, int startNode,
                                       Set<Integer> dryEdges) {
        Deque<Integer> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(startNode);
        seen.add(startNode);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (Edge edge : graph.edgesOf(node)) {
                boolean wet = !solution.restFluids()
                        .getOrDefault(edge.index(), FluidStack.EMPTY).isEmpty();
                if (wet || solution.edgeFlows().get(edge.index()).mbPerTick() != 0) continue;
                dryEdges.add(edge.index());
                int other = edge.a() == node ? edge.b() : edge.a();
                if (seen.add(other)) queue.add(other);
            }
        }
    }

    private static PipeStatusPayload probeNode(ServerLevel level, Graph graph,
                                               Solution solution, Node node, BlockPos pos) {
        int strongestEdge = -1;
        int strongestRate = 0;
        boolean anyBlocked = false;
        boolean anyStalled = false;
        boolean anyNoHead = false;
        for (Edge edge : graph.edgesOf(node.index())) {
            EdgeFlow flow = solution.edgeFlows().get(edge.index());
            if (flow.mbPerTick() > strongestRate) {
                strongestRate = flow.mbPerTick();
                strongestEdge = edge.index();
            }
            anyBlocked |= solution.blockedEdges().contains(edge.index());
            anyStalled |= solution.stalledEdges().contains(edge.index());
            anyNoHead |= solution.noHeadEdges().contains(edge.index());
        }

        FluidStack fluid = strongestEdge >= 0
                ? solution.edgeFluids().getOrDefault(strongestEdge, FluidStack.EMPTY)
                : FluidStack.EMPTY;
        if (fluid.isEmpty()) {
            for (Edge edge : graph.edgesOf(node.index())) {
                FluidStack rest = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
                if (!rest.isEmpty()) { fluid = rest; break; }
            }
        }

        Double head = solution.nodeHeads().get(node.index());
        boolean hasPressure = head != null;
        float pressure = hasPressure
                ? (float) (head - SableCompat.getWorldY(level, pos)) : 0;

        Double ceiling = solution.nodeCeilings().get(node.index());
        boolean hasHeadroom = ceiling != null;
        float headroom = 0;
        float headTotal = 0;
        if (hasHeadroom) {
            double cellY = SableCompat.getWorldY(level, pos);
            headroom = (float) (ceiling - cellY);
            headTotal = budget(solution, node.index(), ceiling, cellY, fluid);
        }

        byte status = anyStalled ? PipeStatusPayload.STATUS_STALLED
                : strongestRate > 0 ? PipeStatusPayload.STATUS_FLOWING
                : anyNoHead ? PipeStatusPayload.STATUS_NO_HEAD
                : anyBlocked ? PipeStatusPayload.STATUS_BLOCKED
                : PipeStatusPayload.STATUS_NO_FLOW;
        byte detail = PipeStatusPayload.DETAIL_NONE;
        for (Edge edge : graph.edgesOf(node.index())) {
            detail = detail(solution, edge.index(), status);
            if (detail != PipeStatusPayload.DETAIL_NONE) break;
        }
        if (status == PipeStatusPayload.STATUS_NO_FLOW && fluid.isEmpty()) {
            Set<Integer> starved = starvedDryEdges(level, graph, solution);
            for (Edge edge : graph.edgesOf(node.index())) {
                if (starved.contains(edge.index())) {
                    detail = PipeStatusPayload.DETAIL_PUMP_STARVED;
                    break;
                }
            }
        }
        boolean hasSuction = hasPressure && pressure < -0.05f && !isGas(fluid);
        float suctionMargin = hasSuction
                ? (float) (PipesNPhysicsConfig.SUCTION_LIMIT.get() + pressure) : 0;

        Solution.PumpLoad load = solution.pumpLoads().get(node.index());
        boolean hasPumpLoad = load != null;
        float headAgainst = hasPumpLoad ? (float) load.headAgainst() : 0;
        float frictionFactor = hasPumpLoad ? (float) load.frictionFactor() : 0;

        return new PipeStatusPayload(pos, status, strongestRate, null,
                fluid.copyWithAmount(1), hasPressure, pressure, hasHeadroom, headroom, headTotal,
                detail, hasSuction, suctionMargin, hasPumpLoad, headAgainst, frictionFactor);
    }

    /**
     * The real fluid crossing this edge per tick: the net of the executed transfers across the
     * cut this edge defines, NOT the solver's hydraulic flow (which the lip cap / max-flow cap
     * can throttle below — so a near-empty source reads a brisk flow yet barely trickles out).
     * Only a BRIDGE edge cleanly splits the graph; a parallel edge can't be cut, so it falls
     * back to the solved flow.
     */
    public static int actualEdgeFlow(Graph graph, Solution solution, Edge edge) {
        Set<Integer> aSide = reachableWithout(graph, edge.a(), edge.index());
        if (aSide.contains(edge.b())) {
            return solution.edgeFlows().get(edge.index()).mbPerTick();
        }
        int net = 0;
        for (Solution.Transfer transfer : solution.transfers()) {
            Node from = graph.nodeAt(transfer.from());
            Node to = graph.nodeAt(transfer.to());
            if (from == null || to == null) continue;
            boolean fromA = aSide.contains(from.index());
            boolean toA = aSide.contains(to.index());
            if (fromA != toA) net += fromA ? transfer.fluid().getAmount() : -transfer.fluid().getAmount();
        }
        return Math.abs(net);
    }

    /** Nodes reachable from start without crossing the excluded edge — defines the edge's cut. */
    private static Set<Integer> reachableWithout(Graph graph, int start, int excludeEdge) {
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (Edge e : graph.edgesOf(node)) {
                if (e.index() == excludeEdge) continue;
                int other = e.a() == node ? e.b() : e.a();
                if (seen.add(other)) queue.add(other);
            }
        }
        return seen;
    }

    private static byte status(Solution solution, int edgeIndex, int mbPerTick) {
        if (solution.stalledEdges().contains(edgeIndex)) return PipeStatusPayload.STATUS_STALLED;
        if (mbPerTick > 0) return PipeStatusPayload.STATUS_FLOWING;
        if (solution.noHeadEdges().contains(edgeIndex)) return PipeStatusPayload.STATUS_NO_HEAD;
        if (solution.blockedEdges().contains(edgeIndex)) return PipeStatusPayload.STATUS_BLOCKED;
        return PipeStatusPayload.STATUS_NO_FLOW;
    }

    private static Direction directionBetween(BlockPos from, BlockPos to) {
        return Direction.fromDelta(
                to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }
}
