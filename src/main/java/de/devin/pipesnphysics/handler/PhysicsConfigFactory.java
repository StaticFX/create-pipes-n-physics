package de.devin.pipesnphysics.handler;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.physics.PhysicsConfig;
import de.devin.pipesnphysics.physics.SimConfig;

/**
 * Reads from NeoForge mod config and produces immutable config snapshots.
 * Call once per computation so config changes take effect immediately.
 */
public final class PhysicsConfigFactory {

    private PhysicsConfigFactory() {}

    public static SimConfig simConfig() {
        return new SimConfig(
                PipesNPhysicsConfig.GRAVITY_PRESSURE_PER_BLOCK.get().floatValue(),
                PipesNPhysicsConfig.GRAVITY_DEAD_ZONE.get().floatValue(),
                500.0f, // maxFlow — max mB per edge per sim tick (uncoupled from display pressure cap)
                10.0f,  // conductance — flow = COND * |ΔΦ| / viscosity
                PipesNPhysicsConfig.PIPE_BURST_THRESHOLD.get().floatValue(),
                PipesNPhysicsConfig.PIPE_FRICTION_PER_BLOCK.get().floatValue(),
                1000,  // per-pipe capacity in mB
                10.0f, // taperMargin — head below which flow tapers
                256.0f, // defaultPumpHead — reach ~50 tiles at friction=5
                2.0f,  // frontK — ~2 tiles/tick at full head with water viscosity
                5.0f   // hysteresis — head margin before FLOWING→DRAINING
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
