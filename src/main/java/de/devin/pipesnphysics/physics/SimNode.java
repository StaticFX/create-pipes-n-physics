package de.devin.pipesnphysics.physics;

import net.minecraft.core.BlockPos;

/**
 * A node in the contracted fluid network.
 *
 * @param id unique identity
 * @param kind role in the network
 * @param elevation world Y coordinate
 * @param staticPressure driving potential at this node (pump output, source head, 0 for junctions)
 * @param head head budget this node supplies (consumed by friction + gravity over distance)
 * @param pushSidePos for PUMP nodes: the BlockPos of the push-side neighbor (the block the
 *                    pump faces toward). Used for asymmetric Φ: staticPressure only applies
 *                    on edges toward this side. null for non-pumps.
 */
public record SimNode(NodeId id, SimNodeKind kind, double elevation,
                      float staticPressure, float head, BlockPos pushSidePos) {

    /** Convenience constructor for non-pump nodes (pushSidePos = null). */
    public SimNode(NodeId id, SimNodeKind kind, double elevation,
                   float staticPressure, float head) {
        this(id, kind, elevation, staticPressure, head, null);
    }
}
