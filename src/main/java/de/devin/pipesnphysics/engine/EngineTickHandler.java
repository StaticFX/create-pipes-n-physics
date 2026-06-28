package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server tick driver for the engine.
 *
 * Pipes mark themselves dirty every tick (the transport-cancel mixin), so every
 * live network is seeded by all of its pipes. Three rules keep this cheap and make
 * each network tick exactly ONCE per server tick:
 *
 *   1. Seeds are resolved to a pipe position first ({@link GraphBuilder#findSeed}),
 *      so a mark on a pump's open face or a tank wall cannot bypass deduplication.
 *   2. The first seed to reach a network claims every position its discovery walk
 *      covered ({@link Graph#coverage}); later seeds inside the coverage are skipped.
 *      Without this an N-pipe network would be solved and transferred N times per
 *      tick — one of the root causes of the old engine's oscillations.
 *   3. Networks that solved to "no flow" are put to sleep and only re-checked on a
 *      heartbeat, unless something meaningful changed ({@link #markChanged}: pump
 *      flips, speed changes, topology edits), which wakes them immediately. The
 *      heartbeat is SLOW for a settled, pumpless network but FAST for one holding a
 *      running pump (it is armed — see {@link #recheckTicks}), so a pump-fed sink
 *      drained by a recipe, or fed from a source that just rose past its draw lip,
 *      catches up promptly instead of waiting out the full idle interval.
 */
public final class EngineTickHandler {
    private static final int IDLE_RECHECK_TICKS = 20;

    /**
     * The fast re-check for an ARMED-but-idle network: one holding a RUNNING PUMP that moved
     * nothing this tick. Such a pump is burning stress to deliver and is idle only because its
     * sink is momentarily full (or too high to lift) or its source momentarily below the draw
     * lip / empty — level changes inside a tank or basin that fire NO block event to wake us.
     * It must top its sink off (or resume from its refilling source) the instant conditions
     * allow, so it re-checks this much sooner than a truly idle (pumpless, settled) network —
     * otherwise a basin consumed by a recipe, or a sink one block above a draining source,
     * only catches up once per {@link #IDLE_RECHECK_TICKS} heartbeat, reading as "only refills
     * after it empties". A dead-headed pump (a NO_HEAD edge) is the original case; a strong
     * pump pinned to zero flow by an unsuppliable source — which carries no NO_HEAD flag — is
     * the one {@link #recheckTicks} also catches.
     */
    private static final int BACKED_UP_RECHECK_TICKS = 4;

    private static final Map<ResourceKey<Level>, Set<BlockPos>> DIRTY = new HashMap<>();
    private static final Map<ResourceKey<Level>, Set<BlockPos>> URGENT = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> QUIET = new HashMap<>();

    private EngineTickHandler() {}

    /** Routine per-tick mark; honored unless the network is sleeping. */
    public static void markDirty(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        DIRTY.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(pos.immutable());
    }

    /** Something meaningful changed (pump flip, speed, topology): wake the network. */
    public static void markChanged(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        markDirty(level, pos);
        URGENT.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(pos.immutable());
    }

    /** Discard all pending work — called on server stop. */
    public static void clear() {
        DIRTY.clear();
        URGENT.clear();
        QUIET.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DIRTY.isEmpty() && URGENT.isEmpty()) return;
        event.getServer().getAllLevels().forEach(EngineTickHandler::tickLevel);
    }

    private static void tickLevel(ServerLevel level) {
        Set<BlockPos> work = DIRTY.remove(level.dimension());
        Set<BlockPos> urgent = URGENT.remove(level.dimension());
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        if (work == null) work = Set.of();
        if (urgent == null) urgent = Set.of();

        Map<BlockPos, Long> quiet = QUIET.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        long now = level.getGameTime();
        Set<BlockPos> covered = new HashSet<>();

        for (BlockPos pos : urgent) {
            tickNetwork(level, pos, covered, quiet, now, true);
        }
        for (BlockPos pos : work) {
            tickNetwork(level, pos, covered, quiet, now, false);
        }

        if (quiet.size() > 4096) quiet.values().removeIf(until -> until <= now);
    }

    private static void tickNetwork(ServerLevel level, BlockPos pos, Set<BlockPos> covered,
                                    Map<BlockPos, Long> quiet, long now, boolean wake) {
        if (!level.isLoaded(pos)) return;
        BlockPos seed = GraphBuilder.findSeed(level, pos);
        if (seed == null || covered.contains(seed)) return;
        if (!wake) {
            Long sleepUntil = quiet.get(seed);
            if (sleepUntil != null && sleepUntil > now) return;
        }

        Graph graph = GraphBuilder.build(level, seed);
        if (graph.isEmpty()) return;
        covered.addAll(graph.coverage());

        Solution solution = FlowSolver.solve(level, graph);

        // Advance the visual fronts FIRST, then deliver only the transfers whose fluid
        // has actually reached its sink: a freshly started flow fills the source-side
        // pipe before the sink begins to fill (travel time). A receding equalization
        // hump has no flow yet still needs per-tick updates, so keep the network awake
        // until its drain animation finishes.
        boolean draining = CreatePipeRendering.apply(level, graph, solution);

        List<Solution.Transfer> ready = new ArrayList<>();
        for (Solution.Transfer transfer : solution.transfers()) {
            if (CreatePipeRendering.deliveryReady(level, graph, solution, transfer)) {
                ready.add(transfer);
            }
        }
        FluidEngine.apply(level, ready);

        if (solution.active() || solution.hasTransfer() || draining) {
            graph.coverage().forEach(quiet::remove);
        } else {
            long until = now + recheckTicks(solution, hasRunningPump(level, graph));
            for (BlockPos cell : graph.coverage()) quiet.put(cell, until);
        }
    }

    /**
     * How long an idle network may sleep before its next re-check. A network that is merely
     * settled (gravity equalized, pump off, no pump) only changes on a block edit and can sleep
     * the full {@link #IDLE_RECHECK_TICKS} heartbeat. A network holding a RUNNING PUMP — or one
     * showing a dead-headed NO_HEAD edge — is ARMED: it is actively trying to move fluid and is
     * idle only because of a transient (full sink, source below its draw lip) that fires no
     * block event, so it re-checks on the fast {@link #BACKED_UP_RECHECK_TICKS} heartbeat and
     * resumes promptly. (A running pump subsumes the NO_HEAD case, but both are kept explicit.)
     */
    public static int recheckTicks(Solution solution, boolean armedByPump) {
        return armedByPump || !solution.noHeadEdges().isEmpty()
                ? BACKED_UP_RECHECK_TICKS : IDLE_RECHECK_TICKS;
    }

    /** Whether any pump on this network is spun up — i.e. the network is armed (see {@link #recheckTicks}). */
    public static boolean hasRunningPump(ServerLevel level, Graph graph) {
        for (Node pump : graph.pumps()) {
            if (FlowSolver.isPumpRunning(level, pump)) return true;
        }
        return false;
    }
}
