package de.devin.pipesnphysics.physics;

import java.util.List;

/**
 * Pure physics formulas for pipe flow.
 * No Minecraft dependencies — all inputs are plain numbers.
 *
 * Core model:
 * - Gravity adds pressure per downhill segment
 * - Friction removes pressure on horizontal segments
 * - Merging junctions sum incoming pressures
 * - Splitting junctions divide pressure evenly
 * - Pipes burst when pressure exceeds the configured threshold
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
     * Steeper pipes have less friction. Vertical pipes have zero friction.
     *
     * @param elevationAngleDegrees world-space angle from horizontal (0 = flat, 90 = vertical)
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
     * Gravity pressure contribution for a single edge.
     * For liquids (gravityDirection=+1): positive when going downhill.
     * For gases (gravityDirection=-1): positive when going uphill.
     *
     * @param fromWorldY upstream node's world Y
     * @param toWorldY downstream node's world Y
     * @param gravityDirection +1 for liquids (flow down), -1 for gases (flow up)
     * @return gravity pressure delta for this edge
     */
    public float gravityDelta(double fromWorldY, double toWorldY, float gravityDirection) {
        float heightDiff = (float) (fromWorldY - toWorldY);
        if (Math.abs(heightDiff) < config.deadZone()) return 0;
        return heightDiff * config.gravityPerBlock() * gravityDirection;
    }

    /**
     * Gravity delta assuming liquid (downward flow). Convenience for default behavior.
     */
    public float gravityDelta(double fromWorldY, double toWorldY) {
        return gravityDelta(fromWorldY, toWorldY, 1.0f);
    }

    /**
     * Determine gravity direction for a fluid.
     *
     * @param lighterThanAir true if the fluid is lighter than air (from FluidType.isLighterThanAir())
     * @return +1 for liquids (flow down), -1 for gases (flow up)
     */
    public static float gravityDirection(boolean lighterThanAir) {
        return lighterThanAir ? -1.0f : 1.0f;
    }

    /**
     * Pressure delivered through an edge from upstream to downstream.
     * Accounts for gravity direction, gravity magnitude, and friction.
     *
     * @param outgoingPressure pressure leaving the upstream node on this edge
     * @param fromWorldY upstream node's world Y
     * @param toWorldY downstream node's world Y
     * @param elevationAngleDegrees the edge's elevation angle
     * @param viscosityMultiplier fluid viscosity scaling
     * @param gravityDirection +1 for liquids, -1 for gases
     * @return pressure arriving at the downstream node, clamped to >= 0
     */
    public float edgeDeliveredPressure(float outgoingPressure, double fromWorldY, double toWorldY,
                                       float elevationAngleDegrees, float viscosityMultiplier,
                                       float gravityDirection) {
        float gravity = gravityDelta(fromWorldY, toWorldY, gravityDirection);
        float friction = segmentFriction(elevationAngleDegrees, viscosityMultiplier);
        return Math.max(0, outgoingPressure + gravity - friction);
    }

    /**
     * Edge delivered pressure assuming liquid (downward flow).
     */
    public float edgeDeliveredPressure(float outgoingPressure, double fromWorldY, double toWorldY,
                                       float elevationAngleDegrees, float viscosityMultiplier) {
        return edgeDeliveredPressure(outgoingPressure, fromWorldY, toWorldY,
                elevationAngleDegrees, viscosityMultiplier, 1.0f);
    }

    /**
     * Sum incoming pressures at a merge junction (2+ pipes into 1).
     *
     * @param incomingPressures pressures arriving from each incoming edge
     * @return total merged pressure
     */
    public float junctionMergePressure(List<Float> incomingPressures) {
        float total = 0;
        for (float p : incomingPressures) {
            total += p;
        }
        return total;
    }

    /**
     * Divide pressure evenly at a split junction (1 pipe into 2+).
     *
     * @param pressure the node's total pressure
     * @param fanOut number of outgoing edges
     * @return pressure per outgoing edge
     */
    public float junctionSplitPressure(float pressure, int fanOut) {
        if (fanOut <= 1) return pressure;
        return pressure / fanOut;
    }

    /**
     * Flow rate derived from pressure.
     *
     * @param pressure the pressure at a node
     * @return flow rate in mB/t
     */
    public float flowFromPressure(float pressure) {
        return pressure / 2f;
    }

    /**
     * Check if a pipe would burst at the given pressure.
     *
     * @param pressure the pressure at the node
     * @return true if pressure exceeds the burst threshold
     */
    public boolean wouldBurst(float pressure) {
        return config.burstingEnabled() && pressure > config.burstThreshold();
    }

    /**
     * Pump push pressure for the output side.
     *
     * @param pumpBase pump's base pressure from RPM
     * @return pressure added to the push-side pipe
     */
    public float pumpPushPressure(float pumpBase) {
        return pumpBase * config.pumpPushRatio();
    }

    /**
     * Pump pull pressure for the input side.
     *
     * @param pumpBase pump's base pressure from RPM
     * @return pressure added to the pull-side pipe
     */
    public float pumpPullPressure(float pumpBase) {
        return pumpBase * config.pumpPullRatio();
    }
}
