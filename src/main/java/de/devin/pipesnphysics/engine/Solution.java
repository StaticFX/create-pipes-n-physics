package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 *   transfers    — the per-pass endpoint intents (what each column could give/take); the
 *                  actual movement is executed by the brigade from `passes`, these remain
 *                  for stall classification and diagnostics,
 *   passes       — per fluid pass, the signed solved flow per edge; {@link PipeFlowExecutor}
 *                  executes them through the pipes' stored volume,
 *   actualFlow   — per edge, the mB that REALLY moved this tick (strongest boundary movement),
 *                  filled in by the executor after the solve; what goggles/overlays show,
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
 *   heldEdges    — pump-fed runs dead-heading a shut gate (a closed valve): the pump
 *                  HOLDS its pressurized column up to the gate (no flow crosses), so the
 *                  renderer keeps it full instead of letting it recede, and it resumes
 *                  the instant the gate reopens. The "head doesn't reset when blocked"
 *                  behavior, generalized — sink-full / no-head runs already hold via the
 *                  renderer's backed-up guard; this adds the case the solver used to drop,
 *   active       — whether any meaningful flow exists (used to keep ticking).
 *
 * {@link FluidEngine#apply} hands the passes to {@link PipeFlowExecutor}, which executes them
 * as plug flow through the pipes' stored volume; transfers remain for stall classification and
 * diagnostics only. The head fields feed the /pipegraph visualizer and the pipe goggle overlay.
 */
public record Solution(
        List<EdgeFlow> edgeFlows,
        List<Transfer> transfers,
        List<FlowPass> passes,
        int[] actualFlow,
        Map<Integer, Double> nodeHeads,
        Map<Integer, Double> nodeCeilings,
        Map<Integer, Double> nodeAnchors,
        Map<Integer, FluidStack> edgeFluids,
        Map<Integer, FluidStack> restFluids,
        Set<Integer> blockedEdges,
        Set<Integer> stalledEdges,
        Set<Integer> noHeadEdges,
        Set<Integer> heldEdges,
        Map<Integer, Reason> edgeReasons,
        Map<Integer, PumpLoad> pumpLoads,
        boolean active
) {
    /** Why a blocked/stalled edge cannot move its fluid, when the solver knows. */
    public enum Reason { VALVE, PUMP_OFF, CREST, SINK_FULL, SOURCE_DRY, CHECK_VALVE, OTHER_FLUID }

    /**
     * One fluid pass's solved flow, signed per edge index (positive = a→b), in mB/t. The transfer
     * brigade ({@link PipeFlowExecutor}) executes these through the pipes' stored volume; passes
     * run in the solve's order (largest fluid first).
     */
    public record FlowPass(FluidStack fluid, double[] edgeFlow) {}

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

    /**
     * One planned endpoint-to-endpoint movement; amount is the stack's amount in mB. {@code fromFace}/
     * {@code toFace} are the sides to drain/fill a SIDE-SPECIFIC handler through (see {@link
     * BoundaryColumn#accessFace}); null means resolve side-agnostically.
     */
    public record Transfer(BlockPos from, Direction fromFace, BlockPos to, Direction toFace, FluidStack fluid) {
        public Transfer(BlockPos from, BlockPos to, FluidStack fluid) {
            this(from, null, to, null, fluid);
        }
    }

    /** The zero-flow decision for a graph: every edge NONE, nothing planned, inactive. */
    public static Solution idle(Graph graph) {
        List<EdgeFlow> flows = new ArrayList<>(graph.edges().size());
        for (Edge e : graph.edges()) flows.add(EdgeFlow.none(e.index()));
        return new Solution(flows, List.of(), List.of(), new int[graph.edges().size()],
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), false);
    }

    /** Whether any endpoint transfer was planned this tick (diagnostics — execution is the brigade). */
    public boolean hasTransfer() {
        return !transfers.isEmpty();
    }

    /**
     * Whether an edge is BACKED UP: full of fluid pressed against a stop (a pump holding a shut
     * gate or an over-high sink, a dead conduit against a full tank) rather than merely idle.
     * The settle phase treats such an edge fill-only — pressure packs it, never drains it.
     */
    public boolean isBackedUp(int edgeIndex) {
        return heldEdges.contains(edgeIndex)
                || noHeadEdges.contains(edgeIndex)
                || (stalledEdges.contains(edgeIndex) && edgeReasons.get(edgeIndex) == Reason.SINK_FULL);
    }

    /** Whether the solver broke this edge's liquid column at its crest (a broken siphon). */
    public boolean isCrestBroken(int edgeIndex) {
        return blockedEdges.contains(edgeIndex) && edgeReasons.get(edgeIndex) == Reason.CREST;
    }
}
