package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.physics.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import java.util.*;

/**
 * Orchestrates gravity-driven fluid flow for pipe networks without pumps.
 * Handles scheduling, cooldowns, and Minecraft-specific validation.
 * Delegates physics computation to {@link NetworkSolver}.
 */
public class GravityFlowHandler {

    private static final Set<ScheduledCheck> scheduledChecks = new HashSet<>();

    /**
     * When true, wipePressure() calls should NOT trigger a gravity recheck.
     * Set during pressure reapplication to prevent wipe→schedule→process→wipe loops.
     */
    public static volatile boolean suppressWipeReschedule = false;

    private static final Map<BlockPos, Long> lastProcessedTick = new HashMap<>();

    private record ScheduledCheck(Level level, BlockPos pos) {
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ScheduledCheck sc)) return false;
            return pos.equals(sc.pos) && level == sc.level;
        }
        @Override public int hashCode() { return pos.hashCode(); }
    }

    public static void scheduleCheck(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        scheduledChecks.add(new ScheduledCheck(level, pos));
    }

    public static void clearCooldown(BlockPos pos) {
        lastProcessedTick.remove(pos);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (scheduledChecks.isEmpty()) return;
        if (!PipesNPhysicsConfig.ENABLE_GRAVITY_FLOW.get()) {
            scheduledChecks.clear();
            return;
        }

        Set<ScheduledCheck> toProcess = new HashSet<>(scheduledChecks);
        scheduledChecks.clear();

        Set<BlockPos> alreadyProcessed = new HashSet<>();
        for (ScheduledCheck check : toProcess) {
            if (alreadyProcessed.contains(check.pos)) continue;
            if (!check.level.isLoaded(check.pos)) continue;
            processNetwork(check.level, check.pos, alreadyProcessed);
        }
    }

    private static void processNetwork(Level level, BlockPos startPos, Set<BlockPos> alreadyProcessed) {
        long currentTick = level.getGameTime();
        Long lastTick = lastProcessedTick.get(startPos);
        int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
        if (lastTick != null && currentTick - lastTick < recheckTicks) return;

        PipeGraph graph = PipeGraphBuilder.discover(level, startPos);

        // Mark cooldown for all discovered pipe nodes
        for (NodeId node : graph.pipeNodeIds()) {
            BlockPos pos = PipeGraphBuilder.posOf(node);
            alreadyProcessed.add(pos);
            lastProcessedTick.put(pos, currentTick);
        }

        // Active pump takes over — don't apply gravity on top of pump pressure
        if (graph.hasActivePump()) return;
        if (graph.endpoints().size() < 2) return;

        // Find source: highest endpoint where handler is above its pipe
        NetworkEndpoint source = null;
        for (NetworkEndpoint ep : graph.endpoints()) {
            if (!ep.isHandlerAbovePipe()) continue;
            if (source == null || ep.handlerWorldY() > source.handlerWorldY()) {
                source = ep;
            }
        }
        if (source == null) return;

        // Verify source has fluid (Minecraft-specific capability check)
        BlockPos sourceHandlerPos = PipeGraphBuilder.posOf(source.handlerNode());
        Direction sourceFace = PipeGraphBuilder.directionOf(source.faceIndex()).getOpposite();
        var sourceHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, sourceHandlerPos, sourceFace);
        if (sourceHandler == null) return;

        FluidStack sourceFluid = FluidStack.EMPTY;
        for (int tankIndex = 0; tankIndex < sourceHandler.getTanks(); tankIndex++) {
            if (!sourceHandler.getFluidInTank(tankIndex).isEmpty()) {
                sourceFluid = sourceHandler.getFluidInTank(tankIndex);
                break;
            }
        }
        if (sourceFluid.isEmpty()) return;

        // Filter sinks: must accept the source fluid (Minecraft-specific)
        List<NetworkEndpoint> validEndpoints = new ArrayList<>();
        validEndpoints.add(source);
        for (NetworkEndpoint ep : graph.endpoints()) {
            if (ep == source) continue;
            BlockPos sinkPos = PipeGraphBuilder.posOf(ep.handlerNode());
            Direction sinkFace = PipeGraphBuilder.directionOf(ep.faceIndex()).getOpposite();
            var sinkHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, sinkPos, sinkFace);
            if (sinkHandler == null) {
                validEndpoints.add(ep);
                continue;
            }
            var testStack = sourceFluid.copyWithAmount(1);
            int accepted = sinkHandler.fill(testStack, FluidAction.SIMULATE);
            if (accepted > 0) {
                validEndpoints.add(ep);
            }
        }
        if (validEndpoints.size() < 2) return;

        // Build filtered graph and solve physics
        PipeGraph filteredGraph = new PipeGraph(
                graph.nodes(), graph.adjacency(), validEndpoints);

        PhysicsConfig config = PhysicsConfigFactory.fromModConfig();
        PipeFormulas formulas = new PipeFormulas(config);
        NetworkSolver solver = new NetworkSolver(formulas);
        GravityFlowResult result = solver.solveGravityFlow(filteredGraph);
        if (result == null) return;

        // Apply result to Create's transport system
        applyFlowResult(level, graph, result);
    }

    private static void applyFlowResult(Level level, PipeGraph graph, GravityFlowResult result) {
        suppressWipeReschedule = true;
        try {
            // Wipe dead branches
            for (NodeId node : graph.pipeNodeIds()) {
                BlockPos pos = PipeGraphBuilder.posOf(node);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe == null) continue;
                if (!result.pipePressures().containsKey(node)) {
                    if (pipe.hasAnyPressure()) pipe.wipePressure();
                }
            }

            // Apply pressure to active pipes
            for (NodeId node : result.activePipes()) {
                BlockPos pos = PipeGraphBuilder.posOf(node);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe == null) continue;

                float pressure = result.pipePressures().getOrDefault(node, 0f);
                if (pressure <= 0) continue;

                if (pipe.hasAnyPressure()) pipe.wipePressure();

                // Inbound side
                Integer inboundFace = result.inboundFaceIndex().get(node);
                if (inboundFace != null) {
                    pipe.addPressure(PipeGraphBuilder.directionOf(inboundFace), true, pressure);
                }

                // Outbound sides (to other active pipes)
                Set<Integer> outFaces = result.outboundFaceIndices().getOrDefault(node, Set.of());
                for (int faceIdx : outFaces) {
                    BlockPos neighborPos = pos.relative(PipeGraphBuilder.directionOf(faceIdx));
                    if (result.activePipes().contains(PipeGraphBuilder.nodeOf(neighborPos))) {
                        pipe.addPressure(PipeGraphBuilder.directionOf(faceIdx), false, pressure);
                    }
                }

                // Outbound to sinks
                for (NetworkEndpoint sink : result.sinkEndpoints()) {
                    if (sink.pipeNode().equals(node) && result.validSinks().contains(sink)) {
                        pipe.addPressure(PipeGraphBuilder.directionOf(sink.faceIndex()), false, pressure);
                    }
                }
            }
        } finally {
            suppressWipeReschedule = false;
        }
    }
}
