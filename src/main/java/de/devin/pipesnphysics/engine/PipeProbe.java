package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

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
        FluidStack fluid = solution.edgeFluids().getOrDefault(edge.index(), FluidStack.EMPTY);

        Direction direction = null;
        if (flow.direction() != EdgeFlow.Direction.NONE) {
            boolean towardB = flow.direction() == EdgeFlow.Direction.A_TO_B;
            BlockPos next = towardB
                    ? (cell + 1 < edge.pipes().size() ? edge.pipes().get(cell + 1) : graph.node(edge.b()).pos())
                    : (cell > 0 ? edge.pipes().get(cell - 1) : graph.node(edge.a()).pos());
            direction = directionBetween(pos, next);
        }

        Double headA = solution.nodeHeads().get(edge.a());
        Double headB = solution.nodeHeads().get(edge.b());
        float pressure = 0;
        boolean hasPressure = headA != null && headB != null;
        if (hasPressure) {
            double frac = (cell + 1.0) / (edge.length() + 1);
            double headHere = headA + (headB - headA) * frac;
            pressure = (float) (headHere - SableCompat.getWorldY(level, pos));
        }

        Double ceilingA = solution.nodeCeilings().get(edge.a());
        Double ceilingB = solution.nodeCeilings().get(edge.b());
        boolean hasHeadroom = ceilingA != null || ceilingB != null;
        float headroom = 0;
        if (hasHeadroom) {
            double ceiling = Math.max(ceilingA != null ? ceilingA : Double.NEGATIVE_INFINITY,
                    ceilingB != null ? ceilingB : Double.NEGATIVE_INFINITY);
            headroom = (float) (ceiling - SableCompat.getWorldY(level, pos));
        }

        byte status = status(solution, edge.index(), flow.mbPerTick());
        return new PipeStatusPayload(pos, status, flow.mbPerTick(), direction,
                fluid.copyWithAmount(1), hasPressure, pressure, hasHeadroom, headroom);
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

        Double head = solution.nodeHeads().get(node.index());
        boolean hasPressure = head != null;
        float pressure = hasPressure
                ? (float) (head - SableCompat.getWorldY(level, pos)) : 0;

        Double ceiling = solution.nodeCeilings().get(node.index());
        boolean hasHeadroom = ceiling != null;
        float headroom = hasHeadroom
                ? (float) (ceiling - SableCompat.getWorldY(level, pos)) : 0;

        byte status = anyStalled ? PipeStatusPayload.STATUS_STALLED
                : strongestRate > 0 ? PipeStatusPayload.STATUS_FLOWING
                : anyNoHead ? PipeStatusPayload.STATUS_NO_HEAD
                : anyBlocked ? PipeStatusPayload.STATUS_BLOCKED
                : PipeStatusPayload.STATUS_NO_FLOW;
        return new PipeStatusPayload(pos, status, strongestRate, null,
                fluid.copyWithAmount(1), hasPressure, pressure, hasHeadroom, headroom);
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
