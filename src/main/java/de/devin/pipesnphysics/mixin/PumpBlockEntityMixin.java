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

import java.util.*;

/**
 * Makes pump range gravity-aware using siphon physics:
 * - Horizontal pipes cost 1 range (unchanged)
 * - Downward pipes cost 0 range (gravity-assisted)
 * - Upward pipes below pump Y cost 0 range (siphon effect — U-pipe recovery)
 * - Upward pipes above pump Y cost 2 range (working against gravity)
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

    private static int nextDistance(int current, Direction face, BlockPos connectedPos, int pumpY) {
        if (face == Direction.DOWN) return current;
        if (face == Direction.UP && connectedPos.getY() <= pumpY) return current;
        if (face == Direction.UP) return current + 2;
        return current + 1;
    }

    private static boolean hasReachedValidEndpoint(net.minecraft.world.level.LevelAccessor world, BlockFace face, boolean pull) {
        BlockPos connectedPos = face.getConnectedPos();
        BlockState connectedState = world.getBlockState(connectedPos);
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, connectedPos);

        if (pipe != null)
            return false;

        if (connectedState.getBlock() instanceof PumpBlock)
            return false;

        // Check for fluid handler capability
        if (world instanceof net.minecraft.world.level.Level level) {
            var handler = level.getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
                    connectedPos, face.getFace().getOpposite());
            if (handler != null)
                return true;
        }

        // Open-ended pipe
        return FluidPropagator.isOpenEnd(world, face.getPos(), face.getFace());
    }

    /**
     * @author PipesNPhysics
     * @reason Gravity-aware pump range: siphon physics for U-pipes
     */
    @Overwrite
    protected void distributePressureTo(Direction side) {
        PumpBlockEntity self = (PumpBlockEntity) (Object) this;

        if (self.getSpeed() == 0)
            return;

        BlockPos worldPosition = self.getBlockPos();
        int pumpY = worldPosition.getY();

        BlockFace start = new BlockFace(worldPosition, side);
        // Inline isFront + isPullingOnSide (both are simple)
        BlockState pumpState = self.getBlockState();
        Direction front = pumpState.getBlock() instanceof PumpBlock
                ? pumpState.getValue(PumpBlock.FACING) : side;
        boolean isFront = side == front;
        boolean pull = !isFront; // isPullingOnSide returns !front
        Set<BlockFace> targets = new HashSet<>();
        Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph = new HashMap<>();

        if (!pull)
            FluidPropagator.resetAffectedFluidNetworks(self.getLevel(), worldPosition, side.getOpposite());

        if (!hasReachedValidEndpoint(self.getLevel(), start, pull)) {

            pipeGraph.computeIfAbsent(worldPosition, $ -> Pair.of(0, new IdentityHashMap<>()))
                    .getSecond()
                    .put(side, pull);
            pipeGraph.computeIfAbsent(start.getConnectedPos(), $ -> Pair.of(1, new IdentityHashMap<>()))
                    .getSecond()
                    .put(side.getOpposite(), !pull);

            List<Pair<Integer, BlockPos>> frontier = new ArrayList<>();
            Set<BlockPos> visited = new HashSet<>();
            int maxDistance = FluidPropagator.getPumpRange();
            frontier.add(Pair.of(1, start.getConnectedPos()));

            while (!frontier.isEmpty()) {
                Pair<Integer, BlockPos> entry = frontier.remove(0);
                int distance = entry.getFirst();
                BlockPos currentPos = entry.getSecond();

                if (!self.getLevel().isLoaded(currentPos))
                    continue;
                if (visited.contains(currentPos))
                    continue;
                visited.add(currentPos);
                BlockState currentState = self.getLevel().getBlockState(currentPos);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(self.getLevel(), currentPos);
                if (pipe == null)
                    continue;

                for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                    BlockFace blockFace = new BlockFace(currentPos, face);
                    BlockPos connectedPos = blockFace.getConnectedPos();

                    if (!self.getLevel().isLoaded(connectedPos))
                        continue;
                    if (blockFace.isEquivalent(start))
                        continue;
                    if (hasReachedValidEndpoint(self.getLevel(), blockFace, pull)) {
                        pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                                .getSecond()
                                .put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(self.getLevel(), connectedPos);
                    if (pipeBehaviour == null)
                        continue;
                    if (pipeBehaviour.getClass().getSimpleName().equals("PumpFluidTransferBehaviour"))
                        continue;
                    if (visited.contains(connectedPos))
                        continue;

                    int next = nextDistance(distance, face, connectedPos, pumpY);
                    if (next >= maxDistance) {
                        pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                                .getSecond()
                                .put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                            .getSecond()
                            .put(face, pull);
                    pipeGraph.computeIfAbsent(connectedPos, $ -> Pair.of(next, new IdentityHashMap<>()))
                            .getSecond()
                            .put(face.getOpposite(), !pull);
                    frontier.add(Pair.of(next, connectedPos));
                }
            }
        }

        // DFS
        Map<Integer, Set<BlockFace>> validFaces = new HashMap<>();
        searchForEndpointRecursively(pipeGraph, targets, validFaces,
                new BlockFace(start.getPos(), start.getOppositeFace()), pull);

        float pressure = Math.abs(self.getSpeed());
        for (Set<BlockFace> set : validFaces.values()) {
            int parallelBranches = Math.max(1, set.size() - 1);
            for (BlockFace face : set) {
                BlockPos pipePos = face.getPos();
                Direction pipeSide = face.getFace();

                if (pipePos.equals(worldPosition))
                    continue;

                boolean inbound = pipeGraph.get(pipePos)
                        .getSecond()
                        .get(pipeSide);
                FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(self.getLevel(), pipePos);
                if (pipeBehaviour == null)
                    continue;

                pipeBehaviour.addPressure(pipeSide, inbound, pressure / parallelBranches);
            }
        }
    }
}
