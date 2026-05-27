package de.devin.pipesnphysics.physics;

/**
 * Configuration for the fluid simulation.
 * All head values are in "blocks of water column" (ρ=1, G=1 defines the unit).
 *
 * @param G              gravity strength — 1.0 means head = blocks of elevation
 * @param EPS            potential deadband — |ΔΦ| <= EPS means no flow
 * @param maxFlow        max flow per edge per tick (mB) — the pipe bore limit
 * @param conductance    gravity/equalization flow per unit ΔΦ (mB/t per head)
 * @param burstThreshold flow rate at which pipes burst (mB/t)
 * @param frictionPerBlock friction head consumed per tile of pipe
 * @param perPipeCapacity  visual capacity per pipe cell (mB) — used for bore display only
 * @param taperMargin    head remaining below which flow tapers toward 0
 * @param defaultPumpHead  fallback head for pumps without kinetic data
 * @param frontK         front-advance coefficient: tiles/tick = frontK * head / viscosity
 * @param hysteresis     head deficit before FLOWING reverts to DRAINING
 * @param speedToHead    RPM → head conversion: head = RPM * speedToHead (blocks of water per RPM)
 * @param flowPerRPM     pump throughput: mB/t = RPM * flowPerRPM (capped at maxFlow)
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
        float hysteresis,
        float speedToHead,
        float flowPerRPM
) {}
