package de.devin.pipesnphysics.physics;

/**
 * Diagnostic breakdown of pump pressure at a specific pipe.
 * Shows how RPM, gravity, and friction combine to determine flow.
 *
 * @param pumpBase      base pressure from RPM
 * @param gravityAssist positive = downhill from pump, negative = uphill
 * @param friction      accumulated friction along the path
 * @param net           effective pressure (pumpBase + gravityAssist - friction, clamped ≥ 0)
 */
public record PumpPressureBreakdown(
        float pumpBase,
        float gravityAssist,
        float friction,
        float net
) {}
