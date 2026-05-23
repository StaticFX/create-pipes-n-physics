package de.devin.pipesnphysics.physics;

/**
 * Configuration for the fluid simulation.
 *
 * @param G gravity strength (game units per block of elevation)
 * @param EPS potential deadband — |ΔΦ| <= EPS means no flow
 * @param maxFlow max flow per edge per tick (mB)
 * @param conductance flow per unit potential difference
 * @param burstThreshold potential at which pipes burst
 * @param frictionPerBlock friction resistance per pipe cell in a branch
 * @param perPipeCapacity fluid capacity per pipe cell (mB)
 */
public record SimConfig(
        float G,
        float EPS,
        float maxFlow,
        float conductance,
        float burstThreshold,
        float frictionPerBlock,
        int perPipeCapacity
) {}
