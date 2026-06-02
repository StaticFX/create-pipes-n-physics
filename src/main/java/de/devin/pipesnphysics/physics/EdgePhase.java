package de.devin.pipesnphysics.physics;

/**
 * Lifecycle phase of an edge in the fluid network.
 */
public enum EdgePhase {
    EMPTY,
    CHARGING,
    STALLED,
    FLOWING,
    DRAINING;

    /** True if the edge has fluid in it (front advancing, stalled, or steady flow). */
    public boolean hasFront() {
        return this == CHARGING || this == STALLED || this == DRAINING;
    }
}
