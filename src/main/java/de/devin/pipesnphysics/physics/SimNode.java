package de.devin.pipesnphysics.physics;

/**
 * A node in the contracted fluid network.
 *
 * @param id unique identity
 * @param kind role in the network
 * @param elevation world Y coordinate
 * @param staticPressure driving potential at this node (pump output, source head, 0 for junctions)
 * @param head head budget this node supplies (consumed by friction + gravity over distance)
 */
public record SimNode(NodeId id, SimNodeKind kind, double elevation,
                      float staticPressure, float head) {}
