package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.handler.PipeGraphBuilder;
import de.devin.pipesnphysics.handler.PhysicsConfigFactory;
import de.devin.pipesnphysics.physics.*;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.*;

/**
 * Renders animated arrow indicators along pipes when the player wears goggles.
 * Uses {@link PipeGraphBuilder} for network discovery and {@link NetworkSolver}
 * for gravity flow paths, eliminating duplicated BFS logic.
 */
public class PumpRangeRenderer {

    private static final float PER_SEGMENT_SPEED = 1.5f;
    private static final float TRAVELING_SPEED = 3.0f;
    private static final float FADE_FRACTION = 0.2f;
    private static final int FULL_BRIGHTNESS = 0xF000F0;

    private static final MultiBufferSource.BufferSource OWN_BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(256));

    private static List<PipePath> cachedPaths = Collections.emptyList();
    private static BlockPos cachedPumpPos = null;
    private static int cacheTickStamp = -1;
    private static final int CACHE_LIFETIME = 5;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PipesNPhysicsConfig.SHOW_PUMP_RANGE_ARROWS.get()) {
            clearCache();
            return;
        }
        if (!GogglesItem.isWearingGoggles(mc.player)) {
            clearCache();
            return;
        }

        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            clearCache();
            return;
        }

        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos targetPos = blockHit.getBlockPos();
        BlockState state = mc.level.getBlockState(targetPos);
        int currentTick = AnimationTickHolder.getTicks();

        if (!(state.getBlock() instanceof PumpBlock)) {
            // Looking at a pipe — check if it has active flow
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(mc.level, targetPos);
            if (pipe == null || !pipe.hasAnyPressure()) { clearCache(); return; }
            boolean hasFlow = false;
            for (Direction side : Direction.values()) {
                var flow = pipe.getFlow(side);
                if (flow != null && !flow.fluid.isEmpty()) { hasFlow = true; break; }
            }
            if (!hasFlow) { clearCache(); return; }

            if (!targetPos.equals(cachedPumpPos) || currentTick - cacheTickStamp > CACHE_LIFETIME) {
                cachedPaths = traceFlowPaths(mc.level, targetPos);
                cachedPumpPos = targetPos;
                cacheTickStamp = currentTick;
            }
        } else {
            // Looking at a pump
            if (!targetPos.equals(cachedPumpPos) || currentTick - cacheTickStamp > CACHE_LIFETIME) {
                cachedPaths = tracePumpPaths(mc.level, targetPos, state);
                cachedPumpPos = targetPos;
                cacheTickStamp = currentTick;
            }
        }

        if (cachedPaths.isEmpty()) return;
        renderArrows(event.getPoseStack(), mc, cachedPaths);
    }

    private static void clearCache() {
        cachedPaths = Collections.emptyList();
        cachedPumpPos = null;
    }

    /**
     * Trace flow paths from a pipe. Uses the graph builder to discover the network,
     * then checks for pump or gravity flow.
     */
    private static List<PipePath> traceFlowPaths(Level level, BlockPos pipePos) {
        PipeGraph graph = PipeGraphBuilder.discover(level, pipePos);

        if (graph.hasActivePump()) {
            // Find the pump node and trace from there
            for (var entry : graph.nodes().entrySet()) {
                if (entry.getValue().isPump()) {
                    BlockPos pumpPos = PipeGraphBuilder.posOf(entry.getKey());
                    return tracePumpPaths(level, pumpPos, level.getBlockState(pumpPos));
                }
            }
        }

        // Gravity-driven: use the solver to get flow paths
        return traceGravityPaths(graph);
    }

    /**
     * Trace gravity flow paths using the NetworkSolver.
     * Extracts branch paths from the solver's flow parent map.
     */
    private static List<PipePath> traceGravityPaths(PipeGraph graph) {
        PipeFormulas formulas = new PipeFormulas(PhysicsConfigFactory.fromModConfig());
        NetworkSolver solver = new NetworkSolver(formulas);
        GravityFlowResult flow = solver.solveGravityFlow(graph);
        if (flow == null) return Collections.emptyList();

        // Convert flowParent (NodeId → NodeId) to BlockPos parent map
        Map<BlockPos, BlockPos> parentMap = new LinkedHashMap<>();
        List<BlockPos> orderedPipes = new ArrayList<>();
        BlockPos sourcePos = PipeGraphBuilder.posOf(flow.source().pipeNode());
        orderedPipes.add(sourcePos);

        for (NodeId nodeId : flow.activePipes()) {
            BlockPos pos = PipeGraphBuilder.posOf(nodeId);
            orderedPipes.add(pos);
            NodeId parentId = flow.flowParent().get(nodeId);
            if (parentId != null) {
                parentMap.put(pos, PipeGraphBuilder.posOf(parentId));
            }
        }

        // Compute max visual range for color gradient
        float frictionPB = formulas.config().frictionPerBlock();
        float maxPressure = formulas.config().maxPressure();
        int maxRange = frictionPB > 0.001f ? (int) (maxPressure / frictionPB) : 999;

        // Extract branches and build PipePaths
        List<List<BlockPos>> branches = extractBranches(orderedPipes, parentMap, sourcePos);
        List<PipePath> paths = new ArrayList<>();
        for (List<BlockPos> branch : branches) {
            List<Integer> distances = new ArrayList<>();
            for (int i = 0; i < branch.size(); i++) distances.add(i);
            paths.add(new PipePath(branch, distances, maxRange, false, true));
        }
        return paths;
    }

    /**
     * Trace pump paths. Uses a simplified BFS with visual distances for arrow rendering.
     * The pump's own BFS is kept because it uses a custom visual distance formula
     * (down=free, up=costly) that differs from the physics solver.
     */
    private static List<PipePath> tracePumpPaths(Level level, BlockPos pumpPos, BlockState pumpState) {
        Direction facing = pumpState.getValue(PumpBlock.FACING);
        PipeFormulas formulas = new PipeFormulas(PhysicsConfigFactory.fromModConfig());
        List<PipePath> allPaths = new ArrayList<>();

        for (Direction side : new Direction[]{facing, facing.getOpposite()}) {
            boolean isPull = side != facing;

            BlockPos startPos = pumpPos.relative(side);
            if (FluidPropagator.getPipe(level, startPos) == null) continue;

            float pumpSpeed = 0;
            BlockEntity be = level.getBlockEntity(pumpPos);
            if (be instanceof KineticBlockEntity kbe)
                pumpSpeed = Math.abs(kbe.getSpeed());

            PipeGraph graph = PipeGraphBuilder.discover(level, startPos);
            NodeId startNode = PipeGraphBuilder.nodeOf(startPos);
            double pumpWorldY = startPos.getY() + 0.5;
            int startFace = side.ordinal();

            NetworkSolver solver = new NetworkSolver(formulas);
            PumpFlowResult result = solver.solvePumpReach(graph, startNode, startFace,
                    pumpSpeed, pumpWorldY, 256);

            List<BlockPos> orderedPositions = new ArrayList<>();
            orderedPositions.add(pumpPos);
            Map<BlockPos, BlockPos> parentMap = new LinkedHashMap<>();
            Map<BlockPos, Integer> distanceMap = new HashMap<>();
            distanceMap.put(pumpPos, 0);

            for (NodeId nodeId : result.reachableNodes()) {
                BlockPos pos = PipeGraphBuilder.posOf(nodeId);
                orderedPositions.add(pos);
                distanceMap.put(pos, result.hopCounts().getOrDefault(nodeId, 1));
            }

            // Build parent map from graph adjacency
            Set<BlockPos> visited = new HashSet<>();
            visited.add(pumpPos);
            Queue<BlockPos> bfs = new ArrayDeque<>();
            bfs.add(startPos);
            parentMap.put(startPos, pumpPos);
            while (!bfs.isEmpty()) {
                BlockPos current = bfs.poll();
                if (!visited.add(current)) continue;
                NodeId currentId = PipeGraphBuilder.nodeOf(current);
                if (!result.reachableNodes().contains(currentId) && !current.equals(startPos)) continue;
                for (PipeEdge edge : graph.adjacency().getOrDefault(currentId, List.of())) {
                    BlockPos next = PipeGraphBuilder.posOf(edge.to());
                    if (visited.contains(next)) continue;
                    if (!result.reachableNodes().contains(edge.to())) continue;
                    parentMap.put(next, current);
                    bfs.add(next);
                }
            }

            if (orderedPositions.size() > 1) {
                int maxDist = result.hopCounts().values().stream().mapToInt(i -> i).max().orElse(1);
                List<List<BlockPos>> branches = extractBranches(orderedPositions, parentMap, pumpPos);
                for (List<BlockPos> branch : branches) {
                    List<Integer> distances = new ArrayList<>();
                    for (BlockPos pos : branch) distances.add(distanceMap.getOrDefault(pos, 0));
                    allPaths.add(new PipePath(branch, distances, maxDist, isPull, false));
                }
            }
        }
        return allPaths;
    }

    private static List<List<BlockPos>> extractBranches(List<BlockPos> positions,
                                                        Map<BlockPos, BlockPos> parentMap, BlockPos root) {
        Set<BlockPos> hasChildren = new HashSet<>(parentMap.values());
        List<BlockPos> leaves = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (!pos.equals(root) && !hasChildren.contains(pos)) leaves.add(pos);
        }
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
            if (branch.size() > 1) branches.add(branch);
        }
        return branches;
    }

    private static void renderArrows(PoseStack poseStack, Minecraft mc, List<PipePath> paths) {
        int mode = PipesNPhysicsConfig.ARROW_RENDER_MODE.get();
        if (mode == 1) renderTravelingArrows(poseStack, mc, paths);
        else renderPerSegmentArrows(poseStack, mc, paths);
    }

    private static void renderPerSegmentArrows(PoseStack poseStack, Minecraft mc, List<PipePath> paths) {
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float partialTick = AnimationTickHolder.getPartialTicks();
        float time = (AnimationTickHolder.getTicks() + partialTick) / 20f;

        BakedModel model = mc.getModelManager().getModel(ClientEvents.ARROW_MODEL);
        if (model == null || model == mc.getModelManager().getMissingModel()) return;

        VertexConsumer consumer = OWN_BUFFER.getBuffer(RenderType.translucent());
        for (PipePath path : paths) {
            if (path.positions.size() < 2) continue;
            float slide = (time * PER_SEGMENT_SPEED) % 1.0f;
            for (int i = 0; i < path.positions.size() - 1; i++) {
                BlockPos segFrom = path.isPull ? path.positions.get(i + 1) : path.positions.get(i);
                BlockPos segTo = path.isPull ? path.positions.get(i) : path.positions.get(i + 1);
                float[] color = pathColor(path, i);
                float alpha = segmentAlpha(path, i, slide);
                emitArrow(poseStack, mc, consumer, model, camera, segFrom, segTo, slide,
                        color[0], color[1], color[2], alpha);
            }
        }
        OWN_BUFFER.endBatch();
    }

    private static void renderTravelingArrows(PoseStack poseStack, Minecraft mc, List<PipePath> paths) {
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float partialTick = AnimationTickHolder.getPartialTicks();
        float time = (AnimationTickHolder.getTicks() + partialTick) / 20f;

        BakedModel model = mc.getModelManager().getModel(ClientEvents.ARROW_TRAVELING_MODEL);
        if (model == null || model == mc.getModelManager().getMissingModel()) return;

        VertexConsumer consumer = OWN_BUFFER.getBuffer(RenderType.translucent());
        if (paths.isEmpty()) { OWN_BUFFER.endBatch(); return; }

        List<PipePath> pushPaths = new ArrayList<>(), pullPaths = new ArrayList<>();
        for (PipePath path : paths) { if (path.isPull) pullPaths.add(path); else pushPaths.add(path); }

        renderTravelingGroup(poseStack, mc, consumer, model, camera, time, pushPaths, false);
        renderTravelingGroup(poseStack, mc, consumer, model, camera, time, pullPaths, true);
        OWN_BUFFER.endBatch();
    }

    private static void renderTravelingGroup(PoseStack poseStack, Minecraft mc, VertexConsumer consumer,
                                              BakedModel model, Vec3 camera, float time,
                                              List<PipePath> paths, boolean reverse) {
        if (paths.isEmpty()) return;
        int prefixLen = paths.get(0).positions.size();
        for (PipePath path : paths) {
            int commonPrefixLength = 0;
            int maxCheck = Math.min(prefixLen, path.positions.size());
            for (int i = 0; i < maxCheck; i++) {
                if (!path.positions.get(i).equals(paths.get(0).positions.get(i))) break;
                commonPrefixLength = i + 1;
            }
            prefixLen = commonPrefixLength;
        }

        int longestSegs = 0;
        for (PipePath path : paths) longestSegs = Math.max(longestSegs, path.positions.size() - 1);
        if (longestSegs < 1) return;

        float pathPos = (time * TRAVELING_SPEED) % longestSegs;
        int prefixSegs = Math.max(0, prefixLen - 1);

        if (pathPos < prefixSegs) {
            renderArrowAtPosition(poseStack, mc, consumer, model, camera, paths.get(0), pathPos, reverse, longestSegs);
        } else {
            for (PipePath path : paths) {
                int segs = path.positions.size() - 1;
                if (pathPos >= segs) continue;
                renderArrowAtPosition(poseStack, mc, consumer, model, camera, path, pathPos, reverse, longestSegs);
            }
        }
    }

    private static void renderArrowAtPosition(PoseStack poseStack, Minecraft mc, VertexConsumer consumer,
                                               BakedModel model, Vec3 camera, PipePath path,
                                               float pathPos, boolean reverse, int totalSegs) {
        int segs = path.positions.size() - 1;
        int segIndex = Math.min((int) pathPos, segs - 1);
        float segFrac = pathPos - segIndex;

        BlockPos segFrom, segTo;
        int colorIndex;
        if (reverse) {
            int reverseIndex = segs - 1 - segIndex;
            segFrom = path.positions.get(reverseIndex + 1);
            segTo = path.positions.get(reverseIndex);
            colorIndex = reverseIndex;
        } else {
            segFrom = path.positions.get(segIndex);
            segTo = path.positions.get(segIndex + 1);
            colorIndex = segIndex;
        }

        float[] color = pathColor(path, colorIndex);
        float alpha = 1.0f;
        if (pathPos < 1.0f) alpha = Math.clamp(pathPos, 0.0f, 1.0f);
        else if (pathPos > segs - 1) alpha = Math.clamp(segs - pathPos, 0.0f, 1.0f);

        emitArrow(poseStack, mc, consumer, model, camera, segFrom, segTo, segFrac,
                color[0], color[1], color[2], alpha);
    }

    private static float[] pathColor(PipePath path, int segIndex) {
        float t = Math.min(1.0f, (float) path.distances.get(segIndex + 1) / Math.max(1, path.maxDistance));
        if (path.isGravity) return new float[]{0.2f * t, 0.4f * (1.0f - t), 1.0f - 0.5f * t};
        else return new float[]{t, 1.0f - t, 0.0f};
    }

    private static float segmentAlpha(PipePath path, int segIndex, float slide) {
        int spawnSeg = path.isPull ? path.positions.size() - 2 : 0;
        int despawnSeg = path.isPull ? 0 : path.positions.size() - 2;
        if (segIndex == spawnSeg) return Math.clamp(slide / FADE_FRACTION, 0.0f, 1.0f);
        if (segIndex == despawnSeg) return Math.clamp((1.0f - slide) / FADE_FRACTION, 0.0f, 1.0f);
        return 1.0f;
    }

    private static void emitArrow(PoseStack poseStack, Minecraft mc, VertexConsumer consumer,
                                   BakedModel model, Vec3 camera,
                                   BlockPos segFrom, BlockPos segTo, float frac,
                                   float r, float g, float b, float alpha) {
        double x = segFrom.getX() + (segTo.getX() - segFrom.getX()) * frac + 0.5;
        double y = segFrom.getY() + (segTo.getY() - segFrom.getY()) * frac + 0.5;
        double z = segFrom.getZ() + (segTo.getZ() - segFrom.getZ()) * frac + 0.5;

        Direction dir = Direction.getNearest(
                segTo.getX() - segFrom.getX(),
                segTo.getY() - segFrom.getY(),
                segTo.getZ() - segFrom.getZ());

        poseStack.pushPose();
        poseStack.translate(x - camera.x - 0.5, y - camera.y - 0.5, z - camera.z - 0.5);
        poseStack.translate(0.5, 0.5, 0.5);
        applyDirectionRotation(poseStack, dir);
        poseStack.translate(-0.5, -0.5, -0.5);

        for (BakedQuad quad : model.getQuads(null, null, mc.level.random)) {
            consumer.putBulkData(poseStack.last(), quad, r, g, b, alpha,
                    FULL_BRIGHTNESS, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
    }

    private static void applyDirectionRotation(PoseStack poseStack, Direction dir) {
        switch (dir) {
            case NORTH -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
            case WEST -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-90));
            case EAST -> poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));
            case UP -> poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90));
            case DOWN -> poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(90));
            default -> {}
        }
    }

    private record PipePath(List<BlockPos> positions, List<Integer> distances, int maxDistance,
                            boolean isPull, boolean isGravity) {}
}
