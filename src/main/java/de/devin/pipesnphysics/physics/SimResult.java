package de.devin.pipesnphysics.physics;

import java.util.List;
import java.util.Map;

/**
 * Per-tick output of the fluid simulator.
 *
 * @param flowRates signed flow rate per edge (+ means a→b)
 * @param bursts pipes that exceeded burst threshold
 * @param collisions fluid collision events (incompatible fluids meeting)
 * @param potentials potential at each node (for goggle display)
 */
public record SimResult(
        float[] flowRates,
        List<BurstEvent> bursts,
        List<CollisionEvent> collisions,
        Map<NodeId, Float> potentials
) {}
