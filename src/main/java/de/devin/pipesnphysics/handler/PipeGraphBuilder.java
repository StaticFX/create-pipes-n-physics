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
 * Adapts a Minecraft pipe network into a pure {@link PipeGraph}.
 * Resolves all block states, fluid handler capabilities, and Sable elevation
 * transforms during construction so the physics solver never touches Minecraft.
 * Each discovered node gets a {@link PipeNode} with its kind and world-Y.
 */
public final class PipeGraphBuilder {

    private PipeGraphBuilder() {}

    /**
     * BFS through the pipe network starting at {@code startPos}, producing a pure graph.
     */
    public static PipeGraph discover(Level level, BlockPos startPos) {
        Map<NodeId, PipeNode> nodes = new LinkedHashMap<>();
        Map<NodeId, List<PipeEdge>> adjacency = new HashMap<>();
        List<NetworkEndpoint> endpoints = new ArrayList<>();

        Queue<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new LinkedHashSet<>();
        frontier.add(startPos);

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            if (!visited.add(current)) continue;
            if (!level.isLoaded(current)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) {
                visited.remove(current);
                continue;
            }

            NodeId currentId = nodeOf(current);
            double currentY = SableCompat.getWorldY(level, current);
            nodes.put(currentId, new PipeNode(currentId, NodeKind.PIPE, currentY));

            BlockState currentState = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos neighbor = current.relative(face);
                if (!level.isLoaded(neighbor)) continue;

                BlockState neighborState = level.getBlockState(neighbor);

                // Active pump — record as a PUMP node but don't traverse through
                if (neighborState.getBlock() instanceof PumpBlock) {
                    BlockEntity be = level.getBlockEntity(neighbor);
                    if (be instanceof KineticBlockEntity kbe && kbe.getSpeed() != 0) {
                        NodeId pumpId = nodeOf(neighbor);
                        double pumpY = SableCompat.getWorldY(level, neighbor);
                        nodes.put(pumpId, new PipeNode(pumpId, NodeKind.PUMP, pumpY));
                    }
                    continue;
                }

                // Fluid handler endpoint (tank, basin, etc.)
                var handler = level.getCapability(
                        Capabilities.FluidHandler.BLOCK, neighbor, face.getOpposite());
                if (handler != null) {
                    endpoints.add(new NetworkEndpoint(
                            nodeOf(neighbor), currentId, face.ordinal(),
                            SableCompat.getWorldY(level, neighbor), currentY));
                    continue;
                }

                // Connected pipe — add edge and continue BFS
                if (FluidPropagator.getPipe(level, neighbor) != null) {
                    float elevation = SableCompat.getPipeElevation(level, current, face);
                    double toY = SableCompat.getWorldY(level, neighbor);

                    adjacency.computeIfAbsent(currentId, k -> new ArrayList<>())
                            .add(new PipeEdge(currentId, nodeOf(neighbor),
                                    elevation, currentY, toY, face.ordinal()));
                    frontier.add(neighbor);
                    continue;
                }

                // Open end
                if (FluidPropagator.isOpenEnd(level, current, face)) {
                    endpoints.add(new NetworkEndpoint(
                            nodeOf(neighbor), currentId, face.ordinal(),
                            SableCompat.getWorldY(level, neighbor), currentY));
                }
            }
        }

        return new PipeGraph(nodes, adjacency, endpoints);
    }

    /** Wrap a BlockPos into a NodeId. */
    public static NodeId nodeOf(BlockPos pos) {
        return new NodeId(pos.immutable());
    }

    /** Unwrap a NodeId back to a BlockPos. */
    public static BlockPos posOf(NodeId node) {
        return node.pos();
    }

    /** Get a Direction from a face index (Direction ordinal). */
    public static Direction directionOf(int faceIndex) {
        return Direction.values()[faceIndex];
    }
}
