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
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Manages fluid flow networks using the potential-based simulation.
 * Each tick: builds the contracted graph, runs the simulator, applies results.
 */
public class GravityFlowHandler {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final Set<ScheduledCheck> scheduledChecks = new HashSet<>();
    public static volatile boolean suppressWipeReschedule = false;

    private static final Map<BlockPos, Long> lastProcessedTick = new HashMap<>();
    private static final Map<BlockPos, Long> lastTransferTick = new HashMap<>();
    private static final Map<BlockPos, FluidNetwork> cachedNetworks = new HashMap<>();

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
        lastTransferTick.clear();
        scheduledChecks.clear();
        cachedNetworks.clear();
    }

    private static final int STALE_ENTRY_TICKS = 6000;
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

        // Reuse cached network or build new one.
        // Normalize the cache key: use the smallest pipe position in the network
        // so all pipes in the same network share one cached entry.
        SimConfig config = PhysicsConfigFactory.simConfig();
        FluidNetwork network = cachedNetworks.get(startPos);
        if (network == null) {
            network = NetworkBuilder.build(level, startPos, config);
            // Cache under ALL node positions so any pipe lookup hits the same network
            for (var entry : network.nodes().entrySet()) {
                cachedNetworks.put(PipeGraphBuilder.posOf(entry.getKey()), network);
            }
            for (SimEdge edge : network.edges()) {
                for (BlockPos pos : edge.pipePositions()) {
                    cachedNetworks.put(pos, network);
                }
            }
        }

        // Mark all pipes as processed
        for (SimEdge edge : network.edges()) {
            for (BlockPos pos : edge.pipePositions()) {
                alreadyProcessed.add(pos);
                lastProcessedTick.put(pos, currentTick);
            }
        }
        for (var entry : network.nodes().entrySet()) {
            BlockPos pos = PipeGraphBuilder.posOf(entry.getKey());
            alreadyProcessed.add(pos);
            lastProcessedTick.put(pos, currentTick);
        }

        if (network.edges().isEmpty()) {
            // Single-pipe network: transfer directly between endpoints
            handleSingleNodeNetwork(level, network, startPos, currentTick);
            return;
        }

        // Find fluid in the network and build fluid registry
        Map<String, SimFluid> fluids = new HashMap<>();
        FluidStack networkFluid = findAndRegisterFluid(level, network, fluids);

        // Always run the simulator — it handles edge phase transitions even without fluid
        // (e.g. DRAINING edges need to tick even when no new fluid is available)
        FluidSimulator simulator = new FluidSimulator(config);
        SimResult result = simulator.tick(network, fluids);

        StringBuilder phases = new StringBuilder();
        for (SimEdge e : network.edges()) {
            phases.append(e.phase().name().charAt(0)).append("(fp=")
                    .append(String.format("%.1f", e.frontPos())).append(",fill=")
                    .append(e.totalFill()).append(") ");
        }
        LOGGER.debug("Sim at {}: edges={} phases=[{}] flowRates={} fluid={}",
                startPos, network.edges().size(), phases,
                java.util.Arrays.toString(result.flowRates()),
                networkFluid.getHoverName().getString());

        // Apply visual state to pipes
        applyVisuals(level, network, result, networkFluid);

        // Transfer fluid at boundary nodes
        Long lastTransfer = lastTransferTick.get(startPos);
        int elapsed = lastTransfer != null ? (int) (currentTick - lastTransfer) : 1;
        elapsed = Math.max(1, Math.min(elapsed, 20));

        boolean transferred = transferFluid(level, network, result, networkFluid, fluids, elapsed);
        LOGGER.debug("Transfer at {}: transferred={} elapsed={}", startPos, transferred, elapsed);
        if (transferred) {
            lastTransferTick.put(startPos, currentTick);
        }

        // Store breakdowns on pipe block entities for goggle display
        storeBreakdowns(level, network, result);
    }

    private static FluidStack findAndRegisterFluid(Level level, FluidNetwork network,
                                                    Map<String, SimFluid> fluids) {
        // Check all nodes for adjacent fluid handlers
        for (var entry : network.nodes().entrySet()) {
            SimNode node = entry.getValue();
            BlockPos pos = PipeGraphBuilder.posOf(entry.getKey());

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, neighbor, dir.getOpposite());
                if (handler == null) continue;
                for (int i = 0; i < handler.getTanks(); i++) {
                    FluidStack stack = handler.getFluidInTank(i);
                    if (!stack.isEmpty()) {
                        registerFluid(stack, fluids);
                        return stack;
                    }
                }
            }
        }

        // Check pipes for flowing fluid
        for (SimEdge edge : network.edges()) {
            for (BlockPos pos : edge.pipePositions()) {
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe == null) continue;
                for (Direction d : Direction.values()) {
                    var flow = pipe.getFlow(d);
                    if (flow != null && !flow.fluid.isEmpty()) {
                        registerFluid(flow.fluid, fluids);
                        return flow.fluid;
                    }
                }
            }
        }

        return FluidStack.EMPTY;
    }

    private static void registerFluid(FluidStack stack, Map<String, SimFluid> fluids) {
        String id = stack.getFluid().builtInRegistryHolder().key().location().toString();
        if (fluids.containsKey(id)) return;
        boolean lighter = stack.getFluid().getFluidType().isLighterThanAir();
        float density = stack.getFluid().getFluidType().getDensity(stack) / 1000f;
        if (density <= 0) density = 1.0f;
        float viscosity = stack.getFluid().getFluidType().getViscosity(stack) / 1000f;
        if (viscosity <= 0) viscosity = 1.0f;
        fluids.put(id, new SimFluid(id, lighter ? FluidPhase.GAS : FluidPhase.LIQUID, density, viscosity));
    }

    private static void seedFluidIntoEdges(Level level, FluidNetwork network,
                                            Map<String, SimFluid> fluids, FluidStack networkFluid) {
        String fluidId = networkFluid.getFluid().builtInRegistryHolder().key().location().toString();
        for (SimEdge edge : network.edges()) {
            if (!edge.isEmpty()) continue;
            // Check if either endpoint is a source with fluid
            for (NodeId nodeId : List.of(edge.a(), edge.b())) {
                SimNode node = network.node(nodeId);
                if (node == null) continue;
                if (node.kind() == SimNodeKind.SOURCE || node.kind() == SimNodeKind.PUMP) {
                    // Seed the edge with fluid at capacity
                    edge.pushFromA(fluidId, edge.capacity());
                    break;
                }
            }
        }
    }

    private static void applyVisuals(Level level, FluidNetwork network, SimResult result,
                                      FluidStack networkFluid) {
        suppressWipeReschedule = true;
        try {
            for (int i = 0; i < network.edges().size(); i++) {
                SimEdge edge = network.edges().get(i);
                float rate = result.flowRates()[i];

                for (BlockPos pos : edge.pipePositions()) {
                    FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                    if (pipe == null) continue;

                    if (edge.isEmpty() || rate == 0) {
                        if (pipe.hasAnyPressure()) pipe.wipePressure();
                        continue;
                    }

                    float pressure = Math.abs(rate);
                    if (!pipe.hasAnyPressure()) {
                        // Set pressure in the flow direction
                        // For each pipe, determine inbound/outbound from the edge direction
                        for (Direction dir : Direction.values()) {
                            var conn = pipe.getConnection(dir);
                            if (conn == null) continue;
                            BlockPos neighbor = pos.relative(dir);
                            // Check if this direction is along the edge
                            if (edge.pipePositions().contains(neighbor)
                                    || PipeGraphBuilder.posOf(edge.a()).equals(neighbor)
                                    || PipeGraphBuilder.posOf(edge.b()).equals(neighbor)) {
                                pipe.addPressure(dir, rate > 0, pressure);
                            }
                        }
                    }
                }
            }
        } finally {
            suppressWipeReschedule = false;
        }
    }

    private static boolean transferFluid(Level level, FluidNetwork network, SimResult result,
                                          FluidStack networkFluid, Map<String, SimFluid> fluids,
                                          int elapsedTicks) {
        if (networkFluid.isEmpty()) return false;

        // Find the bottleneck flow rate
        float flowRate = Float.MAX_VALUE;
        for (float rate : result.flowRates()) {
            float abs = Math.abs(rate);
            if (abs > 0 && abs < flowRate) flowRate = abs;
        }
        if (flowRate <= 0 || flowRate == Float.MAX_VALUE) return false;
        int transferAmount = Math.max(1, (int) (flowRate * elapsedTicks));

        // Collect boundary handlers, use potentials for direction
        List<HandlerInfo> handlers = new ArrayList<>();
        for (var entry : network.nodes().entrySet()) {
            SimNode node = entry.getValue();
            if (node.kind() == SimNodeKind.JUNCTION || node.kind() == SimNodeKind.DEAD_END) continue;
            BlockPos nodePos = PipeGraphBuilder.posOf(entry.getKey());
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = nodePos.relative(dir);
                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, neighbor, dir.getOpposite());
                if (handler != null) {
                    handlers.add(new HandlerInfo(entry.getKey(), handler, neighbor));
                    break;
                }
            }
        }
        if (handlers.size() < 2) return false;

        // Sort by potential descending: highest Φ = source, lowest = sink
        handlers.sort((a, b) -> Float.compare(
                result.potentials().getOrDefault(b.node, 0f),
                result.potentials().getOrDefault(a.node, 0f)));

        HandlerInfo src = null;
        for (HandlerInfo h : handlers) {
            if (!h.handler.drain(networkFluid.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()) {
                src = h; break;
            }
        }
        if (src == null) return false;

        boolean didTransfer = false;
        for (int i = handlers.size() - 1; i >= 0; i--) {
            HandlerInfo dst = handlers.get(i);
            if (dst.pos.equals(src.pos)) continue;
            if (dst.handler.fill(networkFluid.copyWithAmount(1), FluidAction.SIMULATE) <= 0) continue;
            FluidStack drained = src.handler.drain(networkFluid.copyWithAmount(transferAmount), FluidAction.SIMULATE);
            if (drained.isEmpty()) break;
            int accepted = dst.handler.fill(drained, FluidAction.SIMULATE);
            if (accepted <= 0) continue;
            FluidStack actual = src.handler.drain(networkFluid.copyWithAmount(accepted), FluidAction.EXECUTE);
            if (!actual.isEmpty()) {
                dst.handler.fill(actual, FluidAction.EXECUTE);
                didTransfer = true;
            }
            break;
        }
        return didTransfer;
    }

    private static void storeBreakdowns(Level level, FluidNetwork network, SimResult result) {
        for (int i = 0; i < network.edges().size(); i++) {
            SimEdge edge = network.edges().get(i);
            float rate = Math.abs(result.flowRates()[i]);

            Float phiA = result.potentials().get(edge.a());
            Float phiB = result.potentials().get(edge.b());
            float gravity = (phiA != null ? phiA : 0) - (phiB != null ? phiB : 0);

            PressureBreakdown breakdown = new PressureBreakdown(
                    Math.abs(gravity), 0, 0, 0,
                    edge.resistance(), rate, false, false);

            for (BlockPos pos : edge.pipePositions()) {
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe instanceof PipeFlowData data) {
                    data.pipesnphysics$setBreakdown(breakdown);
                    pipe.blockEntity.notifyUpdate();
                }
            }
        }
    }

    /**
     * Handle networks with only 1 pipe (no edges in contracted graph).
     * Transfer directly between endpoint handlers adjacent to the single node.
     */
    private static void handleSingleNodeNetwork(Level level, FluidNetwork network,
                                                 BlockPos startPos, long currentTick) {
        if (network.nodes().isEmpty()) return;

        SimNode node = network.nodes().values().iterator().next();
        BlockPos nodePos = PipeGraphBuilder.posOf(node.id());

        // Find all adjacent fluid handlers
        List<HandlerInfo> handlers = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = nodePos.relative(dir);
            var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, neighbor, dir.getOpposite());
            if (handler != null) {
                handlers.add(new HandlerInfo(node.id(), handler, neighbor));
            }
        }

        if (handlers.size() < 2) return;
        LOGGER.debug("SingleNode transfer at {} with {} handlers", nodePos, handlers.size());

        // Find source (has fluid) and sink (can accept)
        for (HandlerInfo src : handlers) {
            for (int i = 0; i < src.handler.getTanks(); i++) {
                FluidStack fluid = src.handler.getFluidInTank(i);
                if (fluid.isEmpty()) continue;

                for (HandlerInfo dst : handlers) {
                    if (dst.pos.equals(src.pos)) continue;
                    int accepted = dst.handler.fill(fluid.copyWithAmount(
                            Math.min(fluid.getAmount(), 10)), FluidAction.SIMULATE);
                    if (accepted <= 0) continue;

                    FluidStack drained = src.handler.drain(
                            fluid.copyWithAmount(accepted), FluidAction.EXECUTE);
                    if (!drained.isEmpty()) {
                        dst.handler.fill(drained, FluidAction.EXECUTE);
                    }
                    return;
                }
            }
        }
    }

    private record HandlerInfo(NodeId node, IFluidHandler handler, BlockPos pos) {}
}
