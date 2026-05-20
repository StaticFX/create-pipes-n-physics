package de.devin.pipesnphysics.physics;

/**
 * Immutable snapshot of all physics configuration values.
 * Created from the mod's NeoForge config by the integration layer.
 */
public record PhysicsConfig(
        float gravityPerBlock,
        float frictionPerBlock,
        float maxPressure,
        float deadZone,
        float minGravityAngleDegrees,
        boolean anglePhysicsEnabled,
        boolean pumpGravityEnabled,
        float pumpGravityFactor,
        int maxGravityRange,
        boolean frictionAffectsFlow,
        float viscosityScaling
) {}
