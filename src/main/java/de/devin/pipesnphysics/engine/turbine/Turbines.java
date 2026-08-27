package de.devin.pipesnphysics.engine.turbine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.TurbineAdapter;
import de.devin.pipesnphysics.api.TurbineApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the engine knows about a turbine: whether a block is running as one, what it is rated for,
 * and how to hand it the fall it just swallowed. The dual of {@code engine.pump.Pumps} and the same
 * single-source rule — the solve, the drive and {@code /pipegraph} all ask here.
 *
 * A pump can be read from the outside because every pump publishes its strength as pipe pressure;
 * power going OUT has no such shared currency, so a turbine that is not Create's own pump must be
 * ADAPTED: a mod registers a {@link TurbineAdapter} for its block ({@code TurbineApi}) and the
 * engine drives it exactly like the built-in one. Both kinds are the same graph node as a pump with
 * their head NEGATED (§5.4) — everything else falls out of that sign.
 */
public final class Turbines {
    private Turbines() {}

    /** Whether the block here is acting as a turbine right now. */
    public static boolean isTurbine(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof HydroTurbine turbine) {
            return turbine.pipesnphysics$isTurbine();
        }
        TurbineAdapter adapter = adapterAt(level, pos);
        return adapter != null && adapter.isTurbine(level, pos);
    }

    /** Blocks of head it takes out of the line — the fall it needs before it passes anything. */
    public static double ratedHead(Level level, BlockPos pos) {
        TurbineAdapter adapter = adapterAt(level, pos);
        double rated = adapter == null ? 0 : adapter.ratedHead(level, pos);
        return rated > 0 ? rated : TurbineRating.ratedHead();
    }

    /** Its internal conductance: flow per block of head, so free-flow caps at what it swallows. */
    public static double internalConductance(Level level, BlockPos pos) {
        TurbineAdapter adapter = adapterAt(level, pos);
        double swallow = adapter == null ? 0 : adapter.swallowMb(level, pos);
        if (swallow <= 0) return TurbineRating.internalConductance();
        return swallow / ratedHead(level, pos);
    }

    /** The side it discharges through when the block's own facing does not say — else null. */
    public static Direction pushSide(Level level, BlockPos pos) {
        TurbineAdapter adapter = adapterAt(level, pos);
        return adapter == null ? null : adapter.pushSide(level, pos);
    }

    /** Hand it the fluid that really fell through it this tick; silence is its spin-down signal. */
    public static void drive(Level level, BlockPos pos, int flowMb) {
        if (level.getBlockEntity(pos) instanceof HydroTurbine turbine) {
            turbine.pipesnphysics$driveTurbine(flowMb);
            return;
        }
        TurbineAdapter adapter = adapterAt(level, pos);
        if (adapter != null) adapter.driveTurbine(level, pos, flowMb);
    }

    /**
     * Whether a block is a registered turbine at all — identity, not "is it turning". Asked for every
     * cell while the graph is built, so it takes the state the caller already holds.
     */
    public static boolean isRegistered(BlockState state) {
        return PipesNPhysicsConfig.ENABLE_HYDRO_TURBINE.get()
                && TurbineApi.isTurbineBlock(state.getBlock());
    }

    private static TurbineAdapter adapterAt(Level level, BlockPos pos) {
        if (!PipesNPhysicsConfig.ENABLE_HYDRO_TURBINE.get()) return null;
        return TurbineApi.adapter(level.getBlockState(pos).getBlock());
    }
}
