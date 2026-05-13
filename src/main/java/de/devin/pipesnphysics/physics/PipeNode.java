package de.devin.pipesnphysics.physics;

/**
 * A node in a pipe network graph, carrying its identity, role, physical
 * properties, and live flow state.
 *
 * @param id     opaque identity (backed by BlockPos at integration layer)
 * @param kind   the node's role in the network
 * @param worldY pre-resolved world-space Y coordinate
 * @param flow   current flow state, or {@code null} if no active flow
 */
public record PipeNode(NodeId id, NodeKind kind, double worldY, FlowState flow) {

    /** Create a node with no active flow. */
    public PipeNode(NodeId id, NodeKind kind, double worldY) {
        this(id, kind, worldY, null);
    }

    /** Return a copy with updated flow state. */
    public PipeNode withFlow(FlowState flow) {
        return new PipeNode(id, kind, worldY, flow);
    }

    public boolean isPipe() { return kind == NodeKind.PIPE; }
    public boolean isPump() { return kind == NodeKind.PUMP; }
    public boolean isTank() { return kind == NodeKind.TANK; }
}
