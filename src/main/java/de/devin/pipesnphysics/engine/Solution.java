package de.devin.pipesnphysics.engine;

import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The decision {@link FlowSolver} makes for one tick of one network.
 *
 * Carries:
 *   edgeFlows    — one entry per graph edge, giving direction + rate (NONE if idle),
 *   transfers    — the endpoint-to-endpoint fluid movements to apply this tick,
 *   nodeHeads    — player-facing hydraulic head per graph node index (blocks):
 *                  anchored at real reservoirs and static across zero-flow branches,
 *                  so a dead-headed pump shows ambient instead of phantom vacuum;
 *                  absent for nodes cut off from every reservoir,
 *   nodeCeilings — the friction-free potential per graph node index: reservoir
 *                  anchors plus pump boosts along the way — the elevation fluid
 *                  could at most be pushed to from that node,
 *   edgeFluids   — per graph edge, a sample of the fluid carrying the dominant flow,
 *   blockedEdges — edges that cannot carry their fluid this tick (closed valve or
 *                  pump, filter mismatch, or a broken column at a crest) as opposed
 *                  to edges that are merely balanced,
 *   stalledEdges — edges whose solved flow is pressurized and ready but moved
 *                  nothing because the endpoints could not give or take (sink full,
 *                  source undrainable); flow resumes instantly when room appears,
 *   noHeadEdges  — pump edges where the opposing head exceeds the pump's head:
 *                  the pump is simply too weak for the lift it faces,
 *   active       — whether any meaningful flow exists (used to keep ticking).
 *
 * {@link FluidEngine#apply} executes the transfers; the rest feeds the /pipegraph
 * visualizer and the pipe goggle overlay.
 */
public record Solution(
        List<EdgeFlow> edgeFlows,
        List<Transfer> transfers,
        Map<Integer, Double> nodeHeads,
        Map<Integer, Double> nodeCeilings,
        Map<Integer, FluidStack> edgeFluids,
        Set<Integer> blockedEdges,
        Set<Integer> stalledEdges,
        Set<Integer> noHeadEdges,
        boolean active
) {

    /** One planned endpoint-to-endpoint movement; amount is the stack's amount in mB. */
    public record Transfer(BlockPos from, BlockPos to, FluidStack fluid) {}

    public static Solution idle(Graph graph) {
        List<EdgeFlow> flows = new ArrayList<>(graph.edges().size());
        for (Edge e : graph.edges()) flows.add(EdgeFlow.none(e.index()));
        return new Solution(flows, List.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), false);
    }

    public boolean hasTransfer() {
        return !transfers.isEmpty();
    }
}
