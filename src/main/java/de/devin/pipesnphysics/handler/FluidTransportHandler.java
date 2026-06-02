package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
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
public class FluidTransportHandler {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final Set<ScheduledCheck> scheduledChecks = new HashSet<>();

    private static final Map<DimPos, Long> lastProcessedTick = new HashMap<>();
    private static final Map<DimPos, Long> lastTransferTick = new HashMap<>();
    private static final Map<DimPos, FluidNetwork> cachedNetworks = new HashMap<>();
    private static final Set<DimPos> activeNetworks = new HashSet<>();
    private static final Map<DimPos, Integer> failedTransferCount = new HashMap<>();

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

    /** Returns true if a pipe at this position is part of a cached network. */
    public static boolean isManaged(Level level, BlockPos pos) {
        return cachedNetworks.containsKey(DimPos.of(level, pos));
    }

    /** Get the cached network for a position (for testing/debug). */
    public static FluidNetwork getCachedNetwork(Level level, BlockPos pos) {
        return cachedNetworks.get(DimPos.of(level, pos));
    }

    /**
     * Full network invalidation: clears the cached network so it's rebuilt from
     * scratch on the next tick. Use for real topology changes (pipe placed/removed,
     * sub-level rotation).
     */
    public static void clearCooldown(Level level, BlockPos pos) {
        DimPos key = DimPos.of(level, pos);
        lastProcessedTick.remove(key);
        FluidNetwork net = cachedNetworks.remove(key);
        if (net != null) {
            cachedNetworks.values().removeIf(n -> n == net);
        }
    }

    /**
     * Lightweight recheck: clears the tick cooldown so the network re-ticks
     * immediately, but preserves the cached network and all edge state
     * (phases, front positions, flow rates). Use for pressure/speed changes
     * that don't alter topology (wrench, pump speed change).
     */
    public static void scheduleRecheck(Level level, BlockPos pos) {
        lastProcessedTick.remove(DimPos.of(level, pos));
    }

    public static void clearAllCooldowns() {
        lastProcessedTick.clear();
        lastTransferTick.clear();
        scheduledChecks.clear();
        cachedNetworks.clear();
        activeNetworks.clear();
        failedTransferCount.clear();
        OpenEndFluidHandler.accumulator.clear();
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

        Set<DimPos> alreadyProcessed = new HashSet<>();
        for (ScheduledCheck check : toProcess) {
            DimPos dimPos = DimPos.of(check.level, check.pos);
            if (alreadyProcessed.contains(dimPos)) continue;
            if (!check.level.isLoaded(check.pos)) continue;
            processNetwork(check.level, check.pos, alreadyProcessed);
        }
    }

    private static void processNetwork(Level level, BlockPos startPos, Set<DimPos> alreadyProcessed) {
        if (!SableCompat.isSubLevelReady(level, startPos)) return;

        DimPos startDimPos = DimPos.of(level, startPos);
        long currentTick = level.getGameTime();
        Long lastTick = lastProcessedTick.get(startDimPos);
        int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
        // Active networks (nonzero flow) process every tick for smooth transfer.
        // Idle networks keep the longer recheckTicks interval to save CPU.
        // Check active state by canonical key (first node), not startPos, so all
        // pipes in the same network share one active/idle state.
        FluidNetwork cachedNet = cachedNetworks.get(startDimPos);
        DimPos activeKey = cachedNet != null
                ? DimPos.of(level, PipeGraphBuilder.posOf(cachedNet.nodes().keySet().iterator().next()))
                : startDimPos;
        int interval = activeNetworks.contains(activeKey) ? 1 : recheckTicks;
        if (lastTick != null && currentTick - lastTick < interval) return;

        // Reuse cached network or build new one.
        // Pumps are proper graph nodes (always in the topology regardless of speed),
        // so no stale-cache detection needed — updatePumpSpeeds refreshes each tick.
        SimConfig config = PhysicsConfigFactory.simConfig();
        FluidNetwork network = cachedNetworks.get(startDimPos);
        if (network == null) {
            network = NetworkBuilder.build(level, startPos, config);
            for (var entry : network.nodes().entrySet()) {
                cachedNetworks.put(DimPos.of(level, PipeGraphBuilder.posOf(entry.getKey())), network);
            }
            for (SimEdge edge : network.edges()) {
                for (BlockPos pos : edge.pipePositions()) {
                    cachedNetworks.put(DimPos.of(level, pos), network);
                }
            }
        }

        // Mark all pipes as processed
        for (SimEdge edge : network.edges()) {
            for (BlockPos pos : edge.pipePositions()) {
                DimPos dp = DimPos.of(level, pos);
                alreadyProcessed.add(dp);
                lastProcessedTick.put(dp, currentTick);
            }
        }
        for (var entry : network.nodes().entrySet()) {
            DimPos dp = DimPos.of(level, PipeGraphBuilder.posOf(entry.getKey()));
            alreadyProcessed.add(dp);
            lastProcessedTick.put(dp, currentTick);
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
        boolean pumpSpeedChanged = updatePumpSpeeds(level, network);

        // Snapshot front positions before tick — used to detect effectively stalled edges
        // (CHARGING but front didn't advance) for the keepActive decision.
        float[] prevFront = new float[network.edges().size()];
        for (int i = 0; i < network.edges().size(); i++) {
            prevFront[i] = network.edges().get(i).frontPos();
        }

        // Always run the simulator — it handles edge phase transitions even without fluid
        // (e.g. DRAINING edges need to tick even when no new fluid is available)
        FluidSimulator simulator = new FluidSimulator(config);
        SimResult result = simulator.tick(network, fluids);

        // Compute bottleneck: min of all non-zero edge rates across active edges.
        // Transfer starts once every edge in the circuit has completed at least one
        // charging cycle (i.e. has a non-zero flow rate). An active edge with rate=0
        // means its front hasn't reached the far end yet — circuit is incomplete.
        float bottleneck = 0;
        boolean circuitComplete = !network.edges().isEmpty();
        if (circuitComplete) {
            bottleneck = Float.MAX_VALUE;
            for (int i = 0; i < network.edges().size(); i++) {
                SimEdge edge = network.edges().get(i);
                if (edge.phase() == EdgePhase.EMPTY || edge.phase() == EdgePhase.DRAINING) {
                    circuitComplete = false;
                    break;
                }
                float abs = Math.abs(result.flowRates()[i]);
                if (abs == 0) { circuitComplete = false; break; }
                if (abs < bottleneck) bottleneck = abs;
            }
            if (!circuitComplete || bottleneck == Float.MAX_VALUE) bottleneck = 0;
        }

        // An edge is "animating" if it's DRAINING, or CHARGING and its front
        // actually advanced this tick. CHARGING edges whose front didn't move
        // are effectively stalled and shouldn't keep the network ticking.
        boolean hasAnimating = false;
        for (int i = 0; i < network.edges().size(); i++) {
            SimEdge edge = network.edges().get(i);
            if (edge.phase() == EdgePhase.DRAINING) { hasAnimating = true; break; }
            if (edge.phase() == EdgePhase.CHARGING && edge.frontPos() != prevFront[i]) {
                hasAnimating = true; break;
            }
        }

        // Apply visuals via Flow objects (never addPressure — it triggers wipe cycles).
        // Always call: Flow objects don't interfere with Create's transfer logic.
        CreatePipeRendering.applyVisuals(level, network, result, networkFluid);

        // Transfer fluid at boundary nodes.
        // Use a canonical key (first node) so elapsed is consistent regardless of which
        // pipe triggered processing — all pipes in the same network share one timer.
        DimPos transferKey = DimPos.of(level, PipeGraphBuilder.posOf(network.nodes().keySet().iterator().next()));
        Long lastTransfer = lastTransferTick.get(transferKey);
        int elapsed = lastTransfer != null ? (int) (currentTick - lastTransfer) : recheckTicks;
        elapsed = Math.max(1, Math.min(elapsed, 20));

        int transferResult = transferFluid(level, network, result, networkFluid, fluids, elapsed, bottleneck);
        boolean transferred = transferResult > 0;
        if (transferred) {
            LOGGER.info("Xfer n={} e={} rates={} elapsed={} active={}",
                    network.nodes().size(), network.edges().size(),
                    java.util.Arrays.toString(result.flowRates()),
                    elapsed, activeNetworks.contains(transferKey));
            lastTransferTick.put(transferKey, currentTick);
        }

        // Track consecutive failed transfers while circuit is complete AND flow rates
        // are valid (bottleneck > 0).
        //
        // Source dry (-1): force DRAINING — the circuit broke because there's no fluid.
        // Sink full (-2): DON'T drain — the pipe is pressurized, the sink is temporarily
        //   full (e.g. open end placed a block). De-activate the network instead.
        if (!transferred && circuitComplete && bottleneck > 0) {
            int fails = failedTransferCount.merge(transferKey, 1, Integer::sum);
            if (fails >= 5) {
                if (transferResult == -2) {
                    // Sink full: stay pressurized, just slow down ticking
                    activeNetworks.remove(transferKey);
                } else {
                    // Source dry or other failure: drain the circuit
                    for (SimEdge edge : network.edges()) {
                        if (edge.phase() == EdgePhase.FLOWING || edge.phase() == EdgePhase.CHARGING) {
                            edge.setPhase(EdgePhase.DRAINING);
                        }
                    }
                }
                failedTransferCount.remove(transferKey);
            }
        } else if (transferred) {
            failedTransferCount.remove(transferKey);
        }

        // Store breakdowns on pipe block entities for goggle display
        CreatePipeRendering.storeBreakdowns(level, network, result);

        // Recompute hasAnimating AFTER the drain counter may have set edges to DRAINING.
        // Edges forced to DRAINING by the drain counter are always animating.
        hasAnimating = false;
        for (int i = 0; i < network.edges().size(); i++) {
            SimEdge edge = network.edges().get(i);
            if (edge.phase() == EdgePhase.DRAINING) { hasAnimating = true; break; }
            if (edge.phase() == EdgePhase.CHARGING && edge.frontPos() != prevFront[i]) {
                hasAnimating = true; break;
            }
        }

        // A network stays active (ticks every tick) when anything is happening:
        // fluid moving, fronts advancing/draining, complete circuit, or pump speed changing.
        boolean hasFlow = false;
        for (float rate : result.flowRates()) {
            if (Math.abs(rate) > 0) { hasFlow = true; break; }
        }
        boolean keepActive = transferred || hasFlow || hasAnimating
                || circuitComplete || pumpSpeedChanged;

        if (keepActive) {
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
            if (node.kind() != SimNodeKind.SOURCE) continue;

            BlockPos nodePos = PipeGraphBuilder.posOf(entry.getKey());

            // Check if the node IS a tank (handler-position node)
            var directBE = level.getBlockEntity(nodePos);
            if (directBE instanceof FluidTankBlockEntity tankBE) {
                float pressure = TankPressureUtil.computeFillPressure(tankBE, density, config.G());
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

                float pressure = TankPressureUtil.computeFillPressure(tankBE2, density, config.G());
                entry.setValue(new SimNode(
                        node.id(), node.kind(), node.elevation(),
                        pressure, pressure));
                break;
            }
        }
    }

    /** Why a transfer attempt failed. */
    private enum TransferFailure { SOURCE_DRY, SINK_FULL, NO_CIRCUIT }

    /**
     * @return positive = amount transferred, 0 = no transfer, negative = failure reason
     *         (-1 = source dry, -2 = sink full, -3 = no circuit)
     */
    private static int transferFluid(Level level, FluidNetwork network, SimResult result,
                                      FluidStack networkFluid, Map<String, SimFluid> fluids,
                                      int elapsedTicks, float bottleneck) {
        if (networkFluid.isEmpty()) return -3;

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
                        if (other.kind() == SimNodeKind.SOURCE
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
        if (handlers.size() < 2) return -3;

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
        if (hasPump) {
            if (src == null) return -1;  // source dry
            if (dst == null) return -2;  // sink full
            // Pump regime: use the true bottleneck (min of ALL edge rates).
            // A zero-rate edge means the pump can't push through that segment
            // (insufficient head for the pipe length). No transfer until all
            // edges in the circuit carry flow.
            if (bottleneck <= 0) return 0;
            int amount = Math.max(1, (int) (bottleneck * elapsedTicks));
            return doTransfer(src, dst, networkFluid, amount) ? amount : -1;
        }

        // --- Regime 2: Gravity equalization (communicating vessels) ---
        // Transfer at the sim's per-tick flow rate (Q ∝ |ΔΦ|), which naturally
        // decays as tanks equalize. When ΔΦ drops below EPS, bridge the remaining
        // deadband gap with a small fixed step.
        if (!hasPump) {
            // Normal flow-driven transfer — use the sim's per-tick rate directly.
            // Active networks tick every game tick, so no elapsed multiplier needed.
            // The rate naturally decays as tanks equalize (Q ∝ |ΔΦ|).
            if (src != null && dst != null && bottleneck > 0) {
                int amount = Math.max(1, (int) bottleneck);
                return doTransfer(src, dst, networkFluid, amount) ? amount : 0;
            }

            // Deadband bridging: sim reports zero flow but same-elevation tanks
            // haven't converged (ΔΦ dropped below EPS before reaching equal fill).
            // Skip while any edge is still priming — bridging would equalize the
            // tanks before the visual front reaches the far end.
            boolean stillCharging = network.edges().stream()
                    .anyMatch(e -> e.phase() == EdgePhase.CHARGING);
            if (stillCharging) return 0;

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
                    return doTransfer(hi, lo, networkFluid, Math.min(diff / 2, 10)) ? 1 : 0;
                }
            }
        }

        return 0;
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
     * Refresh PUMP node pressure and head from current RPM.
     * Returns true if any pump's speed changed (caller should keep the network active).
     */
    private static boolean updatePumpSpeeds(Level level, FluidNetwork network) {
        SimConfig config = PhysicsConfigFactory.simConfig();
        boolean changed = false;
        for (var entry : network.nodes().entrySet()) {
            SimNode node = entry.getValue();
            if (node.kind() != SimNodeKind.PUMP) continue;
            BlockPos nodePos = PipeGraphBuilder.posOf(entry.getKey());
            var be = level.getBlockEntity(nodePos);
            if (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kbe) {
                float head = Math.abs(kbe.getSpeed()) * config.speedToHead();
                if (head != node.staticPressure()) {
                    entry.setValue(new SimNode(node.id(), node.kind(), node.elevation(),
                            head, head));
                    changed = true;
                }
            }
        }
        return changed;
    }

    private record HandlerInfo(NodeId node, IFluidHandler handler, BlockPos pos) {}

    /**
     * Virtual IFluidHandler for open-ended pipes. Accumulates partial fills and places
     * a fluid source block once a full bucket (1000 mB) is gathered. Mirrors Create's
     * OpenEndedPipe behavior of placing fluid blocks into the world.
     */
    private static class OpenEndFluidHandler implements IFluidHandler {
        private static final Map<DimPos, Integer> accumulator = new HashMap<>();

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

            DimPos key = DimPos.of(level, pos);
            int spaceLeft = 1000 - accumulator.getOrDefault(key, 0);
            int accept = Math.min(resource.getAmount(), spaceLeft);
            if (accept <= 0) return 0;

            if (action.execute()) {
                int total = accumulator.getOrDefault(key, 0) + accept;
                if (total >= 1000) {
                    var fluidBlock = resource.getFluid().defaultFluidState().createLegacyBlock();
                    level.setBlockAndUpdate(pos, fluidBlock);
                    accumulator.remove(key);
                } else {
                    accumulator.put(key, total);
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
