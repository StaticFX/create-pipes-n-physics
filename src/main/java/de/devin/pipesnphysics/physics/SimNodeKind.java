package de.devin.pipesnphysics.physics;

/**
 * Role of a node in the contracted fluid network.
 */
public enum SimNodeKind {
    JUNCTION,
    SOURCE,
    SINK,
    PUMP,
    TANK,
    DEAD_END
}
