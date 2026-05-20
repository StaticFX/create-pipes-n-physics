package de.devin.pipesnphysics.physics;

/**
 * Pure physics formulas for pipe flow.
 * No Minecraft dependencies — all inputs are plain numbers.
 *
 * <p>Core model: {@code effectivePressure = headPressure - pathFriction}</p>
 *
 * <p>Friction scales with pipe elevation angle. Pipes steeper than
 * {@code minGravityAngle} are fully gravity-assisted (zero friction).
 * Below that threshold, friction ramps quadratically from zero to full
 * at 0° (flat). This means nearly any downhill pipe flows freely,
 * while only flat pipes incur full friction.</p>
 */
public final class PipeFormulas {

    private static final float VERTICAL_THRESHOLD = 89.0f;

    private final PhysicsConfig config;

    public PipeFormulas(PhysicsConfig config) {
        this.config = config;
    }

    public PhysicsConfig config() {
        return config;
    }

    /**
     * Friction for a single pipe segment based on its elevation angle.
     *
     * @param elevationAngleDegrees world-space angle from horizontal (0° = flat, 90° = vertical)
     * @return friction value for this segment
     */
    public float segmentFriction(float elevationAngleDegrees) {
        return segmentFriction(elevationAngleDegrees, 1.0f);
    }

    public float segmentFriction(float elevationAngleDegrees, float viscosityMultiplier) {
        float base;
        if (!config.anglePhysicsEnabled()) {
            base = elevationAngleDegrees >= VERTICAL_THRESHOLD ? 0 : config.frictionPerBlock();
        } else {
            float threshold = config.minGravityAngleDegrees();
            if (threshold <= 0) {
                float sinElev = (float) Math.sin(Math.toRadians(elevationAngleDegrees));
                float factor = 1.0f - sinElev;
                base = factor * factor * config.frictionPerBlock();
            } else if (elevationAngleDegrees >= threshold) {
                base = 0;
            } else {
                float t = elevationAngleDegrees / threshold;
                float factor = 1.0f - t;
                base = factor * factor * config.frictionPerBlock();
            }
        }
        float scaled = 1.0f + (viscosityMultiplier - 1.0f) * config.viscosityScaling();
        return base * scaled;
    }

    /**
     * Pressure at a node in a gravity-only network.
     *
     * @param sourceWorldY source fluid handler's world-space Y
     * @param nodeWorldY   this pipe's world-space Y
     * @param pathFriction accumulated friction from source to this node
     * @return pressure (clamped to [0, maxPressure])
     */
    public float gravityPressure(double sourceWorldY, double nodeWorldY, float pathFriction) {
        float heightDiff = (float) (sourceWorldY - nodeWorldY);
        if (heightDiff < config.deadZone()) return 0;
        return Math.clamp(heightDiff * config.gravityPerBlock() - pathFriction, 0, config.maxPressure());
    }

    /**
     * Convert linearly accumulated friction to diminishing (logarithmic) friction.
     * Each additional pipe segment adds less friction than the last.
     * Used when {@code frictionAffectsFlow} is enabled to soften the flow penalty.
     *
     * @param linearFriction total friction accumulated linearly along the path
     * @return diminished friction value
     */
    public float diminishingFriction(float linearFriction) {
        float frictionPB = config.frictionPerBlock();
        if (frictionPB < 0.001f) return 0;
        float linearSegments = linearFriction / frictionPB;
        return frictionPB * (float) Math.log(1 + linearSegments);
    }

    /**
     * Compute the bottleneck flow pressure for a pump network.
     * When {@code frictionAffectsFlow} is true, uses diminishing friction at the
     * worst pipe to determine the uniform flow rate for the whole series.
     * When false, returns pumpBase unchanged (vanilla-like: friction only limits range).
     *
     * @param pumpBase        pump's base pressure (from RPM)
     * @param maxLinearFriction highest accumulated friction among reachable pipes
     * @param gravityAtWorst  gravity assist at the pipe with most friction
     * @return uniform pressure to apply to all reachable pipes
     */
    public float bottleneckFlowPressure(float pumpBase, float maxLinearFriction, float gravityAtWorst) {
        if (!config.frictionAffectsFlow()) return pumpBase;
        float diminished = diminishingFriction(maxLinearFriction);
        return Math.max(0, pumpBase + gravityAtWorst - diminished);
    }

    /**
     * Pressure at a node in a pump network with gravity assist.
     * Not capped by maxPressure — pump pressure is driven by RPM,
     * which can exceed the gravity pressure ceiling.
     *
     * @param pumpBase     pump's base pressure (from RPM/speed)
     * @param pumpWorldY   pump's world-space Y
     * @param nodeWorldY   this pipe's world-space Y
     * @param pathFriction accumulated friction from pump to this node
     * @return pressure (clamped to ≥ 0)
     */
    public float pumpPressure(float pumpBase, double pumpWorldY, double nodeWorldY,
                              float pathFriction) {
        float gravityAssist = config.pumpGravityEnabled()
                ? (float) (pumpWorldY - nodeWorldY) * config.gravityPerBlock() * config.pumpGravityFactor()
                : 0;
        return Math.max(0, pumpBase + gravityAssist - pathFriction);
    }
}
