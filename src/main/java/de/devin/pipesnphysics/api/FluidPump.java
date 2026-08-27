package de.devin.pipesnphysics.api;

import net.minecraft.core.Direction;

/**
 * Implemented by a pump's own block entity to state how hard it pumps — the exact, guess-free way
 * for an addon to add a pump the hydraulic engine drives.
 *
 * Implement this only when the engine cannot already read your machine from the outside: it takes
 * the pressure a pump publishes to Create's pipe transport (the currency every pump on the pipe
 * network already speaks, whatever powers it), and failing that the speed of the shaft turning it.
 * Both are read automatically once the block is known to be a pump — through the
 * {@code pipesnphysics:pumps} block tag, {@link PumpApi}, or this interface.
 */
public interface FluidPump {
    /**
     * How hard this pump pushes, in Create RPM — the scale a Mechanical Pump's shaft speed is on, so
     * the engine's head (blocks per RPM) and throughput (mB/t per RPM) apply to it unchanged. 0 means
     * stopped, which makes the pump a closed valve like an unpowered Mechanical Pump.
     */
    double pumpStrength();

    /** The side this pump pushes toward, or null to read it off the block's own facing. */
    default Direction pumpPushSide() {
        return null;
    }
}
