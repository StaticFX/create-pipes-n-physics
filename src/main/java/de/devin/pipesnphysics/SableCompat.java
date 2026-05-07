package de.devin.pipesnphysics;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

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

    /**
     * Get the tilt angle (degrees) of a block's sub-level relative to world.
     * 0° = level (local Y = world Y), 90° = on its side, 180° = upside down.
     * Returns 0 if the block is not on a sub-level.
     */
    public static float getTiltAngle(Level level, BlockPos pos) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        if (sub == null) return 0;
        return pipesnphysics$angleFromPose(sub.logicalPose());
    }

    /**
     * Client-side tilt angle — uses getContainingClient for block entities on sub-levels.
     * More reliable on the client than the Level+BlockPos version.
     */
    public static float getTiltAngleClient(BlockEntity be) {
        // Try multiple lookup methods
        ClientSubLevelAccess sub = SableCompanion.INSTANCE.getContainingClient(be);
        if (sub == null) sub = SableCompanion.INSTANCE.getContainingClient(be.getBlockPos());
        if (sub == null && be.getLevel() != null) {
            SubLevelAccess sub2 = SableCompanion.INSTANCE.getContaining(be.getLevel(), be.getBlockPos());
            if (sub2 instanceof ClientSubLevelAccess csa) sub = csa;
        }
        if (sub == null) return -1; // -1 = not found (distinguishable from 0 = level)
        Pose3dc pose = sub.renderPose();
        if (pose == null) pose = sub.logicalPose();
        return pipesnphysics$angleFromPose(pose);
    }

    /**
     * Get the elevation angle (degrees) of a direction in world space.
     * Transforms the local direction by the sub-level's pose, then computes
     * the angle between the world direction and the horizontal XZ plane.
     * Returns 0-90. Positive = downward slope (gravity helps).
     */
    public static float getPipeElevation(Level level, BlockPos pos, net.minecraft.core.Direction dir) {
        if (dir == null) return 0;
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        if (sub != null) {
            Pose3dc pose = sub.logicalPose();
            if (pose != null) {
                Vector3d worldDir = pose.transformNormal(
                        new Vector3d(dir.getStepX(), dir.getStepY(), dir.getStepZ()),
                        new Vector3d());
                double len = Math.sqrt(worldDir.x*worldDir.x + worldDir.y*worldDir.y + worldDir.z*worldDir.z);
                if (len > 0.001) {
                    return (float) Math.toDegrees(Math.asin(Math.min(1, Math.max(-1, Math.abs(worldDir.y) / len))));
                }
            }
        }
        // Not on sub-level — use raw direction
        return (float) Math.toDegrees(Math.asin(Math.abs(dir.getStepY())));
    }

    private static float pipesnphysics$angleFromPose(Pose3dc pose) {
        if (pose == null) return 0;
        Vector3d localUp = pose.transformNormalInverse(new Vector3d(0, 1, 0), new Vector3d());
        double len = Math.sqrt(localUp.x*localUp.x + localUp.y*localUp.y + localUp.z*localUp.z);
        if (len < 0.001) return 0;
        localUp.div(len);
        return (float) Math.toDegrees(Math.acos(Math.min(1, Math.max(-1, localUp.y))));
    }
}
