package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
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

public class GravityFlowHandler {

    private static final Set<ScheduledCheck> scheduledChecks = new HashSet<>();
    
    public static volatile boolean suppressWipeReschedule = false;

    private static final Map<BlockPos, Long> lastProcessedTick = new HashMap<>();
    private static final Map<BlockPos, PressureBreakdown> cachedBreakdowns = new HashMap<>();

    public static PressureBreakdown getCachedBreakdown(BlockPos pos) {
        return cachedBreakdowns.get(pos);
    }

    private record ScheduledCheck(Level level, BlockPos pos) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ScheduledCheck(Level level1, BlockPos pos1))) return false;
            return pos.equals(pos1) && level == level1;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(level) + pos.hashCode();
        }
    }

    public static void scheduleCheck(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        scheduledChecks.add(new ScheduledCheck(level, pos));
    }

    public static void clearCooldown(BlockPos pos) {
        lastProcessedTick.remove(pos);
    }

    public static void clearAllCooldowns() {
        lastProcessedTick.clear();
        scheduledChecks.clear();
        cachedBreakdowns.clear();
    }

    private static final int STALE_ENTRY_TICKS = 6000; // ~5 minutes
    private static long lastPurgeTick = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (scheduledChecks.isEmpty()) return;
        if (!PipesNPhysicsConfig.ENABLE_GRAVITY_FLOW.get()) {
            scheduledChecks.clear();
            return;
        }

        long gameTick = event.getServer().overworld().getGameTime();
        if (gameTick - lastPurgeTick > STALE_ENTRY_TICKS) {
            lastPurgeTick = gameTick;
            lastProcessedTick.entrySet().removeIf(e -> gameTick - e.getValue() > STALE_ENTRY_TICKS);
            cachedBreakdowns.keySet().retainAll(lastProcessedTick.keySet());
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
        if (!SableCompat.isSubLevelReady(level, startPos)) return;

        long currentTick = level.getGameTime();
        Long lastTick = lastProcessedTick.get(startPos);
        int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
        if (lastTick != null && currentTick - lastTick < recheckTicks) return;

        PipeGraph graph = PipeGraphBuilder.discover(level, startPos);

        for (NodeId node : graph.pipeNodeIds()) {
            BlockPos pos = PipeGraphBuilder.posOf(node);
            alreadyProcessed.add(pos);
            lastProcessedTick.put(pos, currentTick);
        }

        if (graph.hasActivePump()) return;
        if (graph.endpoints().size() < 2) return;

        NetworkEndpoint source = null;
        for (NetworkEndpoint ep : graph.endpoints()) {
            if (!ep.isHandlerAbovePipe()) continue;
            if (source == null || ep.handlerWorldY() > source.handlerWorldY()) {
                source = ep;
            }
        }
        if (source == null) return;

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

        int totalFluid = 0;
        int totalCapacity = 0;
        for (int tankIndex = 0; tankIndex < sourceHandler.getTanks(); tankIndex++) {
            totalFluid += sourceHandler.getFluidInTank(tankIndex).getAmount();
            totalCapacity += sourceHandler.getTankCapacity(tankIndex);
        }
        double fillFraction = totalCapacity > 0 ? (double) totalFluid / totalCapacity : 1.0;

        double tankHeight = source.handlerWorldY() - source.pipeWorldY();
        var tankBE = level.getBlockEntity(sourceHandlerPos);
        if (tankBE instanceof FluidTankBlockEntity ftbe) {
            tankHeight = ftbe.getHeight();
        }
        double fluidSurfaceY = source.pipeWorldY() + tankHeight * Math.floor(fillFraction * 20) / 20.0;
        NetworkEndpoint originalSource = source;
        source = new NetworkEndpoint(
                source.handlerNode(), source.pipeNode(), source.faceIndex(),
                fluidSurfaceY, source.pipeWorldY());

        List<NetworkEndpoint> validEndpoints = new ArrayList<>();
        validEndpoints.add(source);
        for (NetworkEndpoint ep : graph.endpoints()) {
            if (ep == originalSource) continue;
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
            } else if (FluidPropagator.isOpenEnd(level, PipeGraphBuilder.posOf(ep.pipeNode()),
                    PipeGraphBuilder.directionOf(ep.faceIndex()))) {
                validEndpoints.add(ep);
            }
        }
        if (validEndpoints.size() < 2) return;

        PipeGraph filteredGraph = new PipeGraph(
                graph.nodes(), graph.adjacency(), validEndpoints);

        PhysicsConfig config = PhysicsConfigFactory.fromModConfig();
        PipeFormulas formulas = new PipeFormulas(config);
        NetworkSolver solver = new NetworkSolver(formulas);
        float viscosity = sourceFluid.getFluid().getFluidType().getViscosity(sourceFluid) / 1000f;
        GravityFlowResult result = solver.solveGravityFlow(filteredGraph, viscosity);
        if (result == null) return;

        Map<NodeId, PressureBreakdown> breakdowns = solver.computeAllBreakdowns(filteredGraph, result, viscosity);
        for (var entry : breakdowns.entrySet()) {
            cachedBreakdowns.put(PipeGraphBuilder.posOf(entry.getKey()), entry.getValue());
        }

        applyFlowResult(level, graph, result);
    }

    private static void applyFlowResult(Level level, PipeGraph graph, GravityFlowResult result) {
        suppressWipeReschedule = true;
        try {
            for (NodeId node : graph.pipeNodeIds()) {
                BlockPos pos = PipeGraphBuilder.posOf(node);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe == null) continue;
                if (!result.pipePressures().containsKey(node)) {
                    if (pipe.hasAnyPressure()) pipe.wipePressure();
                }
            }

            for (NodeId node : result.activePipes()) {
                BlockPos pos = PipeGraphBuilder.posOf(node);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe == null) continue;

                float pressure = result.pipePressures().getOrDefault(node, 0f);
                if (pressure <= 0) continue;

                if (pipe.hasAnyPressure()) pipe.wipePressure();

                Integer inboundFace = result.inboundFaceIndex().get(node);
                if (inboundFace != null) {
                    pipe.addPressure(PipeGraphBuilder.directionOf(inboundFace), true, pressure);
                }

                Set<Integer> outFaces = result.outboundFaceIndices().getOrDefault(node, Set.of());
                for (int faceIdx : outFaces) {
                    BlockPos neighborPos = pos.relative(PipeGraphBuilder.directionOf(faceIdx));
                    NodeId neighborNode = PipeGraphBuilder.nodeOf(neighborPos);
                    if (result.activePipes().contains(neighborNode)) {
                        float outPressure = result.pipePressures().getOrDefault(neighborNode, pressure);
                        pipe.addPressure(PipeGraphBuilder.directionOf(faceIdx), false, outPressure);
                    }
                }

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
