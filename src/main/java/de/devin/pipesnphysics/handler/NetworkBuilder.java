package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.physics.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.*;

/**
 * Builds a contracted FluidNetwork from the Minecraft world.
 * Pipe runs between nodes are contracted into single edges.
 * Nodes are junctions, pumps, tanks, open ends, and dead ends.
 */
public final class NetworkBuilder {

    private NetworkBuilder() {}

    /**
     * Discover and contract the pipe network starting from startPos.
     * Expands across pump boundaries automatically.
     */
    public static FluidNetwork build(Level level, BlockPos startPos, SimConfig config) {
        // Phase 1: BFS to find all pipe positions and their connections
        Map<BlockPos, List<BlockPos>> pipeConnections = new HashMap<>();
        Map<BlockPos, BlockPos> pumpPositions = new LinkedHashMap<>();
        Map<BlockPos, EndpointData> endpoints = new LinkedHashMap<>();
        Set<BlockPos> allPipes = new LinkedHashSet<>();

        discoverRawNetwork(level, startPos, allPipes, pipeConnections, pumpPositions, endpoints);

        // Expand across pumps — iterate until no new pumps are discovered
        Set<BlockPos> expandedPumps = new HashSet<>();
        boolean foundNew = true;
        while (foundNew) {
            foundNew = false;
            for (BlockPos pumpPos : new ArrayList<>(pumpPositions.keySet())) {
                if (!expandedPumps.add(pumpPos)) continue;
                for (Direction side : Direction.values()) {
                    BlockPos otherSide = pumpPos.relative(side);
                    if (allPipes.contains(otherSide)) continue;
                    if (FluidPropagator.getPipe(level, otherSide) == null) continue;
                    int pumpsBefore = pumpPositions.size();
                    discoverRawNetwork(level, otherSide, allPipes, pipeConnections, pumpPositions, endpoints);
                    if (pumpPositions.size() > pumpsBefore) foundNew = true;
                }
            }
        }

        // Phase 2: Identify nodes (anything that isn't a simple pass-through)
        Set<BlockPos> nodePositions = new HashSet<>();
        for (BlockPos pipe : allPipes) {
            List<BlockPos> conns = pipeConnections.getOrDefault(pipe, List.of());
            if (conns.size() != 2) {
                nodePositions.add(pipe);
            }
        }
        // Pumps and endpoint-adjacent pipes are also nodes
        for (BlockPos pump : pumpPositions.keySet()) {
            // Pipes adjacent to pumps are nodes
            for (Direction side : Direction.values()) {
                BlockPos adj = pump.relative(side);
                if (allPipes.contains(adj)) nodePositions.add(adj);
            }
        }
        for (var ep : endpoints.values()) {
            nodePositions.add(ep.pipePos);
        }
        // If no nodes found, pick any pipe
        if (nodePositions.isEmpty() && !allPipes.isEmpty()) {
            nodePositions.add(allPipes.iterator().next());
        }

        // Phase 3: Build SimNodes
        Map<NodeId, SimNode> nodes = new LinkedHashMap<>();
        for (BlockPos pos : nodePositions) {
            NodeId id = PipeGraphBuilder.nodeOf(pos);
            double elevation = SableCompat.getWorldY(level, pos);
            SimNodeKind kind = classifyNode(pos, pipeConnections, pumpPositions, endpoints);
            float staticPressure = computeStaticPressure(level, pos, pumpPositions, endpoints, kind);
            float head = computeHead(level, pos, pumpPositions, endpoints, kind, config);
            nodes.put(id, new SimNode(id, kind, elevation, staticPressure, head));
        }

        // Debug: log raw data
        org.apache.logging.log4j.LogManager.getLogger().debug(
                "NetworkBuilder: pipes={} nodes={} conns={} endpoints={}",
                allPipes.size(), nodePositions.size(),
                pipeConnections.entrySet().stream()
                        .map(e -> e.getKey().toShortString() + "→" + e.getValue().stream()
                                .map(BlockPos::toShortString).toList())
                        .toList(),
                endpoints.size());

        // Phase 4: Contract pipe runs into edges
        List<SimEdge> edges = new ArrayList<>();
        Set<BlockPos> visitedPipes = new HashSet<>();
        int edgeId = 0;

        for (BlockPos nodePos : nodePositions) {
            List<BlockPos> conns = pipeConnections.getOrDefault(nodePos, List.of());
            for (BlockPos neighbor : conns) {
                if (nodePositions.contains(neighbor) && !visitedPipes.contains(neighbor)) {
                    // Direct node-to-node edge (single pipe between two nodes)
                    List<BlockPos> path = List.of(nodePos, neighbor);
                    float friction = config.frictionPerBlock();
                    edges.add(new SimEdge(edgeId++,
                            PipeGraphBuilder.nodeOf(nodePos),
                            PipeGraphBuilder.nodeOf(neighbor),
                            1, config.perPipeCapacity(),
                            friction, path));
                    continue;
                }
                if (visitedPipes.contains(neighbor)) continue;
                if (!allPipes.contains(neighbor)) continue;

                // Walk along the pipe run until we hit another node
                List<BlockPos> path = new ArrayList<>();
                path.add(nodePos);
                BlockPos current = neighbor;
                BlockPos prev = nodePos;

                while (current != null && !nodePositions.contains(current)) {
                    path.add(current);
                    visitedPipes.add(current);
                    List<BlockPos> nextConns = pipeConnections.getOrDefault(current, List.of());
                    BlockPos next = null;
                    for (BlockPos c : nextConns) {
                        if (!c.equals(prev) && allPipes.contains(c)) {
                            next = c;
                            break;
                        }
                    }
                    prev = current;
                    current = next;
                }

                if (current != null && nodePositions.contains(current)) {
                    path.add(current);
                    int length = path.size() - 2; // exclude the two endpoint nodes
                    length = Math.max(1, length);
                    float friction = config.frictionPerBlock() * length;
                    int capacity = config.perPipeCapacity() * length;
                    edges.add(new SimEdge(edgeId++,
                            PipeGraphBuilder.nodeOf(nodePos),
                            PipeGraphBuilder.nodeOf(current),
                            length, capacity, friction,
                            path.subList(1, path.size() - 1))); // pipe positions (excluding nodes)
                }
            }
        }

        // Create edges through pumps: connect pipe nodes on opposite sides
        for (BlockPos pumpPos : pumpPositions.keySet()) {
            List<BlockPos> pumpAdjacentNodes = new ArrayList<>();
            for (Direction side : Direction.values()) {
                BlockPos adj = pumpPos.relative(side);
                if (nodePositions.contains(adj)) {
                    pumpAdjacentNodes.add(adj);
                }
            }
            // Create edges between all pairs of pump-adjacent nodes
            for (int i = 0; i < pumpAdjacentNodes.size(); i++) {
                for (int j = i + 1; j < pumpAdjacentNodes.size(); j++) {
                    BlockPos posA = pumpAdjacentNodes.get(i);
                    BlockPos posB = pumpAdjacentNodes.get(j);
                    // Only if not already connected via pipe connections
                    List<BlockPos> connsA = pipeConnections.getOrDefault(posA, List.of());
                    if (connsA.contains(posB)) continue;
                    edges.add(new SimEdge(edgeId++,
                            PipeGraphBuilder.nodeOf(posA),
                            PipeGraphBuilder.nodeOf(posB),
                            1, config.perPipeCapacity(),
                            config.frictionPerBlock(),
                            List.of())); // no intermediate pipe positions
                }
            }
        }

        // Deduplicate edges (a→b and b→a are the same edge)
        List<SimEdge> dedupedEdges = new ArrayList<>();
        Set<String> seenEdges = new HashSet<>();
        for (SimEdge edge : edges) {
            String key1 = edge.a() + "-" + edge.b();
            String key2 = edge.b() + "-" + edge.a();
            if (!seenEdges.contains(key1) && !seenEdges.contains(key2)) {
                dedupedEdges.add(edge);
                seenEdges.add(key1);
            }
        }

        org.apache.logging.log4j.LogManager.getLogger().debug(
                "NetworkBuilder: raw_edges={} deduped_edges={}", edges.size(), dedupedEdges.size());
        return new FluidNetwork(nodes, dedupedEdges);
    }

    private static void discoverRawNetwork(Level level, BlockPos startPos,
                                            Set<BlockPos> allPipes,
                                            Map<BlockPos, List<BlockPos>> pipeConnections,
                                            Map<BlockPos, BlockPos> pumpPositions,
                                            Map<BlockPos, EndpointData> endpoints) {
        Queue<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(startPos);

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            if (allPipes.contains(current)) continue;
            if (!level.isLoaded(current)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) continue;

            allPipes.add(current);
            List<BlockPos> connections = new ArrayList<>();

            BlockState currentState = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos neighbor = current.relative(face);
                if (!level.isLoaded(neighbor)) continue;

                BlockState neighborState = level.getBlockState(neighbor);

                if (neighborState.getBlock() instanceof PumpBlock) {
                    BlockEntity be = level.getBlockEntity(neighbor);
                    if (be instanceof KineticBlockEntity kbe && kbe.getSpeed() != 0) {
                        pumpPositions.put(neighbor.immutable(), current.immutable());
                    }
                    continue;
                }

                var handler = level.getCapability(
                        Capabilities.FluidHandler.BLOCK, neighbor, face.getOpposite());
                if (handler != null) {
                    endpoints.put(neighbor.immutable(),
                            new EndpointData(current.immutable(), neighbor.immutable(), face));
                    continue;
                }

                if (FluidPropagator.getPipe(level, neighbor) != null) {
                    connections.add(neighbor.immutable());
                    frontier.add(neighbor);
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, current, face)) {
                    endpoints.put(neighbor.immutable(),
                            new EndpointData(current.immutable(), neighbor.immutable(), face));
                }
            }

            pipeConnections.put(current.immutable(), connections);
        }
    }

    private static SimNodeKind classifyNode(BlockPos pos,
                                             Map<BlockPos, List<BlockPos>> pipeConnections,
                                             Map<BlockPos, BlockPos> pumpPositions,
                                             Map<BlockPos, EndpointData> endpoints) {
        // Check if adjacent to an endpoint — all endpoints are potential source/sinks.
        // Direction is determined by Φ, not by static classification.
        // Mark as SOURCE so fluid gets seeded into adjacent edges.
        // Endpoint takes priority over pump adjacency.
        for (var ep : endpoints.values()) {
            if (ep.pipePos.equals(pos)) {
                return SimNodeKind.SOURCE;
            }
        }

        // Check if adjacent to a pump (only if not also an endpoint)
        for (BlockPos pumpPos : pumpPositions.keySet()) {
            if (pumpPos.distManhattan(pos) == 1) {
                return SimNodeKind.PUMP;
            }
        }

        List<BlockPos> conns = pipeConnections.getOrDefault(pos, List.of());
        if (conns.size() == 0) return SimNodeKind.DEAD_END;
        if (conns.size() == 1) return SimNodeKind.DEAD_END;
        return SimNodeKind.JUNCTION;
    }

    private static float computeStaticPressure(Level level, BlockPos pos,
                                                Map<BlockPos, BlockPos> pumpPositions,
                                                Map<BlockPos, EndpointData> endpoints,
                                                SimNodeKind kind) {
        // Pumps contribute their speed as static pressure
        if (kind == SimNodeKind.PUMP) {
            for (var entry : pumpPositions.entrySet()) {
                BlockPos pumpPos = entry.getKey();
                if (pumpPos.distManhattan(pos) != 1) continue;
                BlockEntity be = level.getBlockEntity(pumpPos);
                if (be instanceof KineticBlockEntity kbe) {
                    BlockState pumpState = level.getBlockState(pumpPos);
                    if (pumpState.getBlock() instanceof PumpBlock) {
                        Direction facing = pumpState.getValue(PumpBlock.FACING);
                        BlockPos pushSide = pumpPos.relative(facing);
                        // Only the push side gets positive pressure
                        if (pushSide.equals(pos)) {
                            return Math.abs(kbe.getSpeed());
                        }
                    }
                }
            }
        }

        // Sources get pressure from the height difference to their handler
        if (kind == SimNodeKind.SOURCE) {
            for (var ep : endpoints.values()) {
                if (ep.pipePos.equals(pos)) {
                    // Static pressure from tank height above pipe
                    // (the gravity term handles the actual direction)
                    return 0; // gravity is handled by Φ, not staticPressure for sources
                }
            }
        }

        return 0;
    }

    private static float computeHead(Level level, BlockPos pos,
                                       Map<BlockPos, BlockPos> pumpPositions,
                                       Map<BlockPos, EndpointData> endpoints,
                                       SimNodeKind kind, SimConfig config) {
        // Pumps supply head = speed
        if (kind == SimNodeKind.PUMP) {
            for (var entry : pumpPositions.entrySet()) {
                BlockPos pumpPos = entry.getKey();
                if (pumpPos.distManhattan(pos) != 1) continue;
                BlockEntity be = level.getBlockEntity(pumpPos);
                if (be instanceof KineticBlockEntity kbe) {
                    return Math.abs(kbe.getSpeed());
                }
            }
            return config.defaultPumpHead();
        }

        // Sources supply head from the height of the fluid above the pipe
        if (kind == SimNodeKind.SOURCE) {
            for (var ep : endpoints.values()) {
                if (!ep.pipePos.equals(pos)) continue;
                double handlerY = SableCompat.getWorldY(level, ep.handlerPos);
                double pipeY = SableCompat.getWorldY(level, pos);
                float heightDiff = (float) Math.abs(handlerY - pipeY);
                // Head from gravity: ρ·g·Δy (density assumed 1 for head budget)
                return heightDiff * config.G();
            }
        }

        return 0;
    }

    record EndpointData(BlockPos pipePos, BlockPos handlerPos, Direction face) {}

    // Keep static helpers for compatibility
    public static NodeId nodeOf(BlockPos pos) {
        return PipeGraphBuilder.nodeOf(pos);
    }

    public static BlockPos posOf(NodeId node) {
        return PipeGraphBuilder.posOf(node);
    }
}
