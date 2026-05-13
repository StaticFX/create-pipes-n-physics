package de.devin.pipesnphysics.physics;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Output of {@link NetworkSolver#solveGravityFlow(PipeGraph)}.
 * Contains pressure maps, flow topology, and computed flow states
 * for the integration layer to apply to Create's transport system.
 */
public record GravityFlowResult(
        NetworkEndpoint source,
        double sourceWorldY,
        List<NetworkEndpoint> validSinks,
        Map<NodeId, Float> pipePressures,
        Map<NodeId, NodeId> flowParent,
        Map<NodeId, Integer> inboundFaceIndex,
        Map<NodeId, Set<Integer>> outboundFaceIndices,
        Set<NodeId> activePipes,
        List<NetworkEndpoint> sinkEndpoints,
        Map<NodeId, FlowState> flowStates
) {}
