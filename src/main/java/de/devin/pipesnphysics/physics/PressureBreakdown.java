package de.devin.pipesnphysics.physics;

/**
 * Diagnostic breakdown of pressure at a pipe node.
 * Shows how gravity, pumps, merges, splits, and friction combine,
 * plus live simulation state for phase-aware goggle tooltips.
 *
 * @param gravityContribution pressure gained from height differences
 * @param pumpContribution pressure added by pumps (push or pull)
 * @param mergeContribution extra pressure gained from junction merging
 * @param splitPenalty pressure lost from junction splitting
 * @param friction total friction accumulated along the path
 * @param net effective flow rate (mB/t) after all contributions and losses
 * @param capped true if pressure was clamped to maxPressure
 * @param bursting true if pressure exceeds the burst threshold
 * @param phase edge lifecycle phase (EMPTY, CHARGING, STALLED, FLOWING, DRAINING)
 * @param frontProgress charging front position as fraction 0..1 (CHARGING/DRAINING only)
 * @param deltaPhi potential difference driving this edge (Φ_A - Φ_B)
 * @param headRemaining surplus head at the downstream end of this edge
 * @param edgeLength number of pipe blocks in this edge
 * @param headAtUpstream head budget at the upstream node (entry point of the edge)
 */
public record PressureBreakdown(
        float gravityContribution,
        float pumpContribution,
        float mergeContribution,
        float splitPenalty,
        float friction,
        float net,
        boolean capped,
        boolean bursting,
        EdgePhase phase,
        float frontProgress,
        float deltaPhi,
        float headRemaining,
        int edgeLength,
        float headAtUpstream
) {}
