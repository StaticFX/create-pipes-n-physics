package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Builds a contracted {@link Graph} from a Create pipe network in the world.
 *
 * Algorithm:
 *   1. BFS over Create pipe blocks starting at startPos. Pumps are pipes too, so
 *      they are discovered automatically and recorded as PUMP candidates.
 *   2. While walking, record adjacent blocks that expose IFluidHandler as HANDLER
 *      candidates (tanks, basins, drains, portable interfaces, etc.).
 *   3. Classify each pipe cell:
 *        - exactly 2 pipe/handler connections → pass-through, becomes part of an edge,
 *        - anything else → JUNCTION node.
 *   4. Walk each junction's outgoing connections along pass-through pipes until
 *      another node is reached. Each such walk is contracted into a single Edge.
 *   5. Pumps split their pipe run into two edges (one per side); the pump itself
 *      is a node so flow naturally has to pass through it.
 *
 * The resulting Graph is connected, immutable, and Minecraft-independent except
 * for its BlockPos references and the world-Y coordinates baked in at construction.
 */
public final class GraphBuilder {

    private GraphBuilder() {}

    /**
     * Build a graph containing the network reachable from startPos.
     *
     * If startPos is not a pipe, pump, or handler, BFS extends one block outward
     * to try to find one. Returns an empty graph if no network is found.
     */
    public static Graph build(Level level, BlockPos startPos) {
        BlockPos seed = findSeed(level, startPos);
        if (seed == null) return new Graph(List.of(), List.of(), Set.of());

        // Step 1+2: discover all pipe cells and adjacent handler positions.
        Discovery d = discover(level, seed);
        if (d.pipes.isEmpty() && d.pumps.isEmpty()) return new Graph(List.of(), List.of(), Set.of());

        // Step 3: identify which pipe cells are nodes (not 2-connection pass-throughs).
        Set<BlockPos> nodePositions = new LinkedHashSet<>();
        nodePositions.addAll(d.pumps);
        nodePositions.addAll(d.handlers);
        nodePositions.addAll(d.openEnds.keySet());
        for (BlockPos pipe : d.pipes) {
            int conns = d.connections.getOrDefault(pipe, List.of()).size();
            if (conns != 2) nodePositions.add(pipe);
        }
        // If no junctions/handlers/pumps exist, treat the start as the single node.
        if (nodePositions.isEmpty()) nodePositions.add(d.pipes.iterator().next());

        // Build Node list with stable indices.
        List<Node> nodes = new ArrayList<>();
        Map<BlockPos, Integer> indexOf = new HashMap<>();
        for (BlockPos pos : nodePositions) {
            Node.Kind kind;
            Direction facing = null;
            Direction openFace = null;
            if (d.pumps.contains(pos)) {
                kind = Node.Kind.PUMP;
                BlockState bs = level.getBlockState(pos);
                if (bs.getBlock() instanceof PumpBlock) {
                    facing = bs.getValue(PumpBlock.FACING);
                }
            } else if (d.handlers.contains(pos)) {
                kind = Node.Kind.HANDLER;
            } else if (d.openEnds.containsKey(pos)) {
                kind = Node.Kind.OPEN_END;
                openFace = d.openEnds.get(pos);
            } else {
                kind = Node.Kind.JUNCTION;
            }
            int idx = nodes.size();
            nodes.add(new Node(idx, pos, kind, SableCompat.getWorldY(level, pos), facing, openFace));
            indexOf.put(pos, idx);
        }

        // Step 4: contract pipe runs between nodes into edges.
        List<Edge> edges = new ArrayList<>();
        Set<String> seenEdgeKeys = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos nodePos : nodePositions) {
            int fromIdx = indexOf.get(nodePos);
            for (BlockPos neighbor : d.connections.getOrDefault(nodePos, List.of())) {
                // Direct node-to-node: pipe between two adjacent nodes, or a node touching another node.
                if (nodePositions.contains(neighbor)) {
                    int toIdx = indexOf.get(neighbor);
                    addEdgeIfNew(edges, seenEdgeKeys, fromIdx, toIdx, List.of());
                    continue;
                }
                // Walk the pipe run starting at neighbor until we hit another node.
                if (!d.pipes.contains(neighbor) || visited.contains(neighbor)) continue;

                List<BlockPos> path = new ArrayList<>();
                BlockPos prev = nodePos;
                BlockPos cur = neighbor;
                while (cur != null && !nodePositions.contains(cur)) {
                    path.add(cur);
                    visited.add(cur);
                    BlockPos next = null;
                    for (BlockPos c : d.connections.getOrDefault(cur, List.of())) {
                        if (!c.equals(prev)) { next = c; break; }
                    }
                    prev = cur;
                    cur = next;
                }
                if (cur == null) continue; // walk ran off the end (shouldn't happen on a closed graph)
                int toIdx = indexOf.get(cur);
                addEdgeIfNew(edges, seenEdgeKeys, fromIdx, toIdx, path);
            }
        }

        Set<BlockPos> coverage = new HashSet<>();
        coverage.addAll(d.pipes);
        coverage.addAll(d.pumps);
        coverage.addAll(d.handlers);
        coverage.addAll(d.openEnds.keySet());
        return new Graph(List.copyOf(nodes), List.copyOf(edges), Set.copyOf(coverage));
    }

    /** If startPos isn't a pipe, look one block in each direction for one. Returns null if none found. */
    public static BlockPos findSeed(Level level, BlockPos startPos) {
        if (isPipeLike(level, startPos)) return startPos;
        for (Direction d : Direction.values()) {
            BlockPos adj = startPos.relative(d);
            if (isPipeLike(level, adj)) return adj;
        }
        return null;
    }

    private static boolean isPipeLike(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        return FluidPropagator.getPipe(level, pos) != null;
    }

    /** BFS state. */
    private static final class Discovery {
        final Set<BlockPos> pipes = new LinkedHashSet<>();   // pure pipe cells (incl. straight bits that pumps share via FluidTransportBehaviour are excluded — pumps are tracked separately)
        final Set<BlockPos> pumps = new LinkedHashSet<>();   // pump positions
        final Set<BlockPos> handlers = new LinkedHashSet<>(); // adjacent IFluidHandler positions
        final Map<BlockPos, Direction> openEnds = new LinkedHashMap<>(); // space pos -> face back toward its pipe
        final Map<BlockPos, List<BlockPos>> connections = new HashMap<>();
    }

    private static Discovery discover(Level level, BlockPos start) {
        Discovery d = new Discovery();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        frontier.add(start.immutable());

        while (!frontier.isEmpty()) {
            BlockPos cur = frontier.poll();
            if (!visited.add(cur)) continue;
            if (!level.isLoaded(cur)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cur);
            if (pipe == null) continue;

            BlockState curState = level.getBlockState(cur);
            boolean isPump = curState.getBlock() instanceof PumpBlock;
            if (isPump) d.pumps.add(cur);
            else d.pipes.add(cur);

            List<BlockPos> conns = new ArrayList<>();
            for (Direction face : FluidPropagator.getPipeConnections(curState, pipe)) {
                BlockPos neighbor = cur.relative(face);
                if (!level.isLoaded(neighbor)) continue;
                BlockState nState = level.getBlockState(neighbor);

                if (nState.getBlock() instanceof PumpBlock) {
                    d.pumps.add(neighbor.immutable());
                    conns.add(neighbor.immutable());
                    frontier.add(neighbor.immutable());
                    continue;
                }

                var handler = level.getCapability(
                        Capabilities.FluidHandler.BLOCK, neighbor, face.getOpposite());
                if (handler != null) {
                    d.handlers.add(neighbor.immutable());
                    conns.add(neighbor.immutable());
                    continue;
                }

                if (FluidPropagator.getPipe(level, neighbor) != null) {
                    conns.add(neighbor.immutable());
                    frontier.add(neighbor.immutable());
                    continue;
                }

                // An open pipe end facing air, a fluid, or a vanilla fluid target
                // (cauldron etc.) becomes an OPEN_END boundary node at the space block.
                if (FluidPropagator.isOpenEnd(level, cur, face)) {
                    d.openEnds.putIfAbsent(neighbor.immutable(), face.getOpposite());
                    conns.add(neighbor.immutable());
                }
            }
            d.connections.put(cur, conns);
        }

        // Ensure each boundary block is recorded with its connection back to the
        // pipes that found it. (Reverse links so the contraction walk can traverse
        // both directions.)
        Set<BlockPos> boundaries = new LinkedHashSet<>(d.handlers);
        boundaries.addAll(d.openEnds.keySet());
        for (BlockPos boundary : boundaries) {
            List<BlockPos> back = new ArrayList<>();
            for (var entry : d.connections.entrySet()) {
                if (entry.getValue().contains(boundary)) back.add(entry.getKey());
            }
            d.connections.put(boundary, back);
        }

        return d;
    }

    /**
     * Add an edge unless this exact run was already added from its other end. The
     * key includes the run's terminal pipe cells so PARALLEL runs between the same
     * two nodes stay distinct edges (the same physical run walked from either end
     * yields the same key; a different run between the same nodes does not).
     */
    private static void addEdgeIfNew(List<Edge> edges, Set<String> seen, int a, int b, List<BlockPos> pipes) {
        if (a == b) return;
        String nodeKey = a < b ? a + ":" + b : b + ":" + a;
        String pathKey;
        if (pipes.isEmpty()) {
            pathKey = "direct";
        } else {
            long first = pipes.get(0).asLong();
            long last = pipes.get(pipes.size() - 1).asLong();
            pathKey = Math.min(first, last) + "_" + Math.max(first, last);
        }
        if (!seen.add(nodeKey + "|" + pathKey)) return;
        edges.add(new Edge(edges.size(), a, b, List.copyOf(pipes)));
    }
}
