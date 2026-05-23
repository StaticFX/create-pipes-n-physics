package de.devin.pipesnphysics.physics;

/**
 * Diagnostic breakdown of pressure at a pipe node.
 * Shows how gravity, pumps, merges, splits, and friction combine.
 *
 * @param gravityContribution pressure gained from height differences
 * @param pumpContribution pressure added by pumps (push or pull)
 * @param mergeContribution extra pressure gained from junction merging
 * @param splitPenalty pressure lost from junction splitting
 * @param friction total friction accumulated along the path
 * @param net effective pressure after all contributions and losses
 * @param capped true if pressure was clamped to maxPressure
 * @param bursting true if pressure exceeds the burst threshold
 */
public record PressureBreakdown(
        float gravityContribution,
        float pumpContribution,
        float mergeContribution,
        float splitPenalty,
        float friction,
        float net,
        boolean capped,
        boolean bursting
) {}
