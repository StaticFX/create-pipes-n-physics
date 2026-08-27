package de.devin.pipesnphysics.engine.probe;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import de.devin.pipesnphysics.engine.graph.GraphCache;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.pump.Pumps;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.store.PipeWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    /**
     * How old the engine's own per-tick solution may be and still answer a probe. Matches the
     * server-side request throttle, so a probe never reads staler data than its own cadence; an
     * awake network re-solves every tick and always serves fresh, a sleeping one falls back to a
     * dedicated solve exactly as before.
     */
    private static final int SOLUTION_MAX_AGE_TICKS = 4;
    /** Elevation slack below which a run counts as LEVEL with its opening (see supplyBelowOpening). */
    private static final double RISE_EPS = 0.05;

    private PipeProbe() {}

    /**
     * Answers a status probe at {@code pos}. Cache-first: reuses the engine's cached graph and
     * its recent per-tick solution when fresh enough (at most {@code SOLUTION_MAX_AGE_TICKS}
     * old), and only falls back to a dedicated build + read-only solve for a network the engine
     * is not currently ticking.
     */
    public static PipeStatusPayload probe(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        Graph graph = GraphCache.get(level, pos, now);
        Solution solution = graph == null ? null
                : GraphCache.recentSolution(level, graph, now, SOLUTION_MAX_AGE_TICKS);
        if (graph == null) {
            graph = GraphBuilder.build(level, pos);
            if (graph.isEmpty()) return PipeStatusPayload.notConnected(pos);
            GraphCache.store(level, graph, now);
        }
        if (solution == null) solution = FlowSolver.solve(level, graph);

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

        EndpointHeads heads = endpointHeads(graph, solution, edge);
        PipeStore.Store store = PipeStore.at(level, pos);
        FluidStack fluid = cellFluid(level, solution, edge, cell, pos, store, heads);
        int holdsMb = store != null ? store.amount() : 0;

        Direction direction = flowDirection(graph, edge, cell, pos, flow, actualFlow);

        float pressure = 0;
        float runWorstPressure = 0;
        boolean hasPressure = heads.known();
        if (hasPressure) {
            pressure = (float) (heads.interpolatedAt(edge, cell) - SableCompat.getWorldY(level, pos));
            runWorstPressure = runWorstPressure(level, solution, edge, heads.a(), heads.b(), pressure);
        }

        Headroom headroom = edgeHeadroom(level, solution, edge, pos, fluid);

        byte status = status(solution, edge.index(), actualFlow);
        byte detail = edgeDetail(level, graph, solution, edge, status, fluid);
        // THE NO-FLOW STORY IS A HIERARCHY: (1) is there any FLUID — a path wall (valve/filter,
        // crest) on a dry run with no supplying end has nothing to stop, so the supply story wins
        // (pump starved / dry — "shows valve shut, but in reality the source is dry"); (2) can it
        // REACH — below-opening and no-head already outrank the walls they refine; (3) only then
        // the wall itself. Machine-state facts (an unpowered pump) are never walls and keep their
        // message even on a dry line.
        if (fluid.isEmpty() && isPathWall(detail)
                && !wallHasSomethingToStop(level, graph, solution, edge)) {
            status = PipeStatusPayload.STATUS_NO_FLOW;
            detail = edgeDetail(level, graph, solution, edge, status, fluid);
        }
        // The air-break margin ("how much more lift before the column snaps over the crest") is an
        // EARLY warning about LIFT — meaningful only while fluid is moving or a pump is being asked
        // to raise it. On a settled NO_FLOW run nothing is lifting, so it is noise (like the reach
        // line, suppressed the same way): a resting pipe sitting a hair above its SOLVED waterline
        // would read a spurious margin — and after the Create-tank render inset that solved surface
        // sits BELOW the visible fill, so the cell is actually SUBMERGED, not in suction. Also
        // suppressed once the solver has broken the column (a CREST edge): there is no margin left,
        // and this recomputed value can read a small positive number that contradicts the "air break
        // over the crest" reason the same run shows — the client prints the concrete fix instead.
        boolean crestBroken = detail == PipeStatusPayload.DETAIL_CREST
                || detail == PipeStatusPayload.DETAIL_BELOW_OPENING;
        boolean hasSuction = hasPressure && !crestBroken && !isGas(fluid)
                && status != PipeStatusPayload.STATUS_NO_FLOW
                && runWorstPressure < -0.05f;
        float suctionMargin = hasSuction
                ? (float) (PipesNPhysicsConfig.SUCTION_LIMIT.get() + runWorstPressure) : 0;
        return new PipeStatusPayload(pos, status, actualFlow, direction,
                fluid.copyWithAmount(1), hasPressure, pressure, headroom.shown(),
                headroom.remaining(), headroom.total(),
                detail, hasSuction, suctionMargin, false, 0, 0, holdsMb);
    }

    /** The solved display heads at an edge's two endpoints, closed-gate mirroring applied. */
    private record EndpointHeads(Double a, Double b) {
        boolean known() { return a != null && b != null; }

        /** The head linearly interpolated at a cell of the run (cells sit between the endpoints). */
        double interpolatedAt(Edge edge, int cell) {
            double frac = (cell + 1.0) / (edge.length() + 1);
            return a + (b - a) * frac;
        }
    }

    private static EndpointHeads endpointHeads(Graph graph, Solution solution, Edge edge) {
        Double headA = solution.nodeHeads().get(edge.a());
        Double headB = solution.nodeHeads().get(edge.b());
        // A closed-gate endpoint (a shut valve) has no head of its own; take the opposite
        // endpoint's head — but ONLY from a real
        // reservoir (a HANDLER). A gate↔open-end segment has no supply (the open end is air, its
        // head is the spill threshold), so it stays dry rather than reading the mouth as a waterline.
        if (graph.node(edge.a()).isClosedGate() && graph.node(edge.b()).isHandler() && headB != null) {
            headA = headB;
        } else if (graph.node(edge.b()).isClosedGate() && graph.node(edge.a()).isHandler() && headA != null) {
            headB = headA;
        }
        return new EndpointHeads(headA, headB);
    }

    /**
     * The fluid shown is the cell's REAL stored content when it holds any (plug flow can carry
     * a different fluid than the pass sample; a dry riser above the waterline genuinely holds
     * nothing and reads dry). With per-cell volume disabled (capacity 0) fall back to the
     * solver's picture: the pass fluid, or the rest fluid where the waterline reaches the cell.
     */
    private static FluidStack cellFluid(ServerLevel level, Solution solution, Edge edge, int cell,
                                        BlockPos pos, PipeStore.Store store, EndpointHeads heads) {
        if (store != null && store.amount() > 0) return store.fluid();
        if (PipeStore.capacityMb() > 0) return FluidStack.EMPTY;
        FluidStack fluid = solution.edgeFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (fluid.isEmpty() && heads.known()) {
            FluidStack rest = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
            if (!rest.isEmpty()
                    && heads.interpolatedAt(edge, cell) >= SableCompat.getWorldY(level, pos) - 0.45) {
                fluid = rest;
            }
        }
        return fluid;
    }

    /** The world direction the cell's content leaves toward, or null when nothing actually moves. */
    private static Direction flowDirection(Graph graph, Edge edge, int cell, BlockPos pos,
                                           EdgeFlow flow, int actualFlow) {
        if (actualFlow <= 0 || flow.direction() == EdgeFlow.Direction.NONE) return null;
        boolean towardB = flow.direction() == EdgeFlow.Direction.A_TO_B;
        BlockPos next = towardB
                ? (cell + 1 < edge.pipes().size() ? edge.pipes().get(cell + 1) : graph.node(edge.b()).pos())
                : (cell > 0 ? edge.pipes().get(cell - 1) : graph.node(edge.a()).pos());
        return PipeGeometry.between(pos, next);
    }

    /** The goggle's lift/reach numbers: whether to show the line, the remaining lift, the total. */
    private record Headroom(boolean shown, float remaining, float total) {
        static final Headroom NONE = new Headroom(false, 0, 0);
    }

    /**
     * The lift/reach line for an edge cell, seeded from the higher endpoint ceiling. A gas
     * ceiling lives in buoyancy units, not world elevation, so "ceiling − cellY" is
     * meaningless for it — suppress the whole lift/reach line, as budget() and the suction
     * margin already are, instead of painting a false "Reach limit" on a gas pipe that is
     * flowing fine.
     */
    private static Headroom edgeHeadroom(ServerLevel level, Solution solution, Edge edge,
                                         BlockPos pos, FluidStack fluid) {
        Double ceilingA = solution.nodeCeilings().get(edge.a());
        Double ceilingB = solution.nodeCeilings().get(edge.b());
        if ((ceilingA == null && ceilingB == null) || isGas(fluid)) return Headroom.NONE;
        int winner = ceilingB != null && (ceilingA == null || ceilingB > ceilingA)
                ? edge.b() : edge.a();
        return headroomAt(level, solution, winner, solution.nodeCeilings().get(winner), pos, fluid);
    }

    /** Headroom and budget seeded from one ceiling node — shared by the edge-cell and node probes. */
    private static Headroom headroomAt(ServerLevel level, Solution solution, int ceilingNode,
                                       double ceiling, BlockPos pos, FluidStack fluid) {
        double cellY = SableCompat.getWorldY(level, pos);
        return new Headroom(true, (float) (ceiling - cellY),
                budget(solution, ceilingNode, ceiling, cellY, fluid));
    }

    /**
     * The detail shown for an edge cell: the solver's recorded culprit, upgraded for the two
     * states only visible from here — a dry cell in a starved pump's region, and a held column.
     */
    private static byte edgeDetail(ServerLevel level, Graph graph, Solution solution,
                                   Edge edge, byte status, FluidStack fluid) {
        byte detail = detail(solution, edge.index(), status);
        if (detail == PipeStatusPayload.DETAIL_CREST && supplyBelowOpening(level, graph, edge)) {
            detail = PipeStatusPayload.DETAIL_BELOW_OPENING;
        }
        if (status == PipeStatusPayload.STATUS_NO_FLOW && fluid.isEmpty()) {
            Byte starvedCause = starvedDryEdges(level, graph, solution).get(edge.index());
            if (starvedCause != null) detail = starvedCause;
        }
        // A pump holding its column against a shut valve: not idly settled, not dry — say "held".
        if (status == PipeStatusPayload.STATUS_NO_FLOW
                && solution.heldEdges().contains(edge.index())) {
            detail = PipeStatusPayload.DETAIL_HELD;
        }
        // NO_HEAD on a turbine's run is the OPPOSITE story from a pump's: nothing is being asked
        // to lift, the fall is simply short of what the turbine's rating demands.
        if (status == PipeStatusPayload.STATUS_NO_HEAD && touchesTurbine(level, graph, edge)) {
            detail = PipeStatusPayload.DETAIL_TURBINE_FALL;
        }
        return detail;
    }

    /** Whether either end of this run is a pump dialed to run backwards as a turbine. */
    private static boolean touchesTurbine(ServerLevel level, Graph graph, Edge edge) {
        for (int end : new int[] {edge.a(), edge.b()}) {
            Node node = graph.node(end);
            if (node.isPump() && FlowSolver.isTurbine(level, node)) return true;
        }
        return false;
    }

    /**
     * Whether a CREST-gated edge's real wall is the supply's fluid standing below the pipe's
     * APERTURE on a run that never rises above it — a same-level draw whose fluid simply cannot
     * reach the opening. "Air break over the crest" reads as a climb problem and sent players
     * hunting a crest on a dead-flat run (a basin a third full beside a same-level pump); the
     * goggle and /pipegraph word this case as "supply below opening" instead. A run that DOES
     * rise past the opening keeps the crest wording — filling to the lip alone may not fix it.
     */
    public static boolean supplyBelowOpening(ServerLevel level, Graph graph, Edge edge) {
        for (int end : new int[] {edge.a(), edge.b()}) {
            Node node = graph.node(end);
            if (!node.isHandler()) continue;
            BoundaryColumn column = BoundaryColumn.resolve(level, node);
            if (column == null || !column.isFiniteReservoir() || column.isEmpty()) continue;
            BlockPos opening = PipeGeometry.adjacentCell(graph, edge, end);
            if (opening == null) continue;
            double lip = PipeWindow.lipY(level, opening);
            if (column.renderedSurface() > lip) continue; // reaches its opening — not this wall
            boolean risen = false;
            for (BlockPos cell : edge.pipes()) {
                risen |= PipeWindow.lipY(level, cell) > lip + RISE_EPS;
            }
            if (!risen) return true;
        }
        return false;
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

    /** Package-private: {@code PumpRangeProbe} suppresses its reach sleeve on the same grounds. */
    static boolean isGas(FluidStack fluid) {
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
            case CHECK_VALVE -> PipeStatusPayload.DETAIL_CHECK_VALVE;
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
     *
     * Each dry edge is tagged with WHY: a pump with no edge on its push (facing) side has nowhere
     * to deliver ({@code DETAIL_PUMP_NO_OUTPUT} - its outlet faces a solid block); otherwise the
     * supply it does have can't be drawn ({@code DETAIL_PUMP_STARVED}). Folding the two into one
     * "check the source" message sent the player to a perfectly full tank.
     */
    public static Map<Integer, Byte> starvedDryEdges(ServerLevel level, Graph graph, Solution solution) {
        Map<Integer, Byte> dry = new HashMap<>();
        for (Node pump : graph.pumps()) {
            if (Pumps.strength(level, pump.pos()) <= 0.01 || pump.pumpFacing() == null) continue;
            boolean movesNothing = true;
            for (Edge edge : graph.edgesOf(pump.index())) {
                int idx = edge.index();
                // A path wall (valve/filter, crest) with nothing to stop — a dry branch whose
                // reservoir ends are empty — is not this pump's stop: nothing is there to filter
                // or lift, so it must not mask the real story (a starved supply).
                Solution.Reason reason = solution.edgeReasons().get(idx);
                boolean mootWall = solution.blockedEdges().contains(idx)
                        && (reason == Solution.Reason.VALVE || reason == Solution.Reason.CREST
                                || reason == Solution.Reason.CHECK_VALVE)
                        && !wallHasSomethingToStop(level, graph, solution, edge);
                if (solution.edgeFlows().get(idx).mbPerTick() > 0
                        || solution.stalledEdges().contains(idx)
                        || solution.noHeadEdges().contains(idx)
                        || (solution.blockedEdges().contains(idx) && !mootWall)) {
                    movesNothing = false;
                    break;
                }
            }
            if (!movesNothing) continue;
            // A pump with nothing on its push side is not short of supply: it has nowhere to
            // deliver. Tag its dry region so the message names the OUTPUT, not the source.
            byte cause = pushSideConnected(graph, pump)
                    ? PipeStatusPayload.DETAIL_PUMP_STARVED
                    : PipeStatusPayload.DETAIL_PUMP_NO_OUTPUT;
            floodDryRegion(graph, solution, pump.index(), cause, dry);
        }
        return dry;
    }

    /**
     * QUESTION ONE of the story hierarchy — is there any fluid this wall could be stopping?
     * A wall flag (a valve/filter rejection, a crest gate) is a per-pass "this fluid was refused
     * passage HERE"; with the edge dry (no stored content, no rest claim) and no reservoir end
     * holding anything, the flag belongs to a fluid that is entirely absent (a separation rig's
     * OTHER line records it through its filter), and the wall is MOOT — the supply story wins.
     * A pump end contributes nothing (it stores nothing itself); a junction or open end is
     * unknowable and stays conservative — the wall is presumed binding.
     */
    private static boolean wallHasSomethingToStop(ServerLevel level, Graph graph,
                                                  Solution solution, Edge edge) {
        if (!solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY).isEmpty()) {
            return true;
        }
        for (BlockPos pos : edge.pipes()) {
            PipeStore.Store cell = PipeStore.at(level, pos);
            if (cell != null && cell.amount() > 0) return true;
        }
        for (int end : new int[] {edge.a(), edge.b()}) {
            Node node = graph.node(end);
            if (node.isPump()) continue;
            if (!node.isHandler()) return true;
            BoundaryColumn column = BoundaryColumn.resolve(level, node);
            if (column == null || !column.isFiniteReservoir() || !column.isEmpty()) return true;
        }
        return false;
    }

    /** The details that are PATH WALLS — flags a pass records where its fluid was refused. */
    private static boolean isPathWall(byte detail) {
        return detail == PipeStatusPayload.DETAIL_VALVE
                || detail == PipeStatusPayload.DETAIL_CREST
                || detail == PipeStatusPayload.DETAIL_BELOW_OPENING
                || detail == PipeStatusPayload.DETAIL_CHECK_VALVE;
    }

    /** Whether any wall-flagged edge at this node really stops something (the node-probe twin). */
    private static boolean anyAdjacentWallBinding(ServerLevel level, Graph graph,
                                                  Solution solution, Node node) {
        for (Edge edge : graph.edgesOf(node.index())) {
            Solution.Reason reason = solution.edgeReasons().get(edge.index());
            if ((reason == Solution.Reason.VALVE || reason == Solution.Reason.CREST
                    || reason == Solution.Reason.CHECK_VALVE)
                    && wallHasSomethingToStop(level, graph, solution, edge)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the pump has a network connection on its PUSH (facing) side. With none, a running
     * pump can pull from its source fine but has nowhere to deliver - its outlet faces a solid
     * block (or open air with no pipe), so the failure is the OUTPUT, not the supply.
     */
    private static boolean pushSideConnected(Graph graph, Node pump) {
        BlockPos pushBlock = pump.pos().relative(pump.pumpFacing());
        for (Edge edge : graph.edgesOf(pump.index())) {
            if (PipeGeometry.adjacentCell(graph, edge, pump.index()).equals(pushBlock)) return true;
        }
        return false;
    }

    /** Flood the dry (no fluid, no flow) edges reachable from a starved pump, tagging the cause. */
    private static void floodDryRegion(Graph graph, Solution solution, int startNode,
                                       byte cause, Map<Integer, Byte> dryEdges) {
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
                dryEdges.putIfAbsent(edge.index(), cause);
                int other = edge.a() == node ? edge.b() : edge.a();
                if (seen.add(other)) queue.add(other);
            }
        }
    }

    private static PipeStatusPayload probeNode(ServerLevel level, Graph graph,
                                               Solution solution, Node node, BlockPos pos) {
        NodeFlows flows = NodeFlows.scan(graph, solution, node);
        FluidStack fluid = nodeFluid(graph, solution, node, flows.strongestEdge());
        // A junction/gate cell is a one-cell slot with REAL stored content — the goggle's
        // "Holds" reads it exactly as it reads an edge cell's (a pump stores nothing, so its
        // store reads 0; a handler node is not a pipe cell and has no store at all).
        PipeStore.Store slot = PipeStore.at(level, pos);
        int holdsMb = slot != null ? slot.amount() : 0;
        if (fluid.isEmpty() && holdsMb > 0) fluid = slot.fluid();

        Double head = solution.nodeHeads().get(node.index());
        boolean hasPressure = head != null;
        float pressure = hasPressure
                ? (float) (head - SableCompat.getWorldY(level, pos)) : 0;

        Double ceiling = solution.nodeCeilings().get(node.index());
        Headroom headroom = ceiling != null
                ? headroomAt(level, solution, node.index(), ceiling, pos, fluid)
                : Headroom.NONE;

        byte status = flows.status();
        byte detail = nodeDetail(level, graph, solution, node, status, fluid);
        // The node twin of the edge-cell story hierarchy (see probeEdgeCell): a moot path wall
        // on a dry node falls through to the supply story.
        if (fluid.isEmpty() && isPathWall(detail)
                && !anyAdjacentWallBinding(level, graph, solution, node)) {
            status = PipeStatusPayload.STATUS_NO_FLOW;
            detail = nodeDetail(level, graph, solution, node, status, fluid);
        }
        // The air-break margin is a LIFT diagnostic — noise on a settled run (see the edge probe).
        boolean hasSuction = hasPressure && !isGas(fluid)
                && status != PipeStatusPayload.STATUS_NO_FLOW && pressure < -0.05f;
        float suctionMargin = hasSuction
                ? (float) (PipesNPhysicsConfig.SUCTION_LIMIT.get() + pressure) : 0;

        Solution.PumpLoad load = solution.pumpLoads().get(node.index());
        boolean hasPumpLoad = load != null;
        float headAgainst = hasPumpLoad ? (float) load.headAgainst() : 0;
        float frictionFactor = hasPumpLoad ? (float) load.frictionFactor() : 0;

        return new PipeStatusPayload(pos, status, flows.strongestRate(), null,
                fluid.copyWithAmount(1), hasPressure, pressure, headroom.shown(),
                headroom.remaining(), headroom.total(),
                detail, hasSuction, suctionMargin, hasPumpLoad, headAgainst, frictionFactor, holdsMb);
    }

    /** What a node's branches are doing: the strongest ACTUAL rate plus the flag union over them. */
    private record NodeFlows(int strongestEdge, int strongestRate,
                             boolean anyBlocked, boolean anyStalled, boolean anyNoHead) {
        static NodeFlows scan(Graph graph, Solution solution, Node node) {
            int strongestEdge = -1;
            int strongestRate = 0;
            boolean anyBlocked = false;
            boolean anyStalled = false;
            boolean anyNoHead = false;
            for (Edge edge : graph.edgesOf(node.index())) {
                // The honest "mB/t through this pump" — the fluid ACTUALLY moved, matching the pipe in
                // front of it. The raw solved flow is the pump's hydraulic CAPABILITY, which the source/
                // sink/lip then throttle: a starved pump would read near its cap while the pipe it feeds
                // only trickles (the "pump says 240, pipe says 3" mismatch).
                int actual = actualEdgeFlow(graph, solution, edge);
                if (actual > strongestRate) {
                    strongestRate = actual;
                    strongestEdge = edge.index();
                }
                anyBlocked |= solution.blockedEdges().contains(edge.index());
                anyStalled |= solution.stalledEdges().contains(edge.index());
                anyNoHead |= solution.noHeadEdges().contains(edge.index());
            }
            return new NodeFlows(strongestEdge, strongestRate, anyBlocked, anyStalled, anyNoHead);
        }

        byte status() {
            return anyStalled ? PipeStatusPayload.STATUS_STALLED
                    : strongestRate > 0 ? PipeStatusPayload.STATUS_FLOWING
                    : anyNoHead ? PipeStatusPayload.STATUS_NO_HEAD
                    : anyBlocked ? PipeStatusPayload.STATUS_BLOCKED
                    : PipeStatusPayload.STATUS_NO_FLOW;
        }
    }

    /** The strongest branch's pass fluid, falling back to any branch's rest fluid when idle. */
    private static FluidStack nodeFluid(Graph graph, Solution solution, Node node, int strongestEdge) {
        FluidStack fluid = strongestEdge >= 0
                ? solution.edgeFluids().getOrDefault(strongestEdge, FluidStack.EMPTY)
                : FluidStack.EMPTY;
        if (fluid.isEmpty()) {
            for (Edge edge : graph.edgesOf(node.index())) {
                FluidStack rest = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
                if (!rest.isEmpty()) return rest;
            }
        }
        return fluid;
    }

    /**
     * The detail shown for a node: the first branch with a culprit matching the status,
     * upgraded to a starved-pump cause when the node sits dry in a starved region.
     */
    private static byte nodeDetail(ServerLevel level, Graph graph, Solution solution,
                                   Node node, byte status, FluidStack fluid) {
        byte detail = PipeStatusPayload.DETAIL_NONE;
        for (Edge edge : graph.edgesOf(node.index())) {
            detail = detail(solution, edge.index(), status);
            if (detail == PipeStatusPayload.DETAIL_CREST && supplyBelowOpening(level, graph, edge)) {
                detail = PipeStatusPayload.DETAIL_BELOW_OPENING;
            }
            if (detail != PipeStatusPayload.DETAIL_NONE) break;
        }
        if (status == PipeStatusPayload.STATUS_NO_FLOW && fluid.isEmpty()) {
            Map<Integer, Byte> starved = starvedDryEdges(level, graph, solution);
            for (Edge edge : graph.edgesOf(node.index())) {
                Byte cause = starved.get(edge.index());
                if (cause != null) { detail = cause; break; }
            }
        }
        return detail;
    }

    /**
     * The real fluid that crossed this edge per tick — the executor's recorded boundary movement
     * ({@link PipeFlowExecutor}), NOT the solver's hydraulic flow (which the lip cap / max-flow
     * cap / an unprimed pipe can hold below — so a near-empty source would read a brisk flow yet
     * barely trickle). Zero on a solution that was never applied (a sleeping network's fresh
     * probe solve moves nothing).
     */
    public static int actualEdgeFlow(Graph graph, Solution solution, Edge edge) {
        int[] actual = solution.actualFlow();
        return edge.index() < actual.length ? actual[edge.index()] : 0;
    }

    private static byte status(Solution solution, int edgeIndex, int mbPerTick) {
        // REAL movement wins: a run still priming against a full sink is flowing (into the pipe),
        // not yet stalled — it stalls once the column is full and nothing moves anymore.
        if (mbPerTick > 0) return PipeStatusPayload.STATUS_FLOWING;
        if (solution.stalledEdges().contains(edgeIndex)) return PipeStatusPayload.STATUS_STALLED;
        if (solution.noHeadEdges().contains(edgeIndex)) return PipeStatusPayload.STATUS_NO_HEAD;
        if (solution.blockedEdges().contains(edgeIndex)) return PipeStatusPayload.STATUS_BLOCKED;
        return PipeStatusPayload.STATUS_NO_FLOW;
    }
}
