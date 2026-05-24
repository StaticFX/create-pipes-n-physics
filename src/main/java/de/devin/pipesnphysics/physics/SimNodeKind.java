package de.devin.pipesnphysics.physics;

/**
 * Role of a node in the contracted fluid network.
 * SOURCE covers any fluid handler endpoint (tanks, basins, drains, etc.)
 * — they act as source or sink depending on flow direction.
 */
public enum SimNodeKind {
    JUNCTION,
    SOURCE,
    PUMP,
    DEAD_END
}
