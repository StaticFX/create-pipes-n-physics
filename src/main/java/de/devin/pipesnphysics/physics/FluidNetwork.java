package de.devin.pipesnphysics.physics;

import java.util.*;

/**
 * Mutable state of one connected fluid network.
 * Contains the contracted graph (nodes + edges) and per-tick computed values.
 */
public class FluidNetwork {

    private final Map<NodeId, SimNode> nodes;
    private final List<SimEdge> edges;
    private final Map<NodeId, List<Integer>> incidentEdges;
    private boolean dirty;

    private float[] flowRate;
    private float[] potential;

    public FluidNetwork(Map<NodeId, SimNode> nodes, List<SimEdge> edges) {
        this.nodes = nodes;
        this.edges = edges;
        this.incidentEdges = new HashMap<>();
        this.dirty = true;

        for (int i = 0; i < edges.size(); i++) {
            SimEdge e = edges.get(i);
            incidentEdges.computeIfAbsent(e.a(), k -> new ArrayList<>()).add(i);
            incidentEdges.computeIfAbsent(e.b(), k -> new ArrayList<>()).add(i);
        }

        this.flowRate = new float[edges.size()];
        this.potential = new float[nodes.size()];
    }

    public Map<NodeId, SimNode> nodes() { return nodes; }
    public List<SimEdge> edges() { return edges; }
    public List<Integer> edgesAt(NodeId node) { return incidentEdges.getOrDefault(node, List.of()); }
    public SimNode node(NodeId id) { return nodes.get(id); }

    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void clearDirty() { dirty = false; }

    public float[] flowRates() { return flowRate; }
    public float flowRate(int edgeIndex) { return flowRate[edgeIndex]; }
    public void setFlowRate(int edgeIndex, float rate) { flowRate[edgeIndex] = rate; }

    public float potential(NodeId node) {
        int idx = 0;
        for (NodeId id : nodes.keySet()) {
            if (id.equals(node)) return potential[idx];
            idx++;
        }
        return 0;
    }

    public void setPotential(NodeId node, float value) {
        int idx = 0;
        for (NodeId id : nodes.keySet()) {
            if (id.equals(node)) { potential[idx] = value; return; }
            idx++;
        }
    }
}
