package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.CreateFluidCompat;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.solve.Apportion;
import de.devin.pipesnphysics.engine.solve.NetworkSolver;
import de.devin.pipesnphysics.engine.solve.UnionFind;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.BranchSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.NodeSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates one tick of a pipe network into {@link NetworkSolver} terms and back.
 *
 * Model summary (see NetworkSolver for the math):
 *   - every fluid endpoint becomes a reservoir whose head is its fluid surface height,
 *   - every pipe run becomes a conductance scaled by 1/length and the fluid's viscosity,
 *   - every pump becomes an EMF of |RPM| · head-per-RPM pushing out of its FACING side,
 *     with check valves on both flanks (Create pumps block reverse flow and act as
 *     closed valves when unpowered),
 *   - a connection whose opening sits above the tank's surface cannot draw fluid out
 *     (it would pull air), only push in,
 *   - a run whose highest cell sits more than the suction limit above the local head
 *     cannot hold a liquid column and carries nothing (the siphon rule).
 *
 * Networks holding several different fluids are solved one fluid at a time on the
 * shared topology; an endpoint that can neither give nor take the pass fluid is a wall
 * for it (a single-fluid tank of the wrong fluid), while a MULTI-FLUID sink — a basin
 * keeps each ingredient in its own segment — still joins as a sink. Empty endpoints are
 * claimed by the most plentiful fluid first. Fluids never mix inside a tick.
 *
 * The solver is read-only: it SIMULATE-probes capabilities but never moves fluid.
 * {@link FluidEngine#apply} executes the returned transfers.
 */
public final class FlowSolver {
    private static final double MIN_PUMP_SPEED = 0.01;
    private static final double LIP_DRAIN_RATE = 0.5;
    private static final double LIP_DREGS_MB = 4;
    private static final double ACTIVE_FLOW_EPS = 0.05;
    private static final double FLOW_TOLERANCE = 1.0e-7;

    /** Valve-throttle governor: max relaxation rounds, and how close to the target flow is "converged". */
    private static final int GOVERNOR_MAX_ROUNDS = 24;
    private static final double GOVERNOR_TOLERANCE = 0.02;
    /** Below this fully-open flow (mB/t) a throttled run has nothing worth governing. */
    private static final double GOVERNOR_MIN_FLOW = 0.05;

    private FlowSolver() {}

    public static Solution solve(Level level, Graph graph) {
        if (graph.isEmpty() || graph.edges().isEmpty()) return Solution.idle(graph);

        Columns columns = Columns.collect(level, graph);
        if (columns.distinct.isEmpty()) return Solution.idle(graph);

        List<FluidStack> groupSamples = groupSamplesByVolume(columns.distinct);
        if (groupSamples.isEmpty()) return Solution.idle(graph);

        Map<Integer, PumpState> pumps = collectPumps(level, graph);
        // Per-edge data that does NOT depend on the fluid (the valve throttle and the crest geometry):
        // resolve it ONCE here rather than re-scanning every pipe cell's BE / world-Y on every fluid pass.
        Map<Integer, EdgeStatics> edgeStatics = computeEdgeStatics(level, graph);

        GroupResults results = new GroupResults(graph.edges().size());
        Set<BlockPos> claimedEmpties = new HashSet<>();
        boolean active = false;

        for (FluidStack sample : groupSamples) {
            active |= solveGroup(level, graph, columns, pumps, edgeStatics, sample, claimedEmpties, results);
        }

        settleBlockedRuns(graph, columns, results);

        Set<Integer> stalled = new HashSet<>(results.stalledEdges);
        stalled.removeAll(results.movingEdges);
        Set<Integer> noHead = new HashSet<>(results.noHeadEdges);
        noHead.removeAll(results.movingEdges);
        Set<Integer> blocked = new HashSet<>(results.blockedEdges);
        blocked.removeAll(results.movingEdges);
        Set<Integer> held = new HashSet<>(results.heldEdges);
        held.removeAll(results.movingEdges);
        return new Solution(toEdgeFlows(graph, results.edgeFlow), results.transfers,
                results.nodeHeads, results.nodeCeilings, results.nodeAnchors,
                results.edgeFluids, results.restFluids, blocked, stalled, noHead, held,
                results.edgeReasons, results.pumpLoads, active);
    }

    /** Accumulators shared by the per-fluid passes of one solve. */
    private static final class GroupResults {
        final double[] edgeFlow;
        final double[] strongestEdgeFlow;
        final Map<Integer, Double> nodeHeads = new HashMap<>();
        final Map<Integer, Double> nodeCeilings = new HashMap<>();
        final Map<Integer, Double> nodeAnchors = new HashMap<>();
        final Map<Integer, FluidStack> edgeFluids = new HashMap<>();
        final Map<Integer, FluidStack> restFluids = new HashMap<>();
        final Set<Integer> blockedEdges = new HashSet<>();
        final Set<Integer> stalledEdges = new HashSet<>();
        final Set<Integer> noHeadEdges = new HashSet<>();
        final Set<Integer> heldEdges = new HashSet<>();
        final Set<Integer> movingEdges = new HashSet<>();
        final Map<Integer, Solution.Reason> edgeReasons = new HashMap<>();
        final Map<Integer, Solution.PumpLoad> pumpLoads = new HashMap<>();
        final List<Solution.Transfer> transfers = new ArrayList<>();

        GroupResults(int edgeCount) {
            edgeFlow = new double[edgeCount];
            strongestEdgeFlow = new double[edgeCount];
        }
    }

    /**
     * A BLOCKED run — an unpowered pump, a shut filter — that touches a reservoir STILL HOLDING fluid
     * has that fluid sitting in the pipe up to the blockage: it must render as settled water, not
     * blank. The blocked branch never assembled, so it got no rest fluid or heads; supply them here
     * from the filled reservoir (flat at its surface, so {@code restEdge} fills the submerged cells).
     * Gated on the endpoint reservoir being NON-EMPTY, so the downstream of a shut gate into an empty
     * tank still renders DRY (the "no phantom water past a barrier" invariant). Moving edges are
     * skipped — a later pass carried real flow across the same cut.
     */
    private static void settleBlockedRuns(Graph graph, Columns columns, GroupResults results) {
        for (int edgeIndex : results.blockedEdges) {
            if (results.movingEdges.contains(edgeIndex) || results.restFluids.containsKey(edgeIndex)) continue;
            Edge edge = graph.edge(edgeIndex);
            BoundaryColumn supply = filledReservoir(columns, edge.a());
            if (supply == null) supply = filledReservoir(columns, edge.b());
            if (supply == null) continue;
            boolean gas = supply.contents().getFluid().getFluidType().isLighterThanAir();
            double head = columnHead(supply, gas);
            results.restFluids.put(edgeIndex, supply.contents().copyWithAmount(1));
            results.nodeHeads.putIfAbsent(edge.a(), head);
            results.nodeHeads.putIfAbsent(edge.b(), head);
        }
    }

    /** The finite reservoir column at a graph node if it currently HOLDS fluid, else null. */
    private static BoundaryColumn filledReservoir(Columns columns, int node) {
        BoundaryColumn column = columns.byNode.get(node);
        return column != null && column.isFiniteReservoir() && !column.isEmpty() ? column : null;
    }

    // ------------------------------------------------------------------ columns

    private static final class Columns {
        final Map<Integer, BoundaryColumn> byNode = new HashMap<>();
        final List<BoundaryColumn> distinct = new ArrayList<>();

        static Columns collect(Level level, Graph graph) {
            Columns columns = new Columns();
            // If ANY open end on this network spilled recently, hold off finite-source
            // intake everywhere on it — the network must not suck back a block it (or a
            // sibling mouth, after the spill flows over) just spat out.
            int cooldown = PipesNPhysicsConfig.OPEN_END_INTAKE_COOLDOWN_TICKS.get();
            boolean networkSpilled = false;
            for (Node node : graph.nodes()) {
                if (node.isOpenEnd() && OpenEndPipes.recentlySpilled(level, node.pos(), cooldown)) {
                    networkSpilled = true;
                    break;
                }
            }
            Map<BlockPos, BoundaryColumn> byIdentity = new LinkedHashMap<>();
            for (Node node : graph.nodes()) {
                BoundaryColumn resolved;
                if (node.isHandler()) {
                    resolved = BoundaryColumn.resolve(level, node);
                    // Feed the relay detector this handler's live contents so it can spot a block that
                    // spontaneously gains fluid (a relay) versus one we merely fill (see RelayDetector).
                    if (resolved != null) {
                        RelayDetector.observe(level, resolved.accessPos(),
                                resolved.contents().getFluid(), resolved.contentMb());
                    }
                } else if (node.isOpenEnd()) {
                    resolved = BoundaryColumn.forOpenEnd(level, node, networkSpilled);
                } else {
                    continue;
                }
                if (resolved == null) continue;
                BoundaryColumn column = byIdentity.computeIfAbsent(resolved.identity(), k -> {
                    columns.distinct.add(resolved);
                    return resolved;
                });
                column.addMemberNode(node.index());
                columns.byNode.put(node.index(), column);
            }
            return columns;
        }
    }

    private static List<FluidStack> groupSamplesByVolume(List<BoundaryColumn> columns) {
        List<FluidStack> samples = new ArrayList<>();
        List<Double> volumes = new ArrayList<>();
        for (BoundaryColumn column : columns) {
            if (column.isEmpty()) continue;
            // An infinite source (pulley / open-end intake) reports a brimming stand-in
            // capacity, not real inventory; counting it would let a single atmospheric
            // mouth outrank every real tank and seize the largest-volume-first pass.
            // Register its fluid so a pass still runs, but contribute zero to the tally.
            double volume = column.isInfiniteSource() ? 0 : column.contentMb();
            int index = indexOfSameFluid(samples, column.contents());
            if (index < 0) {
                samples.add(column.contents().copyWithAmount(1));
                volumes.add(volume);
            } else {
                volumes.set(index, volumes.get(index) + volume);
            }
        }
        List<FluidStack> ordered = new ArrayList<>(samples);
        ordered.sort((x, y) -> Double.compare(
                volumes.get(samples.indexOf(y)), volumes.get(samples.indexOf(x))));
        return ordered;
    }

    private static int indexOfSameFluid(List<FluidStack> samples, FluidStack stack) {
        for (int i = 0; i < samples.size(); i++) {
            if (FluidStack.isSameFluidSameComponents(samples.get(i), stack)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ pumps

    /**
     * A pump is an EMF source with a pump curve, not an unlimited-flow ideal source:
     * its internal conductance caps free-flow throughput at roughly
     * {@code |RPM| · flowPerRpm} mB/tick. Without that cap the solver would draw
     * enormous flows whose friction drawdown drags suction-side heads far below the
     * pipes and falsely trips the cavitation gate.
     */
    private record PumpState(boolean open, double head, Direction pushSide,
                             double internalConductance) {}

    private static Map<Integer, PumpState> collectPumps(Level level, Graph graph) {
        double headPerRpm = PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();
        double flowPerRpm = PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
        Map<Integer, PumpState> pumps = new HashMap<>();
        for (Node pump : graph.pumps()) {
            float speed = level.getBlockEntity(pump.pos()) instanceof KineticBlockEntity kinetic
                    ? kinetic.getSpeed() : 0;
            double mult = CreateFluidCompat.isCentrifugalPump(level, pump.pos())
                    ? CreateFluidCompat.PERFORMANCE_MULTIPLIER : 1.0;
            double head = Math.abs(speed) * headPerRpm * mult;
            pumps.put(pump.index(), new PumpState(isPumpRunning(level, pump), head, pump.pumpFacing(),
                    flowPerRpm / headPerRpm));
        }
        return pumps;
    }

    /**
     * Whether a pump is spun up enough to develop head and behave as an OPEN check valve.
     * A pump below this speed (or whose facing has not resolved) is a closed valve: it
     * neither drives flow nor lets fluid pass. Shared with {@link EngineTickHandler}, which
     * keeps a network holding a running pump on the fast re-check heartbeat even while it
     * sits idle — the pump is ARMED, momentarily blocked by a full sink or a source below
     * its draw lip, and must resume the instant either changes (neither fires a block event).
     */
    public static boolean isPumpRunning(Level level, Node pump) {
        if (pump.pumpFacing() == null) return false;
        return level.getBlockEntity(pump.pos()) instanceof KineticBlockEntity kinetic
                && Math.abs(kinetic.getSpeed()) > MIN_PUMP_SPEED;
    }

    // ------------------------------------------------------------------ one fluid pass

    private record BranchMeta(int edgeIndex, BoundaryColumn columnA, BoundaryColumn columnB,
                              double lipA, double lipB,
                              int pumpNode, double pumpHead, double pumpInternalG,
                              double throttle) {}

    private static boolean solveGroup(Level level, Graph graph, Columns columns,
                                      Map<Integer, PumpState> pumps,
                                      Map<Integer, EdgeStatics> edgeStatics, FluidStack sample,
                                      Set<BlockPos> claimedEmpties, GroupResults results) {
        FluidType type = sample.getFluid().getFluidType();
        boolean gas = type.isLighterThanAir();
        double viscosityScale = 1000.0 / Math.max(1, type.getViscosity());
        double conductancePerTile = PipesNPhysicsConfig.PIPE_CONDUCTANCE.get() * viscosityScale;

        int[] solverIndex = new int[graph.nodes().size()];
        Arrays.fill(solverIndex, -1);
        List<NodeSpec> nodeSpecs = new ArrayList<>();
        List<Boolean> canSupply = new ArrayList<>();
        List<BoundaryColumn> participants = new ArrayList<>();
        Map<BoundaryColumn, Integer> columnIndex = new HashMap<>();

        for (BoundaryColumn column : columns.distinct) {
            if (!participates(level, column, sample, claimedEmpties)) continue;
            int index = nodeSpecs.size();
            nodeSpecs.add(columnSpec(column, gas));
            canSupply.add(!column.isEmpty());
            participants.add(column);
            columnIndex.put(column, index);
            for (int member : column.memberNodes()) solverIndex[member] = index;
        }
        // A SINGLE reservoir still solves: zero flow (nothing to move it TO), but it records the
        // settled display heads + restFluids, so a pipe dead-ended below a lone tank's surface —
        // e.g. a running pump capped by a solid block on its push side — renders the resting water
        // instead of blanking. A walled neighbour assembles no branch (its node has no solver index),
        // so the pass still bails at `branches.isEmpty()`; this only fires for a real conducting dead end.
        if (participants.isEmpty()) return false;

        for (Node node : graph.nodes()) {
            if (node.isHandler() || node.isOpenEnd() || node.isClosedGate()) continue;
            solverIndex[node.index()] = nodeSpecs.size();
            nodeSpecs.add(new NodeSpec(0, 0));
            canSupply.add(false);
        }

        // A closed gate (a fully-shut valve) is a WALL: give each incident edge its OWN
        // zero-capacitance dead-end node so no flow crosses it. A pump on one side then
        // dead-heads the gate (the implicit-Euler solve yields head = supply + pump boost —
        // the held head, with zero flow), while the far side settles to its reservoir.
        // Keyed (gateNode, edge) so assembleBranch resolves the right dead-end per edge.
        Map<Long, Integer> gateEdgeIndex = new HashMap<>();
        for (Node node : graph.nodes()) {
            if (!node.isClosedGate()) continue;
            for (Edge edge : graph.edgesOf(node.index())) {
                gateEdgeIndex.put(gateKey(node.index(), edge.index()), nodeSpecs.size());
                nodeSpecs.add(new NodeSpec(0, 0));
                canSupply.add(false);
            }
        }

        List<BranchSpec> branches = new ArrayList<>();
        List<BranchMeta> meta = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            assembleBranch(level, graph, columns, pumps, edgeStatics.get(edge.index()), edge,
                    solverIndex, gateEdgeIndex, sample, gas, conductancePerTile, branches, meta, results);
        }
        if (branches.isEmpty()) return false;

        Governed governed = solveGoverned(nodeSpecs, branches, meta, 1,
                PipesNPhysicsConfig.SUCTION_LIMIT.get());
        NetworkSolver.Result result = governed.result();
        // Downstream (pump load, display, transfers) reads the EFFECTIVE conductances the governor
        // settled on, so a throttled run's readouts match the flow it actually solved.
        branches = governed.branches();

        recordDisplayHeads(graph, solverIndex, nodeSpecs, canSupply, branches, result,
                gas, results.nodeHeads, results.nodeCeilings, results.nodeAnchors);

        // Which hydraulic islands hold a SUPPLY (a non-empty source), so a pump dead-heading a
        // shut gate is only flagged "held" when it actually has water behind it — a pump placed
        // where an open end was develops a head but holds NOTHING, and must not render a column.
        int[] island = islands(branches, result);
        Set<Integer> suppliedIslands = new HashSet<>();
        for (int i = 0; i < canSupply.size(); i++) {
            if (canSupply.get(i)) suppliedIslands.add(island[i]);
        }

        boolean active = false;
        for (int b = 0; b < branches.size(); b++) {
            int edgeIndex = meta.get(b).edgeIndex();
            double flow = result.flows()[b];
            results.edgeFlow[edgeIndex] += flow;
            active |= Math.abs(flow) > ACTIVE_FLOW_EPS;

            // A pump driving out toward a shut gate HOLDS its column up to it — but only if its
            // island has a supply (see above). No flow crosses the gate; the head doesn't reset.
            if (meta.get(b).pumpNode() >= 0) {
                Edge e = graph.edge(edgeIndex);
                int gateNode = graph.node(e.a()).isClosedGate() ? e.a()
                        : graph.node(e.b()).isClosedGate() ? e.b() : -1;
                if (gateNode >= 0) {
                    int pumpSolver = solverIndex[e.other(gateNode)];
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
            if (Math.abs(flow) > Math.max(ACTIVE_FLOW_EPS, results.strongestEdgeFlow[edgeIndex])) {
                results.strongestEdgeFlow[edgeIndex] = Math.abs(flow);
                results.edgeFluids.put(edgeIndex, sample);
            }

            recordPumpLoad(meta.get(b), branches.get(b), flow, result.active()[b], results.pumpLoads);
        }

        TransferPlan plan = planTransfers(level, participants, columnIndex, branches, meta, result,
                sample, gas, claimedEmpties, results.transfers);

        // Pressurized but nothing moved (sink full, source undrainable): the pass
        // is STALLED — distinguish it from genuine flow so the player isn't shown
        // movement that never happens. A later pass that really moves fluid over
        // the same edge overrides the stall for display.
        Solution.Reason stallReason = plan.hadSource()
                ? Solution.Reason.SINK_FULL : Solution.Reason.SOURCE_DRY;
        for (int b = 0; b < branches.size(); b++) {
            int rounded = (int) Math.round(Math.abs(result.flows()[b]));
            if (rounded < 1) continue;
            int edgeIndex = meta.get(b).edgeIndex();
            if (plan.plannedMb() > 0) {
                results.movingEdges.add(edgeIndex);
            } else {
                results.stalledEdges.add(edgeIndex);
                results.edgeReasons.putIfAbsent(edgeIndex, stallReason);
            }
        }
        return active;
    }

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
    private static Governed solveGoverned(List<NodeSpec> nodeSpecs, List<BranchSpec> branches,
                                          List<BranchMeta> meta, double dt, double suctionLimit) {
        int m = branches.size();
        boolean anyThrottled = false;
        for (int b = 0; b < m; b++) {
            if (meta.get(b).throttle() < 1 - 1e-6) { anyThrottled = true; break; }
        }
        if (!anyThrottled) {
            return new Governed(NetworkSolver.solve(nodeSpecs, branches, dt, suctionLimit), branches);
        }

        double[] scale = new double[m];
        double[] target = new double[m];
        Arrays.fill(scale, 1);
        Arrays.fill(target, Double.NaN);

        List<BranchSpec> effective = branches;
        NetworkSolver.Result result = NetworkSolver.solve(nodeSpecs, branches, dt, suctionLimit);
        for (int round = 0; round < GOVERNOR_MAX_ROUNDS; round++) {
            if (round == 0) {
                // First solve is fully open (all scales 1): its flows are the reference the
                // throttle percentages apply to.
                for (int b = 0; b < m; b++) {
                    double throttle = meta.get(b).throttle();
                    if (throttle < 1 - 1e-6) target[b] = throttle * Math.abs(result.flows()[b]);
                }
            }
            boolean converged = true;
            for (int b = 0; b < m; b++) {
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
            effective = scaleConductance(branches, scale);
            result = NetworkSolver.solve(nodeSpecs, effective, dt, suctionLimit);
        }
        return new Governed(result, effective);
    }

    /** A governed solve: the settled result plus the effective (throttle-scaled) branch conductances. */
    private record Governed(NetworkSolver.Result result, List<BranchSpec> branches) {}

    /** A copy of the branch list with each branch's conductance multiplied by {@code scale[b]}. */
    private static List<BranchSpec> scaleConductance(List<BranchSpec> branches, double[] scale) {
        List<BranchSpec> scaled = new ArrayList<>(branches.size());
        for (int b = 0; b < branches.size(); b++) {
            BranchSpec s = branches.get(b);
            scaled.add(scale[b] == 1 ? s
                    : new BranchSpec(s.a(), s.b(), s.conductance() * scale[b], s.emf(),
                            s.allowedSign(), s.crestHeight(), s.crestPos()));
        }
        return scaled;
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
    private static boolean participates(Level level, BoundaryColumn column, FluidStack sample,
                                        Set<BlockPos> claimedEmpties) {
        IFluidHandler cap = column.handler(level);
        // An open end is decided from engine state, NEVER by probing the capability with
        // fill/drain(SIMULATE): those MUTATE the world — Create's OpenEndedPipe wipes a differing
        // buffered fluid and runs the spill-collision reaction (a lake block turning to stone)
        // BEFORE their own simulate guard, so a foreign fluid's pass corrupts the mouth. The handler
        // is still resolved above for its side effects (it populates the open-end cache and drives
        // manageSource, which apply() depends on) — that is normal per-tick management, not a probe.
        // An intake mouth gives only its own fluid; an empty outlet accepts any unclaimed pass fluid.
        if (column.isOpenEnd()) {
            if (column.isInfiniteSource()) {
                return FluidStack.isSameFluidSameComponents(column.contents(), sample);
            }
            if (claimedEmpties.contains(column.identity())) return false;
            return true;
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
     * A liquid column's head is its surface elevation. A gas column's head rises with
     * fill (compression) and falls with elevation (buoyancy) — an approximation that
     * makes gases seek upward and denser fill push outward.
     *
     * A liquid open end is a fixed boundary at its MOUTH, not a column that rises with
     * whatever block sits in front of it. Modelling a spilled source block as a
     * brimming reservoir (surface at the block top) makes the engine reclaim its own
     * spill — place a block, read it as full, drain it back, place it again, forever.
     * Pinning the head at the mouth gives spill and intake a single threshold, so a
     * broken pipe drains to the mouth level and settles instead of flickering, and an
     * intake mouth (see {@link BoundaryColumn#forOpenEnd}) draws in only while the
     * network sits below the mouth ("vacuum"), never while it would spill.
     */
    private static double columnHead(BoundaryColumn column, boolean gas) {
        if (!gas && column.isOpenEnd()) return column.baseY() + 0.5;
        // On a tilted sub-level the fill rises along the column's local-up, so it adds only
        // fillHeight·cos(tilt) of world height (fillScale = 1 when level). Without this a tilted
        // tank's surface is over-estimated and spills out an open end that is physically above it.
        double fillHeight = column.fillFraction() * column.heightBlocks() * column.fillScale();
        return NetworkSolver.surfaceHead(column.baseY(), fillHeight, gas);
    }

    /**
     * The solver node for a column. A finite reservoir carries a capacity CEILING — its head when
     * full (fill = height) — so the active set clamps it to GIVE-ONLY when full: the box-constrained
     * dual of the empty→receive-only wall, replacing the old emf-gated fullDeadlock/preFullSign
     * special-casing (see {@link NetworkSolver}). The EMPTY→receive-only side deliberately stays a
     * static wall in assembleBranch (its lip-contradiction early-return is load-bearing for the
     * drained-riser recede), so the floor is left unbounded. Boundaries (open ends, pulleys) keep
     * their own one-way rules and are fully unbounded. The ceiling goes through {@link
     * NetworkSolver#surfaceHead} with the same fill scale as {@code columnHead}, so a gas column
     * (head rises with fill) still reads full at its top.
     */
    private static NodeSpec columnSpec(BoundaryColumn column, boolean gas) {
        double head = columnHead(column, gas);
        if (!column.isFiniteReservoir()) return new NodeSpec(column.capacitance(), head);
        double span = column.heightBlocks() * column.fillScale();
        double ceiling = NetworkSolver.surfaceHead(column.baseY(), span, gas);
        return new NodeSpec(column.capacitance(), head, Double.NEGATIVE_INFINITY, ceiling);
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
    private static void recordPumpLoad(BranchMeta meta, BranchSpec branch, double flow,
                                       boolean active, Map<Integer, Solution.PumpLoad> pumpLoads) {
        if (meta.pumpNode() < 0 || !active) return;
        double emf = meta.pumpHead();
        double branchG = branch.conductance();
        double q = Math.abs(flow);
        if (emf <= 1e-6 || branchG <= 1e-9 || q <= ACTIVE_FLOW_EPS) return;
        double against = emf - q / branchG;
        double friction = Math.max(0, Math.min(1, branchG / meta.pumpInternalG()));
        Solution.PumpLoad load = new Solution.PumpLoad(emf, against, friction, q);
        pumpLoads.merge(meta.pumpNode(), load,
                (old, fresh) -> fresh.drivingFlow() > old.drivingFlow() ? fresh : old);
    }

    // ------------------------------------------------------------------ branch assembly

    private static void assembleBranch(Level level, Graph graph, Columns columns,
                                       Map<Integer, PumpState> pumps, EdgeStatics statics, Edge edge,
                                       int[] solverIndex, Map<Long, Integer> gateEdgeIndex,
                                       FluidStack sample,
                                       boolean gas,
                                       double conductancePerTile,
                                       List<BranchSpec> branches, List<BranchMeta> meta,
                                       GroupResults results) {
        Set<Integer> blockedEdges = results.blockedEdges;
        int solverA = solverNodeFor(graph, solverIndex, gateEdgeIndex, edge, edge.a());
        int solverB = solverNodeFor(graph, solverIndex, gateEdgeIndex, edge, edge.b());
        if (solverA < 0 || solverB < 0 || solverA == solverB) return;
        if (!runAcceptsFluid(level, graph, edge, sample)) {
            blockedEdges.add(edge.index());
            results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.VALVE);
            return;
        }

        // A valve the shaft has opened still caps the run by the angle the player dialed
        // in; 0 degrees shuts it as hard as the shaft would. The factor is applied to the
        // FINAL conductance below (after the pump-internal cap), not here — a pump's tiny
        // internal conductance otherwise masks the throttle on every pumped run. Fluid-independent,
        // so it (and the crest below) is precomputed once per edge (see computeEdgeStatics).
        double throttle = statics.throttle();
        if (throttle <= 0) {
            blockedEdges.add(edge.index());
            results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.VALVE);
            return;
        }

        double conductance = conductancePerTile / (edge.length() + 1);
        double emf = 0;
        int allowedSign = 0;
        int driveNode = -1;
        double driveHead = 0;
        double driveInternalG = 0;

        for (int side = 0; side < 2; side++) {
            int nodeIndex = side == 0 ? edge.a() : edge.b();
            PumpState pump = pumps.get(nodeIndex);
            if (pump == null) continue;
            if (!pump.open()) {
                blockedEdges.add(edge.index());
                results.edgeReasons.putIfAbsent(edge.index(), Solution.Reason.PUMP_OFF);
                return;
            }

            Node pumpNode = graph.node(nodeIndex);
            BlockPos toward = PipeGeometry.adjacentCell(graph, edge, nodeIndex);
            int outSign = side == 0 ? +1 : -1;

            if (toward.equals(pumpNode.pos().relative(pump.pushSide()))) {
                emf += outSign * pump.head();
                allowedSign = combineSign(allowedSign, outSign);
                conductance = Math.min(conductance, pump.internalConductance());
                // The pump driving this run; a second pump pushing into the same
                // edge makes the load attribution ambiguous, so flag it off.
                driveNode = driveNode < 0 ? nodeIndex : -2;
                driveHead = pump.head();
                driveInternalG = pump.internalConductance();
            } else if (toward.equals(pumpNode.pos().relative(pumpNode.effectivePullSide()))) {
                allowedSign = combineSign(allowedSign, -outSign);
            } else {
                blockedEdges.add(edge.index());
                return;
            }
            if (allowedSign == Integer.MIN_VALUE) {
                blockedEdges.add(edge.index());
                return;
            }
        }

        double lipA = Double.NaN;
        double lipB = Double.NaN;
        double crestHeight = Double.NaN;
        double crestPos = 0;

        BoundaryColumn columnA = columns.byNode.get(edge.a());
        BoundaryColumn columnB = columns.byNode.get(edge.b());

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
        if (allowedSign == Integer.MIN_VALUE) return;

        if (!gas) {
            if (columnA != null) {
                BlockPos opening = PipeGeometry.adjacentCell(graph, edge, edge.a());
                lipA = SableCompat.getWorldY(level, opening) - 0.5;
                if (!canDrawFrom(level, graph.node(edge.a()), columnA, opening, lipA)) {
                    allowedSign = combineSign(allowedSign, -1);
                }
            }
            if (columnB != null) {
                BlockPos opening = PipeGeometry.adjacentCell(graph, edge, edge.b());
                lipB = SableCompat.getWorldY(level, opening) - 0.5;
                if (!canDrawFrom(level, graph.node(edge.b()), columnB, opening, lipB)) {
                    allowedSign = combineSign(allowedSign, +1);
                }
            }
            // A lip conflict (e.g. a pump trying to draw from below a tank's
            // waterline) is "no supply", not a fault.
            if (allowedSign == Integer.MIN_VALUE) return;

            crestHeight = statics.crestHeight();
            crestPos = statics.crestPos();
        }

        // The full→give-only DUAL of the empty rule (a full reservoir can only give, never receive)
        // is now the solver's job: a finite reservoir carries a capacity box (see columnSpec), and the
        // active set seeds it give-only when full, then walls the branch — so a backed-up run fills an
        // UPSTREAM reservoir with room instead of routing a through-current into a full TERMINAL and
        // zeroing the whole line (the "goofy_network" freeze). The dead-conduit case (a full end whose
        // opening rises above its waterline, or two full ends facing each other) and its SINK_FULL
        // render flag are re-derived from the solved saturation in solveGroup — a single uniform
        // mechanism, replacing the old per-branch emf-gated fullDeadlock/preFullSign special-casing.

        // The throttle is NOT baked into the conductance here. Scaling conductance only limits
        // the flow when the valve's own run is the binding resistor — in series with a strong
        // pump (whose tiny internal conductance dominates the loop) halving a fat pipe's
        // conductance barely moves the flow, so "let through 50%" did almost nothing (74→67 on
        // a real pump). Instead the throttle is a THROUGHPUT GOVERNOR applied by {@code solveGoverned}:
        // it caps the run's flow to {@code throttle × fully-open flow}, so 50% always means half,
        // wherever the valve sits. The angle is carried on the meta for that loop.
        branches.add(new BranchSpec(solverA, solverB, conductance, emf, allowedSign,
                crestHeight, crestPos));
        meta.add(new BranchMeta(edge.index(),
                columns.byNode.get(edge.a()), columns.byNode.get(edge.b()), lipA, lipB,
                driveNode, driveHead, driveInternalG, throttle));
        // Whether this is a held FEED candidate (a pump driving out toward a shut gate) is decided
        // post-solve in solveGroup, where the hydraulic islands are known — the pump only HOLDS a
        // column if it actually has a supply behind it (a source in its island).
    }

    /**
     * The solver node for an edge endpoint. A closed-gate node is a WALL — each incident edge
     * gets its OWN zero-cap dead-end node (from {@code gateEdgeIndex}) so no flow crosses it;
     * every other node uses its shared index.
     */
    private static int solverNodeFor(Graph graph, int[] solverIndex,
                                     Map<Long, Integer> gateEdgeIndex, Edge edge, int nodeIndex) {
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
     */
    private static boolean runAcceptsFluid(Level level, Graph graph, Edge edge, FluidStack sample) {
        if (edge.pipes().isEmpty()) return true;

        BlockPos previous = graph.node(edge.a()).pos();
        for (int i = 0; i < edge.pipes().size(); i++) {
            BlockPos cell = edge.pipes().get(i);
            BlockPos next = i + 1 < edge.pipes().size()
                    ? edge.pipes().get(i + 1)
                    : graph.node(edge.b()).pos();

            var behaviour = FluidPropagator.getPipe(level, cell);
            if (behaviour != null) {
                var state = level.getBlockState(cell);
                Direction fromPrevious = PipeGeometry.between(cell, previous);
                Direction fromNext = PipeGeometry.between(cell, next);
                if (fromPrevious != null && !behaviour.canPullFluidFrom(sample, state, fromPrevious)) return false;
                if (fromNext != null && !behaviour.canPullFluidFrom(sample, state, fromNext)) return false;
            }
            previous = cell;
        }
        return true;
    }

    /** Per-edge data that does not depend on the pass fluid: the valve throttle and the crest geometry. */
    private record EdgeStatics(double throttle, double crestHeight, double crestPos) {}

    /** Resolve every edge's fluid-independent {@link EdgeStatics} once, before the per-fluid passes. */
    private static Map<Integer, EdgeStatics> computeEdgeStatics(Level level, Graph graph) {
        Map<Integer, EdgeStatics> statics = new HashMap<>(graph.edges().size() * 2);
        for (Edge edge : graph.edges()) {
            double crestHeight = Double.NaN;
            double crestPos = 0;
            for (int i = 0; i < edge.pipes().size(); i++) {
                double cellY = SableCompat.getWorldY(level, edge.pipes().get(i));
                if (Double.isNaN(crestHeight) || cellY > crestHeight) {
                    crestHeight = cellY;
                    crestPos = (i + 1.0) / (edge.length() + 1);
                }
            }
            statics.put(edge.index(), new EdgeStatics(runThrottle(level, edge), crestHeight, crestPos));
        }
        return statics;
    }

    /**
     * The tightest valve throttle along a run, as a 0..1 conductance factor (1 when no
     * valve restricts it). A valve the shaft has shut is already rejected by
     * {@link #runAcceptsFluid}, so only opened valves reach here; the most-closed one
     * sets the rate.
     */
    private static double runThrottle(Level level, Edge edge) {
        if (!PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return 1;
        double factor = 1;
        for (BlockPos cell : edge.pipes()) {
            if (level.getBlockEntity(cell) instanceof ValveThrottle valve) {
                factor = Math.min(factor, valve.pipesnphysics$valveThrottle());
            }
        }
        return factor;
    }


    /**
     * Fluid can only leave a column through an opening its surface reaches. Open
     * ends are exempt: their opening is inside the fluid by construction (a pipe
     * mouth submerged in a lake), wherever it points.
     */
    private static boolean canDrawFrom(Level level, Node handlerNode, BoundaryColumn column,
                                       BlockPos opening, double lip) {
        if (column.isOpenEnd() || column.isInfiniteSource()) return true;
        double surface = columnHead(column, false);
        if (surface <= lip) return false;
        return SableCompat.canFluidReachPipe(level, handlerNode.pos(), opening, column.fillFraction());
    }

    /** Merge one-way constraints; {@code Integer.MIN_VALUE} marks a contradiction. */
    private static int combineSign(int current, int wanted) {
        if (current == Integer.MIN_VALUE || current == -wanted) return Integer.MIN_VALUE;
        return wanted;
    }

    /**
     * A branch's effective one-way sign after combining its static constraint with the saturation
     * of each solver endpoint (mirroring {@code NetworkSolver}), or {@link Integer#MIN_VALUE} when
     * they contradict — a dead conduit. A full node ({@code +1}) gives only (flow OUT); flow out of
     * endpoint {@code a} is {@code a→b} ({@code +1}), out of {@code b} is {@code b→a} ({@code -1}),
     * so the induced signs are {@code satA} and {@code -satB}.
     */
    private static int deadConduitSign(int staticSign, int satA, int satB) {
        int sign = staticSign;
        if (satA != 0) sign = combineSign(sign, satA);
        if (sign != Integer.MIN_VALUE && satB != 0) sign = combineSign(sign, -satB);
        return sign;
    }

    // ------------------------------------------------------------------ transfer planning

    /** What a pass actually scheduled, and whether either side had anything to offer. */
    private record TransferPlan(int plannedMb, boolean hadSource, boolean hadSink) {}

    private static TransferPlan planTransfers(Level level, List<BoundaryColumn> participants,
                                              Map<BoundaryColumn, Integer> columnIndex,
                                              List<BranchSpec> branches, List<BranchMeta> meta,
                                              NetworkSolver.Result result, FluidStack sample,
                                              boolean gas,
                                              Set<BlockPos> claimedEmpties,
                                              List<Solution.Transfer> transfers) {
        int maxFlow = PipesNPhysicsConfig.MAX_FLOW_PER_ENDPOINT.get();

        // Pair sources to sinks only within a hydraulic island: the set of columns
        // reachable from one another through conducting (active) branches this tick.
        // A closed valve, an off pump, or a broken crest splits the network into
        // halves the solver leaves internally balanced; without this grouping the
        // greedy pairing below would spill one half's clamped-sink surplus into a sink
        // on the OTHER side of the barrier, teleporting fluid across it.
        int[] island = islands(branches, result);

        List<BoundaryColumn> sources = new ArrayList<>();
        List<Integer> giving = new ArrayList<>();
        List<Integer> sourceIsland = new ArrayList<>();
        List<BoundaryColumn> sinks = new ArrayList<>();
        List<Integer> taking = new ArrayList<>();
        List<Integer> sinkIsland = new ArrayList<>();

        for (BoundaryColumn column : participants) {
            int node = columnIndex.get(column);
            double delta = result.netInflow()[node];
            if (delta < 0) {
                double outflow = Math.min(-delta, maxFlow);
                outflow = Math.min(outflow, lipDrainCap(column, node,
                        branches, meta, result, gas));
                // An open-end intake mouth's contentMb is the real per-tick world yield;
                // never request past it. Create's drain returns the requested amount even
                // when the body holds less (a 250 mB honey block under the 256 cap), which
                // would deposit more into the sink than left the world — fluid from nothing.
                if (column.isOpenEnd() && column.isInfiniteSource()) {
                    outflow = Math.min(outflow, column.contentMb());
                }
                int amount = (int) Math.round(outflow);
                if (amount < 1) continue;
                amount = Math.min(amount, probeDrainable(level, column, sample, amount));
                if (amount >= 1) {
                    sources.add(column);
                    giving.add(amount);
                    sourceIsland.add(island[node]);
                }
            } else if (delta > 0) {
                int amount = (int) Math.round(Math.min(delta, maxFlow));
                if (amount < 1) continue;
                amount = Math.min(amount, probeFillable(level, column, sample, amount));
                if (amount >= 1) {
                    sinks.add(column);
                    taking.add(amount);
                    sinkIsland.add(island[node]);
                }
            }
        }

        // Apportion within each hydraulic island by PROPORTIONAL share, not first-come-first-served.
        // When a source's clamped give cannot satisfy all its island's sinks, the old greedy pairing
        // let the first-discovered sink take everything and starved the rest EVERY tick — delivery
        // tracked invisible graph-discovery order ("one machine on the manifold never gets fluid
        // unless I pause the other"). Give each sink a fraction of the shortfall proportional to its
        // take (largest-remainder rounding keeps integer mB and conservation), then realise it with a
        // northwest-corner fill. The island grouping is unchanged (no fluid crosses a barrier).
        int planned = 0;
        for (int id : new LinkedHashSet<>(sourceIsland)) {
            List<Integer> srcIdx = indicesInIsland(sourceIsland, id);
            List<Integer> snkIdx = indicesInIsland(sinkIsland, id);
            if (snkIdx.isEmpty()) continue;

            int give = sumAt(giving, srcIdx);
            int take = sumAt(taking, snkIdx);
            int move = Math.min(give, take);
            if (move <= 0) continue;

            int[] srcShare = Apportion.largestRemainder(move, weightsAt(giving, srcIdx));
            int[] snkShare = Apportion.largestRemainder(move, weightsAt(taking, snkIdx));

            int j = 0;
            for (int i = 0; i < srcIdx.size(); i++) {
                BoundaryColumn source = sources.get(srcIdx.get(i));
                while (srcShare[i] > 0 && j < snkIdx.size()) {
                    if (snkShare[j] <= 0) { j++; continue; }
                    BoundaryColumn sink = sinks.get(snkIdx.get(j));
                    int amount = Math.min(srcShare[i], snkShare[j]);
                    transfers.add(new Solution.Transfer(
                            source.accessPos(), source.accessFace(),
                            sink.accessPos(), sink.accessFace(), sample.copyWithAmount(amount)));
                    if (sink.isEmpty()) claimedEmpties.add(sink.identity());
                    srcShare[i] -= amount;
                    snkShare[j] -= amount;
                    planned += amount;
                }
            }
        }
        return new TransferPlan(planned, !sources.isEmpty(), !sinks.isEmpty());
    }

    private static List<Integer> indicesInIsland(List<Integer> island, int id) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < island.size(); i++) {
            if (island.get(i) == id) out.add(i);
        }
        return out;
    }

    private static int sumAt(List<Integer> values, List<Integer> indices) {
        int sum = 0;
        for (int i : indices) sum += values.get(i);
        return sum;
    }

    private static int[] weightsAt(List<Integer> values, List<Integer> indices) {
        int[] out = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) out[i] = values.get(indices.get(i));
        return out;
    }

    /**
     * Connected-component id per solver node over the conducting (active) branches,
     * so transfer planning can tell which columns are actually plumbed together this
     * tick. Branches the solver dropped (closed valve / off pump / broken crest) are
     * absent, so the halves they used to join fall into separate components.
     */
    private static int[] islands(List<BranchSpec> branches, NetworkSolver.Result result) {
        UnionFind uf = new UnionFind(result.heads().length);
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
            uf.union(branches.get(b).a(), branches.get(b).b());
        }
        return uf.roots();
    }

    /** What the handler will really give up this tick, probed without mutating it. */
    private static int probeDrainable(Level level, BoundaryColumn column, FluidStack sample, int amount) {
        IFluidHandler cap = column.handler(level);
        // Open ends are never probed through Create's handler (see participates): an intake mouth
        // yields its own precomputed per-tick amount, a receive-only outlet gives nothing.
        if (column.isOpenEnd()) {
            return column.isInfiniteSource() ? Math.min(amount, column.contentMb()) : 0;
        }
        return cap == null ? 0
                : BoundaryColumn.drainMatching(cap, sample.copyWithAmount(amount), FluidAction.SIMULATE).getAmount();
    }

    /** What the handler will really accept this tick, probed without mutating it. */
    private static int probeFillable(Level level, BoundaryColumn column, FluidStack sample, int amount) {
        IFluidHandler cap = column.handler(level);
        // Open ends are never probed through Create's handler (see participates): an intake mouth
        // takes nothing, a receive-only outlet always accepts the spill (accumulation is at apply).
        if (column.isOpenEnd()) {
            return column.isInfiniteSource() ? 0 : amount;
        }
        return cap == null ? 0
                : cap.fill(sample.copyWithAmount(amount), FluidAction.SIMULATE);
    }

    /**
     * Bound how fast a column may drain toward the lowest opening it is currently
     * flowing out of: at most half the volume above that opening per tick, reaching
     * zero exactly at the lip where {@link #canDrawFrom} closes the connection.
     * Sharing that threshold is what lets a tank fed from a side or top connection
     * settle at the opening instead of flapping across it. The last few mB above
     * the lip may leave in one go so tanks drain to genuinely empty instead of
     * keeping an asymptotic puddle.
     */
    private static double lipDrainCap(BoundaryColumn column, int solverIdx,
                                      List<BranchSpec> branches, List<BranchMeta> meta,
                                      NetworkSolver.Result result, boolean gas) {
        if (gas || column.isOpenEnd() || column.isInfiniteSource()) return Double.MAX_VALUE;

        double minLip = Double.NaN;
        for (int b = 0; b < branches.size(); b++) {
            double flow = result.flows()[b];
            if (Math.abs(flow) <= FLOW_TOLERANCE) continue;

            BranchMeta m = meta.get(b);
            double lip = Double.NaN;
            if (m.columnA() != null && columnMatches(m.columnA(), column)
                    && branches.get(b).a() == solverIdx && flow > 0) {
                lip = m.lipA();
            } else if (m.columnB() != null && columnMatches(m.columnB(), column)
                    && branches.get(b).b() == solverIdx && flow < 0) {
                lip = m.lipB();
            }
            if (!Double.isNaN(lip) && (Double.isNaN(minLip) || lip < minLip)) minLip = lip;
        }
        if (Double.isNaN(minLip)) return Double.MAX_VALUE;

        double surface = columnHead(column, false);
        double aboveLipMb = column.capacitance() * (surface - minLip);
        if (aboveLipMb <= 0) return 0;
        return Math.max(Math.min(aboveLipMb, LIP_DREGS_MB), LIP_DRAIN_RATE * aboveLipMb);
    }

    private static boolean columnMatches(BoundaryColumn a, BoundaryColumn b) {
        return a.identity().equals(b.identity());
    }

    /**
     * Player-facing heads, used by the overlay gradient, the goggle pressure line,
     * and /pipegraph. Real reservoirs anchor them; from there they spread outward
     * over active branches, but only in directions fluid could actually move —
     * never backward through a check valve or out of a connection that cannot
     * supply. A branch that carries flow keeps its solved heads; across a ZERO-flow
     * branch the head continues unchanged, which drops the EMF jump of a
     * dead-headed pump. Pipes no reservoir can reach hold no fluid and show no
     * pressure at all — an idle pump must not paint phantom vacuum (or phantom
     * tank pressure) over dry lines.
     */
    private static void recordDisplayHeads(Graph graph, int[] solverIndex,
                                           List<NodeSpec> nodeSpecs, List<Boolean> canSupply,
                                           List<BranchSpec> branches,
                                           NetworkSolver.Result result,
                                           boolean gas,
                                           Map<Integer, Double> nodeHeads,
                                           Map<Integer, Double> nodeCeilings,
                                           Map<Integer, Double> nodeAnchors) {
        int n = nodeSpecs.size();

        // The display/planning traversals below spread heads only along PERMITTED directions, which
        // now include the capacity-box saturation the solver applied (a full column gives-only, an
        // empty one receives-only) — those no longer live in branch.allowedSign(), so fold the solved
        // saturation back in per branch. On a dead-conduit contradiction, keep the pre-full static sign
        // (as the old fullDeadlock path did), so the render stays byte-for-byte what it was.
        int[] sign = new int[branches.size()];
        for (int b = 0; b < branches.size(); b++) {
            int s = deadConduitSign(branches.get(b).allowedSign(),
                    result.saturation()[branches.get(b).a()], result.saturation()[branches.get(b).b()]);
            sign[b] = s == Integer.MIN_VALUE ? branches.get(b).allowedSign() : s;
        }

        List<List<Integer>> incident = new ArrayList<>(n);
        for (int i = 0; i < n; i++) incident.add(new ArrayList<>());
        for (int b = 0; b < branches.size(); b++) {
            if (!result.active()[b]) continue;
            incident.get(branches.get(b).a()).add(b);
            incident.get(branches.get(b).b()).add(b);
        }

        double[] display = new double[n];
        boolean[] known = new boolean[n];
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (nodeSpecs.get(i).capacitance() > 0) {
                display[i] = result.heads()[i];
                known[i] = true;
                frontier.add(i);
            }
        }
        while (!frontier.isEmpty()) {
            int current = frontier.poll();
            for (int b : incident.get(current)) {
                BranchSpec branch = branches.get(b);
                boolean fromA = branch.a() == current;
                if (sign[b] != 0 && sign[b] != (fromA ? +1 : -1)) continue;
                int other = fromA ? branch.b() : branch.a();
                if (known[other]) continue;
                display[other] = Math.abs(result.flows()[b]) > FLOW_TOLERANCE
                        ? result.heads()[other]
                        : display[current];
                known[other] = true;
                frontier.add(other);
            }
        }

        // The ceiling is the friction-free potential: how high fluid could at most
        // be pushed from each node. Seeded ONLY by reservoirs that can actually
        // supply — an empty tank drives nothing and must not anchor the field (it
        // receives its ceiling from the supply side like any pipe) — and grows by
        // each pump boost crossed along permitted directions. Unlike display heads
        // it traverses ALL assembled branches, including ones the check valves shut
        // this tick: a pump line stopped because the lift exceeds its head is
        // precisely where the player needs the ceiling readout.
        List<List<Integer>> planningIncident = new ArrayList<>(n);
        for (int i = 0; i < n; i++) planningIncident.add(new ArrayList<>());
        for (int b = 0; b < branches.size(); b++) {
            planningIncident.get(branches.get(b).a()).add(b);
            planningIncident.get(branches.get(b).b()).add(b);
        }

        // The anchor rides along with the ceiling: the supply surface a node's
        // budget is measured from. Ceiling − anchor is the total head budget;
        // elevation climbed above the anchor is the part already spent.
        double[] ceiling = new double[n];
        double[] anchor = new double[n];
        boolean[] ceilingKnown = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (nodeSpecs.get(i).capacitance() > 0 && canSupply.get(i)) {
                ceiling[i] = result.heads()[i];
                anchor[i] = result.heads()[i];
                ceilingKnown[i] = true;
                frontier.add(i);
            }
        }
        while (!frontier.isEmpty()) {
            int current = frontier.poll();
            for (int b : planningIncident.get(current)) {
                BranchSpec branch = branches.get(b);
                boolean fromA = branch.a() == current;
                if (sign[b] != 0 && sign[b] != (fromA ? +1 : -1)) continue;
                int other = fromA ? branch.b() : branch.a();
                if (ceilingKnown[other]) continue;
                double boost = fromA ? Math.max(0, branch.emf()) : Math.max(0, -branch.emf());
                ceiling[other] = ceiling[current] + boost;
                anchor[other] = anchor[current];
                ceilingKnown[other] = true;
                frontier.add(other);
            }
        }

        // Fluid on a pump's suction side WILL receive the pump's boost once it
        // passes through, so every node feeding a pump carries the boosts waiting
        // downstream: reverse-relax the best boost-sum along allowed directions
        // and add it on top. Without this, suction-side junctions and pipes read
        // ambient or slightly negative head while the line works perfectly.
        double[] boostAhead = new double[n];
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            for (int b = 0; b < branches.size(); b++) {
                BranchSpec branch = branches.get(b);
                double forward = Math.max(0, branch.emf());
                double backward = Math.max(0, -branch.emf());
                if (sign[b] >= 0) {
                    double viaB = forward + boostAhead[branch.b()];
                    if (viaB > boostAhead[branch.a()] + 1e-9) {
                        boostAhead[branch.a()] = viaB;
                        changed = true;
                    }
                }
                if (sign[b] <= 0) {
                    double viaA = backward + boostAhead[branch.a()];
                    if (viaA > boostAhead[branch.b()] + 1e-9) {
                        boostAhead[branch.b()] = viaA;
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        for (int i = 0; i < n; i++) {
            if (ceilingKnown[i]) ceiling[i] += boostAhead[i];
        }

        // A suction run no reservoir can feed — empty source tank, draw gated at
        // the lip — must still answer "what could the pumps ahead do from here".
        // Anchor each such node at the head a supply arriving right there would
        // have, plus the boosts waiting downstream; without this the pulling
        // side of an idle pump shows nothing while the pushing side reads fine.
        for (Node node : graph.nodes()) {
            int index = solverIndex[node.index()];
            if (index < 0 || ceilingKnown[index] || boostAhead[index] <= 0) continue;
            anchor[index] = anchorHead(nodeSpecs.get(index), node, gas);
            ceiling[index] = anchor[index] + boostAhead[index];
            ceilingKnown[index] = true;
        }

        for (Node node : graph.nodes()) {
            int index = solverIndex[node.index()];
            if (index < 0) continue;
            if (known[index]) nodeHeads.put(node.index(), display[index]);
            if (!ceilingKnown[index]) continue;
            Double previous = nodeCeilings.get(node.index());
            if (previous == null || ceiling[index] > previous) {
                nodeCeilings.put(node.index(), ceiling[index]);
                nodeAnchors.put(node.index(), anchor[index]);
            }
        }
    }

    /** The head a fresh supply would have if its surface sat exactly at this node. */
    private static double anchorHead(NodeSpec spec, Node node, boolean gas) {
        if (spec.capacitance() > 0) return spec.head();
        return NetworkSolver.surfaceHead(node.worldY(), 0, gas);
    }

    private static List<EdgeFlow> toEdgeFlows(Graph graph, double[] edgeFlow) {
        List<EdgeFlow> flows = new ArrayList<>(graph.edges().size());
        for (Edge edge : graph.edges()) {
            double q = edgeFlow[edge.index()];
            int mb = (int) Math.round(Math.abs(q));
            if (mb == 0) {
                flows.add(EdgeFlow.none(edge.index()));
            } else {
                flows.add(new EdgeFlow(edge.index(),
                        q > 0 ? EdgeFlow.Direction.A_TO_B : EdgeFlow.Direction.B_TO_A, mb));
            }
        }
        return flows;
    }
}
