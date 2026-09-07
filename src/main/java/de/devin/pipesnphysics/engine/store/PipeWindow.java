package de.devin.pipesnphysics.engine.store;

import com.simibubi.create.content.fluids.pipes.AxisPipeBlock;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The vertical WINDOW a pipe cell holds — and renders — its fluid in: the geometry the settle
 * ({@code FlowNetwork}) uses for its rest targets, matched by the client renderer
 * ({@code PipeFluidRenderer}). A HORIZONTAL (X/Z) straight cell fills only the 6/16 BORE (floor at
 * {@code cellBottom + 3/16}); a VERTICAL (Y-axis) riser fills the FULL block. Every
 * stored-volume↔height conversion must use this ONE window, or a settled pipe's surface sits off
 * the tank it equalized with.
 */
public final class PipeWindow {
    /** Bore floor as a block-local offset — matches the renderer's PIPE_RADIUS (3/16). */
    public static final double BORE_BOTTOM = 0.5 - 3.0 / 16;
    /** Bore height — the 6/16 window a horizontal pipe draws its fluid in. */
    public static final double BORE_HEIGHT = 2 * (3.0 / 16);
    /**
     * Opening LIP as a block-local offset — the bottom of the pipe's connection APERTURE, the
     * 4×4-pixel window fluid actually enters through (owner-specified: it sits one pixel above
     * Create's tank fluid floor, the 5/16 render inset — so 6/16). Compared against the RENDERED
     * surface — the fluid the player sees — never the liquid head (§5a lip rule). Sitting a
     * pixel ABOVE the tank render floor is what lets a base-row tank keep its visible puddle:
     * at 5/16 the lip coincided with the render floor and every tank drained to nothing.
     */
    public static final double LIP_BOTTOM = 6.0 / 16;

    private PipeWindow() {}

    /** World-Y of the draw LIP of an opening through this cell: the aperture bottom for a bore cell, the block bottom for a riser. */
    public static double lipY(Level level, BlockPos pos) {
        double cellBottom = SableCompat.getWorldY(level, pos) - 0.5;
        return fillsFullBlock(level, pos) ? cellBottom : cellBottom + LIP_BOTTOM;
    }

    /**
     * The elevation a reservoir's RENDERED surface must clear to give through an opening — the ONE
     * datum every draw gate reads, so the solve's wall and the executor's can never drift apart. A
     * pump actively PULLING keeps the opening cell's BLOCK floor instead of the aperture lip: its
     * suction takes the puddle under the pipe too.
     */
    public static double drawLipY(Level level, BlockPos opening, boolean pumpPulls) {
        return pumpPulls ? SableCompat.getWorldY(level, opening) - 0.5 : lipY(level, opening);
    }

    /**
     * The same threshold IN THE PASS'S OWN FRAME, mirrored for a lighter-than-air fluid: a buoyant
     * column reaches an opening by sinking DOWN to it, not by rising to it, so its gate is the
     * aperture's TOP and the comparison runs upside down (negated, like the solver's gas head).
     * Read against {@code BoundaryColumn.drawHead(gas)}, which mirrors to match.
     *
     * Without this the gas frame had NO give gate at all — the solve drained a gas vessel through
     * an opening its gas layer never reached, while the settle, which does read the mirrored
     * geometry, poured it straight back every tick. A nearly-empty CO2 tank whose pipe left below
     * its ceiling pocket ground 3 mB back and forth forever ("the fluid flickers a lot inside the
     * pipes"; the /pipegraph tell is an alternating {@code recent ·3 →3 ·3 →3} strip on an edge
     * reading {@code idle solved=0 actual=3}).
     */
    public static double drawLipY(Level level, BlockPos opening, boolean pumpPulls, boolean gas) {
        if (!gas) return drawLipY(level, opening, pumpPulls);
        double cellTop = SableCompat.getWorldY(level, opening) + 0.5;
        if (pumpPulls) return -cellTop;
        return -(fillsFullBlock(level, opening) ? cellTop : cellTop - LIP_BOTTOM);
    }

    /** Whether this cell is a VERTICAL straight pipe — its fluid fills the full block height. */
    public static boolean fillsFullBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.hasProperty(AxisPipeBlock.AXIS)
                && state.getValue(AxisPipeBlock.AXIS) == Direction.Axis.Y;
    }

    /** World-Y of the bottom of a cell's fluid window — bore floor horizontal, block bottom vertical. */
    public static double bottomY(Level level, BlockPos pos) {
        double cellBottom = SableCompat.getWorldY(level, pos) - 0.5;
        return fillsFullBlock(level, pos) ? cellBottom : cellBottom + BORE_BOTTOM;
    }

    /** Height of a cell's fluid window: the full block for a riser, else the 6/16 bore. */
    public static double height(Level level, BlockPos pos) {
        return fillsFullBlock(level, pos) ? 1.0 : BORE_HEIGHT;
    }

    /** Fraction of a cell's window sitting below the surface line, clamped 0..1. */
    public static double fill(Level level, BlockPos pos, double line) {
        return Math.clamp((line - bottomY(level, pos)) / height(level, pos), 0, 1);
    }

}
