package de.devin.pipesnphysics.physics;

/**
 * A node in the contracted fluid network. Nodes are junctions, sources, sinks,
 * pumps, or dead ends. Plain pipe runs are contracted into edges.
 *
 * @param id unique identity
 * @param kind role in the network
 * @param elevation world Y coordinate
 * @param staticPressure driving pressure at this node (pump output, source head, 0 for junctions)
 */
public record SimNode(NodeId id, SimNodeKind kind, double elevation, float staticPressure) {}
