package de.devin.pipesnphysics.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Small geometry helpers over the contracted graph, shared so the copies cannot drift apart. */
public final class PipeGeometry {
    private PipeGeometry() {}

    /** The face pointing from {@code from} toward the adjacent {@code to}, or null if not adjacent. */
    public static Direction between(BlockPos from, BlockPos to) {
        return Direction.fromDelta(
                to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    /** The cell (first pipe, or the opposite node) an edge touches at the given node — its first step out. */
    public static BlockPos adjacentCell(Graph graph, Edge edge, int nodeIndex) {
        if (edge.pipes().isEmpty()) return graph.node(edge.other(nodeIndex)).pos();
        return nodeIndex == edge.a()
                ? edge.pipes().get(0)
                : edge.pipes().get(edge.pipes().size() - 1);
    }
}
