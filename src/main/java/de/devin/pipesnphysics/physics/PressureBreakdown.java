package de.devin.pipesnphysics.physics;

/**
 * Diagnostic breakdown of pressure for a gravity-driven network.
 * Computed at the sink that determines the actual flow rate.
 *
 * @param head     total head pressure from source-to-sink height difference
 * @param friction total accumulated friction along the path to the sink
 * @param net      effective pressure (head - friction, clamped to [0, maxPressure])
 * @param capped   true if net was clamped by maxPressure
 */
public record PressureBreakdown(
        float head,
        float friction,
        float net,
        boolean capped
) {}
