package de.devin.pipesnphysics;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Utility for Sable sub-level compatibility.
 * When Sable is installed, positions on moving structures are projected into world space.
 * When Sable is not installed, positions are returned unchanged (no-op fallback).
 */
public class SableCompat {

    /**
     * Get the world-space Y coordinate of a block position,
     * accounting for sub-level transformations (rotation, translation).
     */
    public static double getWorldY(Level level, BlockPos pos) {
        Vec3 worldPos = SableCompanion.INSTANCE.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
        return worldPos.y;
    }

    /**
     * Get the world-space position of a block,
     * accounting for sub-level transformations.
     */
    public static Vec3 getWorldPos(Level level, BlockPos pos) {
        return SableCompanion.INSTANCE.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
    }

    /**
     * Get the vertical height difference between two positions in world space,
     * accounting for sub-level transformations.
     * Returns positive if 'higher' is above 'lower' in world space.
     */
    public static double getHeightDifference(Level level, BlockPos higher, BlockPos lower) {
        double higherY = getWorldY(level, higher);
        double lowerY = getWorldY(level, lower);
        return higherY - lowerY;
    }
}
