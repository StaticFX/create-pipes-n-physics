package de.devin.pipesnphysics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Sable Companion compatibility layer.
 * Loads the real implementation if Sable Companion is present,
 * otherwise uses a no-op implementation that returns vanilla defaults.
 */
public class SableCompat {

    private static final SableCompatProvider PROVIDER;

    static {
        boolean found;
        try {
            Class.forName("dev.ryanhcode.sable.companion.SableCompanion", false,
                    SableCompat.class.getClassLoader());
            found = true;
        } catch (ClassNotFoundException e) {
            found = false;
        }
        PROVIDER = found ? new SableCompanionImpl() : new NoOpProvider();
    }

    public static boolean isCompanionLoaded() {
        return PROVIDER instanceof SableCompanionImpl;
    }

    public static void clearCaches() {
        PROVIDER.clearCaches();
    }

    public static boolean isSubLevelReady(Level level, BlockPos pos) {
        return PROVIDER.isSubLevelReady(level, pos);
    }

    public static double getWorldY(Level level, BlockPos pos) {
        return PROVIDER.getWorldY(level, pos);
    }

    public static Vec3 getWorldPos(Level level, BlockPos pos) {
        return PROVIDER.getWorldPos(level, pos);
    }

    public static float getTiltAngle(Level level, BlockPos pos) {
        return PROVIDER.getTiltAngle(level, pos);
    }

    public static float getTiltAngleClient(BlockEntity be) {
        return PROVIDER.getTiltAngleClient(be);
    }

    public static boolean hasSubLevelRotated(Level level, BlockPos pos) {
        return PROVIDER.hasSubLevelRotated(level, pos);
    }

    public static boolean isOnSubLevelClient(BlockPos pos) {
        return PROVIDER.isOnSubLevelClient(pos);
    }

    public static double getHeightDifference(Level level, BlockPos higher, BlockPos lower) {
        return PROVIDER.getWorldY(level, higher) - PROVIDER.getWorldY(level, lower);
    }

    public static float getPipeElevation(Level level, BlockPos pos, Direction dir) {
        if (dir == null) return 0;
        return PROVIDER.getPipeElevation(level, pos, dir);
    }

    public static float getClientPipeElevation(BlockPos pos, Direction dir) {
        if (dir == null) return -1;
        return PROVIDER.getClientPipeElevation(pos, dir);
    }

    public static boolean canFluidReachPipe(Level level, BlockPos tankPos, BlockPos pipePos, double fillFraction) {
        return PROVIDER.canFluidReachPipe(level, tankPos, pipePos, fillFraction);
    }


    /**
     * No-op implementation when Sable Companion is not installed.
     */
    private static class NoOpProvider implements SableCompatProvider {
        @Override
        public void clearCaches() {
        }

        @Override
        public boolean isSubLevelReady(Level level, BlockPos pos) {
            return true;
        }

        @Override
        public double getWorldY(Level level, BlockPos pos) {
            return pos.getY() + 0.5;
        }

        @Override
        public Vec3 getWorldPos(Level level, BlockPos pos) {
            return Vec3.atCenterOf(pos);
        }

        @Override
        public float getTiltAngle(Level level, BlockPos pos) {
            return 0;
        }

        @Override
        public float getTiltAngleClient(BlockEntity be) {
            return -1;
        }

        @Override
        public float getPipeElevation(Level level, BlockPos pos, Direction dir) {
            return (float) Math.toDegrees(Math.asin(Math.abs(dir.getStepY())));
        }

        @Override
        public boolean hasSubLevelRotated(Level level, BlockPos pos) {
            return false;
        }

        @Override
        public boolean isOnSubLevelClient(BlockPos pos) {
            return false;
        }

        @Override
        public float getClientPipeElevation(BlockPos pos, Direction dir) {
            return -1;
        }

        @Override
        public boolean canFluidReachPipe(Level level, BlockPos tankPos, BlockPos pipePos, double fillFraction) {
            return true;
        }
    }
}
