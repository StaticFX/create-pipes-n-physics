package de.devin.pipesnphysics.compat;

import de.devin.pipesnphysics.engine.motion.CentrifugeField;
import de.devin.pipesnphysics.engine.motion.MomentumField;
import de.devin.pipesnphysics.engine.probe.SublevelSpinProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

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
        SublevelSpinProbe.clear();
        CentrifugeField.clear();
        MomentumField.clear();
        if (SUBLEVELS_PRESENT) {
            SableSubLevelDriver.clear();
            SablePhysicsCompat.clear();
        }
    }

    public static boolean isSubLevelReady(Level level, BlockPos pos) {
        return PROVIDER.isSubLevelReady(level, pos);
    }

    /**
     * The unique-id string of the physics sub-level containing this cell, or null off a sub-level — the
     * SAME identity a contraption's physics tick records its momentum frame under, so the read side can
     * attribute a cell to its own rigid body instead of guessing by proximity.
     */
    public static String getSubLevelId(Level level, BlockPos pos) {
        return PROVIDER.getSubLevelId(level, pos);
    }

    /**
     * Whether this cell sits on a physics sub-level at all — the cheap (no-allocation) form of
     * {@link #getSubLevelId}. A cell off one rides no rigid body: it cannot move, so anything
     * sourced from its motion is inert by construction and need not be measured or remembered.
     */
    public static boolean isOnSubLevel(Level level, BlockPos pos) {
        return PROVIDER.isOnSubLevel(level, pos);
    }

    /**
     * First non-null result of reading the block on each OTHER Sable contraption whose physical
     * bounds overlap the world position {@code origin} projects to — the mechanism behind
     * cross-level piping (ship A's mouth drinking a fluid block on ship B where the two overlap, or a
     * main-level mouth over a contraption). {@code origin} is the mouth's OWN-level (plot) position; the reader is invoked
     * with the corresponding block position on each overlapping contraption. The host-world block
     * under the mouth is NOT visited here (callers read it separately via the projected position);
     * this adds only the other-contraption hits. Returns null off Sable or when nothing overlaps.
     */
    public static <T> T atOverlappingContraptions(Level level, BlockPos origin, BiFunction<Level, BlockPos, T> reader) {
        return PROVIDER.atOverlappingContraptions(level, origin, reader);
    }

    /**
     * How the sub-level containing this position maps onto the screen THIS frame, or null when the
     * position is not on one (and always without Sable). CLIENT ONLY — it reads the interpolated
     * render pose, which is what an overlay has to match to land on the contraption a player sees.
     */
    public static SubLevelFrame clientFrame(Level level, BlockPos pos, float partialTicks) {
        return PROVIDER.clientFrame(level, pos, partialTicks);
    }

    /**
     * The render frame of every contraption within {@code radius} of a world point — how a renderer
     * that sweeps the world around the camera finds the plot chunks it would otherwise never reach.
     */
    public static List<SubLevelFrame> clientFramesNear(Level level, Vec3 center, double radius,
                                                       float partialTicks) {
        return PROVIDER.clientFramesNear(level, center, radius, partialTicks);
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

    /**
     * Like {@link #getColumnBaseY} but for a tank whose geometric center is not at
     * {@code (width/2, height/2, width/2)} — horizontal fluid vessels lay out along X/Z.
     */
    public static double getColumnBaseYAtCenter(Level level, BlockPos pos, double halfX, double halfY,
                                                double halfZ, int verticalBlocks) {
        return PROVIDER.getColumnBaseYAtCenter(level, pos, halfX, halfY, halfZ, verticalBlocks);
    }

    public static float getTiltAngle(Level level, BlockPos pos) {
        return PROVIDER.getTiltAngle(level, pos);
    }

    public static float getTiltAngleClient(BlockEntity be) {
        return PROVIDER.getTiltAngleClient(be);
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
        public <T> T atOverlappingContraptions(Level level, BlockPos origin, BiFunction<Level, BlockPos, T> reader) {
            return null; // no sub-levels without Sable: nothing overlaps
        }

        @Override
        public SubLevelFrame clientFrame(Level level, BlockPos pos, float partialTicks) {
            return null; // every block is drawn where it stands
        }

        @Override
        public List<SubLevelFrame> clientFramesNear(Level level, Vec3 center, double radius,
                                                    float partialTicks) {
            return List.of();
        }

        @Override
        public String getSubLevelId(Level level, BlockPos pos) {
            return null; // no sub-levels without Sable: every cell is main-level
        }

        @Override
        public boolean isOnSubLevel(Level level, BlockPos pos) {
            return false;
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
        public double getColumnBaseYAtCenter(Level level, BlockPos pos, double halfX, double halfY,
                                             double halfZ, int verticalBlocks) {
            return pos.getY() + halfY - 0.5 * verticalBlocks;
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
