package de.devin.pipesnphysics.handler;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.physics.PhysicsConfig;
import de.devin.pipesnphysics.physics.SimConfig;

/**
 * Reads from NeoForge mod config and produces immutable config snapshots.
 * Call once per computation so config changes take effect immediately.
 *
 * Unit system: head is in "blocks of water column" (G=1, ρ_water=1).
 * A full 1-block tank produces 1 head. 256 RPM pump produces 32 head (=32 block lift).
 */
public final class PhysicsConfigFactory {

    private PhysicsConfigFactory() {}

    public static SimConfig simConfig() {
        return new SimConfig(
                1.0f,       // G — 1 block of water = 1 head (defines the unit)
                0.01f,      // EPS — tanks settle within ~80 mB
                128.0f,     // maxFlow — bore limit mB/t; strongest pump saturates one pipe
                100.0f,     // conductance — gravity equalize ~100 mB/t at ΔΦ=1
                512.0f,     // burstThreshold — 4× maxFlow (rare)
                0.1f,       // frictionPerBlock — reach = head/0.1 = 10× head
                35,         // perPipeCapacity — bore: (3/16)² × 1000 ≈ 35 mB/block
                1.0f,       // taperMargin — reach tapers over the last block
                32.0f,      // defaultPumpHead — fallback (256 RPM equivalent)
                0.5f,       // frontK — head 8 → 4 blocks/t charge speed
                1.0f,       // hysteresis — only drain on real deficit, not flicker
                0.125f,     // speedToHead — 256 RPM → 32 head (= 32-block lift)
                0.5f        // flowPerRPM — 256 RPM → 128 mB/t = maxFlow
        );
    }

    public static PhysicsConfig fromModConfig() {
        return new PhysicsConfig(
                PipesNPhysicsConfig.GRAVITY_PRESSURE_PER_BLOCK.get().floatValue(),
                PipesNPhysicsConfig.PIPE_FRICTION_PER_BLOCK.get().floatValue(),
                PipesNPhysicsConfig.MAX_GRAVITY_PRESSURE.get().floatValue(),
                PipesNPhysicsConfig.GRAVITY_DEAD_ZONE.get().floatValue(),
                PipesNPhysicsConfig.MIN_GRAVITY_ANGLE.get().floatValue(),
                PipesNPhysicsConfig.ENABLE_PIPE_ANGLE_PHYSICS.get(),
                PipesNPhysicsConfig.ENABLE_PUMP_GRAVITY.get(),
                PipesNPhysicsConfig.PUMP_GRAVITY_FACTOR.get().floatValue(),
                PipesNPhysicsConfig.MAX_GRAVITY_RANGE.get(),
                PipesNPhysicsConfig.FRICTION_AFFECTS_FLOW.get(),
                PipesNPhysicsConfig.VISCOSITY_SCALING.get().floatValue(),
                PipesNPhysicsConfig.PUMP_PUSH_RATIO.get().floatValue(),
                PipesNPhysicsConfig.PUMP_PULL_RATIO.get().floatValue(),
                PipesNPhysicsConfig.PIPE_BURST_THRESHOLD.get().floatValue(),
                PipesNPhysicsConfig.ENABLE_PIPE_BURSTING.get(),
                PipesNPhysicsConfig.BURST_WARNING_TICKS.get(),
                PipesNPhysicsConfig.MAX_BURSTS_PER_TICK.get(),
                PipesNPhysicsConfig.MAX_CYCLE_ITERATIONS.get()
        );
    }
}
