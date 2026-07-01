package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.CreateFluidCompat;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.solve.NetworkSolver;
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

    private FlowSolver() {}

    public static Solution solve(Level level, Graph graph) {
        if (graph.isEmpty() || graph.edges().isEmpty()) return Solution.idle(graph);

        Columns columns = Columns.collect(level, graph);
        if (columns.distinct.isEmpty()) return Solution.idle(graph);

        List<FluidStack> groupSamples = groupSamplesByVolume(columns.distinct);
        if (groupSamples.isEmpty()) return Solution.idle(graph);

        Map<Integer, PumpState> pumps = collectPumps(level, graph);

        GroupResults results = new GroupResults(graph.edges().size());
        Set<BlockPos> claimedEmpties = new HashSet<>();
        boolean active = false;

        for (FluidStack sample : groupSamples) {
            active |= solveGroup(level, graph, columns, pumps, sample, claimedEmpties, results);
        }

        Set<Integer> stalled = new HashSet<>(results.stalledEdges);
        stalled.removeAll(results.movingEdges);
        Set<Integer> noHead = new HashSet<>(results.noHeadEdges);
        noHead.removeAll(results.movingEdges);
        Set<Integer> blocked = new HashSet<>(results.blockedEdges);
        blocked.removeAll(results.movingEdges);
        return new Solution(toEdgeFlows(graph, results.edgeFlow), results.transfers,
                results.nodeHeads, results.nodeCeilings, results.nodeAnchors,
                results.edgeFluids, results.restFluids, blocked, stalled, noHead,
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
        final Set<Integer> movingEdges = new HashSet<>();
        final Map<Integer, Solution.Reason> edgeReasons = new HashMap<>();
        final Map<Integer, Solution.PumpLoad> pumpLoads = new HashMap<>();
        final List<Solution.Transfer> transfers = new ArrayList<>();

        GroupResults(int edgeCount) {
            edgeFlow = new double[edgeCount];
            strongestEdgeFlow = new double[edgeCount];
        }
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
                    flowPerRpm * mult / headPerRpm));
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
                              int pumpNode, double pumpHead, double pumpInternalG) {}

    private static boolean solveGroup(Level level, Graph graph, Columns columns,
                                      Map<Integer, PumpState> pumps, FluidStack sample,
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
            nodeSpecs.add(new NodeSpec(column.capacitance(), columnHead(column, gas)));
            canSupply.add(!column.isEmpty());
            participants.add(column);
            columnIndex.put(column, index);
            for (int member : column.memberNodes()) solverIndex[member] = index;
        }
        if (participants.size() < 2) return false;

        for (Node node : graph.nodes()) {
            if (node.isHandler() || node.isOpenEnd()) continue;
            solverIndex[node.index()] = nodeSpecs.size();
            nodeSpecs.add(new NodeSpec(0, 0));
            canSupply.add(false);
        }

        List<BranchSpec> branches = new ArrayList<>();
        List<BranchMeta> meta = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            assembleBranch(level, graph, columns, pumps, edge, solverIndex, sample,
                    gas, conductancePerTile, branches, meta, results);
        }
        if (branches.isEmpty()) return false;

        NetworkSolver.Result result = NetworkSolver.solve(nodeSpecs, branches, 1,
                PipesNPhysicsConfig.SUCTION_LIMIT.get());

        recordDisplayHeads(graph, solverIndex, nodeSpecs, canSupply, branches, result,
                gas, results.nodeHeads, results.nodeCeilings, results.nodeAnchors);

        boolean active = false;
        for (int b = 0; b < branches.size(); b++) {
            int edgeIndex = meta.get(b).edgeIndex();
            double flow = result.flows()[b];
            results.edgeFlow[edgeIndex] += flow;
            active |= Math.abs(flow) > ACTIVE_FLOW_EPS;

            // The fluid that fills this run even at rest: passes run largest-volume
            // first, so the dominant fluid claims the edge for static rendering.
            results.restFluids.putIfAbsent(edgeIndex, sample);

            if (result.crestBlocked()[b]) {
                results.blockedEdges.add(edgeIndex);
                results.edgeReasons.putIfAbsent(edgeIndex, Solution.Reason.CREST);
            }
            if (result.backflowBlocked()[b] && branches.get(b).emf() != 0) {
                results.noHeadEdges.add(edgeIndex);
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
        if (cap == null) return false;
        if (!column.isEmpty()) {
            return !cap.drain(sample.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()
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
        double fillHeight = column.fillFraction() * column.heightBlocks();
        return NetworkSolver.surfaceHead(column.baseY(), fillHeight, gas);
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
                                       Map<Integer, PumpState> pumps, Edge edge,
                                       int[] solverIndex, FluidStack sample,
                                       boolean gas,
                                       double conductancePerTile,
                                       List<BranchSpec> branches, List<BranchMeta> meta,
                                       GroupResults results) {
        Set<Integer> blockedEdges = results.blockedEdges;
        int solverA = solverIndex[edge.a()];
        int solverB = solverIndex[edge.b()];
        if (solverA < 0 || solverB < 0 || solverA == solverB) return;
        if (!runAcceptsFluid(level, graph, edge, sample)) {
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
            BlockPos toward = adjacentCell(graph, edge, nodeIndex);
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

        // A column with nothing in it can only receive — without this, the solver
        // would model an empty reservoir as a fluid source and distort the heads.
        // A conflict here (both ends empty, or an empty end behind a pump) is an
        // ordinary "nothing to move", not a fault worth flagging to the player.
        if (columnA != null && columnA.isEmpty()) allowedSign = combineSign(allowedSign, -1);
        if (columnB != null && columnB.isEmpty()) allowedSign = combineSign(allowedSign, +1);

        // A hose pulley only ever supplies (it draws from a fluid body, it does not
        // accept fluid back through the same connection). Pin the branch to flow OUT
        // of it; this also stops the engine from pushing fluid into the pulley and
        // having it deposit blocks it would then drain straight back.
        if (columnA != null && columnA.isInfiniteSource()) allowedSign = combineSign(allowedSign, +1);
        if (columnB != null && columnB.isInfiniteSource()) allowedSign = combineSign(allowedSign, -1);
        if (allowedSign == Integer.MIN_VALUE) return;

        if (!gas) {
            if (columnA != null) {
                BlockPos opening = adjacentCell(graph, edge, edge.a());
                lipA = SableCompat.getWorldY(level, opening) - 0.5;
                if (!canDrawFrom(level, graph.node(edge.a()), columnA, opening, lipA)) {
                    allowedSign = combineSign(allowedSign, -1);
                }
            }
            if (columnB != null) {
                BlockPos opening = adjacentCell(graph, edge, edge.b());
                lipB = SableCompat.getWorldY(level, opening) - 0.5;
                if (!canDrawFrom(level, graph.node(edge.b()), columnB, opening, lipB)) {
                    allowedSign = combineSign(allowedSign, +1);
                }
            }
            // A lip conflict (e.g. a pump trying to draw from below a tank's
            // waterline) is "no supply", not a fault.
            if (allowedSign == Integer.MIN_VALUE) return;

            for (int i = 0; i < edge.pipes().size(); i++) {
                double cellY = SableCompat.getWorldY(level, edge.pipes().get(i));
                if (Double.isNaN(crestHeight) || cellY > crestHeight) {
                    crestHeight = cellY;
                    crestPos = (i + 1.0) / (edge.length() + 1);
                }
            }
        }

        branches.add(new BranchSpec(solverA, solverB, conductance, emf, allowedSign,
                crestHeight, crestPos));
        meta.add(new BranchMeta(edge.index(),
                columns.byNode.get(edge.a()), columns.byNode.get(edge.b()), lipA, lipB,
                driveNode, driveHead, driveInternalG));
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
                Direction fromPrevious = directionBetween(cell, previous);
                Direction fromNext = directionBetween(cell, next);
                if (fromPrevious != null && !behaviour.canPullFluidFrom(sample, state, fromPrevious)) return false;
                if (fromNext != null && !behaviour.canPullFluidFrom(sample, state, fromNext)) return false;
            }
            previous = cell;
        }
        return true;
    }

    private static Direction directionBetween(BlockPos from, BlockPos to) {
        return Direction.fromDelta(
                to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
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

    private static BlockPos adjacentCell(Graph graph, Edge edge, int nodeIndex) {
        if (edge.pipes().isEmpty()) return graph.node(edge.other(nodeIndex)).pos();
        return nodeIndex == edge.a()
                ? edge.pipes().get(0)
                : edge.pipes().get(edge.pipes().size() - 1);
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

        int planned = 0;
        for (int s = 0; s < sources.size(); s++) {
            int give = giving.get(s);
            for (int t = 0; t < sinks.size() && give > 0; t++) {
                if (!sourceIsland.get(s).equals(sinkIsland.get(t))) continue;
                int take = taking.get(t);
                if (take <= 0) continue;
                int amount = Math.min(give, take);
                transfers.add(new Solution.Transfer(
                        sources.get(s).accessPos(), sinks.get(t).accessPos(),
                        sample.copyWithAmount(amount)));
                if (sinks.get(t).isEmpty()) claimedEmpties.add(sinks.get(t).identity());
                give -= amount;
                taking.set(t, take - amount);
                planned += amount;
            }
        }
        return new TransferPlan(planned, !sources.isEmpty(), !sinks.isEmpty());
    }

    /**
     * Connected-component id per solver node over the conducting (active) branches,
     * so transfer planning can tell which columns are actually plumbed together this
     * tick. Branches the solver dropped (closed valve / off pump / broken crest) are
     * absent, so the halves they used to join fall into separate components.
     */
    private static int[] islands(List<BranchSpec> branches, NetworkSolver.Result result) {
        int n = result.heads().length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int b = 0; b < branches.size(); b++) {
            if (result.active()[b]) union(parent, branches.get(b).a(), branches.get(b).b());
        }
        for (int i = 0; i < n; i++) parent[i] = find(parent, i);
        return parent;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /** What the handler will really give up this tick, probed without mutating it. */
    private static int probeDrainable(Level level, BoundaryColumn column, FluidStack sample, int amount) {
        IFluidHandler cap = column.handler(level);
        return cap == null ? 0
                : cap.drain(sample.copyWithAmount(amount), FluidAction.SIMULATE).getAmount();
    }

    /** What the handler will really accept this tick, probed without mutating it. */
    private static int probeFillable(Level level, BoundaryColumn column, FluidStack sample, int amount) {
        IFluidHandler cap = column.handler(level);
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
                if (branch.allowedSign() != 0 && branch.allowedSign() != (fromA ? +1 : -1)) continue;
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
                if (branch.allowedSign() != 0 && branch.allowedSign() != (fromA ? +1 : -1)) continue;
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
            for (BranchSpec branch : branches) {
                double forward = Math.max(0, branch.emf());
                double backward = Math.max(0, -branch.emf());
                if (branch.allowedSign() >= 0) {
                    double viaB = forward + boostAhead[branch.b()];
                    if (viaB > boostAhead[branch.a()] + 1e-9) {
                        boostAhead[branch.a()] = viaB;
                        changed = true;
                    }
                }
                if (branch.allowedSign() <= 0) {
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
