package de.devin.pipesnphysics.handler;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.physics.PhysicsConfig;

/**
 * Reads from NeoForge mod config and produces an immutable {@link PhysicsConfig}
 * snapshot for the physics package. Call once per computation, not once at startup,
 * so config changes take effect immediately.
 */
public final class PhysicsConfigFactory {

    private PhysicsConfigFactory() {}

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
                PipesNPhysicsConfig.FRICTION_AFFECTS_FLOW.get()
        );
    }
}
