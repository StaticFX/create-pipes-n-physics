package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.solve.Apportion;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.BranchSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one {@link FluidPass}'s solved net inflows into concrete endpoint transfers: which column
 * gives how much to which column this tick. Every amount is clamped three ways — the per-endpoint
 * flow cap, the lip drain cap, and a SIMULATE probe of what the handler will really give or take —
 * and sources pair to sinks only within the same hydraulic island, so no fluid ever crosses a
 * barrier (closed valve, off pump, broken crest) the solver split the network on.
 */
final class TransferPlanner {
    private static final double LIP_DRAIN_RATE = 0.5;
    private static final double LIP_DREGS_MB = 4;

    private final Level level;
    private final FluidStack sample;
    private final boolean gas;
    private final List<BranchSpec> branches;
    private final List<FluidPass.BranchMeta> meta;
    private final NetworkSolver.Result result;
    private final int[] island;
    private final Set<BlockPos> claimedEmpties;

    /**
     * What a pass actually scheduled, and whether either side had anything to offer.
     * {@code hadSource}/{@code hadSink} distinguish SINK_FULL (a source stood ready but no sink
     * accepted) from SOURCE_DRY (a sink waited but no source could give).
     */
    record TransferPlan(int plannedMb, boolean hadSource, boolean hadSink) {}

    /** A column's clamped offer for this tick — how much it gives or takes, and the hydraulic island it belongs to. */
    private record Endpoint(BoundaryColumn column, int amountMb, int island) {}

    TransferPlanner(Level level, FluidStack sample, boolean gas, List<BranchSpec> branches,
                    List<FluidPass.BranchMeta> meta, NetworkSolver.Result result, int[] island,
                    Set<BlockPos> claimedEmpties) {
        this.level = level;
        this.sample = sample;
        this.gas = gas;
        this.branches = branches;
        this.meta = meta;
        this.result = result;
        this.island = island;
        this.claimedEmpties = claimedEmpties;
    }

    /**
     * Plan this pass's transfers into {@code transfers}. Sources pair to sinks greedily but only
     * within a hydraulic island: the set of columns reachable from one another through conducting
     * (active) branches this tick. A closed valve, an off pump, or a broken crest splits the
     * network into halves the solver leaves internally balanced; without this grouping the pairing
     * below would spill one half's clamped-sink surplus into a sink on the OTHER side of the
     * barrier, teleporting fluid across it.
     */
    TransferPlan plan(List<BoundaryColumn> participants, Map<BoundaryColumn, Integer> columnIndex,
                      List<Solution.Transfer> transfers) {
        List<Endpoint> sources = new ArrayList<>();
        List<Endpoint> sinks = new ArrayList<>();
        collectEndpoints(participants, columnIndex, sources, sinks);

        // Apportion within each hydraulic island by PROPORTIONAL share, not first-come-first-served.
        // When a source's clamped give cannot satisfy all its island's sinks, the old greedy pairing
        // let the first-discovered sink take everything and starved the rest EVERY tick — delivery
        // tracked invisible graph-discovery order ("one machine on the manifold never gets fluid
        // unless I pause the other"). Give each sink a fraction of the shortfall proportional to its
        // take (largest-remainder rounding keeps integer mB and conservation), then realise it with a
        // northwest-corner fill. The island grouping is unchanged (no fluid crosses a barrier).
        int planned = 0;
        for (int islandId : distinctIslandsInOrder(sources)) {
            planned += planIsland(islandId, sources, sinks, transfers);
        }
        return new TransferPlan(planned, !sources.isEmpty(), !sinks.isEmpty());
    }

    /** Each participant's solved net inflow, clamped to what it can really give or take this tick. */
    private void collectEndpoints(List<BoundaryColumn> participants,
                                  Map<BoundaryColumn, Integer> columnIndex,
                                  List<Endpoint> sources, List<Endpoint> sinks) {
        int maxFlow = PipesNPhysicsConfig.MAX_FLOW_PER_ENDPOINT.get();
        for (BoundaryColumn column : participants) {
            int node = columnIndex.get(column);
            double delta = result.netInflow()[node];
            if (delta < 0) {
                double outflow = Math.min(-delta, maxFlow);
                outflow = Math.min(outflow, lipDrainCap(column, node));
                // An open-end intake mouth's contentMb is the real per-tick world yield;
                // never request past it. Create's drain returns the requested amount even
                // when the body holds less (a 250 mB honey block under the 256 cap), which
                // would deposit more into the sink than left the world — fluid from nothing.
                if (column.isOpenEnd() && column.isInfiniteSource()) {
                    outflow = Math.min(outflow, column.contentMb());
                }
                int amount = (int) Math.round(outflow);
                if (amount < 1) continue;
                amount = Math.min(amount, probeDrainable(column, amount));
                if (amount >= 1) sources.add(new Endpoint(column, amount, island[node]));
            } else if (delta > 0) {
                int amount = (int) Math.round(Math.min(delta, maxFlow));
                if (amount < 1) continue;
                amount = Math.min(amount, probeFillable(column, amount));
                if (amount >= 1) sinks.add(new Endpoint(column, amount, island[node]));
            }
        }
    }

    /** Pair one island's sources to its sinks by proportional share; the planned total in mB. */
    private int planIsland(int islandId, List<Endpoint> sources, List<Endpoint> sinks,
                           List<Solution.Transfer> transfers) {
        List<Endpoint> giving = inIsland(sources, islandId);
        List<Endpoint> taking = inIsland(sinks, islandId);
        if (taking.isEmpty()) return 0;

        int move = Math.min(totalAmount(giving), totalAmount(taking));
        if (move <= 0) return 0;

        int[] sourceShare = Apportion.largestRemainder(move, amounts(giving));
        int[] sinkShare = Apportion.largestRemainder(move, amounts(taking));

        int planned = 0;
        int j = 0;
        for (int i = 0; i < giving.size(); i++) {
            BoundaryColumn source = giving.get(i).column();
            while (sourceShare[i] > 0 && j < taking.size()) {
                if (sinkShare[j] <= 0) { j++; continue; }
                BoundaryColumn sink = taking.get(j).column();
                int amount = Math.min(sourceShare[i], sinkShare[j]);
                transfers.add(new Solution.Transfer(
                        source.accessPos(), source.accessFace(),
                        sink.accessPos(), sink.accessFace(), sample.copyWithAmount(amount)));
                if (sink.isEmpty()) claimedEmpties.add(sink.identity());
                sourceShare[i] -= amount;
                sinkShare[j] -= amount;
                planned += amount;
            }
        }
        return planned;
    }

    private static List<Integer> distinctIslandsInOrder(List<Endpoint> sources) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (Endpoint source : sources) ids.add(source.island());
        return new ArrayList<>(ids);
    }

    private static List<Endpoint> inIsland(List<Endpoint> endpoints, int islandId) {
        List<Endpoint> matching = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            if (endpoint.island() == islandId) matching.add(endpoint);
        }
        return matching;
    }

    private static int totalAmount(List<Endpoint> endpoints) {
        int sum = 0;
        for (Endpoint endpoint : endpoints) sum += endpoint.amountMb();
        return sum;
    }

    private static int[] amounts(List<Endpoint> endpoints) {
        int[] amounts = new int[endpoints.size()];
        for (int i = 0; i < endpoints.size(); i++) amounts[i] = endpoints.get(i).amountMb();
        return amounts;
    }

    /** What the handler will really give up this tick, probed without mutating it. */
    private int probeDrainable(BoundaryColumn column, int amount) {
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
    private int probeFillable(BoundaryColumn column, int amount) {
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
     * zero exactly at the lip where {@code FluidPass.canDrawFrom} closes the connection.
     * Sharing that threshold is what lets a tank fed from a side or top connection
     * settle at the opening instead of flapping across it. The last few mB above
     * the lip may leave in one go so tanks drain to genuinely empty instead of
     * keeping an asymptotic puddle.
     */
    private double lipDrainCap(BoundaryColumn column, int solverIdx) {
        if (gas || column.isOpenEnd() || column.isInfiniteSource()) return Double.MAX_VALUE;

        double minLip = Double.NaN;
        for (int b = 0; b < branches.size(); b++) {
            double flow = result.flows()[b];
            if (Math.abs(flow) <= FlowSolver.FLOW_TOLERANCE) continue;

            FluidPass.BranchMeta branchMeta = meta.get(b);
            double lip = Double.NaN;
            if (branchMeta.columnA() != null && columnMatches(branchMeta.columnA(), column)
                    && branches.get(b).a() == solverIdx && flow > 0) {
                lip = branchMeta.lipA();
            } else if (branchMeta.columnB() != null && columnMatches(branchMeta.columnB(), column)
                    && branches.get(b).b() == solverIdx && flow < 0) {
                lip = branchMeta.lipB();
            }
            if (!Double.isNaN(lip) && (Double.isNaN(minLip) || lip < minLip)) minLip = lip;
        }
        if (Double.isNaN(minLip)) return Double.MAX_VALUE;

        // The DRAW surface, like the canDrawFrom wall and Reservoir.capDrawAtLip — gate and
        // cap must share one surface or an open gate meets a zero cap and the line stalls.
        double surface = column.drawSurface();
        double aboveLipMb = column.capacitance() * (surface - minLip);
        if (aboveLipMb <= 0) return 0;
        return Math.max(Math.min(aboveLipMb, LIP_DREGS_MB), LIP_DRAIN_RATE * aboveLipMb);
    }

    private static boolean columnMatches(BoundaryColumn a, BoundaryColumn b) {
        return a.identity().equals(b.identity());
    }
}
