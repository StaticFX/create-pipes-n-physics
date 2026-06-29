package de.devin.pipesnphysics.compat;

import com.simibubi.create.content.fluids.FluidPropagator;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Drives the engine on Sable sub-levels. A sub-level's blocks live in the parent ServerLevel at
 * far-away "plot" coordinates, so the engine can read and solve them with no changes — but Sable
 * assembles a contraption with raw {@code setBlockState} (no place event) and a dry pipe never
 * self-ticks, so the network is never woken by the normal reactive hooks. This enumerates each
 * sub-level's plot chunks and seeds its pipe cells so the engine drives them like any other
 * network (the QUIET sleep gate still throttles re-solves of idle ones). References full Sable, so
 * it is only loaded when full Sable is present (gated in {@link SableCompat#seedSubLevels}).
 */
final class SableSubLevelDriver {
    private SableSubLevelDriver() {}

    static void seed(ServerLevel level, BiConsumer<Level, BlockPos> seed) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                // Copy the keys: seeding only touches the engine's dirty set, but the BE map is the
                // live chunk's, so a snapshot is the safe way to iterate it.
                List<BlockPos> positions = new ArrayList<>(holder.getChunk().getBlockEntities().keySet());
                for (BlockPos pos : positions) {
                    if (FluidPropagator.getPipe(level, pos) != null) seed.accept(level, pos);
                }
            }
        }
    }
}
