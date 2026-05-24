package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
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
    private static final Set<BlockPos> activeNetworks = new HashSet<>();

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
        // Clear the ENTIRE cached network (stored under all its positions), not just one entry
        FluidNetwork net = cachedNetworks.remove(pos);
        if (net != null) {
            cachedNetworks.values().removeIf(n -> n == net);
        }
    }

    public static void clearAllCooldowns() {
        lastProcessedTick.clear();
        lastTransferTick.clear();
        scheduledChecks.clear();
        cachedNetworks.clear();
        activeNetworks.clear();
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
        // Active networks (nonzero flow) process every tick for smooth transfer.
        // Idle networks keep the longer recheckTicks interval to save CPU.
        // Check active state by canonical key (first node), not startPos, so all
        // pipes in the same network share one active/idle state.
        FluidNetwork cachedNet = cachedNetworks.get(startPos);
        BlockPos activeKey = cachedNet != null
                ? PipeGraphBuilder.posOf(cachedNet.nodes().keySet().iterator().next())
                : startPos;
        int interval = activeNetworks.contains(activeKey) ? 1 : recheckTicks;
        if (lastTick != null && currentTick - lastTick < interval) return;

        // Reuse cached network or build new one.
        // Pumps are proper graph nodes (always in the topology regardless of speed),
        // so no stale-cache detection needed — updatePumpSpeeds refreshes each tick.
        SimConfig config = PhysicsConfigFactory.simConfig();
        FluidNetwork network = cachedNetworks.get(startPos);
        if (network == null) {
            network = NetworkBuilder.build(level, startPos, config);
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

        // Update dynamic tank pressures before simulation
        updateTankPressures(level, network, fluids, config);
        updatePumpSpeeds(level, network);

        // Always run the simulator — it handles edge phase transitions even without fluid
        // (e.g. DRAINING edges need to tick even when no new fluid is available)
        FluidSimulator simulator = new FluidSimulator(config);
        SimResult result = simulator.tick(network, fluids);

        // Compute true bottleneck: min of ALL edge rates. Zero means the circuit
        // isn't complete (some edge still charging or stalled).
        float bottleneck = 0;
        if (network.edges().size() > 0) {
            bottleneck = Float.MAX_VALUE;
            for (float rate : result.flowRates()) {
                float abs = Math.abs(rate);
                if (abs < bottleneck) bottleneck = abs;
            }
            if (bottleneck == Float.MAX_VALUE) bottleneck = 0;
        }

        // Only apply visuals when the circuit is complete (bottleneck > 0).
        // Setting pressure on pipes with an incomplete circuit causes Create's
        // internal logic to transfer fluid independently, bypassing our engine.
        if (bottleneck > 0) {
            applyVisuals(level, network, result, networkFluid);
        } else {
            // Wipe any stale pressure from previous cycles
            suppressWipeReschedule = true;
            try {
                for (SimEdge edge : network.edges()) {
                    for (BlockPos pos : edge.pipePositions()) {
                        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                        if (pipe != null && pipe.hasAnyPressure()) pipe.wipePressure();
                    }
                }
            } finally {
                suppressWipeReschedule = false;
            }
        }

        // Transfer fluid at boundary nodes.
        // Use a canonical key (first node) so elapsed is consistent regardless of which
        // pipe triggered processing — all pipes in the same network share one timer.
        BlockPos transferKey = PipeGraphBuilder.posOf(network.nodes().keySet().iterator().next());
        Long lastTransfer = lastTransferTick.get(transferKey);
        int elapsed = lastTransfer != null ? (int) (currentTick - lastTransfer) : recheckTicks;
        elapsed = Math.max(1, Math.min(elapsed, 20));

        boolean transferred = transferFluid(level, network, result, networkFluid, fluids, elapsed, bottleneck);
        if (transferred) {
            LOGGER.info("Xfer n={} e={} rates={} elapsed={} active={}",
                    network.nodes().size(), network.edges().size(),
                    java.util.Arrays.toString(result.flowRates()),
                    elapsed, activeNetworks.contains(transferKey));
        }
        if (transferred) {
            lastTransferTick.put(transferKey, currentTick);
        }

        // Store breakdowns on pipe block entities for goggle display
        storeBreakdowns(level, network, result);

        // Track active state using the canonical key (same as transfer tick).
        // Networks with nonzero flow process every tick for smooth transfer.
        boolean hasFlow = false;
        for (float rate : result.flowRates()) {
            if (Math.abs(rate) > 0) { hasFlow = true; break; }
        }
        if (transferred || hasFlow) {
            activeNetworks.add(transferKey);
            scheduledChecks.add(new ScheduledCheck(level, startPos));
        } else {
            activeNetworks.remove(transferKey);
        }
    }

    private static FluidStack findAndRegisterFluid(Level level, FluidNetwork network,
                                                    Map<String, SimFluid> fluids) {
        // Check all nodes for fluid handlers (at node position or adjacent)
        for (var entry : network.nodes().entrySet()) {
            SimNode node = entry.getValue();
            BlockPos pos = PipeGraphBuilder.posOf(entry.getKey());

            // Check if the node itself is a handler (handler-position node)
            var directHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            if (directHandler != null) {
                for (int i = 0; i < directHandler.getTanks(); i++) {
                    FluidStack stack = directHandler.getFluidInTank(i);
                    if (!stack.isEmpty()) {
                        registerFluid(stack, fluids);
                        return stack;
                    }
                }
            }

            // Check neighbors
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

    private static void updateTankPressures(Level level, FluidNetwork network,
                                              Map<String, SimFluid> fluids, SimConfig config) {
        SimFluid fluid = fluids.isEmpty() ? null : fluids.values().iterator().next();
        float density = fluid != null ? fluid.density() : 1.0f;

        for (var entry : network.nodes().entrySet()) {
            SimNode node = entry.getValue();
            if (node.kind() != SimNodeKind.TANK) continue;

            BlockPos nodePos = PipeGraphBuilder.posOf(entry.getKey());

            // Check if the node IS a tank (handler-position node)
            var directBE = level.getBlockEntity(nodePos);
            if (directBE instanceof FluidTankBlockEntity tankBE) {
                FluidTankAccessor accessor = (FluidTankAccessor) tankBE;
                int tankHeight = accessor.pipesnphysics$getHeight();
                var inventory = accessor.pipesnphysics$getTankInventory();
                float fillFraction = inventory.getCapacity() > 0
                        ? (float) inventory.getFluidAmount() / inventory.getCapacity()
                        : 0;
                float fillHeight = fillFraction * tankHeight;
                float pressure = density * config.G() * fillHeight;
                entry.setValue(new SimNode(
                        node.id(), node.kind(), node.elevation(),
                        pressure, pressure));
                continue;
            }

            // Check neighbors (pipe-position TANK nodes)
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = nodePos.relative(dir);
                var be = level.getBlockEntity(neighbor);
                if (!(be instanceof FluidTankBlockEntity tankBE2)) continue;

                FluidTankAccessor accessor = (FluidTankAccessor) tankBE2;
                int tankHeight = accessor.pipesnphysics$getHeight();
                var inventory = accessor.pipesnphysics$getTankInventory();
                float fillFraction = inventory.getCapacity() > 0
                        ? (float) inventory.getFluidAmount() / inventory.getCapacity()
                        : 0;
                float fillHeight = fillFraction * tankHeight;
                float pressure = density * config.G() * fillHeight;

                entry.setValue(new SimNode(
                        node.id(), node.kind(), node.elevation(),
                        pressure, pressure));
                break;
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

                    if (edge.isEmpty() || (rate == 0 && edge.phase() != EdgePhase.FLOWING)) {
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
                                          int elapsedTicks, float bottleneck) {
        if (networkFluid.isEmpty()) return false;

        // Collect boundary handlers, use potentials for direction
        List<HandlerInfo> handlers = new ArrayList<>();
        for (var entry : network.nodes().entrySet()) {
            SimNode node = entry.getValue();
            if (node.kind() == SimNodeKind.JUNCTION || node.kind() == SimNodeKind.DEAD_END
                    || node.kind() == SimNodeKind.PUMP) continue;
            BlockPos nodePos = PipeGraphBuilder.posOf(entry.getKey());

            // Check if the node itself is a handler (handler-position node)
            var directHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, nodePos, null);
            if (directHandler != null) {
                handlers.add(new HandlerInfo(entry.getKey(), directHandler, nodePos));
                continue;
            }

            // Check neighbors (pipe-position nodes)
            boolean foundNeighbor = false;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = nodePos.relative(dir);
                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, neighbor, dir.getOpposite());
                if (handler != null) {
                    handlers.add(new HandlerInfo(entry.getKey(), handler, neighbor));
                    foundNeighbor = true;
                    break;
                }
            }

            // Open-ended pipes (SOURCE nodes) have no IFluidHandler at the air block.
            // Create a virtual handler that places/removes fluid blocks in the world,
            // but only if there's a real driving force (pump or gravity drop).
            // Tank fill pressure alone doesn't push through horizontal open pipes.
            if (!foundNeighbor && node.kind() == SimNodeKind.SOURCE) {
                boolean hasDrive = false;
                for (var n : network.nodes().values()) {
                    if (n.kind() == SimNodeKind.PUMP && n.staticPressure() > 0) {
                        hasDrive = true; break;
                    }
                }
                if (!hasDrive) {
                    for (var other : network.nodes().values()) {
                        if (other.kind() == SimNodeKind.TANK
                                && other.elevation() > node.elevation() + 0.5) {
                            hasDrive = true; break;
                        }
                    }
                }
                if (hasDrive) {
                    handlers.add(new HandlerInfo(entry.getKey(),
                            new OpenEndFluidHandler(level, nodePos, networkFluid), nodePos));
                }
            }
        }
        if (handlers.size() < 2) return false;

        // --- Determine the regime ---
        boolean hasPump = network.nodes().values().stream()
                .anyMatch(n -> n.kind() == SimNodeKind.PUMP);

        // Compute net flow direction per handler from sim flow rates.
        Map<NodeId, Float> flowScore = new HashMap<>();
        for (int i = 0; i < network.edges().size(); i++) {
            float rate = result.flowRates()[i];
            if (rate == 0) continue;
            SimEdge edge = network.edges().get(i);
            flowScore.merge(edge.a(), rate, Float::sum);
            flowScore.merge(edge.b(), -rate, Float::sum);
        }

        // Find src (highest outflow) and dst (highest inflow).
        handlers.sort((a, b) -> Float.compare(
                flowScore.getOrDefault(b.node, 0f),
                flowScore.getOrDefault(a.node, 0f)));

        HandlerInfo src = null;
        for (HandlerInfo h : handlers) {
            if (flowScore.getOrDefault(h.node, 0f) <= 0) continue;
            if (!h.handler.drain(networkFluid.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()) {
                src = h; break;
            }
        }

        HandlerInfo dst = null;
        for (int i = handlers.size() - 1; i >= 0; i--) {
            HandlerInfo h = handlers.get(i);
            if (src != null && h.pos.equals(src.pos)) continue;
            if (flowScore.getOrDefault(h.node, 0f) > 0) continue;
            if (h.handler.fill(networkFluid.copyWithAmount(1), FluidAction.SIMULATE) <= 0) continue;
            dst = h; break;
        }

        // --- Regime 1: Pump-driven (one-way push) ---
        // Move at the sim's flow rate. No vEq, no equalization target.
        // The sim's deltaPhi already encodes pump head vs destination back-pressure;
        // when the destination out-pressures the pump, deltaPhi → 0 and flow stops.
        if (hasPump && src != null && dst != null) {
            // Pump regime: use the true bottleneck (min of ALL edge rates).
            // A zero-rate edge means the pump can't push through that segment
            // (insufficient head for the pipe length). No transfer until all
            // edges in the circuit carry flow.
            if (bottleneck <= 0) return false;
            int amount = Math.max(1, (int) (bottleneck * elapsedTicks));
            return doTransfer(src, dst, networkFluid, amount);
        }

        // --- Regime 2: Gravity equalization (communicating vessels) ---
        // Target is equal surfaces. Use vEq to prevent overshoot, under-relaxed
        // for smooth convergence. When ΔΦ drops below EPS (sim reports zero flow),
        // bridge the deadband gap with a direct equalization step.
        if (!hasPump) {
            // Normal flow-driven transfer
            if (src != null && dst != null && bottleneck > 0) {
                int amount = Math.max(1, (int) (bottleneck * elapsedTicks));

                // vEq clamp: don't overshoot equilibrium
                float srcSens = tankSensitivity(level, src.pos, fluids);
                float dstSens = tankSensitivity(level, dst.pos, fluids);
                if (srcSens + dstSens > 0) {
                    float srcPhi = result.potentials().getOrDefault(src.node, 0f);
                    float dstPhi = result.potentials().getOrDefault(dst.node, 0f);
                    int vEq = Math.max(1,
                            (int) (Math.abs(srcPhi - dstPhi) / (srcSens + dstSens)));
                    amount = Math.min(amount, Math.max(1, (int) (vEq * 0.3f)));
                }

                return doTransfer(src, dst, networkFluid, amount);
            }

            // Deadband bridging: sim reports zero flow but same-elevation tanks
            // haven't converged (ΔΦ dropped below EPS before reaching equal fill).
            // Only for same-elevation pairs — gravity drops are handled by the sim
            // (elevation difference keeps ΔΦ above EPS until the source is empty).
            for (int i = 0; i < handlers.size(); i++) {
                for (int j = i + 1; j < handlers.size(); j++) {
                    HandlerInfo h1 = handlers.get(i), h2 = handlers.get(j);
                    SimNode n1 = network.node(h1.node), n2 = network.node(h2.node);
                    if (n1 == null || n2 == null) continue;
                    if (Math.abs(n1.elevation() - n2.elevation()) > 0.5) continue; // gravity pair
                    int f1 = h1.handler.getFluidInTank(0).getAmount();
                    int f2 = h2.handler.getFluidInTank(0).getAmount();
                    if (f1 == f2) continue;
                    float s1 = tankSensitivity(level, h1.pos, fluids);
                    float s2 = tankSensitivity(level, h2.pos, fluids);
                    if (s1 + s2 <= 0) continue;
                    int diff = Math.abs(f1 - f2);
                    if (diff <= 1) continue;
                    HandlerInfo hi = f1 > f2 ? h1 : h2;
                    HandlerInfo lo = f1 > f2 ? h2 : h1;
                    return doTransfer(hi, lo, networkFluid, diff / 2);
                }
            }
        }

        return false;
    }

    private static boolean doTransfer(HandlerInfo src, HandlerInfo dst,
                                       FluidStack networkFluid, int amount) {
        if (amount <= 0) return false;
        FluidStack drained = src.handler.drain(networkFluid.copyWithAmount(amount), FluidAction.SIMULATE);
        if (drained.isEmpty()) return false;
        int accepted = dst.handler.fill(drained, FluidAction.SIMULATE);
        if (accepted <= 0) return false;
        FluidStack actual = src.handler.drain(networkFluid.copyWithAmount(accepted), FluidAction.EXECUTE);
        if (actual.isEmpty()) return false;
        dst.handler.fill(actual, FluidAction.EXECUTE);
        return true;
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

    /**
     * How much Φ changes per mB of fluid added to the tank at pos.
     * Returns ρ·G·tankHeight/capacity for a tank, 0 for a non-tank (fixed Φ).
     */
    private static float tankSensitivity(Level level, BlockPos pos,
                                          Map<String, SimFluid> fluids) {
        float density = fluids.isEmpty() ? 1f : fluids.values().iterator().next().density();
        float G = PhysicsConfigFactory.simConfig().G();

        // Check pos itself and all neighbors for a tank
        for (BlockPos check : new BlockPos[]{pos,
                pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west()}) {
            var be = level.getBlockEntity(check);
            if (be instanceof FluidTankBlockEntity tankBE) {
                FluidTankAccessor acc = (FluidTankAccessor) tankBE;
                int height = acc.pipesnphysics$getHeight();
                int capacity = acc.pipesnphysics$getTankInventory().getCapacity();
                if (capacity <= 0) continue;
                return density * G * height / capacity;
            }
        }
        return 0; // not a tank → fixed Φ, no clamping
    }

    /**
     * Refresh PUMP node staticPressure and head from current pump speed.
     * Handles the case where the network was built before kinetic energy propagated.
     */
    private static void updatePumpSpeeds(Level level, FluidNetwork network) {
        for (var entry : network.nodes().entrySet()) {
            SimNode node = entry.getValue();
            if (node.kind() != SimNodeKind.PUMP) continue;
            BlockPos nodePos = PipeGraphBuilder.posOf(entry.getKey());
            var be = level.getBlockEntity(nodePos);
            if (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kbe) {
                float speed = Math.abs(kbe.getSpeed());
                if (speed != node.staticPressure()) {
                    entry.setValue(new SimNode(node.id(), node.kind(), node.elevation(),
                            speed, speed, node.pushSidePos()));
                }
            }
        }
    }



    private record HandlerInfo(NodeId node, IFluidHandler handler, BlockPos pos) {}

    /**
     * Virtual IFluidHandler for open-ended pipes. Accumulates partial fills and places
     * a fluid source block once a full bucket (1000 mB) is gathered. Mirrors Create's
     * OpenEndedPipe behavior of placing fluid blocks into the world.
     */
    private static class OpenEndFluidHandler implements IFluidHandler {
        private static final Map<BlockPos, Integer> accumulator = new HashMap<>();

        private final Level level;
        private final BlockPos pos;
        private final FluidStack fluidType;

        OpenEndFluidHandler(Level level, BlockPos pos, FluidStack fluidType) {
            this.level = level;
            this.pos = pos;
            this.fluidType = fluidType;
        }

        @Override public int getTanks() { return 1; }

        @Override public FluidStack getFluidInTank(int tank) {
            var state = level.getFluidState(pos);
            if (!state.isEmpty() && state.isSource()) {
                return new FluidStack(state.getType(), 1000);
            }
            return FluidStack.EMPTY;
        }

        @Override public int getTankCapacity(int tank) { return 1000; }

        @Override public boolean isFluidValid(int tank, FluidStack stack) { return true; }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return 0;
            if (!level.getFluidState(pos).isEmpty()) return 0; // already has fluid

            int spaceLeft = 1000 - accumulator.getOrDefault(pos, 0);
            int accept = Math.min(resource.getAmount(), spaceLeft);
            if (accept <= 0) return 0;

            if (action.execute()) {
                int total = accumulator.getOrDefault(pos, 0) + accept;
                if (total >= 1000) {
                    var fluidBlock = resource.getFluid().defaultFluidState().createLegacyBlock();
                    level.setBlockAndUpdate(pos, fluidBlock);
                    accumulator.remove(pos);
                } else {
                    accumulator.put(pos, total);
                }
            }
            return accept;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return drain(resource.getAmount(), action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (maxDrain < 1000) return FluidStack.EMPTY;
            var state = level.getFluidState(pos);
            if (state.isEmpty() || !state.isSource()) return FluidStack.EMPTY;
            FluidStack result = new FluidStack(state.getType(), 1000);
            if (action.execute()) {
                level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            return result;
        }
    }
}
