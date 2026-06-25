package de.devin.pipesnphysics.engine;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A contracted pipe network.
 *
 * A Graph is the pure, Minecraft-independent shape of one connected fluid network.
 * It is built by {@link GraphBuilder#build} from the world and then consumed
 * by {@link FlowSolver} to decide where fluid moves.
 *
 * Invariants:
 *   - Node indices match their position in nodes (node.index() == nodes.indexOf(node)).
 *   - Edge endpoints (a, b) are valid node indices.
 *   - The graph is connected (single connected component).
 *   - coverage contains EVERY world position the discovery walk touched (all pipes,
 *     pumps, and handlers), including cells that did not survive contraction such as
 *     self-loops. {@link EngineTickHandler} uses it to tick each network exactly once.
 *
 * A graph is immutable. To reflect topology changes, build a new graph.
 */
public record Graph(List<Node> nodes, List<Edge> edges, Set<BlockPos> coverage) {
    public Node node(int index) { return nodes.get(index); }

    public Edge edge(int index) { return edges.get(index); }

    /** Edges incident to the given node, in no particular order. */
    public List<Edge> edgesOf(int nodeIndex) {
        List<Edge> result = new ArrayList<>();
        for (Edge e : edges) {
            if (e.a() == nodeIndex || e.b() == nodeIndex) result.add(e);
        }
        return result;
    }

    /** All HANDLER nodes (tanks, basins, drains, etc.). */
    public List<Node> handlers() {
        List<Node> result = new ArrayList<>();
        for (Node n : nodes) if (n.isHandler()) result.add(n);
        return result;
    }

    /** All PUMP nodes. */
    public List<Node> pumps() {
        List<Node> result = new ArrayList<>();
        for (Node n : nodes) if (n.isPump()) result.add(n);
        return result;
    }

    /** Find a node by world position, or null if not in this graph. */
    public Node nodeAt(BlockPos pos) {
        for (Node n : nodes) if (n.pos().equals(pos)) return n;
        return null;
    }

    public boolean isEmpty() { return nodes.isEmpty(); }
}
