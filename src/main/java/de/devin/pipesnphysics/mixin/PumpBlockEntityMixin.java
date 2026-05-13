package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.handler.PhysicsConfigFactory;
import de.devin.pipesnphysics.physics.PipeFormulas;
import org.spongepowered.asm.mixin.Unique;

import java.util.*;

/**
 * Gravity-aware pump pressure distribution.
 * Uses {@link PipeFormulas} for physics computation while keeping Create's
 * internal pipe graph structure for endpoint discovery.
 *
 * <p>Gravity assists downhill flow and penalizes uphill. Friction per segment
 * scales with pipe elevation angle. Pressure at each reachable node =
 * pumpBase + gravityAssist - friction.</p>
 */
@Mixin(value = PumpBlockEntity.class, remap = false)
public abstract class PumpBlockEntityMixin extends KineticBlockEntity {

    private PumpBlockEntityMixin() { super(null, null, null); }

    @Shadow
    protected abstract boolean searchForEndpointRecursively(
            Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph,
            Set<BlockFace> targets,
            Map<Integer, Set<BlockFace>> validFaces,
            BlockFace currentFace,
            boolean pull
    );

    @Unique
    private final Map<BlockPos, Float> pipesnphysics$frictionMap = new HashMap<>();
    @Unique
    private final Map<BlockPos, Float> pipesnphysics$pressureMap = new HashMap<>();

    @Unique
    private static boolean pipesnphysics$hasReachedValidEndpoint(net.minecraft.world.level.LevelAccessor world, BlockFace face) {
        BlockPos connectedPos = face.getConnectedPos();
        BlockState connectedState = world.getBlockState(connectedPos);
        if (FluidPropagator.getPipe(world, connectedPos) != null) return false;
        if (connectedState.getBlock() instanceof PumpBlock) return false;
        if (world instanceof net.minecraft.world.level.Level level) {
            var handler = level.getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
                    connectedPos, face.getFace().getOpposite());
            if (handler != null) return true;
        }
        return FluidPropagator.isOpenEnd(world, face.getPos(), face.getFace());
    }

    /**
     * @author PipesNPhysics
     * @reason Gravity-aware pump range with angle-based friction
     */
    @Overwrite
    protected void distributePressureTo(Direction side) {
        PumpBlockEntity self = (PumpBlockEntity) (Object) this;
        if (self.getSpeed() == 0) return;

        BlockPos worldPosition = self.getBlockPos();
        PipeFormulas formulas = new PipeFormulas(PhysicsConfigFactory.fromModConfig());
        float pumpBase = Math.abs(self.getSpeed());
        double pumpWorldY = SableCompat.getWorldY(self.getLevel(), worldPosition);
        pipesnphysics$frictionMap.clear();
        pipesnphysics$pressureMap.clear();

        BlockFace start = new BlockFace(worldPosition, side);
        BlockState pumpState = self.getBlockState();
        Direction front = pumpState.getBlock() instanceof PumpBlock
                ? pumpState.getValue(PumpBlock.FACING) : side;
        boolean pull = side != front;
        Set<BlockFace> targets = new HashSet<>();
        Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph = new HashMap<>();

        if (!pull)
            FluidPropagator.resetAffectedFluidNetworks(self.getLevel(), worldPosition, side.getOpposite());

        if (!pipesnphysics$hasReachedValidEndpoint(self.getLevel(), start)) {
            pipeGraph.computeIfAbsent(worldPosition, $ -> Pair.of(0, new IdentityHashMap<>()))
                    .getSecond().put(side, pull);
            pipeGraph.computeIfAbsent(start.getConnectedPos(), $ -> Pair.of(1, new IdentityHashMap<>()))
                    .getSecond().put(side.getOpposite(), !pull);

            List<Pair<Integer, BlockPos>> frontier = new ArrayList<>();
            Set<BlockPos> visited = new HashSet<>();
            // Pressure is the real range limiter (accounts for siphons).
            // Hop cap is just a safety limit to prevent infinite BFS.
            int maxDistance = 256;
            frontier.add(Pair.of(1, start.getConnectedPos()));

            float startElevation = SableCompat.getPipeElevation(self.getLevel(), worldPosition, side);
            pipesnphysics$frictionMap.put(start.getConnectedPos(), formulas.segmentFriction(startElevation));

            while (!frontier.isEmpty()) {
                Pair<Integer, BlockPos> frontierEntry = frontier.remove(0);
                int distance = frontierEntry.getFirst();
                BlockPos currentPos = frontierEntry.getSecond();
                if (!self.getLevel().isLoaded(currentPos)) continue;
                if (visited.contains(currentPos)) continue;
                visited.add(currentPos);

                BlockState currentState = self.getLevel().getBlockState(currentPos);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(self.getLevel(), currentPos);
                if (pipe == null) continue;

                for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                    BlockFace blockFace = new BlockFace(currentPos, face);
                    BlockPos connectedPos = blockFace.getConnectedPos();
                    if (!self.getLevel().isLoaded(connectedPos)) continue;
                    if (blockFace.isEquivalent(start)) continue;

                    if (pipesnphysics$hasReachedValidEndpoint(self.getLevel(), blockFace)) {
                        pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                                .getSecond().put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    FluidTransportBehaviour neighborPipe = FluidPropagator.getPipe(self.getLevel(), connectedPos);
                    if (neighborPipe == null) continue;
                    // String comparison: can't import PumpFluidTransferBehaviour (inner class of mixin target)
                    if (neighborPipe.getClass().getSimpleName().equals("PumpFluidTransferBehaviour")) continue;
                    if (visited.contains(connectedPos)) continue;

                    float parentFric = pipesnphysics$frictionMap.getOrDefault(currentPos, 0f);
                    float elevation = SableCompat.getPipeElevation(self.getLevel(), currentPos, face);
                    float nextFric = parentFric + formulas.segmentFriction(elevation);

                    double nodeY = SableCompat.getWorldY(self.getLevel(), connectedPos);
                    float nodePressure = formulas.pumpPressure(pumpBase, pumpWorldY, nodeY, nextFric);

                    int nextHopDistance = distance + 1;
                    if (nodePressure <= 0 || nextHopDistance > maxDistance) {
                        pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                                .getSecond().put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    pipesnphysics$frictionMap.put(connectedPos, nextFric);
                    pipesnphysics$pressureMap.put(connectedPos, nodePressure);
                    pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                            .getSecond().put(face, pull);
                    pipeGraph.computeIfAbsent(connectedPos, $ -> Pair.of(nextHopDistance, new IdentityHashMap<>()))
                            .getSecond().put(face.getOpposite(), !pull);
                    frontier.add(Pair.of(nextHopDistance, connectedPos));
                }
            }
        }

        // DFS to validate reachable endpoints
        Map<Integer, Set<BlockFace>> validFaces = new HashMap<>();
        searchForEndpointRecursively(pipeGraph, targets, validFaces,
                new BlockFace(start.getPos(), start.getOppositeFace()), pull);

        Set<BlockFace> allValidFaces = new HashSet<>();
        for (Set<BlockFace> set : validFaces.values()) {
            for (BlockFace face : set) {
                if (!face.getPos().equals(worldPosition))
                    allValidFaces.add(face);
            }
        }

        // Find the bottleneck pipe (most friction) and its gravity assist
        float maxFriction = 0;
        float gravityAtWorst = 0;
        for (BlockFace face : allValidFaces) {
            float fric = pipesnphysics$frictionMap.getOrDefault(face.getPos(), 0f);
            if (fric > maxFriction) {
                maxFriction = fric;
                float pressure = pipesnphysics$pressureMap.getOrDefault(face.getPos(), pumpBase);
                gravityAtWorst = pressure - pumpBase + fric;
            }
        }

        // Physics determines flow pressure: diminishing friction or vanilla-like
        float flowPressure = formulas.bottleneckFlowPressure(pumpBase, maxFriction, gravityAtWorst);

        // Apply uniform flow pressure to all reachable pipes (conservation of mass)
        for (BlockFace face : allValidFaces) {
            BlockPos pipePos = face.getPos();
            Direction pipeSide = face.getFace();
            boolean inbound = pipeGraph.get(pipePos).getSecond().get(pipeSide);
            FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(self.getLevel(), pipePos);
            if (pipeBehaviour != null) {
                pipeBehaviour.addPressure(pipeSide, inbound, flowPressure);
            }
        }
    }
}
