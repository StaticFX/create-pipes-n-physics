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
 *   3. Networks that solved to "no flow" are put to sleep for a few ticks and only
 *      re-checked on a slow heartbeat, unless something meaningful changed
 *      ({@link #markChanged}: pump flips, speed changes, topology edits), which
 *      wakes them immediately.
 */
public final class EngineTickHandler {
    private static final int IDLE_RECHECK_TICKS = 20;

    /**
     * A network whose only "stillness" is a running pump dead-headed against a full or
     * too-high sink (a NO_HEAD edge) is NOT idle — it must top the sink off the instant it
     * drains. It re-checks this much sooner than a truly idle network, so a basin consumed
     * by a recipe (whose drain fires no block event to wake us) stays topped up rather than
     * refilling once per {@link #IDLE_RECHECK_TICKS} heartbeat — which reads as the basin
     * only refilling after it has emptied.
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
            long until = now + (solution.noHeadEdges().isEmpty()
                    ? IDLE_RECHECK_TICKS : BACKED_UP_RECHECK_TICKS);
            for (BlockPos cell : graph.coverage()) quiet.put(cell, until);
        }
    }
}
