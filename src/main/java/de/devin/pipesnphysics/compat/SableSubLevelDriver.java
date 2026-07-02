package de.devin.pipesnphysics.compat;

import com.simibubi.create.content.fluids.FluidPropagator;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Drives the engine on Sable sub-levels. A sub-level's blocks live in the parent ServerLevel at
 * far-away "plot" coordinates, so the engine can read and solve them with no changes — but Sable
 * assembles a contraption with raw {@code setBlockState} (no place event) and a dry pipe never
 * self-ticks, so the network is never woken by the normal reactive hooks. This enumerates each
 * sub-level's plot chunks and seeds its pipe cells so the engine drives them like any other
 * network (the QUIET sleep gate still throttles re-solves of idle ones). References full Sable, so
 * it is only loaded when full Sable is present (gated in {@link SableCompat#seedSubLevels}).
 *
 * <p>It also REFRESHES each newly-seen pipe's connection shape once ({@link #refreshConnections}).
 * That raw {@code setBlockState} assembly skips the neighbour {@code updateShape} that normally sets
 * a fluid pipe's per-face connection booleans, so a pipe can carry a STALE shape that
 * {@code FluidPipeBlockEntity.canHaveFlowToward} reads — and {@code GraphBuilder} then drops the real
 * edge, leaving the network solving "no flow" until a manual pipe edit re-runs {@code updateShape}.
 * We do that update ourselves so pumps and networks work without the poke.
 */
final class SableSubLevelDriver {
    /**
     * Pipe cells whose connection shape we have already refreshed, so the (world-mutating) refresh
     * runs once per cell rather than every tick — keyed PER sub-level so the cache tracks the
     * contraption's lifetime, not the world's. A {@link WeakHashMap} on the {@code ServerSubLevel}
     * identity auto-evicts a disassembled sub-level (bounding growth), and a contraption RE-assembled
     * at reused plot coords is a NEW {@code ServerSubLevel} → a cache miss → its pipes re-heal (a
     * flat position set would keep the stale shape and the "no flow until poke" bug would return).
     * Server-thread only; also cleared wholesale on server stop.
     */
    private static final Map<ServerSubLevel, Set<BlockPos>> REFRESHED = new WeakHashMap<>();

    private SableSubLevelDriver() {}

    static void seed(ServerLevel level, BiConsumer<Level, BlockPos> seed) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        boolean refresh = PipesNPhysicsConfig.ENABLE_SUBLEVEL_CONNECTION_REFRESH.get();
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            Set<BlockPos> refreshed = refresh ? REFRESHED.computeIfAbsent(sub, k -> new HashSet<>()) : null;
            for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                // Copy the keys: seeding only touches the engine's dirty set, but the BE map is the
                // live chunk's, so a snapshot is the safe way to iterate it (and to setBlock during it).
                List<BlockPos> positions = new ArrayList<>(holder.getChunk().getBlockEntities().keySet());
                for (BlockPos pos : positions) {
                    if (FluidPropagator.getPipe(level, pos) == null) continue;
                    if (refreshed != null && refreshed.add(pos.immutable())) refreshConnections(level, pos);
                    seed.accept(level, pos);
                }
            }
        }
    }

    /**
     * Recompute one pipe's connection shape from its neighbours — the {@code updateShape} the raw
     * assembly skipped — and write it back if it changed, waking the network so it re-solves with the
     * now-correct topology. Uses {@code UPDATE_KNOWN_SHAPE} so the write does not cascade neighbour
     * updates: every pipe on the sub-level is refreshed independently off {@code isPipe} geometry, so
     * no propagation is needed.
     */
    private static void refreshConnections(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockState updated = Block.updateFromNeighbourShapes(state, level, pos);
        if (updated == state) return;
        level.setBlock(pos, updated, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        EngineTickHandler.markChanged(level, pos);
    }

    /** Drop the refreshed-cell cache — called on server stop so a fresh world starts clean. */
    static void clear() {
        REFRESHED.clear();
    }
}
