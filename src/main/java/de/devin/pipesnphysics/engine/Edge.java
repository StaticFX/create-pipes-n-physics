package de.devin.pipesnphysics.engine;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * A contracted run of pipe cells connecting two {@link Node}s.
 *
 * Edges are undirected — they record the unordered pair (a, b). Flow direction
 * is decided each tick by {@link FlowSolver} and reported separately via
 * {@link EdgeFlow}.
 *
 * The pipes list is ordered: the first element is adjacent to node a, the last
 * is adjacent to node b. The list may be empty when two nodes are directly adjacent
 * (e.g. a tank touching a pump with no pipe between them).
 */
public record Edge(int index, int a, int b, List<BlockPos> pipes) {
    /** Number of pipe cells in this edge. */
    public int length() { return pipes.size(); }

    /** The node index at the opposite end of this edge from the given node index. */
    public int other(int nodeIndex) {
        if (nodeIndex == a) return b;
        if (nodeIndex == b) return a;
        throw new IllegalArgumentException("Node " + nodeIndex + " is not an endpoint of edge " + index);
    }
}
