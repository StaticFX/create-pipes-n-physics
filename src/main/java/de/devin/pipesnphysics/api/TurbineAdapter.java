package de.devin.pipesnphysics.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * How a block runs BACKWARDS on a pipe run: the engine takes head out of the line at it and hands it
 * the fluid that really fell through, and this turns that stream into whatever power the mod deals in
 * — rotation, electricity, heat.
 *
 * A pump can be read from the outside (every pump publishes its strength as pipe pressure, §2), but
 * there is no such currency for power going OUT, so a turbine has to be adapted: register one of
 * these for the block through {@link TurbineApi}. Nothing else is needed — the engine handles the
 * hydraulics, including refusing to pass fluid at all until the fall exceeds the rated head.
 */
public interface TurbineAdapter {
    /**
     * One tick's throughput, in mB, measured as what ACTUALLY moved on the outlet flank — a run its
     * supply cannot keep up with reports the trickle, not the rating. Called once per solve while
     * the network is awake; a network with nothing moving stops calling, so silence is the signal to
     * spin down. Never called on the client.
     */
    void driveTurbine(Level level, BlockPos pos, int flowMb);

    /** Whether this block is acting as a turbine right now — a dial, a redstone state, a mode. */
    default boolean isTurbine(Level level, BlockPos pos) {
        return true;
    }

    /** The side fluid leaves through; null reads the block's own facing. */
    default Direction pushSide(Level level, BlockPos pos) {
        return null;
    }

    /**
     * Blocks of head this turbine takes out of the line — the fall it needs before it passes
     * anything. 0 uses the engine's configured rating, which is what most machines should want.
     */
    default double ratedHead(Level level, BlockPos pos) {
        return 0;
    }

    /** The most it passes per tick, in mB — its runner's swallowing capacity. 0 uses the config. */
    default double swallowMb(Level level, BlockPos pos) {
        return 0;
    }
}
