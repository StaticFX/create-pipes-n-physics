package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Multiblock footprint and hydraulic column geometry for Create fluid tanks and Create: Connected
 * horizontal fluid vessels. Vessels reuse {@link FluidTankBlockEntity}'s {@code width}/{@code height}
 * fields but lay out along {@link FluidTankBlockEntity#getMainConnectionAxis()} instead of world-up.
 */
public final class FluidTankGeometry {
    private FluidTankGeometry() {}

    /** End-cap thickness along the tank/vessel length axis (matches Create). */
    public static final float CAP = 1 / 4f;
    /** Puddle lip above the interior floor along the length axis. */
    public static final float PUDDLE = 1 / 16f;
    /** Glass hull wall thickness on cross-section axes. */
    public static final float HULL = 1 / 16f + 1 / 128f;

    /** Multiblock extent along each local axis from the controller origin, in blocks. */
    public record LocalExtents(int x, int y, int z) {}

    /**
     * Inset fluid-interior box in controller-local block coordinates, plus the span along local Y
     * used to map fill fraction to height when the tank is upright.
     */
    public record RenderInterior(
            float xMin, float xMax,
            float yMin, float yMax,
            float zMin, float zMax,
            float fillSpanY
    ) {
        public float centerX() { return (xMin + xMax) * 0.5f; }
        public float centerY() { return (yMin + yMax) * 0.5f; }
        public float centerZ() { return (zMin + zMax) * 0.5f; }
    }

    public static boolean isHorizontal(FluidTankBlockEntity controller) {
        return controller.getMainConnectionAxis() != Direction.Axis.Y;
    }

    /** Every block cell in a multiblock tank or fluid vessel. */
    public static List<BlockPos> footprint(FluidTankBlockEntity controller) {
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int length = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        BlockPos base = controller.getBlockPos();
        Direction.Axis axis = controller.getMainConnectionAxis();

        List<BlockPos> blocks = new ArrayList<>(width * width * length);
        if (axis == Direction.Axis.Y) {
            for (int dx = 0; dx < width; dx++) {
                for (int dy = 0; dy < length; dy++) {
                    for (int dz = 0; dz < width; dz++) {
                        blocks.add(base.offset(dx, dy, dz));
                    }
                }
            }
            return blocks;
        }

        for (int y = 0; y < width; y++) {
            for (int len = 0; len < length; len++) {
                for (int w = 0; w < width; w++) {
                    blocks.add(base.offset(
                            axis == Direction.Axis.X ? len : w,
                            y,
                            axis == Direction.Axis.Z ? len : w));
                }
            }
        }
        return blocks;
    }

    public static List<BlockPos> footprint(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return List.of(pos);
        FluidTankBlockEntity controller = tank.getControllerBE();
        return controller != null ? footprint(controller) : List.of(pos);
    }

    /**
     * Vertical extent in blocks along which fill rises for head equalization. For a vertical tank this
     * is its height; for a horizontal fluid vessel it is the cross-section height ({@code width}).
     */
    public static int columnHeightBlocks(FluidTankBlockEntity controller) {
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int length = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        return isHorizontal(controller) ? width : length;
    }

    public static LocalExtents localExtents(FluidTankBlockEntity controller) {
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int length = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        Direction.Axis axis = controller.getMainConnectionAxis();
        if (axis == Direction.Axis.Y) return new LocalExtents(width, length, width);
        if (axis == Direction.Axis.X) return new LocalExtents(length, width, width);
        return new LocalExtents(width, width, length);
    }

    /**
     * Fluid-interior bounds for tilted rendering and center-of-mass offsets. Caps sit on the
     * length axis (world Y for vertical tanks, X/Z for horizontal fluid vessels); hull walls sit
     * on the cross-section axes.
     */
    public static RenderInterior renderInterior(FluidTankBlockEntity controller, float inset) {
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int length = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        Direction.Axis axis = controller.getMainConnectionAxis();

        float yMin = HULL + inset;
        float yMax = HULL + width - 2 * HULL - inset;
        float hullSpanY = yMax - yMin;

        if (axis == Direction.Axis.Y) {
            float xMin = HULL + inset;
            float xMax = HULL + width - 2 * HULL - inset;
            float zMin = HULL + inset;
            float zMax = HULL + width - 2 * HULL - inset;
            float fillSpanY = length - 2 * CAP - PUDDLE;
            float spanYMin = CAP + inset;
            float spanYMax = CAP + PUDDLE + fillSpanY - inset;
            return new RenderInterior(xMin, xMax, spanYMin, spanYMax, zMin, zMax, fillSpanY);
        }

        float lengthSpan = length - 2 * CAP - PUDDLE;
        float xMin = HULL + inset;
        float xMax = HULL + width - 2 * HULL - inset;
        float zMin = HULL + inset;
        float zMax = HULL + width - 2 * HULL - inset;

        if (axis == Direction.Axis.X) {
            float spanXMin = CAP + inset;
            float spanXMax = CAP + PUDDLE + lengthSpan - inset;
            return new RenderInterior(spanXMin, spanXMax, yMin, yMax, zMin, zMax, hullSpanY);
        }

        float spanZMin = CAP + inset;
        float spanZMax = CAP + PUDDLE + lengthSpan - inset;
        return new RenderInterior(xMin, xMax, yMin, yMax, spanZMin, spanZMax, hullSpanY);
    }

    /** World-Y of the bottom of the hydraulic column used by the solver. */
    public static double columnBaseY(Level level, BlockPos controllerPos, FluidTankBlockEntity controller) {
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int length = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        int vertical = columnHeightBlocks(controller);
        Direction.Axis axis = controller.getMainConnectionAxis();

        if (axis == Direction.Axis.Y) {
            return SableCompat.getColumnBaseY(level, controllerPos, width, vertical);
        }

        double halfX = axis == Direction.Axis.X ? length / 2.0 : width / 2.0;
        double halfY = vertical / 2.0;
        double halfZ = axis == Direction.Axis.Z ? length / 2.0 : width / 2.0;
        return SableCompat.getColumnBaseYAtCenter(level, controllerPos, halfX, halfY, halfZ, vertical);
    }
}
