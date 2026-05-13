package de.devin.pipesnphysics.physics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Minecraft-independent representation of a connected pipe network.
 * Each node carries its kind (pipe, pump, tank, open-end) and optional flow state.
 * Built by the integration layer, consumed by {@link NetworkSolver}.
 */
public record PipeGraph(
        Map<NodeId, PipeNode> nodes,
        Map<NodeId, List<PipeEdge>> adjacency,
        List<NetworkEndpoint> endpoints
) {
    /** True if the network contains an active (powered) pump. */
    public boolean hasActivePump() {
        return nodes.values().stream().anyMatch(PipeNode::isPump);
    }

    /** All pipe-kind nodes in the network. */
    public Set<NodeId> pipeNodeIds() {
        return nodes.entrySet().stream()
                .filter(e -> e.getValue().isPipe())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** Get a node by its ID, or null if not in the graph. */
    public PipeNode node(NodeId id) {
        return nodes.get(id);
    }

    /** Check if a node exists in this graph. */
    public boolean contains(NodeId id) {
        return nodes.containsKey(id);
    }
}
