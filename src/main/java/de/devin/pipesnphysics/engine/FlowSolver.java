package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.solve.NetworkSolver;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.store.PipeWindow;
import de.devin.pipesnphysics.engine.turbine.HydroTurbine;
import de.devin.pipesnphysics.engine.turbine.TurbineRating;
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
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
 * The solve reads top to bottom: {@link Columns} resolves every endpoint into a
 * {@link BoundaryColumn}, present fluids are ordered by total volume, and one
 * {@link FluidPass} per fluid solves the shared topology and records its flows, display
 * fields ({@link DisplayFields}), and endpoint transfers ({@link TransferPlanner}) into
 * the shared accumulators. Empty endpoints are claimed by the most plentiful fluid first;
 * fluids never mix inside a tick.
 *
 * The solver is read-only: it SIMULATE-probes capabilities but never moves fluid.
 * {@link FluidEngine#apply} executes the returned transfers.
 */
public final class FlowSolver {
    private static final double MIN_PUMP_SPEED = 0.01;
    /** Flows below this (mB/t) count as idle for the network's active/sleep decision. */
    static final double ACTIVE_FLOW_EPS = 0.05;
    /** Flows below this (mB/t) are numerical noise, not a direction. */
    static final double FLOW_TOLERANCE = 1.0e-7;

    private FlowSolver() {}

    public static Solution solve(Level level, Graph graph) {
        if (graph.isEmpty() || graph.edges().isEmpty()) return Solution.idle(graph);

        Columns columns = Columns.collect(level, graph);
        if (columns.distinct().isEmpty()) return Solution.idle(graph);

        List<FluidStack> groupSamples = groupSamplesByVolume(columns.distinct());
        if (groupSamples.isEmpty()) return Solution.idle(graph);

        Map<Integer, PumpState> pumps = collectPumps(level, graph);
        // Per-edge data that does NOT depend on the fluid (the valve throttle and the crest geometry):
        // resolve it ONCE here rather than re-scanning every pipe cell's BE / world-Y on every fluid pass.
        Map<Integer, EdgeStatics> edgeStatics = computeEdgeStatics(level, graph);

        GroupResults results = new GroupResults(graph.edges().size());
        Set<BlockPos> claimedEmpties = new HashSet<>();
        boolean active = false;

        for (FluidStack sample : groupSamples) {
            active |= new FluidPass(level, graph, columns, pumps, edgeStatics, sample,
                    claimedEmpties, results).run();
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
                results.passes, new int[graph.edges().size()],
                results.nodeHeads, results.nodeCeilings, results.nodeAnchors,
                results.edgeFluids, results.restFluids, blocked, stalled, noHead, held,
                results.edgeReasons, results.pumpLoads, active);
    }

    /**
     * Mutable accumulators shared by the per-fluid passes of one solve. {@code edgeFlow} sums each
     * pass's signed flow per edge; {@code strongestEdgeFlow} tracks the strongest single pass so the
     * dominant fluid claims the edge's display entry.
     */
    static final class GroupResults {
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
        final List<Solution.FlowPass> passes = new ArrayList<>();

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
            double head = supply.head(gas);
            results.restFluids.put(edgeIndex, supply.contents().copyWithAmount(1));
            results.nodeHeads.putIfAbsent(edge.a(), head);
            results.nodeHeads.putIfAbsent(edge.b(), head);
        }
    }

    /** The finite reservoir column at a graph node if it currently HOLDS fluid, else null. */
    private static BoundaryColumn filledReservoir(Columns columns, int node) {
        BoundaryColumn column = columns.byNode(node);
        return column != null && column.isFiniteReservoir() && !column.isEmpty() ? column : null;
    }

    // ------------------------------------------------------------------ columns

    /**
     * The network's fluid endpoints as {@link BoundaryColumn}s, deduplicated by
     * {@link BoundaryColumn#identity()} so a multiblock tank with several pipe connections
     * appears as ONE column; {@code byNode} resolves each graph node to its (possibly shared)
     * column.
     */
    static final class Columns {
        private final Map<Integer, BoundaryColumn> byNode = new HashMap<>();
        private final List<BoundaryColumn> distinct = new ArrayList<>();

        static Columns collect(Level level, Graph graph) {
            Columns columns = new Columns();
            // What this network's mouths may drink this tick: the spill latch and the pumps
            // actually sucking on them, resolved once so every mouth reads the same way here
            // and in the executor.
            MouthConditions mouths = MouthConditions.of(level, graph);
            Map<BlockPos, BoundaryColumn> byIdentity = new LinkedHashMap<>();
            for (Node node : graph.nodes()) {
                BoundaryColumn resolved;
                if (node.isHandler()) {
                    resolved = BoundaryColumn.resolve(level, node);
                    // Feed the relay detector this handler's live contents so it can spot a block that
                    // spontaneously gains fluid (a relay) versus one we merely fill (see RelayDetector).
                    // FINITE RESERVOIRS ONLY: the detector's whole premise is that a capacitor's stored
                    // amount moves only by OUR transfers, so it needs a REAL reading. A bottomless
                    // column (hose pulley, relay endpoint) reports a synthetic constant — 4,000,000 mB
                    // brimming or 0 empty — that never moves however much we draw, so every drain we
                    // apply read back as an unexplained GAIN of exactly that amount and demoted the
                    // block after STRIKES_TO_DEMOTE ticks of ordinary work. For a hose pulley that cost
                    // it its isHosePulley identity (and with it the output latch) for the whole session,
                    // curable only by breaking the block — the "works again after disconnect and
                    // reconnect" report. A synthetic column can never be evidence either way.
                    if (resolved != null && resolved.isFiniteReservoir()) {
                        RelayDetector.observe(level, resolved.accessPos(),
                                resolved.contents().getFluid(), resolved.contentMb());
                    }
                } else if (node.isOpenEnd()) {
                    resolved = mouths.column(level, node);
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

        /** Every column once, regardless of how many graph nodes connect to it. */
        List<BoundaryColumn> distinct() { return distinct; }

        /** The column a graph node connects to, or null for junctions/pumps. */
        BoundaryColumn byNode(int nodeIndex) { return byNode.get(nodeIndex); }
    }

    /** Present fluids ordered by total held volume, largest first — that pass claims empties first. */
    private static List<FluidStack> groupSamplesByVolume(List<BoundaryColumn> columns) {
        List<FluidStack> samples = new ArrayList<>();
        List<Double> volumes = new ArrayList<>();
        for (BoundaryColumn column : columns) {
            // EVERY distinct fluid the column holds, not just its representative contents(): a
            // multi-fluid basin must get a pass PER fluid so each can be drained (the drain-side
            // dual of the participates() refill fix).
            for (FluidStack held : column.heldFluids()) {
                if (held.isEmpty()) continue;
                // An infinite source (pulley / open-end intake) reports a brimming stand-in
                // capacity, not real inventory; counting it would let a single atmospheric
                // mouth outrank every real tank and seize the largest-volume-first pass.
                // Register its fluid so a pass still runs, but contribute zero to the tally.
                double volume = column.isInfiniteSource() ? 0 : held.getAmount();
                int index = indexOfSameFluid(samples, held);
                if (index < 0) {
                    samples.add(held.copyWithAmount(1));
                    volumes.add(volume);
                } else {
                    volumes.set(index, volumes.get(index) + volume);
                }
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
     *
     * A TURBINE is the same record with the head NEGATED: it takes its rated head back out of the
     * line instead of adding it, keeps the identical one-way flanks (so it conducts only along
     * FACING, which is what pins its rotation to one sign), and caps throughput at its own
     * swallowing capacity. {@code driving} tells the two apart wherever the difference matters —
     * a turbine is not the run's pump, however fast it happens to be turning.
     */
    record PumpState(boolean open, double head, Direction pushSide,
                     double internalConductance, boolean driving) {}

    private static Map<Integer, PumpState> collectPumps(Level level, Graph graph) {
        double headPerRpm = PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();
        double flowPerRpm = PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
        Map<Integer, PumpState> pumps = new HashMap<>();
        for (Node pump : graph.pumps()) {
            if (isTurbine(level, pump)) {
                pumps.put(pump.index(), new PumpState(pump.pumpFacing() != null,
                        -TurbineRating.ratedHead(), pump.pumpFacing(),
                        TurbineRating.internalConductance(), false));
                continue;
            }
            float speed = level.getBlockEntity(pump.pos()) instanceof KineticBlockEntity kinetic
                    ? kinetic.getSpeed() : 0;
            double head = Math.abs(speed) * headPerRpm;
            pumps.put(pump.index(), new PumpState(isPumpRunning(level, pump), head, pump.pumpFacing(),
                    flowPerRpm / headPerRpm, true));
        }
        return pumps;
    }

    /**
     * Whether this pump is dialed to run backwards as a turbine. Such a pump is never "running"
     * (below) however fast the falling water spins it: the fluid drives IT, not the other way
     * round, so none of the pump-driven machinery — priming its outlet, delivering through it,
     * lifting an open mouth into suction — applies.
     */
    public static boolean isTurbine(Level level, Node pump) {
        return level.getBlockEntity(pump.pos()) instanceof HydroTurbine turbine
                && turbine.pipesnphysics$isTurbine();
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
        if (isTurbine(level, pump)) return false;
        return level.getBlockEntity(pump.pos()) instanceof KineticBlockEntity kinetic
                && Math.abs(kinetic.getSpeed()) > MIN_PUMP_SPEED;
    }

    /**
     * How much this pump can move per tick: {@code |RPM| · flowPerRpm} — the same throughput the
     * solver imposes as its internal conductance and the goggle prints as the "Output: … / cap"
     * denominator. Zero for a pump that is not spun up. The executor bounds a pump-driven settle
     * step with it, so a step the solve never sized still moves at the pump's real rating.
     */
    public static int pumpFlowCapMb(Level level, Node pump) {
        if (!isPumpRunning(level, pump)) return 0;
        float speed = level.getBlockEntity(pump.pos()) instanceof KineticBlockEntity kinetic
                ? kinetic.getSpeed() : 0;
        return (int) Math.floor(Math.abs(speed) * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get());
    }

    /**
     * The viscosity the engine flows {@code fluid} at in {@code level}: the registered viscosity,
     * THINNED for a molten fluid in an ultrawarm dimension — vanilla parity, generalized. Vanilla
     * spreads lava 3× faster in the Nether (block tick delay 10 vs 30) but NeoForge's
     * {@code FluidType} carries one flat viscosity, so the dimension rule lives here: any fluid
     * at/above {@code MOLTEN_TEMPERATURE_K} (lava 1300, modded melts ≥1000; water 300 stays
     * water) divides its viscosity by {@code ULTRAWARM_VISCOSITY_THINNING} (default the vanilla
     * 3). Every display that prints viscosity (goggle, /pipegraph) reads THIS number, so what
     * the player sees matches how the pipe flows. Floor 1: some modded fluids register 0.
     */
    public static double effectiveViscosity(Level level, FluidStack fluid) {
        var type = fluid.getFluid().getFluidType();
        double viscosity = Math.max(1, type.getViscosity(fluid));
        double thinning = PipesNPhysicsConfig.ULTRAWARM_VISCOSITY_THINNING.get();
        if (thinning > 1 && level.dimensionType().ultraWarm()
                && type.getTemperature(fluid) >= PipesNPhysicsConfig.MOLTEN_TEMPERATURE_K.get()) {
            viscosity /= thinning;
        }
        return Math.max(1, viscosity);
    }

    // ------------------------------------------------------------------ edge statics

    /**
     * Per-edge data that does not depend on the pass fluid: the valve throttle (a 0..1 conductance
     * factor) and the crest geometry. {@code crestPos} is the fractional position of the run's
     * highest cell, 0 (at node A) to 1 (at node B), which the solver's crest gate interpolates
     * the local head at; {@code crestWet} is whether that cell actually HOLDS fluid — suction can
     * hold an existing column but never create one, so a dry crest above the reachable potential
     * gates instead of self-priming (wire mode, capacity 0, stores nothing and stays always-wet,
     * keeping the legacy instant behaviour).
     */
    record EdgeStatics(double throttle, double crestHeight, double crestFloor, double crestPos,
                       boolean crestWet) {}

    /** Resolve every edge's fluid-independent {@link EdgeStatics} once, before the per-fluid passes. */
    private static Map<Integer, EdgeStatics> computeEdgeStatics(Level level, Graph graph) {
        Map<Integer, EdgeStatics> statics = new HashMap<>(graph.edges().size() * 2);
        for (Edge edge : graph.edges()) {
            statics.put(edge.index(), edgeStatics(level, graph, edge));
        }
        return statics;
    }


    /**
     * The deepest elevation a supply may sit at for a pump drawing through ONE pipe cell: a WET
     * cell already holds a column and sustains a full suction limit below it, a DRY one has to be
     * ESTABLISHED, which a pump manages only on its pulling share and only below the cell's own
     * weir LIP. This is the rule the solve's own crest gate applies ({@code NetworkSolver.crestFactor}),
     * read one CELL at a time: a walk takes the running maximum over the cells it crosses, which for
     * a single run is that run's crest and for a path across several is the worst of them.
     *
     * A JUNCTION is such a cell: it is a real pipe block holding a real slot, but the graph
     * contracts runs BETWEEN junctions, so a hop from one junction to the next is an edge with no
     * cells at all and no crest of its own. In a lattice — where every pipe has three or more
     * connections, so nearly every edge is zero-length — reading the crest per edge therefore
     * reported almost no gate at all (the bare {@code pumpY − SUCTION_LIMIT} fallback), while the
     * few real runs beside them reported the true half-block. Adjacent pipes then came out red
     * next to green with nothing to explain it (reported 2026-08-26; the tell is a `/pipegraph`
     * pump reading `pull 8.00 ↓` on a bone-dry network).
     */
    public static double drawableFloorAt(Level level, BlockPos cell, double primeAllowance) {
        double suction = PipesNPhysicsConfig.SUCTION_LIMIT.get();
        boolean wet = true;
        if (PipeStore.capacityMb() > 0) {
            PipeStore.Store store = PipeStore.at(level, cell);
            wet = store != null && store.amount() > 0;
        }
        return wet ? SableCompat.getWorldY(level, cell) - suction
                : PipeWindow.lipY(level, cell) - Math.min(primeAllowance, suction);
    }

    /**
     * How far a pump developing {@code pumpHead} blocks of push may establish DOWN through its own
     * dry suction line: a pump sucks far more weakly than it pushes, so it spends only
     * {@code pumpPullHeadFraction} of its head doing it (0.1 by default — a 16 RPM pump lifts 4
     * blocks and starts a dry line 0.4 below the pipe). Clamped to the suction limit, which is
     * cavitation and belongs to the fluid rather than to the pump: no RPM buys a column past it.
     *
     * Establishment ONLY. Once the line holds fluid the crest is wet and the column sustains down
     * to the full suction limit like any siphon — the fraction never shrinks a working line.
     */
    public static double pumpPrimeAllowance(double pumpHead) {
        if (pumpHead <= 0) return 0;
        return Math.min(pumpHead * PipesNPhysicsConfig.PUMP_PULL_HEAD_FRACTION.get(),
                PipesNPhysicsConfig.SUCTION_LIMIT.get());
    }

    /** One edge's fluid-independent statics: its valve throttle and its crest geometry. */
    static EdgeStatics edgeStatics(Level level, Graph graph, Edge edge) {
        double crestHeight = Double.NaN;
        double crestPos = 0;
        BlockPos crestCell = null;
        for (int i = 0; i < edge.pipes().size(); i++) {
            double cellY = SableCompat.getWorldY(level, edge.pipes().get(i));
            if (Double.isNaN(crestHeight) || cellY > crestHeight) {
                crestHeight = cellY;
                crestPos = (i + 1.0) / (edge.length() + 1);
                crestCell = edge.pipes().get(i);
            }
        }
        // The crest cell's LIP (its outer shell bottom, the draw-lip datum) is the WEIR
        // threshold: a supply reaching it pours into the cell and over by plain gravity, so
        // a dry crest only gates below it. Same datum as the draw lip, so a tank resting AT
        // its lip sits exactly at the gate boundary, never walled a hair above it.
        double crestFloor = crestCell != null ? PipeWindow.lipY(level, crestCell) : Double.NaN;
        boolean crestWet = true;
        if (crestCell != null && PipeStore.capacityMb() > 0) {
            PipeStore.Store cell = PipeStore.at(level, crestCell);
            crestWet = cell != null && cell.amount() > 0;
        }
        return new EdgeStatics(
                runThrottle(level, graph, edge), crestHeight, crestFloor, crestPos, crestWet);
    }

    /**
     * The tightest valve throttle along a run, as a 0..1 conductance factor (1 when no
     * valve restricts it). A valve the shaft has shut is already rejected by
     * {@code FluidPass.runAcceptsFluid}, so only opened valves reach here; the most-closed
     * one sets the rate. A ONE-WAY valve is a graph NODE (its run splits there), so its
     * throttle no longer sits on either edge's cell list — fold it into both incident
     * edges; in series the tightest cap still governs.
     */
    private static double runThrottle(Level level, Graph graph, Edge edge) {
        if (!PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return 1;
        double factor = 1;
        for (BlockPos cell : edge.pipes()) {
            if (level.getBlockEntity(cell) instanceof ValveThrottle valve) {
                factor = Math.min(factor, valve.pipesnphysics$valveThrottle());
            }
        }
        factor = Math.min(factor, gateThrottle(level, graph.node(edge.a())));
        factor = Math.min(factor, gateThrottle(level, graph.node(edge.b())));
        return factor;
    }

    /** An end node's throttle — only a one-way gate carries one (a CLOSED gate must stay a
     *  held-column wall, never a 0-throttle VALVE block). */
    private static double gateThrottle(Level level, Node node) {
        if (!node.isOneWayGate()) return 1;
        return level.getBlockEntity(node.pos()) instanceof ValveThrottle valve
                ? valve.pipesnphysics$valveThrottle() : 1;
    }

    // ------------------------------------------------------------------ output

    private static List<EdgeFlow> toEdgeFlows(Graph graph, double[] edgeFlow) {
        List<EdgeFlow> flows = new ArrayList<>(graph.edges().size());
        for (Edge edge : graph.edges()) {
            double flow = edgeFlow[edge.index()];
            int mb = (int) Math.round(Math.abs(flow));
            if (mb == 0) {
                flows.add(EdgeFlow.none(edge.index()));
            } else {
                flows.add(new EdgeFlow(edge.index(),
                        flow > 0 ? EdgeFlow.Direction.A_TO_B : EdgeFlow.Direction.B_TO_A, mb));
            }
        }
        return flows;
    }
}
