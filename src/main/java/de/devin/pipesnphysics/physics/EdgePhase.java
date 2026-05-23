package de.devin.pipesnphysics.physics;

/**
 * Lifecycle phase of an edge in the fluid network.
 */
public enum EdgePhase {
    EMPTY,
    CHARGING,
    STALLED,
    FLOWING,
    DRAINING
}
