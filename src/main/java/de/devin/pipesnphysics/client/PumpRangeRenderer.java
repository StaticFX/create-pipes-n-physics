package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.*;

/**
 * Renders animated arrow indicators along pipes connected to a pump
 * when the player wears goggles and looks at the pump.
 * The arrows travel the distance the pump can push/pull.
 */
public class PumpRangeRenderer {

    // Model is registered via ClientEvents.ARROW_MODEL

    /** Own buffer source so we don't flush Create's pipe fluid renders. */
    private static final MultiBufferSource.BufferSource OWN_BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(256));

    /** Cached pipe paths from the last traced pump. */
    private static List<PipePath> cachedPaths = Collections.emptyList();
    private static BlockPos cachedPumpPos = null;
    private static int cacheTickStamp = -1;
    private static final int CACHE_LIFETIME = 5;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (true) return; // DEBUG: disabled to test if mixin causes pipe fluid issue
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!GogglesItem.isWearingGoggles(mc.player)) {
            cachedPaths = Collections.emptyList();
            cachedPumpPos = null;
            return;
        }

        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            cachedPaths = Collections.emptyList();
            cachedPumpPos = null;
            return;
        }

        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos pumpPos = blockHit.getBlockPos();
        BlockState state = mc.level.getBlockState(pumpPos);

        if (!(state.getBlock() instanceof PumpBlock)) {
            cachedPaths = Collections.emptyList();
            cachedPumpPos = null;
            return;
        }

        // Refresh cache periodically
        int currentTick = AnimationTickHolder.getTicks();
        if (!pumpPos.equals(cachedPumpPos) || currentTick - cacheTickStamp > CACHE_LIFETIME) {
            cachedPaths = tracePipePaths(mc.level, pumpPos, state);
            cachedPumpPos = pumpPos;
            cacheTickStamp = currentTick;
        }

        if (cachedPaths.isEmpty()) return;

        renderArrows(event.getPoseStack(), mc, cachedPaths);
    }

    /**
     * Traces all pipe paths from a pump using BFS, mirroring Create's own algorithm.
     * Returns one path per connected branch.
     */
    private static List<PipePath> tracePipePaths(Level level, BlockPos pumpPos, BlockState pumpState) {
        Direction facing = pumpState.getValue(PumpBlock.FACING);
        List<PipePath> allPaths = new ArrayList<>();

        // Trace both sides of the pump
        for (Direction side : new Direction[]{facing, facing.getOpposite()}) {
            boolean isPull = side != facing; // pump pulls on back side

            BlockPos startPos = pumpPos.relative(side);
            FluidTransportBehaviour startPipe = FluidPropagator.getPipe(level, startPos);
            if (startPipe == null) continue;

            // BFS through pipe network with siphon-aware distance
            int maxDistance = FluidPropagator.getPumpRange();
            int pumpY = pumpPos.getY();
            List<BlockPos> orderedPositions = new ArrayList<>();
            orderedPositions.add(pumpPos);

            // Store (distance, pos) pairs like the mixin does
            Queue<int[]> frontier = new ArrayDeque<>(); // [distance, x, y, z]
            Set<BlockPos> visited = new HashSet<>();
            Map<BlockPos, BlockPos> parentMap = new LinkedHashMap<>();

            frontier.add(new int[]{1, startPos.getX(), startPos.getY(), startPos.getZ()});
            visited.add(pumpPos);
            parentMap.put(startPos, pumpPos);

            while (!frontier.isEmpty()) {
                int[] entry = frontier.poll();
                int distance = entry[0];
                BlockPos current = new BlockPos(entry[1], entry[2], entry[3]);
                if (visited.contains(current)) continue;
                visited.add(current);

                if (!level.isLoaded(current)) continue;
                BlockState currentState = level.getBlockState(current);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
                if (pipe == null) continue;

                orderedPositions.add(current);
                if (distance >= maxDistance) continue;

                for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                    BlockPos next = current.relative(face);
                    if (visited.contains(next)) continue;
                    if (FluidPropagator.getPipe(level, next) == null) continue;
                    parentMap.put(next, current);
                    int nextDist = nextDistance(distance, face, next, pumpY);
                    frontier.add(new int[]{nextDist, next.getX(), next.getY(), next.getZ()});
                }
            }

            if (orderedPositions.size() > 1) {
                // Build linear paths by following branches
                List<List<BlockPos>> branches = extractBranches(orderedPositions, parentMap, pumpPos);
                for (List<BlockPos> branch : branches) {
                    allPaths.add(new PipePath(branch, isPull));
                }
            }
        }

        return allPaths;
    }

    private static int nextDistance(int current, Direction face, BlockPos connectedPos, int pumpY) {
        if (face == Direction.DOWN) return current;
        if (face == Direction.UP && connectedPos.getY() <= pumpY) return current;
        if (face == Direction.UP) return current + 2;
        return current + 1;
    }

    /**
     * Extracts linear branches from the BFS tree.
     * Each branch is a path from the pump to a leaf node.
     */
    private static List<List<BlockPos>> extractBranches(List<BlockPos> positions, Map<BlockPos, BlockPos> parentMap, BlockPos root) {
        // Find leaf nodes (positions that are not parents of anything)
        Set<BlockPos> hasChildren = new HashSet<>(parentMap.values());
        List<BlockPos> leaves = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (!pos.equals(root) && !hasChildren.contains(pos)) {
                leaves.add(pos);
            }
        }

        // If no leaves found, the last position is the endpoint
        if (leaves.isEmpty() && positions.size() > 1) {
            leaves.add(positions.get(positions.size() - 1));
        }

        List<List<BlockPos>> branches = new ArrayList<>();
        for (BlockPos leaf : leaves) {
            List<BlockPos> branch = new ArrayList<>();
            BlockPos current = leaf;
            while (current != null) {
                branch.add(current);
                current = parentMap.get(current);
            }
            Collections.reverse(branch);
            if (branch.size() > 1) {
                branches.add(branch);
            }
        }
        return branches;
    }

    private static void renderArrows(PoseStack poseStack, Minecraft mc, List<PipePath> paths) {
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float partialTick = AnimationTickHolder.getPartialTicks();
        float time = (AnimationTickHolder.getTicks() + partialTick) / 20f;

        BakedModel model = mc.getModelManager()
                .getModel(ClientEvents.ARROW_MODEL);
        if (model == null || model == mc.getModelManager().getMissingModel()) return;

        VertexConsumer consumer = OWN_BUFFER.getBuffer(RenderType.translucent());

        for (PipePath path : paths) {
            if (path.positions.size() < 2) continue;

            float speed = 1.5f; // blocks per second
            // Fractional offset that slides all arrows in sync (0 to 1)
            float slide = (time * speed) % 1.0f;

            // One arrow per segment — each offset by the sliding fraction
            for (int i = 0; i < path.positions.size() - 1; i++) {
                BlockPos segFrom, segTo;
                if (path.isPull) {
                    // Pull: arrows move toward the pump (reverse path order)
                    segFrom = path.positions.get(i + 1);
                    segTo = path.positions.get(i);
                } else {
                    // Push: arrows move away from the pump (normal order)
                    segFrom = path.positions.get(i);
                    segTo = path.positions.get(i + 1);
                }

                double x = segFrom.getX() + (segTo.getX() - segFrom.getX()) * slide + 0.5;
                double y = segFrom.getY() + (segTo.getY() - segFrom.getY()) * slide + 0.5;
                double z = segFrom.getZ() + (segTo.getZ() - segFrom.getZ()) * slide + 0.5;

                // Arrow faces the direction of travel
                Direction dir = Direction.getNearest(
                        segTo.getX() - segFrom.getX(),
                        segTo.getY() - segFrom.getY(),
                        segTo.getZ() - segFrom.getZ()
                );

                poseStack.pushPose();
                poseStack.translate(x - camera.x - 0.5, y - camera.y - 0.5, z - camera.z - 0.5);

                // Rotate around the block center
                poseStack.translate(0.5, 0.5, 0.5);
                applyDirectionRotation(poseStack, dir);
                poseStack.translate(-0.5, -0.5, -0.5);

                for (BakedQuad quad : model.getQuads(null, null, mc.level.random)) {
                    consumer.putBulkData(poseStack.last(), quad, 0.4f, 0.9f, 1.0f, 1.0f,
                            0xF000F0, OverlayTexture.NO_OVERLAY);
                }

                poseStack.popPose();
            }
        }

        OWN_BUFFER.endBatch();
    }

    private static void applyDirectionRotation(PoseStack poseStack, Direction dir) {
        switch (dir) {
            case NORTH -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
            case WEST -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-90));
            case EAST -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));
            case UP -> poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(90));
            case DOWN -> poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90));
            default -> {} // SOUTH is default facing
        }
    }

    /** A traced pipe path with direction information. */
    private record PipePath(List<BlockPos> positions, boolean isPull) {}
}
