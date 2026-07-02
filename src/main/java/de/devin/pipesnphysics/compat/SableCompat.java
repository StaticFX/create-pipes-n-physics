package de.devin.pipesnphysics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiConsumer;

public class SableCompat {

    private static final SableCompatProvider PROVIDER;
    /** Whether FULL Sable (the sub-level/physics half) is present, so server sub-levels exist. */
    private static final boolean SUBLEVELS_PRESENT = classPresent("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");

    static {
        PROVIDER = classPresent("dev.ryanhcode.sable.companion.SableCompanion")
                ? new SableCompanionImpl() : new NoOpProvider();
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, SableCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isCompanionLoaded() {
        return PROVIDER instanceof SableCompanionImpl;
    }

    /**
     * Seed every pipe cell on every active sub-level of this level. Sable assembles a
     * contraption with raw {@code setBlockState} (no place event) and a dry pipe never
     * self-ticks, so a sub-level network is otherwise never woken — the engine would be
     * frozen on contraptions. No-op when full Sable is absent.
     */
    public static void seedSubLevels(ServerLevel level, BiConsumer<Level, BlockPos> seed) {
        if (SUBLEVELS_PRESENT) SableSubLevelDriver.seed(level, seed);
    }

    public static void clearCaches() {
        PROVIDER.clearCaches();
        if (SUBLEVELS_PRESENT) SableSubLevelDriver.clear();
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

    /**
     * The world-Y component of the column's local-up axis (cos of the tilt), used to scale a
     * fluid column's fill height: on a tilted tank fluid rises along LOCAL up, not world up, so
     * the surface elevation gains only {@code fillHeight · cosTilt} of world height. 1 when level.
     */
    public static double getUpProjectionY(Level level, BlockPos pos) {
        return PROVIDER.getUpProjectionY(level, pos);
    }

    /**
     * The world-Y of a fluid column's BOTTOM, anchored at the box's projected geometric center so
     * a tilted multiblock tank's surface (= baseY + fillFraction·height·{@link #getUpProjectionY})
     * stays volume-true at the half-full line instead of skewing off the bottom corner. {@code pos}
     * is the controller/handler block, {@code width}×{@code height} its block extent (1×1 for a
     * single block). Reduces to {@code worldY(pos) − 0.5} when level / off a sub-level.
     */
    public static double getColumnBaseY(Level level, BlockPos pos, int width, int height) {
        return PROVIDER.getColumnBaseY(level, pos, width, height);
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
        public double getUpProjectionY(Level level, BlockPos pos) {
            return 1.0;
        }

        @Override
        public double getColumnBaseY(Level level, BlockPos pos, int width, int height) {
            return pos.getY();
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
