package de.devin.pipesnphysics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Facade for Sable Companion compatibility.
 * All methods return sensible no-op defaults when Sable Companion is absent.
 * Actual Sable logic lives in {@code SableCompanionImpl} which is only loaded
 * when Companion classes are on the classpath.
 * <p>
 * This class must NEVER import any Sable types directly.
 */
public class SableCompat {

    static final boolean COMPANION_LOADED;

    static {
        boolean found;
        try {
            Class.forName("dev.ryanhcode.sable.companion.SableCompanion", false,
                    SableCompat.class.getClassLoader());
            found = true;
        } catch (ClassNotFoundException e) {
            found = false;
        }
        COMPANION_LOADED = found;
    }

    public static boolean isCompanionLoaded() {
        return COMPANION_LOADED;
    }

    /**
     * Get the world-space Y coordinate of a block position,
     * accounting for sub-level transformations (rotation, translation).
     */
    public static double getWorldY(Level level, BlockPos pos) {
        if (!COMPANION_LOADED) return pos.getY() + 0.5;
        return SableCompanionImpl.getWorldY(level, pos);
    }

    /**
     * Get the world-space position of a block,
     * accounting for sub-level transformations.
     */
    public static Vec3 getWorldPos(Level level, BlockPos pos) {
        if (!COMPANION_LOADED) return Vec3.atCenterOf(pos);
        return SableCompanionImpl.getWorldPos(level, pos);
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

    /**
     * Get the tilt angle (degrees) of a block's sub-level relative to world.
     * 0° = level, 90° = on its side, 180° = upside down.
     * Returns 0 if the block is not on a sub-level or Sable is absent.
     */
    public static float getTiltAngle(Level level, BlockPos pos) {
        if (!COMPANION_LOADED) return 0;
        return SableCompanionImpl.getTiltAngle(level, pos);
    }

    /**
     * Client-side tilt angle. Returns -1 if not on a sub-level or Sable is absent.
     */
    public static float getTiltAngleClient(BlockEntity be) {
        if (!COMPANION_LOADED) return -1;
        return SableCompanionImpl.getTiltAngleClient(be);
    }

    /**
     * Get the elevation angle (degrees) of a pipe direction in world space.
     * Accounts for sub-level rotation. Returns 0–90.
     */
    public static float getPipeElevation(Level level, BlockPos pos, Direction dir) {
        if (dir == null) return 0;
        if (!COMPANION_LOADED) {
            return (float) Math.toDegrees(Math.asin(Math.abs(dir.getStepY())));
        }
        return SableCompanionImpl.getPipeElevation(level, pos, dir);
    }

    /**
     * Returns true if this block is on a Sable sub-level that rotated since last check.
     * Used by GravityFlowMixin to detect stale gravity data.
     */
    public static boolean hasSubLevelRotated(Level level, BlockPos pos) {
        if (!COMPANION_LOADED) return false;
        return SableCompanionImpl.hasSubLevelRotated(level, pos);
    }

    /**
     * Client-side: check if a block position is on a Sable sub-level.
     */
    public static boolean isOnSubLevelClient(BlockPos pos) {
        if (!COMPANION_LOADED) return false;
        return SableCompanionImpl.isOnSubLevelClient(pos);
    }

    /**
     * Client-side: get the pipe elevation angle using the render pose.
     * Returns -1 if not on a sub-level, or the angle in degrees (0–90).
     */
    public static float getClientPipeElevation(BlockPos pos, Direction dir) {
        if (!COMPANION_LOADED || dir == null) return -1;
        return SableCompanionImpl.getClientPipeElevation(pos, dir);
    }
}
