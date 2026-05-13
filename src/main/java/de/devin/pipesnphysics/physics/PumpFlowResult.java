package de.devin.pipesnphysics.physics;

import java.util.Map;
import java.util.Set;

/**
 * Output of {@link NetworkSolver#solvePumpReach}.
 * Describes which nodes a pump can reach and with what pressure.
 */
public record PumpFlowResult(
        Map<NodeId, Float> accumulatedFriction,
        Map<NodeId, Float> nodePressures,
        Map<NodeId, Integer> hopCounts,
        Set<NodeId> reachableNodes
) {}
