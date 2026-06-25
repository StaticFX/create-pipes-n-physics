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
 *   nodeAnchors  — per graph node index, the supply surface head its ceiling was
 *                  seeded from (before any pump boosts); ceiling − anchor is the
 *                  total head budget, elevation above the anchor is budget spent,
 *   edgeFluids   — per graph edge, a sample of the fluid carrying the dominant flow,
 *   restFluids   — per graph edge, the fluid that fills it even when NOT flowing
 *                  (dominant participating fluid); lets the renderer keep a static
 *                  full pipe visible where it sits below the fluid surface,
 *   blockedEdges — edges that cannot carry their fluid this tick (closed valve or
 *                  pump, filter mismatch, or a broken column at a crest) as opposed
 *                  to edges that are merely balanced,
 *   stalledEdges — edges whose solved flow is pressurized and ready but moved
 *                  nothing because the endpoints could not give or take (sink full,
 *                  source undrainable); flow resumes instantly when room appears,
 *   edgeReasons  — for blocked/stalled edges, the specific culprit when the solver
 *                  knows it; feeds the goggle detail line,
 *   pumpLoads    — per pump node index, the operating point on its pump curve:
 *                  head supplied vs head fought and the pipe-friction factor, so
 *                  the goggle can explain WHY a pump runs below its flow cap,
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
        Map<Integer, Double> nodeAnchors,
        Map<Integer, FluidStack> edgeFluids,
        Map<Integer, FluidStack> restFluids,
        Set<Integer> blockedEdges,
        Set<Integer> stalledEdges,
        Set<Integer> noHeadEdges,
        Map<Integer, Reason> edgeReasons,
        Map<Integer, PumpLoad> pumpLoads,
        boolean active
) {
    /** Why a blocked/stalled edge cannot move its fluid, when the solver knows. */
    public enum Reason { VALVE, PUMP_OFF, CREST, SINK_FULL, SOURCE_DRY }

    /**
     * A pump's operating point on its (linear) pump curve, so the goggle can show
     * what holds its throughput below the flow cap. The cap is reached only at zero
     * back-pressure through a friction-free line; flow = cap · headFactor · friction
     * where headFactor = (headSupplied − headAgainst) / headSupplied.
     *
     * @param headSupplied  blocks of head the pump develops (|RPM| · headPerRpm)
     * @param headAgainst   blocks of that head fought by lift + downstream pressure;
     *                      NEGATIVE when gravity assists (so the goggle can show the
     *                      assist instead of mislabelling it as friction)
     * @param frictionFactor pipe conductance / pump internal conductance, in (0, 1];
     *                      below 1 means the connected run, not the pump, is the limit
     * @param drivingFlow   |flow| on the recorded branch, for picking the busiest
     *                      pass when several fluids could claim one pump
     */
    public record PumpLoad(double headSupplied, double headAgainst, double frictionFactor,
                           double drivingFlow) {}

    /** One planned endpoint-to-endpoint movement; amount is the stack's amount in mB. */
    public record Transfer(BlockPos from, BlockPos to, FluidStack fluid) {}

    public static Solution idle(Graph graph) {
        List<EdgeFlow> flows = new ArrayList<>(graph.edges().size());
        for (Edge e : graph.edges()) flows.add(EdgeFlow.none(e.index()));
        return new Solution(flows, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), false);
    }

    public boolean hasTransfer() {
        return !transfers.isEmpty();
    }
}
