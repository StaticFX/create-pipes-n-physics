package de.devin.pipesnphysics;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

/**
 * Handles gravity-driven fluid flow for pipe networks without pumps.
 * When a fluid source is connected via pipes to a sink at a lower Y level,
 * gravity pressure is applied to enable flow without a pump.
 */
public class GravityFlowHandler {

    private static final Set<ScheduledCheck> scheduledChecks = new HashSet<>();

    private record ScheduledCheck(Level level, BlockPos pos) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ScheduledCheck sc)) return false;
            return pos.equals(sc.pos) && level == sc.level;
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }

    /**
     * Called from the wipePressure mixin to schedule a gravity flow check.
     */
    public static void scheduleCheck(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        scheduledChecks.add(new ScheduledCheck(level, pos));
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

    /**
     * BFS the pipe network from the given position.
     * If no pump is found and there's a valid gravity flow path, apply pressure.
     */
    private static void processNetwork(Level level, BlockPos startPos, Set<BlockPos> alreadyProcessed) {
        // BFS to discover the full pipe network
        Set<BlockPos> networkPipes = new LinkedHashSet<>();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(startPos);

        boolean pumpFound = false;
        List<FluidEndpoint> endpoints = new ArrayList<>();

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            if (!networkPipes.add(current)) continue;
            if (!level.isLoaded(current)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) {
                networkPipes.remove(current);
                continue;
            }

            BlockState currentState = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos neighbor = current.relative(face);
                if (!level.isLoaded(neighbor)) continue;

                // Check for pump — active pumps disable gravity for the network,
                // stopped pumps (speed=0) are traversed as regular pipes
                BlockState neighborState = level.getBlockState(neighbor);
                if (neighborState.getBlock() instanceof PumpBlock) {
                    BlockEntity be = level.getBlockEntity(neighbor);
                    if (be instanceof KineticBlockEntity kbe && kbe.getSpeed() != 0) {
                        pumpFound = true;
                        continue;
                    }
                    // Stopped pump: fall through to pipe check so BFS can traverse through it
                }

                // Check for fluid handler (tank, etc.)
                var handler = level.getCapability(
                        Capabilities.FluidHandler.BLOCK, neighbor, face.getOpposite());
                if (handler != null) {
                    endpoints.add(new FluidEndpoint(neighbor, current, face));
                    continue;
                }

                // Check for another pipe
                if (FluidPropagator.getPipe(level, neighbor) != null) {
                    frontier.add(neighbor);
                    continue;
                }

                // Check for open-ended pipe (valid sink for gravity flow)
                if (FluidPropagator.isOpenEnd(level, current, face)) {
                    endpoints.add(new FluidEndpoint(neighbor, current, face));
                }
            }
        }

        // Mark all found pipes as processed
        alreadyProcessed.addAll(networkPipes);

        // If pump found, let the pump handle flow
        if (pumpFound) return;

        // If any pipe already has pressure (from pump or prior gravity), skip —
        // addPressure() accumulates, so applying twice would double the flow
        for (BlockPos pipePos : networkPipes) {
            FluidTransportBehaviour existingPipe = FluidPropagator.getPipe(level, pipePos);
            if (existingPipe != null && existingPipe.hasAnyPressure()) return;
        }

        // If fewer than 2 endpoints, no flow possible
        if (endpoints.size() < 2) return;

        // Source = highest endpoint, all others are potential sinks
        // Only consider endpoints where the handler is above its pipe in world space
        // (an upside-down tank can't gravity-drain — fluid pools away from the connection)
        FluidEndpoint source = null;
        double sourceWorldY = Double.NEGATIVE_INFINITY;

        for (FluidEndpoint ep : endpoints) {
            double handlerY = SableCompat.getWorldY(level, ep.handlerPos);
            double pipeY = SableCompat.getWorldY(level, ep.pipePos);
            // Handler must be above its pipe for gravity to pull fluid out
            if (handlerY <= pipeY) continue;
            if (handlerY > sourceWorldY) {
                source = ep;
                sourceWorldY = handlerY;
            }
        }

        if (source == null) return;

        List<FluidEndpoint> potentialSinks = new ArrayList<>();
        for (FluidEndpoint ep : endpoints) {
            if (ep != source) potentialSinks.add(ep);
        }

        if (potentialSinks.isEmpty()) return;

        applyGravityFlow(level, networkPipes, source, sourceWorldY, potentialSinks);
    }

    /**
     * Virtual-fill gravity flow with multi-sink support and branch splitting.
     * Each pipe's pressure = hydrostatic head (how far below source) minus friction,
     * split at branch points across valid exits.
     */
    private static void applyGravityFlow(Level level, Set<BlockPos> networkPipes,
                                          FluidEndpoint source, double sourceWorldY,
                                          List<FluidEndpoint> potentialSinks) {
        float gravityPerBlock = PipesNPhysicsConfig.GRAVITY_PRESSURE_PER_BLOCK.get().floatValue();
        float frictionPerBlock = PipesNPhysicsConfig.PIPE_FRICTION_PER_BLOCK.get().floatValue();
        float maxPressure = PipesNPhysicsConfig.MAX_GRAVITY_PRESSURE.get().floatValue();

        float deadZone = PipesNPhysicsConfig.GRAVITY_DEAD_ZONE.get().floatValue();

        // Choose strategy: angle-based for Sable sub-levels, height-based otherwise
        boolean onSubLevel = dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.getContaining(level, source.pipePos) != null;
        GravityFlowStrategy strategy = onSubLevel
                ? new GravityFlowStrategy.AngleBased(gravityPerBlock, frictionPerBlock, maxPressure, deadZone)
                : new GravityFlowStrategy.HeightBased(gravityPerBlock, frictionPerBlock, maxPressure, deadZone);

        // Step 2: BFS from source to build flow graph
        Map<BlockPos, Direction> parentDir = new LinkedHashMap<>();
        Map<BlockPos, Integer> pathLength = new HashMap<>();
        Map<BlockPos, Set<Direction>> outboundDirs = new HashMap<>();

        Queue<BlockPos> bfs = new ArrayDeque<>();
        bfs.add(source.pipePos);
        parentDir.put(source.pipePos, source.face);
        pathLength.put(source.pipePos, 0);

        while (!bfs.isEmpty()) {
            BlockPos current = bfs.poll();
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) continue;

            BlockState currentState = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos neighbor = current.relative(face);
                if (!networkPipes.contains(neighbor)) continue;
                if (parentDir.containsKey(neighbor)) continue;

                parentDir.put(neighbor, face.getOpposite());
                pathLength.put(neighbor, pathLength.get(current) + 1);
                outboundDirs.computeIfAbsent(current, k -> new HashSet<>()).add(face);
                bfs.add(neighbor);
            }
        }

        // Step 3: Identify valid sinks (positive pressure at their pipe)
        Map<BlockPos, List<FluidEndpoint>> sinksByPipe = new HashMap<>();
        for (FluidEndpoint sink : potentialSinks) {
            sinksByPipe.computeIfAbsent(sink.pipePos, k -> new ArrayList<>()).add(sink);
        }

        List<FluidEndpoint> validSinks = new ArrayList<>();
        for (FluidEndpoint sink : potentialSinks) {
            if (!pathLength.containsKey(sink.pipePos)) continue;

            float sinkPressure = strategy.computeSinkPressure(level, sourceWorldY, sink.pipePos,
                    pathLength.get(sink.pipePos), parentDir.get(sink.pipePos));
            if (sinkPressure > 0) {
                validSinks.add(sink);
            }
        }

        if (validSinks.isEmpty()) return;

        // Step 4: Prune dead branches — only keep pipes on paths to valid sinks
        Set<BlockPos> validPipes = new HashSet<>();
        Set<BlockPos> validSinkPipes = new HashSet<>();
        for (FluidEndpoint sink : validSinks) {
            validSinkPipes.add(sink.pipePos);
            BlockPos trace = sink.pipePos;
            while (trace != null && validPipes.add(trace)) {
                if (trace.equals(source.pipePos)) break;
                Direction toward = parentDir.get(trace);
                if (toward == null) break;
                trace = trace.relative(toward);
            }
        }

        // Step 5: Count exits per valid pipe
        Map<BlockPos, Integer> exitCount = new HashMap<>();
        for (BlockPos pos : validPipes) {
            int exits = 0;
            Set<Direction> outs = outboundDirs.getOrDefault(pos, Collections.emptySet());
            for (Direction d : outs) {
                if (validPipes.contains(pos.relative(d))) exits++;
            }
            // Sink endpoints adjacent to this pipe are also exits
            for (FluidEndpoint s : sinksByPipe.getOrDefault(pos, Collections.emptyList())) {
                if (validSinks.contains(s)) exits++;
            }
            exitCount.put(pos, Math.max(1, exits));
        }

        // Step 6: Pressure propagation BFS with virtual-fill hydrostatic model
        Map<BlockPos, Float> pipePressure = new LinkedHashMap<>();
        Map<BlockPos, Float> carriedPressure = new HashMap<>();
        Queue<BlockPos> pressureBFS = new ArrayDeque<>();

        // Angle strategy uses maxPressure as initial carry (each node computes its own pressure)
        carriedPressure.put(source.pipePos, onSubLevel ? maxPressure : Float.MAX_VALUE);
        pressureBFS.add(source.pipePos);

        while (!pressureBFS.isEmpty()) {
            BlockPos pos = pressureBFS.poll();
            if (!validPipes.contains(pos)) continue;
            if (pipePressure.containsKey(pos)) continue;

            float carried = carriedPressure.getOrDefault(pos, 0f);
            float effective = strategy.computeNodePressure(level, sourceWorldY, pos,
                    pathLength.get(pos), parentDir.get(pos), carried);

            if (effective <= 0) continue;

            pipePressure.put(pos, effective);

            int exits = exitCount.getOrDefault(pos, 1);
            float split = effective / exits;

            Set<Direction> outs = outboundDirs.getOrDefault(pos, Collections.emptySet());
            for (Direction d : outs) {
                BlockPos next = pos.relative(d);
                if (validPipes.contains(next) && !pipePressure.containsKey(next)) {
                    // Per-segment contribution (angle strategy accumulates, height passes through)
                    float segmentCarry = strategy.computeSegmentContribution(level, pos, next, d, split);
                    carriedPressure.put(next, segmentCarry);
                    pressureBFS.add(next);
                }
            }
        }

        // Step 7: Apply pressure to each valid pipe
        for (BlockPos pos : validPipes) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
            if (pipe == null) continue;

            float pressure = pipePressure.getOrDefault(pos, 0f);
            if (pressure <= 0) continue;

            // Inbound: direction toward source
            Direction inboundSide = parentDir.get(pos);
            if (inboundSide != null) {
                pipe.addPressure(inboundSide, true, pressure);
            }

            // Outbound: each direction toward valid downstream pipes
            Set<Direction> outs = outboundDirs.getOrDefault(pos, Collections.emptySet());
            for (Direction d : outs) {
                if (validPipes.contains(pos.relative(d))) {
                    pipe.addPressure(d, false, pressure);
                }
            }

            // Outbound: directions toward valid sink endpoints
            for (FluidEndpoint s : sinksByPipe.getOrDefault(pos, Collections.emptyList())) {
                if (validSinks.contains(s)) {
                    pipe.addPressure(s.face, false, pressure);
                }
            }
        }
    }

    private record FluidEndpoint(BlockPos handlerPos, BlockPos pipePos, Direction face) {}
}
