package de.devin.pipesnphysics.physics;

/**
 * Configuration for the fluid simulation.
 *
 * @param G gravity strength (game units per block of elevation)
 * @param EPS potential deadband — |ΔΦ| <= EPS means no flow
 * @param maxFlow max flow per edge per tick (mB)
 * @param conductance base flow per unit potential difference
 * @param burstThreshold potential at which pipes burst
 * @param frictionPerBlock friction head consumed per tile (R_PER_TILE)
 * @param perPipeCapacity fluid capacity per pipe cell (mB)
 * @param taperMargin head remaining below which flow tapers toward 0
 * @param defaultPumpHead default head a pump supplies
 * @param frontK front-advance coefficient: tiles/tick = frontK * headAtFront / viscosity
 * @param hysteresis head margin before FLOWING reverts to DRAINING (anti-flap)
 */
public record SimConfig(
        float G,
        float EPS,
        float maxFlow,
        float conductance,
        float burstThreshold,
        float frictionPerBlock,
        int perPipeCapacity,
        float taperMargin,
        float defaultPumpHead,
        float frontK,
        float hysteresis
) {}
