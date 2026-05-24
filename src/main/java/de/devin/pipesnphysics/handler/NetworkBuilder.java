package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
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

        // Discover endpoints adjacent to pumps (tank directly next to a pump with no
        // pipe in between). The BFS skips pump neighbors with `continue`, so a tank
        // directly touching a pump is never found by the pipe BFS.
        for (BlockPos pumpPos : pumpPositions.keySet()) {
            for (Direction side : Direction.values()) {
                BlockPos neighbor = pumpPos.relative(side);
                if (allPipes.contains(neighbor) || endpoints.containsKey(neighbor)) continue;
                if (!level.isLoaded(neighbor)) continue;
                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, neighbor, side.getOpposite());
                if (handler != null) {
                    boolean isTank = level.getBlockEntity(neighbor) instanceof FluidTankBlockEntity;
                    endpoints.put(neighbor.immutable(),
                            new EndpointData(pumpPos.immutable(), neighbor.immutable(), side, isTank));
                }
            }
        }

        // Add handler-position virtual connections for all endpoints.
        // This gives proper graph structure (e.g. Tank-Pipe-Tank → 2 handler nodes, 1 edge).
        Set<BlockPos> handlerNodePositions = new LinkedHashSet<>();
        for (var ep : endpoints.values()) {
            handlerNodePositions.add(ep.handlerPos);
            pipeConnections.computeIfAbsent(ep.pipePos, k -> new ArrayList<>()).add(ep.handlerPos);
            pipeConnections.computeIfAbsent(ep.handlerPos, k -> new ArrayList<>()).add(ep.pipePos);
        }

        // Add pump positions as virtual graph nodes.
        // Each pump becomes a proper node that splits the run — two pumps produce
        // two nodes and three edges. This lets propagateHead sum them naturally
        // (pump 1 lifts head, friction eats some, pump 2 lifts again).
        Set<BlockPos> pumpNodePositions = new LinkedHashSet<>();
        for (BlockPos pumpPos : pumpPositions.keySet()) {
            pumpNodePositions.add(pumpPos);
            for (Direction side : Direction.values()) {
                BlockPos adj = pumpPos.relative(side);
                if (allPipes.contains(adj)) {
                    pipeConnections.computeIfAbsent(adj, k -> new ArrayList<>()).add(pumpPos);
                    pipeConnections.computeIfAbsent(pumpPos, k -> new ArrayList<>()).add(adj);
                }
            }
        }

        // Phase 2: Identify nodes (anything that isn't a simple pass-through)
        Set<BlockPos> nodePositions = new LinkedHashSet<>();
        for (BlockPos pipe : allPipes) {
            List<BlockPos> conns = pipeConnections.getOrDefault(pipe, List.of());
            if (conns.size() != 2) {
                nodePositions.add(pipe);
            }
        }
        // Pump positions are always nodes
        nodePositions.addAll(pumpNodePositions);
        // Handler-position nodes
        nodePositions.addAll(handlerNodePositions);
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
            float staticPressure = computeStaticPressure(level, pos, pumpPositions, endpoints, kind, config);
            float head = computeHead(level, pos, pumpPositions, endpoints, kind, config);

            // For PUMP nodes, store the push-side BlockPos for asymmetric Φ
            BlockPos pushSidePos = null;
            if (kind == SimNodeKind.PUMP) {
                BlockState pumpState = level.getBlockState(pos);
                if (pumpState.getBlock() instanceof PumpBlock) {
                    Direction facing = pumpState.getValue(PumpBlock.FACING);
                    pushSidePos = pos.relative(facing).immutable();
                }
            }

            nodes.put(id, new SimNode(id, kind, elevation, staticPressure, head, pushSidePos));
        }

        // Phase 4: Contract pipe runs into edges
        List<SimEdge> edges = new ArrayList<>();
        Set<BlockPos> visitedPipes = new HashSet<>();
        int edgeId = 0;

        for (BlockPos nodePos : nodePositions) {
            List<BlockPos> conns = pipeConnections.getOrDefault(nodePos, List.of());
            for (BlockPos neighbor : conns) {
                if (nodePositions.contains(neighbor) && !visitedPipes.contains(neighbor)) {
                    // Direct node-to-node edge
                    List<BlockPos> pipesOnly = new ArrayList<>();
                    if (allPipes.contains(nodePos)) pipesOnly.add(nodePos);
                    if (allPipes.contains(neighbor)) pipesOnly.add(neighbor);
                    float friction = config.frictionPerBlock();
                    edges.add(new SimEdge(edgeId++,
                            PipeGraphBuilder.nodeOf(nodePos),
                            PipeGraphBuilder.nodeOf(neighbor),
                            1, config.perPipeCapacity(),
                            friction, pipesOnly));
                    continue;
                }
                if (visitedPipes.contains(neighbor)) continue;
                if (!allPipes.contains(neighbor) && !nodePositions.contains(neighbor)) continue;

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
                        if (!c.equals(prev) && (allPipes.contains(c) || nodePositions.contains(c))) {
                            next = c;
                            break;
                        }
                    }
                    prev = current;
                    current = next;
                }

                if (current != null && nodePositions.contains(current)) {
                    path.add(current);
                    // Collect only actual pipe positions (exclude handler nodes)
                    List<BlockPos> pipesInPath = new ArrayList<>();
                    for (int pi = 1; pi < path.size() - 1; pi++) {
                        if (allPipes.contains(path.get(pi))) pipesInPath.add(path.get(pi));
                    }
                    int length = Math.max(1, pipesInPath.size());
                    float friction = config.frictionPerBlock() * length;
                    int capacity = config.perPipeCapacity() * length;
                    edges.add(new SimEdge(edgeId++,
                            PipeGraphBuilder.nodeOf(nodePos),
                            PipeGraphBuilder.nodeOf(current),
                            length, capacity, friction,
                            pipesInPath));
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

            // Pumps have FluidTransportBehaviour too — they show up as pipes.
            // Detect when the current position IS a pump (not just a neighbor).
            if (currentState.getBlock() instanceof PumpBlock) {
                BlockEntity be = level.getBlockEntity(current);
                pumpPositions.put(current.immutable(), current.immutable());
            }
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos neighbor = current.relative(face);
                if (!level.isLoaded(neighbor)) continue;

                BlockState neighborState = level.getBlockState(neighbor);

                if (neighborState.getBlock() instanceof PumpBlock) {
                    // Always include pump blocks in the topology so the network
                    // structure is correct even before kinetic energy propagates.
                    // Unpowered pumps get pumpHead=0 (check valve blocks flow).
                    pumpPositions.put(neighbor.immutable(), current.immutable());
                    continue;
                }

                var handler = level.getCapability(
                        Capabilities.FluidHandler.BLOCK, neighbor, face.getOpposite());
                if (handler != null) {
                    boolean isTank = level.getBlockEntity(neighbor) instanceof FluidTankBlockEntity;
                    endpoints.put(neighbor.immutable(),
                            new EndpointData(current.immutable(), neighbor.immutable(), face, isTank));
                    continue;
                }

                if (FluidPropagator.getPipe(level, neighbor) != null) {
                    connections.add(neighbor.immutable());
                    frontier.add(neighbor);
                    continue;
                }

                if (FluidPropagator.isOpenEnd(level, current, face)) {
                    endpoints.put(neighbor.immutable(),
                            new EndpointData(current.immutable(), neighbor.immutable(), face, false));
                }
            }

            pipeConnections.put(current.immutable(), connections);
        }
    }

    private static SimNodeKind classifyNode(BlockPos pos,
                                             Map<BlockPos, List<BlockPos>> pipeConnections,
                                             Map<BlockPos, BlockPos> pumpPositions,
                                             Map<BlockPos, EndpointData> endpoints) {
        // Pump positions are PUMP nodes
        if (pumpPositions.containsKey(pos)) {
            return SimNodeKind.PUMP;
        }

        // Handler-position nodes (the node IS the tank/handler block)
        for (var ep : endpoints.values()) {
            if (ep.handlerPos.equals(pos)) {
                return SimNodeKind.SOURCE;
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
                                                SimNodeKind kind, SimConfig config) {
        // Pump nodes get staticPressure = speed. This is the pump's contribution
        // to Φ — it creates a real potential peak at the pump position, driving flow
        // away on downstream edges. The check valve prevents backflow through the pump.
        if (kind == SimNodeKind.PUMP) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof KineticBlockEntity kbe) {
                return Math.abs(kbe.getSpeed());
            }
            return 0;
        }

        // SOURCE nodes: tanks get dynamic pressure from fill level (ρ·G·fillHeight),
        // other endpoints (basins, drains) get 0 (gravity handled by Φ elevation term).
        // Updated each tick by GravityFlowHandler.updateTankPressures.
        if (kind == SimNodeKind.SOURCE) {
            var directBE = level.getBlockEntity(pos);
            if (directBE instanceof FluidTankBlockEntity tankBE) {
                FluidTankAccessor accessor = (FluidTankAccessor) tankBE;
                int tankHeight = accessor.pipesnphysics$getHeight();
                var inv = accessor.pipesnphysics$getTankInventory();
                float fillFrac = inv.getCapacity() > 0
                        ? (float) inv.getFluidAmount() / inv.getCapacity() : 0;
                return config.G() * fillFrac * tankHeight;
            }
            for (var ep : endpoints.values()) {
                if (!ep.pipePos.equals(pos)) continue;
                var be = level.getBlockEntity(ep.handlerPos);
                if (be instanceof FluidTankBlockEntity tankBE2) {
                    FluidTankAccessor accessor = (FluidTankAccessor) tankBE2;
                    int tankHeight = accessor.pipesnphysics$getHeight();
                    var inv = accessor.pipesnphysics$getTankInventory();
                    float fillFrac = inv.getCapacity() > 0
                            ? (float) inv.getFluidAmount() / inv.getCapacity() : 0;
                    return config.G() * fillFrac * tankHeight;
                }
            }
            return 0;
        }

        return 0;
    }

    private static float computeHead(Level level, BlockPos pos,
                                       Map<BlockPos, BlockPos> pumpPositions,
                                       Map<BlockPos, EndpointData> endpoints,
                                       SimNodeKind kind, SimConfig config) {
        // Pumps supply head = speed * multiplier (reach budget for downstream edges).
        // The multiplier extends reach at low RPM without inflating staticPressure (which drives Φ/throughput).
        if (kind == SimNodeKind.PUMP) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof KineticBlockEntity kbe) {
                return Math.abs(kbe.getSpeed()) * config.pumpHeadMultiplier();
            }
            return config.defaultPumpHead();
        }

        // SOURCE nodes: tanks supply head from fill level, others from height difference.
        if (kind == SimNodeKind.SOURCE) {
            // Check if this is a tank (dynamic fill-based head)
            var directBE = level.getBlockEntity(pos);
            if (directBE instanceof FluidTankBlockEntity tankBE) {
                FluidTankAccessor accessor = (FluidTankAccessor) tankBE;
                int tankHeight = accessor.pipesnphysics$getHeight();
                var inv = accessor.pipesnphysics$getTankInventory();
                float fillFrac = inv.getCapacity() > 0
                        ? (float) inv.getFluidAmount() / inv.getCapacity() : 0;
                return config.G() * fillFrac * tankHeight;
            }
            // Check neighbors for tank or height difference
            for (var ep : endpoints.values()) {
                if (!ep.pipePos.equals(pos)) continue;
                var be = level.getBlockEntity(ep.handlerPos);
                if (be instanceof FluidTankBlockEntity tankBE2) {
                    FluidTankAccessor accessor = (FluidTankAccessor) tankBE2;
                    int tankHeight = accessor.pipesnphysics$getHeight();
                    var inv = accessor.pipesnphysics$getTankInventory();
                    float fillFrac = inv.getCapacity() > 0
                            ? (float) inv.getFluidAmount() / inv.getCapacity() : 0;
                    return config.G() * fillFrac * tankHeight;
                }
                // Non-tank endpoint: head from height difference
                double handlerY = SableCompat.getWorldY(level, ep.handlerPos);
                double pipeY = SableCompat.getWorldY(level, pos);
                float heightDiff = (float) Math.abs(handlerY - pipeY);
                return heightDiff * config.G();
            }
        }

        return 0;
    }

    record EndpointData(BlockPos pipePos, BlockPos handlerPos, Direction face, boolean isTank) {}

    // Keep static helpers for compatibility
    public static NodeId nodeOf(BlockPos pos) {
        return PipeGraphBuilder.nodeOf(pos);
    }

    public static BlockPos posOf(NodeId node) {
        return PipeGraphBuilder.posOf(node);
    }
}
