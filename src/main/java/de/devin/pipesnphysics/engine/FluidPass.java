package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.EndpointApi;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.TransferPlanner.TransferPlan;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.HandlerRoles;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.BranchSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.NodeSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver;
import de.devin.pipesnphysics.engine.solve.UnionFind;
import de.devin.pipesnphysics.engine.store.PipeGates;
import de.devin.pipesnphysics.engine.store.PipeWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One fluid's solve over the shared network topology. Networks holding several different fluids
 * run one pass per fluid (largest total volume first); an endpoint that can neither give nor take
 * the pass fluid is a wall for it, empty endpoints are claimed by the first pass that fills them,
 * and fluids never mix inside a tick.
 *
 * A pass is single-use: construct it with the tick's shared state, then {@link #run()} once. The
 * lifecycle reads top to bottom — collect the participating columns, add junction and closed-gate
 * solver nodes, assemble every edge into a solver branch, solve under the valve-throttle governor,
 * then record flows and display fields ({@link DisplayFields}) and plan the endpoint transfers
 * ({@link TransferPlanner}) off the solved result.
 */
final class FluidPass {
    /** Valve-throttle governor: max relaxation rounds, and how close to the target flow is "converged". */
    private static final int GOVERNOR_MAX_ROUNDS = 24;
    private static final double GOVERNOR_TOLERANCE = 0.02;
    /** Below this fully-open flow (mB/t) a throttled run has nothing worth governing. */
    private static final double GOVERNOR_MIN_FLOW = 0.05;

    private final Level level;
    private final Graph graph;
    private final FlowSolver.Columns columns;
    private final Map<Integer, FlowSolver.PumpState> pumps;
    private final Map<Integer, FlowSolver.EdgeStatics> edgeStatics;
    private final FluidStack sample;
    private final boolean gas;
    private final double conductancePerTile;
    private final double fittingLength;
    private final Set<BlockPos> claimedEmpties;
    private final FlowSolver.GroupResults results;

    // Working state built by run(): the solver-node mapping, the participating columns, and the
    // assembled branches with their metadata. The branch list is replaced by the governor's
    // effective (throttle-scaled) copy after the solve, so downstream readouts match the flow.
    private final int[] solverIndex;
    private final List<NodeSpec> nodeSpecs = new ArrayList<>();
    private final List<Boolean> canSupply = new ArrayList<>();
    private final List<BoundaryColumn> participants = new ArrayList<>();
    private final Map<BoundaryColumn, Integer> columnIndex = new HashMap<>();
    private final Map<Long, Integer> gateEdgeIndex = new HashMap<>();
    private final List<BranchMeta> meta = new ArrayList<>();
    private List<BranchSpec> branches = new ArrayList<>();

    /**
     * Per-branch metadata carried from assembly through transfer planning and the goggle load
     * readout. {@code lipA}/{@code lipB} are the world-Y opening thresholds the lip drain cap
     * settles against (NaN when the endpoint has no column). {@code driveNode} is the graph node
     * index of the single pump driving this branch, -1 when none, -2 when two pumps push into the
     * same edge (load attribution ambiguous). {@code throttle} is the valve governor's 0..1 flow
     * share for the run. {@code gateSign} is the flow sign a one-way valve node at either end
     * demands (0 when none) — a branch the solver then backflow-blocks was stopped by that check
     * valve, and the display says so.
     */
    record BranchMeta(int edgeIndex, BoundaryColumn columnA, BoundaryColumn columnB,
                      double lipA, double lipB,
                      int driveNode, double driveHead, double driveInternalConductance,
                      double throttle, int gateSign) {}

    FluidPass(Level level, Graph graph, FlowSolver.Columns columns,
              Map<Integer, FlowSolver.PumpState> pumps,
              Map<Integer, FlowSolver.EdgeStatics> edgeStatics, FluidStack sample,
              Set<BlockPos> claimedEmpties, FlowSolver.GroupResults results) {
        this.level = level;
        this.graph = graph;
        this.columns = columns;
        this.pumps = pumps;
        this.edgeStatics = edgeStatics;
        this.sample = sample;
        this.claimedEmpties = claimedEmpties;
        this.results = results;

        FluidType type = sample.getFluid().getFluidType();
        this.gas = type.isLighterThanAir();
        double viscosityScale = 1000.0 / FlowSolver.effectiveViscosity(level, sample);
        this.conductancePerTile = PipesNPhysicsConfig.PIPE_CONDUCTANCE.get() * viscosityScale;
        this.fittingLength = PipesNPhysicsConfig.PIPE_FITTING_LENGTH.get();
        this.solverIndex = new int[graph.nodes().size()];
        Arrays.fill(solverIndex, -1);
    }

    /** Solve this fluid's pass and record everything into the shared accumulators; whether meaningful flow exists. */
    boolean run() {
        if (!collectParticipants()) return false;
        addJunctionNodes();
        addGateNodes();
        for (Edge edge : graph.edges()) assembleBranch(edge);
        if (branches.isEmpty()) return false;

        Governed governed = governedSolve();
        NetworkSolver.Result result = governed.result();
        // Downstream (pump load, display, transfers) reads the EFFECTIVE conductances the governor
        // settled on, so a throttled run's readouts match the flow it actually solved.
        branches = governed.branches();

        new DisplayFields(graph, solverIndex, nodeSpecs, canSupply, branches, result, gas)
                .writeInto(results);

        int[] island = islands(result);
        boolean active = recordBranchResults(result, island);
        recordPass(result);

        TransferPlan plan = new TransferPlanner(level, sample, gas, branches, meta, result, island,
                claimedEmpties).plan(participants, columnIndex, results.transfers);
        classifyStalls(result, plan);
        return active;
    }

    /**
     * Register every column that joins this fluid's pass as a solver reservoir.
     *
     * A SINGLE reservoir still solves: zero flow (nothing to move it TO), but it records the
     * settled display heads + restFluids, so a pipe dead-ended below a lone tank's surface —
     * e.g. a running pump capped by a solid block on its push side — renders the resting water
     * instead of blanking. A walled neighbour assembles no branch (its node has no solver index),
     * so the pass still bails at the empty-branches check; this only fires for a real conducting
     * dead end.
     */
    private boolean collectParticipants() {
        for (BoundaryColumn column : columns.distinct()) {
            if (!participates(column)) continue;
            int index = nodeSpecs.size();
            nodeSpecs.add(columnSpec(column));
            canSupply.add(!column.isEmpty());
            participants.add(column);
            columnIndex.put(column, index);
            for (int member : column.memberNodes()) solverIndex[member] = index;
        }
        return !participants.isEmpty();
    }

    /** Junctions and pumps become zero-capacitance Kirchhoff nodes. */
    private void addJunctionNodes() {
        for (Node node : graph.nodes()) {
            if (node.isHandler() || node.isOpenEnd() || node.isClosedGate()) continue;
            solverIndex[node.index()] = nodeSpecs.size();
            nodeSpecs.add(new NodeSpec(0, 0));
            canSupply.add(false);
        }
    }

    /**
     * A closed gate (a fully-shut valve) is a WALL: give each incident edge its OWN
     * zero-capacitance dead-end node so no flow crosses it. A pump on one side then
     * dead-heads the gate (the implicit-Euler solve yields head = supply + pump boost —
     * the held head, with zero flow), while the far side settles to its reservoir.
     * Keyed (gateNode, edge) so branch assembly resolves the right dead-end per edge.
     */
    private void addGateNodes() {
        for (Node node : graph.nodes()) {
            if (!node.isClosedGate()) continue;
            for (Edge edge : graph.edgesOf(node.index())) {
                gateEdgeIndex.put(gateKey(node.index(), edge.index()), nodeSpecs.size());
                nodeSpecs.add(new NodeSpec(0, 0));
                canSupply.add(false);
            }
        }
    }

    /**
     * A column joins a fluid's pass when its handler can actually give or take that fluid,
     * or when it is an unclaimed empty that accepts it.
     *
     * The give/take test is what matters, NOT whether the column's single representative
     * {@code contents()} fluid matches the sample. A MULTI-FLUID sink — a basin keeps each
     * ingredient (e.g. water + milk for builder's tea) in its own 1000 mB segment — can take
     * the pass fluid into a free segment even while {@code contents()} reads the other fluid;
     * a single-fluid tank (drain and fill both zero for a foreign fluid) still walls it, and
     * fluids never mix because we only fill where {@code fill() > 0}. Without this a basin
     * mid-recipe never refills a drained ingredient until BOTH run dry, since each fluid walls
     * the other's pass — the "basin only refills once empty" bug.
     */
    private boolean participates(BoundaryColumn column) {
        IFluidHandler cap = column.handler(level);
        // An addon may veto an endpoint for one fluid (a machine whose tank is only a valid target
        // under conditions the engine cannot see) — the supported hook, so nothing has to mixin
        // into this method. Asked after the handler resolve, which an open end depends on for its
        // per-tick management. Reservoir asks the same veto, so the settle honors it too.
        if (!EndpointApi.allows(level, column.identity(), sample)) return false;
        // An open end is decided from engine state, NEVER by probing the capability with
        // fill/drain(SIMULATE): those MUTATE the world — Create's OpenEndedPipe wipes a differing
        // buffered fluid and runs the spill-collision reaction (a lake block turning to stone)
        // BEFORE their own simulate guard, so a foreign fluid's pass corrupts the mouth. The handler
        // is still resolved above for its side effects (it populates the open-end cache and drives
        // manageSource, which apply() depends on) — that is normal per-tick management, not a probe.
        // An intake mouth gives only its own fluid; an empty outlet accepts any unclaimed pass
        // fluid and VENTS what it cannot place, exactly like Create's own mouth (deliberate:
        // the disposal gate that walled undisposable fluids was reverted by owner decision).
        if (column.isOpenEnd()) {
            if (column.isInfiniteSource()) {
                return FluidStack.isSameFluidSameComponents(column.contents(), sample);
            }
            return !claimedEmpties.contains(column.identity());
        }
        if (cap == null) return false;
        if (!column.isEmpty()) {
            return !BoundaryColumn.drainMatching(cap, sample.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()
                    || cap.fill(sample.copyWithAmount(1), FluidAction.SIMULATE) > 0;
        }
        if (claimedEmpties.contains(column.identity())) return false;
        return cap.fill(sample.copyWithAmount(1), FluidAction.SIMULATE) > 0;
    }

    /**
     * The solver node for a column. A finite reservoir carries a capacity CEILING — its head when
     * full (fill = height) — so the active set clamps it to GIVE-ONLY when full: the box-constrained
     * dual of the empty→receive-only wall, replacing the old emf-gated fullDeadlock/preFullSign
     * special-casing (see {@link NetworkSolver}). The EMPTY→receive-only side deliberately stays a
     * static wall in branch assembly (its lip-contradiction early-return is load-bearing for the
     * drained-riser recede), so the floor is left unbounded. Boundaries (open ends, pulleys) keep
     * their own one-way rules and are fully unbounded. The ceiling goes through {@link
     * NetworkSolver#surfaceHead} with the same fill scale as {@link BoundaryColumn#head}, so a gas
     * column (head rises with fill) still reads full at its top.
     */
    private NodeSpec columnSpec(BoundaryColumn column) {
        double head = column.head(gas);
        if (!column.isFiniteReservoir()) return new NodeSpec(column.capacitance(), head);
        double span = column.heightBlocks() * column.fillScale();
        double ceiling = NetworkSolver.surfaceHead(column.baseY(), column.baseY() + span, span, gas);
        // The crest-gating potential seeds from the DRAW surface (a Create tank draws its fluid
        // inset, ABOVE the liquid surface at low fills; an open bowl gives from any level), so the
        // weir gate agrees with what the column may give — like the draw lip. The solved head
        // stays the liquid surface.
        double reach = gas ? head : column.drawSurface();
        return new NodeSpec(column.capacitance(), head, Double.NEGATIVE_INFINITY, ceiling, reach);
    }

    // ------------------------------------------------------------------ branch assembly

    private void assembleBranch(Edge edge) {
        FlowSolver.EdgeStatics statics = edgeStatics.get(edge.index());
        int solverA = solverNodeFor(edge, edge.a());
        int solverB = solverNodeFor(edge, edge.b());
        if (solverA < 0 || solverB < 0 || solverA == solverB) return;
        if (!runAcceptsFluid(edge)) {
            results.blockedEdges.add(edge.index());
            results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.VALVE);
            return;
        }
        if (runCarriesAnotherFluid(edge, statics)) {
            results.blockedEdges.add(edge.index());
            results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.OTHER_FLUID);
            return;
        }

        // A valve the shaft has opened still caps the run by the angle the player dialed
        // in; 0 degrees shuts it as hard as the shaft would. The factor is applied to the
        // FINAL conductance below (after the pump-internal cap), not here — a pump's tiny
        // internal conductance otherwise masks the throttle on every pumped run. Fluid-independent,
        // so it (and the crest below) is precomputed once per edge (see FlowSolver.computeEdgeStatics).
        double throttle = statics.throttle();
        if (throttle <= 0) {
            results.blockedEdges.add(edge.index());
            results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.VALVE);
            return;
        }

        // A run's resistance is its length PLUS its fittings, counted as the equivalent length of
        // straight pipe they cost (the tee it branches off, its elbows, entry and exit). This is
        // what decides how a junction splits: with fittings free, flow divides as 1/length, so a
        // 2-block branch beat an 8-block one 3:1 — but real pipe flow is turbulent, its loss goes
        // as the SQUARE of the rate, and equal-drop parallel branches then divide as 1/sqrt(length)
        // (67/33 for that pair), which at Minecraft scale the fittings even out further still. The
        // engine's loss law is linear (a laminar resistor, which is what keeps one implicit Euler
        // step per tick), so the equivalent length is how that turbulent split is reproduced —
        // charging real fittings tracks it closely over the whole practical range of run lengths.
        double conductance = conductancePerTile / (edge.length() + fittingLength);
        double emf = 0;
        int allowedSign = 0;
        int gateSign = 0;
        int driveNode = -1;
        double driveHead = 0;
        double driveInternalConductance = 0;
        // Whether a pump on this edge draws (suction) FROM the far column — it then lifts fluid
        // out of that tank regardless of level when pumpDrainAnyLevel is on (see the lip check).
        boolean pumpPullsA = false;
        boolean pumpPullsB = false;
        // The strongest DRIVING pump pulling across this run, whose head pays for establishing
        // through a dry crest (the prime allowance below).
        double pullHead = 0;

        for (int side = 0; side < 2; side++) {
            int nodeIndex = side == 0 ? edge.a() : edge.b();
            int outSign = side == 0 ? +1 : -1;

            // An OPEN one-way valve node (a check valve): this branch may only carry flow ALONG
            // the valve's direction — out of the node on its arrow side, into it on the other.
            // The same sign mechanism as a pump's flank check valves, with no EMF and no
            // conductance cap. Two check valves facing each other wall the run outright.
            Node endNode = graph.node(nodeIndex);
            if (endNode.isOneWayGate()) {
                BlockPos toward = PipeGeometry.adjacentCell(graph, edge, nodeIndex);
                int wanted = toward.equals(endNode.pos().relative(endNode.gateFlow()))
                        ? outSign : -outSign;
                gateSign = combineSign(gateSign, wanted);
                allowedSign = combineSign(allowedSign, wanted);
                if (allowedSign == Integer.MIN_VALUE) {
                    results.blockedEdges.add(edge.index());
                    results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.CHECK_VALVE);
                    return;
                }
            }

            FlowSolver.PumpState pump = pumps.get(nodeIndex);
            if (pump == null) continue;
            if (!pump.open()) {
                results.blockedEdges.add(edge.index());
                results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.PUMP_OFF);
                return;
            }

            Node pumpNode = graph.node(nodeIndex);
            BlockPos toward = PipeGeometry.adjacentCell(graph, edge, nodeIndex);

            if (toward.equals(pumpNode.pos().relative(pump.pushSide()))) {
                // A TURBINE arrives here with a NEGATIVE head: same flank, same one-way sign, but
                // it fights the flow instead of driving it, so the run carries nothing until the
                // fall exceeds its rating. Its throughput cap is its swallowing capacity.
                emf += outSign * pump.head();
                allowedSign = combineSign(allowedSign, outSign);
                conductance = Math.min(conductance, pump.internalConductance());
                // The pump driving this run; a second pump pushing into the same
                // edge makes the load attribution ambiguous, so flag it off.
                if (pump.driving()) {
                    driveNode = driveNode < 0 ? nodeIndex : -2;
                    driveHead = pump.head();
                    driveInternalConductance = pump.internalConductance();
                }
            } else if (toward.equals(pumpNode.pos().relative(pump.pushSide().getOpposite()))) {
                allowedSign = combineSign(allowedSign, -outSign);
                // Only a DRIVEN pump sucks: a turbine is gravity-fed, so the tank feeding it keeps
                // its ordinary draw lip rather than being drained from under the opening.
                if (pump.driving()) {
                    if (side == 0) pumpPullsB = true; else pumpPullsA = true;
                    pullHead = Math.max(pullHead, pump.head());
                }
            } else {
                results.blockedEdges.add(edge.index());
                return;
            }
            if (allowedSign == Integer.MIN_VALUE) {
                results.blockedEdges.add(edge.index());
                // A pump pressing a check valve the wrong way is the valve's story, not the pump's.
                if (gateSign != 0) {
                    results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.CHECK_VALVE);
                }
                return;
            }
        }

        double lipA = Double.NaN;
        double lipB = Double.NaN;
        double crestHeight = Double.NaN;
        double crestFloor = Double.NaN;
        double crestPos = 0;
        boolean crestWet = true;

        BoundaryColumn columnA = columns.byNode(edge.a());
        BoundaryColumn columnB = columns.byNode(edge.b());

        // A column with nothing in it can only receive — without this, the solver would model an
        // empty reservoir as a fluid source and distort the heads. This stays a STATIC wall (not the
        // box) because its interaction with the lip rule is load-bearing: an empty end whose ONLY
        // opening also can't draw from the far end contradicts here and RETURNS the branch unassembled
        // (line below), which is what lets a drained tank-to-tank riser RECEDE instead of rendering as
        // a pressurized column. A conflict here (both ends empty, an empty end behind a pump) is an
        // ordinary "nothing to move", not a fault worth flagging to the player.
        if (columnA != null && columnA.isEmpty()) allowedSign = combineSign(allowedSign, -1);
        if (columnB != null && columnB.isEmpty()) allowedSign = combineSign(allowedSign, +1);

        // A receive-only handler (a sink_only tag or a detector-learned relay — a docking connector,
        // a hose, a passthrough) may be filled but never drained or equalized: pin the branch to flow
        // INTO it, exactly like an empty column. Its own logic sources/moves the fluid, so treating it
        // as a two-way capacitor would fight it. Only finite reservoirs carry this role (open ends and
        // pulleys keep their own one-way rules), which also skips the tag lookup for those.
        if (columnA != null && columnA.isFiniteReservoir()
                && HandlerRoles.isReceiveOnly(level, columnA.accessPos())) {
            allowedSign = combineSign(allowedSign, -1);
        }
        if (columnB != null && columnB.isFiniteReservoir()
                && HandlerRoles.isReceiveOnly(level, columnB.accessPos())) {
            allowedSign = combineSign(allowedSign, +1);
        }

        // An infinite SOURCE (a hose pulley over a body it can drain, an open-end intake
        // mouth) only ever supplies — pin the branch to flow OUT of it. A pulley in the
        // opposite, FILL role is instead modelled as an empty SINK column (receive-only via
        // the isEmpty rule above), and drain-priority + the deposit latch keep the two roles
        // from flipping tick-to-tick and reclaiming the fluid just pushed out.
        if (columnA != null && columnA.isInfiniteSource()) allowedSign = combineSign(allowedSign, +1);
        if (columnB != null && columnB.isInfiniteSource()) allowedSign = combineSign(allowedSign, -1);
        if (allowedSign == Integer.MIN_VALUE) {
            // Ordinarily an unflagged "nothing to move" — but when a CHECK VALVE is one party
            // (its sign against an empty end's receive-only wall: filling THROUGH the valve
            // backward is exactly what it exists to stop, and a two-way valve here would flow),
            // the dry side deserves the valve's story instead of reading as merely dry.
            flagCheckValveConflict(edge, gateSign);
            return;
        }

        if (!gas) {
            // A pump actively drawing from a tank can lift its fluid out of a connection above the
            // waterline (a dip tube), so pumpDrainAnyLevel exempts that column from the draw lip: no
            // give-only wall here, and a bottomless lip (−inf) below so lipDrainCap never settles the
            // tank at the opening. The suction limit (crest gate) still bounds the lift.
            boolean drainAny = PipesNPhysicsConfig.PUMP_DRAIN_ANY_LEVEL.get();
            if (columnA != null) {
                BlockPos opening = PipeGeometry.adjacentCell(graph, edge, edge.a());
                if (drainAny && pumpPullsA) {
                    lipA = Double.NEGATIVE_INFINITY;
                } else {
                    lipA = openingLip(opening, pumpPullsA);
                    if (!canDrawFrom(graph.node(edge.a()), columnA, opening, lipA)) {
                        allowedSign = combineSign(allowedSign, -1);
                    }
                }
            }
            if (columnB != null) {
                BlockPos opening = PipeGeometry.adjacentCell(graph, edge, edge.b());
                if (drainAny && pumpPullsB) {
                    lipB = Double.NEGATIVE_INFINITY;
                } else {
                    lipB = openingLip(opening, pumpPullsB);
                    if (!canDrawFrom(graph.node(edge.b()), columnB, opening, lipB)) {
                        allowedSign = combineSign(allowedSign, +1);
                    }
                }
            }
            // A lip conflict (e.g. a pump trying to draw from below a tank's
            // waterline) is "no supply", not a fault — unless a check valve is one party.
            if (allowedSign == Integer.MIN_VALUE) {
                flagCheckValveConflict(edge, gateSign);
                return;
            }

            crestHeight = statics.crestHeight();
            crestFloor = statics.crestFloor();
            crestPos = statics.crestPos();
            crestWet = statics.crestWet();
        }

        // The full→give-only DUAL of the empty rule (a full reservoir can only give, never receive)
        // is now the solver's job: a finite reservoir carries a capacity box (see columnSpec), and the
        // active set seeds it give-only when full, then walls the branch — so a backed-up run fills an
        // UPSTREAM reservoir with room instead of routing a through-current into a full TERMINAL and
        // zeroing the whole line (the "goofy_network" freeze). The dead-conduit case (a full end whose
        // opening rises above its waterline, or two full ends facing each other) and its SINK_FULL
        // render flag are re-derived from the solved saturation in recordBranchResults — a single
        // uniform mechanism, replacing the old per-branch emf-gated fullDeadlock/preFullSign
        // special-casing.

        // The throttle is NOT baked into the conductance here. Scaling conductance only limits
        // the flow when the valve's own run is the binding resistor — in series with a strong
        // pump (whose tiny internal conductance dominates the loop) halving a fat pipe's
        // conductance barely moves the flow, so "let through 50%" did almost nothing (74→67 on
        // a real pump). Instead the throttle is a THROUGHPUT GOVERNOR applied by {@code governedSolve}:
        // it caps the run's flow to {@code throttle × fully-open flow}, so 50% always means half,
        // wherever the valve sits. The angle is carried on the meta for that loop.
        // A pump SUCKS far more weakly than it pushes: a running pump pulling across this edge may
        // establish through its own dry riser on a fraction of its head (§3), and no further — a
        // supply deeper than that still has to be primed once by hand. Unpumped runs get nothing.
        double primeAllowance = FlowSolver.pumpPrimeAllowance(pullHead);
        branches.add(new BranchSpec(solverA, solverB, conductance, emf, allowedSign,
                crestHeight, crestFloor, crestPos, crestWet, primeAllowance));
        meta.add(new BranchMeta(edge.index(), columnA, columnB, lipA, lipB,
                driveNode, driveHead, driveInternalConductance, throttle, gateSign));
        // Whether this is a held FEED candidate (a pump driving out toward a shut gate) is decided
        // post-solve in recordBranchResults, where the hydraulic islands are known — the pump only
        // HOLDS a column if it actually has a supply behind it (a source in its island).
    }

    /** Mark an unassembled branch BLOCKED by its one-way valve, when one contributed a sign. */
    private void flagCheckValveConflict(Edge edge, int gateSign) {
        if (gateSign == 0) return;
        results.blockedEdges.add(edge.index());
        results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.CHECK_VALVE);
    }

    /**
     * The solver node for an edge endpoint. A closed-gate node is a WALL — each incident edge
     * gets its OWN zero-cap dead-end node (from {@code gateEdgeIndex}) so no flow crosses it;
     * every other node uses its shared index.
     */
    private int solverNodeFor(Edge edge, int nodeIndex) {
        if (graph.node(nodeIndex).isClosedGate()) {
            return gateEdgeIndex.getOrDefault(gateKey(nodeIndex, edge.index()), -1);
        }
        return solverIndex[nodeIndex];
    }

    /** Stable key for a (closed-gate node, incident edge) pair's dead-end solver node. */
    private static long gateKey(int nodeIndex, int edgeIndex) {
        return ((long) nodeIndex << 32) | (edgeIndex & 0xffffffffL);
    }

    /**
     * Honor per-cell fluid gates along a run: closed fluid valves and smart-pipe
     * filters reject fluids via {@code canPullFluidFrom}, exactly as Create's own
     * engine consults them. A run whose cells reject the fluid carries none of it.
     *
     * The same {@link PipeGates} predicate walls the EXECUTOR's moves, so the solve and the
     * settle read one rule — they drifted once, the settle carrying a rejected fluid straight
     * through a filter this branch had already refused.
     */
    private boolean runAcceptsFluid(Edge edge) {
        List<BlockPos> pipes = edge.pipes();
        if (pipes.isEmpty()) return true;
        // The END faces are one-sided: only the run's own cells carry a gate here, never the node
        // they open onto (see PipeGates.admits).
        return PipeGates.admits(level, pipes.getFirst(), graph.node(edge.a()).pos(), sample)
                && PipeGates.conductsAlong(level, pipes, sample)
                && PipeGates.admits(level, pipes.getLast(), graph.node(edge.b()).pos(), sample);
    }

    /**
     * Whether this run belongs to a DIFFERENT fluid right now — a wall for this pass, which waits
     * its turn. A pipe carries one fluid at a time (Create's own network rule, which locks a whole
     * network to one fluid): the column standing in the cells owns the run until it has drained,
     * and an edge an earlier pass is already driving is claimed for the rest of this tick.
     * A SPLIT run (two fluids already resting in it) admits neither until the settle clears it.
     *
     * Without this the engine drove its own passes into one another and Create's crossing-the-
     * streams destroyed the pipe for it — {@code handlePipeFlowCollision} breaks the block for ANY
     * two fluids, reactive or not. A basin mixing two ingredients broke every pipe touching it as
     * soon as one supply ran dry: its emptied tank became an unclaimed empty, the other fluid's
     * pass claimed it, and drove that fluid down a run still full of the first. Crossing the
     * streams is now exactly what the WORLD forces — a reservoir pressing a fluid its column
     * rejects ({@code SettlingRun.pressColumn}), a pump packing a foreign outlet — never the
     * engine's own routing. In wire mode (cells store nothing) there is no column to own, so a
     * run is claimed only for the tick, exactly as before.
     */
    private boolean runCarriesAnotherFluid(Edge edge, FlowSolver.EdgeStatics statics) {
        if (results.claimedRuns.contains(edge.index())) return true;
        FluidStack carried = statics.carrying();
        if (carried.isEmpty()) return false;
        return statics.split() || !FluidStack.isSameFluidSameComponents(carried, sample);
    }

    /**
     * The draw-lip elevation of an opening: gravity flow leaves once the surface reaches the
     * opening cell's LIP — the pipe's outer shell bottom ({@link PipeWindow#lipY}), so a tank
     * settles where its fluid stops touching the pipe the player sees — while a pump actively
     * pulling the column reaches the opening's BLOCK floor (its suction takes the puddle under
     * the pipe too — what lets a base-level pump move ALL fluid), or anywhere under
     * pumpDrainAnyLevel.
     */
    private double openingLip(BlockPos opening, boolean pumpPulls) {
        return PipeWindow.drawLipY(level, opening, pumpPulls);
    }

    /**
     * Fluid can only leave a column through an opening its surface reaches — its DRAW surface:
     * the player judges the lip against the fluid they SEE, and a Create tank draws its fluid
     * inset (up to ~0.31 ABOVE the liquid surface at low fills), so gating on the liquid surface
     * walled a tank whose visible fluid stood well over the pipe ("the fluid inside the tank is
     * higher than the lip, like a lot higher"); an open bowl gives from any level. Open ends are
     * exempt: their opening is inside the fluid by construction (a pipe mouth submerged in a lake).
     */
    private boolean canDrawFrom(Node handlerNode, BoundaryColumn column, BlockPos opening, double lip) {
        if (column.isOpenEnd() || column.isInfiniteSource()) return true;
        // Wall one millibucket early: drains are integer mB and the lip cap zeroes fractionally
        // at the lip, so a surface resting less than 1 mB above it can never actually give — an
        // open gate there is a PERMANENT phantom flow (solved q, SOURCE_DRY stall, scrolling
        // pipes, nothing moving) at exactly the equilibrium every gravity drain ends on.
        double oneMb = 1.0 / Math.max(column.capacitance(), 1);
        if (column.drawSurface() <= lip + oneMb) return false;
        return SableCompat.canFluidReachPipe(level, handlerNode.pos(), opening, column.fillFraction());
    }

    // ------------------------------------------------------------------ governed solve

    /** A governed solve: the settled result plus the effective (throttle-scaled) branch conductances. */
    private record Governed(NetworkSolver.Result result, List<BranchSpec> branches) {}

    /**
     * Solve the network, enforcing each fluid valve's throttle as a THROUGHPUT GOVERNOR: a branch a
     * player has cranked to {@code throttle} (0..1) of full may carry at most {@code throttle × its
     * fully-open flow}, so "let through 50%" always halves the flow — wherever the valve sits.
     *
     * A valve is really a variable resistance, and a resistance in series with a strong pump (whose
     * internal conductance dominates the loop) barely limits the flow; scaling the pipe conductance
     * therefore did almost nothing on a pumped run. Instead this finds, per throttled branch, the
     * conductance scale that makes its flow hit the target, by fixed-point relaxation: solve fully
     * open to read the reference flow, set each target to {@code throttle × reference}, then repeatedly
     * nudge each branch's conductance by {@code target / |flow|} and re-solve. Reducing a branch's
     * conductance monotonically reduces its flow, so this converges; a slack valve in series with a
     * tighter one relaxes back toward fully open (its flow already sits under its target), so the
     * tightest one governs. Only networks that actually hold a part-closed valve pay the extra solves.
     */
    private Governed governedSolve() {
        double suctionLimit = PipesNPhysicsConfig.SUCTION_LIMIT.get();
        int branchCount = branches.size();
        boolean anyThrottled = false;
        for (int b = 0; b < branchCount; b++) {
            if (meta.get(b).throttle() < 1 - 1e-6) { anyThrottled = true; break; }
        }
        if (!anyThrottled) {
            return new Governed(NetworkSolver.solve(nodeSpecs, branches, 1, suctionLimit), branches);
        }

        double[] scale = new double[branchCount];
        double[] target = new double[branchCount];
        Arrays.fill(scale, 1);
        Arrays.fill(target, Double.NaN);

        List<BranchSpec> effective = branches;
        NetworkSolver.Result result = NetworkSolver.solve(nodeSpecs, branches, 1, suctionLimit);
        for (int round = 0; round < GOVERNOR_MAX_ROUNDS; round++) {
            if (round == 0) {
                // First solve is fully open (all scales 1): its flows are the reference the
                // throttle percentages apply to.
                for (int b = 0; b < branchCount; b++) {
                    double throttle = meta.get(b).throttle();
                    if (throttle < 1 - 1e-6) target[b] = throttle * Math.abs(result.flows()[b]);
                }
            }
            boolean converged = true;
            for (int b = 0; b < branchCount; b++) {
                if (Double.isNaN(target[b]) || target[b] < GOVERNOR_MIN_FLOW) continue;
                double flow = Math.abs(result.flows()[b]);
                double ratio = target[b] / Math.max(flow, GOVERNOR_MIN_FLOW);
                // Over target → choke it down; under target with room to open → relax back toward
                // fully open. Either way multiply the scale by the ratio and clamp to (0, 1].
                if (flow > target[b] * (1 + GOVERNOR_TOLERANCE)
                        || (scale[b] < 1 && flow < target[b] * (1 - GOVERNOR_TOLERANCE))) {
                    scale[b] = Math.clamp(scale[b] * ratio, 1e-4, 1);
                    converged = false;
                }
            }
            if (converged) break;
            effective = scaleConductance(scale);
            result = NetworkSolver.solve(nodeSpecs, effective, 1, suctionLimit);
        }
        return new Governed(result, effective);
    }

    /** A copy of the branch list with each branch's conductance multiplied by {@code scale[b]}. */
    private List<BranchSpec> scaleConductance(double[] scale) {
        List<BranchSpec> scaled = new ArrayList<>(branches.size());
        for (int b = 0; b < branches.size(); b++) {
            BranchSpec spec = branches.get(b);
            scaled.add(scale[b] == 1 ? spec
                    : new BranchSpec(spec.a(), spec.b(), spec.conductance() * scale[b], spec.emf(),
                            spec.allowedSign(), spec.crestHeight(), spec.crestFloor(),
                            spec.crestPos(), spec.crestWet(), spec.primeAllowance()));
        }
        return scaled;
    }

    // ------------------------------------------------------------------ result recording

    /** Record the solved flows, stall flags, rest fluids, and pump loads; whether any branch really moves. */
    private boolean recordBranchResults(NetworkSolver.Result result, int[] island) {
        // Which hydraulic islands hold a SUPPLY (a non-empty source), so a pump dead-heading a
        // shut gate is only flagged "held" when it actually has water behind it — a pump placed
        // where an open end was develops a head but holds NOTHING, and must not render a column.
        Set<Integer> suppliedIslands = new HashSet<>();
        for (int i = 0; i < canSupply.size(); i++) {
            if (canSupply.get(i)) suppliedIslands.add(island[i]);
        }

        boolean active = false;
        for (int b = 0; b < branches.size(); b++) {
            int edgeIndex = meta.get(b).edgeIndex();
            double flow = result.flows()[b];
            results.edgeFlow[edgeIndex] += flow;
            active |= Math.abs(flow) > FlowSolver.ACTIVE_FLOW_EPS;

            // A pump driving out toward a shut gate HOLDS its column up to it — but only if its
            // island has a supply (see above). No flow crosses the gate; the head doesn't reset.
            if (meta.get(b).driveNode() >= 0) {
                Edge edge = graph.edge(edgeIndex);
                int gateNode = graph.node(edge.a()).isClosedGate() ? edge.a()
                        : graph.node(edge.b()).isClosedGate() ? edge.b() : -1;
                if (gateNode >= 0) {
                    int pumpSolver = solverIndex[edge.other(gateNode)];
                    if (pumpSolver >= 0 && suppliedIslands.contains(island[pumpSolver])) {
                        results.heldEdges.add(edgeIndex);
                    }
                }
            }

            // The fluid that fills this run even at rest — but ONLY if the edge's island has a
            // SOURCE of it. A run with no supply holds nothing at rest and must render DRY, not
            // phantom water: this is the single invariant behind every "shut valve shows water on
            // the far side" report — the downstream of a shut gate (into an empty tank, an open
            // end, or an unsupplied pump) is a sourceless island. Passes run largest-volume first,
            // so the dominant fluid claims the edge for static rendering.
            if (suppliedIslands.contains(island[branches.get(b).a()])
                    || suppliedIslands.contains(island[branches.get(b).b()])) {
                results.restFluids.putIfAbsent(edgeIndex, sample);
            }

            if (result.crestBlocked()[b]) {
                results.blockedEdges.add(edgeIndex);
                results.edgeReasons.putIfAbsent(edgeIndex, Solution.Reason.CREST);
            }
            if (result.backflowBlocked()[b] && branches.get(b).emf() != 0) {
                results.noHeadEdges.add(edgeIndex);
            }
            // A check valve HOLDING pressure back: a zero-EMF branch a one-way valve signed was
            // deactivated because the head gradient opposed that sign — the run is stopped by the
            // valve, not "settled". (A pump branch in the same shape is NO_HEAD above; a gate that
            // contradicted a pump outright never assembled and was flagged at assembly.)
            if (result.backflowBlocked()[b] && branches.get(b).emf() == 0
                    && meta.get(b).gateSign() != 0) {
                results.blockedEdges.add(edgeIndex);
                results.edgeReasons.putIfAbsent(edgeIndex, Solution.Reason.CHECK_VALVE);
            }
            // A DEAD CONDUIT: the run's own one-way sign (a lip, a pump's check valve) contradicts a
            // full endpoint's give-only clamp, so it carries no flow either way. When that pre-existing
            // sign is non-zero the pipe is a continuous column pressed against the full tank (its
            // opening rises above the waterline, or a pump dead-heads it) and must render FULL — mark
            // it SINK_FULL. A bare contradiction with no prior sign (a U below two full tanks) is
            // already submerged and settles, so it is left unmarked. Re-derived from the solved
            // saturation, replacing the old assembly-time fullDeadlock/preFullSign block.
            int preFullSign = branches.get(b).allowedSign();
            if (preFullSign != 0 && deadConduitSign(preFullSign,
                    result.saturation()[branches.get(b).a()],
                    result.saturation()[branches.get(b).b()]) == Integer.MIN_VALUE) {
                results.stalledEdges.add(edgeIndex);
                results.edgeReasons.putIfAbsent(edgeIndex, Solution.Reason.SINK_FULL);
            }
            if (Math.abs(flow) > Math.max(FlowSolver.ACTIVE_FLOW_EPS, results.strongestEdgeFlow[edgeIndex])) {
                results.strongestEdgeFlow[edgeIndex] = Math.abs(flow);
                results.edgeFluids.put(edgeIndex, sample);
            }

            recordPumpLoad(meta.get(b), branches.get(b), flow, result.active()[b]);
        }
        return active;
    }

    /** The brigade executes this pass's flows through the pipes' stored volume at apply time. */
    private void recordPass(NetworkSolver.Result result) {
        double[] passFlow = new double[graph.edges().size()];
        boolean passFlows = false;
        for (int b = 0; b < branches.size(); b++) {
            passFlow[meta.get(b).edgeIndex()] += result.flows()[b];
            passFlows |= Math.abs(result.flows()[b]) >= 0.5;
        }
        if (passFlows) results.passes.add(new Solution.FlowPass(sample, passFlow));
    }

    /**
     * Pressurized but nothing moved (sink full, source undrainable): the pass is STALLED —
     * distinguish it from genuine flow so the player isn't shown movement that never happens.
     * A later pass that really moves fluid over the same edge overrides the stall for display.
     */
    private void classifyStalls(NetworkSolver.Result result, TransferPlan plan) {
        Solution.Reason stallReason = plan.hadSource()
                ? Solution.Reason.SINK_FULL : Solution.Reason.SOURCE_DRY;
        for (int b = 0; b < branches.size(); b++) {
            int rounded = (int) Math.round(Math.abs(result.flows()[b]));
            if (rounded < 1) continue;
            int edgeIndex = meta.get(b).edgeIndex();
            // This pass drives that run, so it owns it for the rest of the tick — but ONLY if it
            // has a source to drive: a SOURCELESS pass moves nothing into the pipes, and claiming
            // on the solved number alone walls the fluid that really could flow there. The solve
            // is fluid-blind about supply (a column's head and capacitance come from its TOTAL
            // fill, so a basin holding 394 mB of diesel drives a phantom branch in the WATER pass
            // too), and that phantom, claiming first, left the diesel permanently walled off its
            // own line — solved=3, actual=0, the run bone dry. A pass that DOES have a source
            // still claims while its endpoints stall, since its brigade goes on filling the run.
            if (plan.hadSource()) results.claimedRuns.add(edgeIndex);
            if (plan.plannedMb() > 0) {
                results.movingEdges.add(edgeIndex);
            } else {
                results.stalledEdges.add(edgeIndex);
                results.edgeReasons.putIfAbsent(edgeIndex, stallReason);
            }
        }
    }

    /**
     * Capture a running pump's operating point for the goggle load breakdown.
     * From {@code q = G · (emf − Δh)} the head fought is {@code Δh = emf − q/G} (left
     * UNCLAMPED: negative means gravity assists, which the goggle shows rather than
     * blaming friction), and the friction factor is {@code G / internalG} (below 1
     * only when the pipe run, not the pump itself, caps the flow). Emitted only for a
     * single-pump push branch carrying real flow; idle, dead-headed, and ambiguous
     * twin-pump branches are left out so the goggle shows just the bar. When several
     * fluid passes could claim one pump, the busiest (highest flow) wins so the
     * readout is deterministic.
     */
    private void recordPumpLoad(BranchMeta branchMeta, BranchSpec branch, double flow, boolean branchActive) {
        if (branchMeta.driveNode() < 0 || !branchActive) return;
        double emf = branchMeta.driveHead();
        double branchConductance = branch.conductance();
        double drivenFlow = Math.abs(flow);
        if (emf <= 1e-6 || branchConductance <= 1e-9 || drivenFlow <= FlowSolver.ACTIVE_FLOW_EPS) return;
        double against = emf - drivenFlow / branchConductance;
        double friction = Math.max(0, Math.min(1, branchConductance / branchMeta.driveInternalConductance()));
        Solution.PumpLoad load = new Solution.PumpLoad(emf, against, friction, drivenFlow);
        results.pumpLoads.merge(branchMeta.driveNode(), load,
                (old, fresh) -> fresh.drivingFlow() > old.drivingFlow() ? fresh : old);
    }

    // ------------------------------------------------------------------ islands & signs

    /**
     * Connected-component id per solver node over the conducting (active) branches,
     * so transfer planning can tell which columns are actually plumbed together this
     * tick. Branches the solver dropped (closed valve / off pump / broken crest) are
     * absent, so the halves they used to join fall into separate components.
     */
    private int[] islands(NetworkSolver.Result result) {
        UnionFind unionFind = new UnionFind(result.heads().length);
        for (int b = 0; b < branches.size(); b++) {
            if (!result.active()[b]) continue;
            // A DEAD CONDUIT (a run whose own sign contradicts a full endpoint's give-only clamp)
            // carries no flow either way — a barrier, exactly like a closed valve. It must SPLIT the
            // islands so a source on one side cannot spill surplus into an open sink on the other.
            // (The solver keeps it "active" with zeroed conductance; the old code dropped it to
            // inactive, which is what made it split — reproduce that here.)
            if (deadConduitSign(branches.get(b).allowedSign(),
                    result.saturation()[branches.get(b).a()],
                    result.saturation()[branches.get(b).b()]) == Integer.MIN_VALUE) continue;
            unionFind.union(branches.get(b).a(), branches.get(b).b());
        }
        return unionFind.roots();
    }

    /**
     * A branch's effective one-way sign after combining its static constraint with the saturation
     * of each solver endpoint (mirroring {@code NetworkSolver}), or {@link Integer#MIN_VALUE} when
     * they contradict — a dead conduit. A full node ({@code +1}) gives only (flow OUT); flow out of
     * endpoint {@code a} is {@code a→b} ({@code +1}), out of {@code b} is {@code b→a} ({@code -1}),
     * so the induced signs are {@code satA} and {@code -satB}.
     */
    static int deadConduitSign(int staticSign, int satA, int satB) {
        int sign = staticSign;
        if (satA != 0) sign = combineSign(sign, satA);
        if (sign != Integer.MIN_VALUE && satB != 0) sign = combineSign(sign, -satB);
        return sign;
    }

    /** Merge one-way constraints; {@code Integer.MIN_VALUE} marks a contradiction. */
    private static int combineSign(int current, int wanted) {
        if (current == Integer.MIN_VALUE || current == -wanted) return Integer.MIN_VALUE;
        return wanted;
    }
}
