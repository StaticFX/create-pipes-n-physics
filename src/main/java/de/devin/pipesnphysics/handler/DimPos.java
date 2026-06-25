package de.devin.pipesnphysics.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Dimension-aware position key. Pairs a dimension with a block position so that
 * caches keyed by position do not collide across dimensions.
 */
public record DimPos(ResourceKey<Level> dimension, BlockPos pos) {
    public static DimPos of(Level level, BlockPos pos) {
        return new DimPos(level.dimension(), pos.immutable());
    }
}
