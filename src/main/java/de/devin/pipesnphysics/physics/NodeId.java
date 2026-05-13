package de.devin.pipesnphysics.physics;

/**
 * Opaque identity for a node in a pipe network graph.
 * Backed by {@code BlockPos} at the integration layer, but the physics
 * package never sees the Minecraft type.
 */
public record NodeId(Object key) {}
