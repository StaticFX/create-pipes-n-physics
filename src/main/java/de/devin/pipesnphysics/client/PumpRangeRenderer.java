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
import de.devin.pipesnphysics.PipesNPhysicsConfig;
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PipesNPhysicsConfig.SHOW_PUMP_RANGE_ARROWS.get()) {
            cachedPaths = Collections.emptyList();
            cachedPumpPos = null;
            return;
        }
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

        // Refresh cache periodically
        int currentTick = AnimationTickHolder.getTicks();

        if (!(state.getBlock() instanceof PumpBlock)) {
            // Check if it's a pipe with active flow (has pressure AND fluid)
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(mc.level, pumpPos);
            if (pipe == null || !pipe.hasAnyPressure()) {
                cachedPaths = Collections.emptyList();
                cachedPumpPos = null;
                return;
            }
            boolean hasFlow = false;
            for (Direction side : Direction.values()) {
                var flow = pipe.getFlow(side);
                if (flow != null && !flow.fluid.isEmpty()) { hasFlow = true; break; }
            }
            if (!hasFlow) {
                cachedPaths = Collections.emptyList();
                cachedPumpPos = null;
                return;
            }
            // Only show for gravity-driven pipes (no pump in network)
            if (!pumpPos.equals(cachedPumpPos) || currentTick - cacheTickStamp > CACHE_LIFETIME) {
                cachedPaths = traceGravityPaths(mc.level, pumpPos);
                cachedPumpPos = pumpPos;
                cacheTickStamp = currentTick;
            }
        } else {
            if (!pumpPos.equals(cachedPumpPos) || currentTick - cacheTickStamp > CACHE_LIFETIME) {
                cachedPaths = tracePipePaths(mc.level, pumpPos, state);
                cachedPumpPos = pumpPos;
                cacheTickStamp = currentTick;
            }
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
            Map<BlockPos, Integer> distanceMap = new HashMap<>();

            distanceMap.put(pumpPos, 0);
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
                distanceMap.put(current, distance);
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
                    List<Integer> distances = new ArrayList<>();
                    for (BlockPos pos : branch) {
                        distances.add(distanceMap.getOrDefault(pos, 0));
                    }
                    allPaths.add(new PipePath(branch, distances, maxDistance, isPull, false));
                }
            }
        }

        return allPaths;
    }

    private static int nextDistance(int current, Direction face, BlockPos connectedPos, int pumpY) {
        if (face == Direction.DOWN) return current;
        if (face == Direction.UP && connectedPos.getY() <= pumpY) return current;
        if (face == Direction.UP) return current + PipesNPhysicsConfig.UPWARD_PIPE_COST.get();
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
        int mode = PipesNPhysicsConfig.ARROW_RENDER_MODE.get();
        if (mode == 1) {
            renderTravelingArrows(poseStack, mc, paths);
        } else {
            renderPerSegmentArrows(poseStack, mc, paths);
        }
    }

    /** Mode 0: one arrow per pipe segment, all sliding in sync. */
    private static void renderPerSegmentArrows(PoseStack poseStack, Minecraft mc, List<PipePath> paths) {
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float partialTick = AnimationTickHolder.getPartialTicks();
        float time = (AnimationTickHolder.getTicks() + partialTick) / 20f;

        BakedModel model = mc.getModelManager().getModel(ClientEvents.ARROW_MODEL);
        if (model == null || model == mc.getModelManager().getMissingModel()) return;

        VertexConsumer consumer = OWN_BUFFER.getBuffer(RenderType.translucent());

        for (PipePath path : paths) {
            if (path.positions.size() < 2) continue;

            float speed = 1.5f;
            float slide = (time * speed) % 1.0f;

            for (int i = 0; i < path.positions.size() - 1; i++) {
                BlockPos segFrom, segTo;
                if (path.isPull) {
                    segFrom = path.positions.get(i + 1);
                    segTo = path.positions.get(i);
                } else {
                    segFrom = path.positions.get(i);
                    segTo = path.positions.get(i + 1);
                }

                float[] color = pathColor(path, i);
                float alpha = segmentAlpha(path, i, slide);

                emitArrow(poseStack, mc, consumer, model, camera, segFrom, segTo, slide,
                        color[0], color[1], color[2], alpha);
            }
        }

        OWN_BUFFER.endBatch();
    }

    /**
     * Mode 1: single arrow starts at source, splits at branches.
     * Before the branch point: one arrow. After: one per branch.
     * Pull paths show arrows traveling from sinks back to source.
     */
    private static void renderTravelingArrows(PoseStack poseStack, Minecraft mc, List<PipePath> paths) {
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float partialTick = AnimationTickHolder.getPartialTicks();
        float time = (AnimationTickHolder.getTicks() + partialTick) / 20f;

        BakedModel model = mc.getModelManager().getModel(ClientEvents.ARROW_TRAVELING_MODEL);
        if (model == null || model == mc.getModelManager().getMissingModel()) return;

        VertexConsumer consumer = OWN_BUFFER.getBuffer(RenderType.translucent());

        if (paths.isEmpty()) { OWN_BUFFER.endBatch(); return; }

        // Separate push/gravity paths from pull paths (they travel opposite directions)
        List<PipePath> pushPaths = new ArrayList<>();
        List<PipePath> pullPaths = new ArrayList<>();
        for (PipePath p : paths) {
            if (p.isPull) pullPaths.add(p); else pushPaths.add(p);
        }

        renderTravelingGroup(poseStack, mc, consumer, model, camera, time, pushPaths, false);
        renderTravelingGroup(poseStack, mc, consumer, model, camera, time, pullPaths, true);

        OWN_BUFFER.endBatch();
    }

    private static void renderTravelingGroup(PoseStack poseStack, Minecraft mc, VertexConsumer consumer,
                                              BakedModel model, Vec3 camera, float time,
                                              List<PipePath> paths, boolean reverse) {
        if (paths.isEmpty()) return;

        // Find shared prefix length (common path before branches diverge)
        int prefixLen = paths.get(0).positions.size();
        for (PipePath p : paths) {
            int common = 0;
            int maxCheck = Math.min(prefixLen, p.positions.size());
            for (int i = 0; i < maxCheck; i++) {
                if (!p.positions.get(i).equals(paths.get(0).positions.get(i))) break;
                common = i + 1;
            }
            prefixLen = common;
        }

        // Longest path determines the cycle length
        int longestSegs = 0;
        for (PipePath p : paths) longestSegs = Math.max(longestSegs, p.positions.size() - 1);
        if (longestSegs < 1) return;

        float speed = 3.0f;
        float pathPos = (time * speed) % longestSegs;

        int prefixSegs = Math.max(0, prefixLen - 1); // shared segments before branch

        if (pathPos < prefixSegs) {
            // Arrow is still in the shared prefix — render one arrow
            PipePath ref = paths.get(0);
            renderArrowAtPosition(poseStack, mc, consumer, model, camera, ref, pathPos, reverse, longestSegs);
        } else {
            // Arrow has passed the branch point — render one per branch
            for (PipePath path : paths) {
                int segs = path.positions.size() - 1;
                if (pathPos >= segs) continue; // this branch is shorter, arrow already passed
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
            int ri = segs - 1 - segIndex;
            segFrom = path.positions.get(ri + 1);
            segTo = path.positions.get(ri);
            colorIndex = ri;
        } else {
            segFrom = path.positions.get(segIndex);
            segTo = path.positions.get(segIndex + 1);
            colorIndex = segIndex;
        }

        float[] color = pathColor(path, colorIndex);

        // Fade in at start, fade out at end
        float alpha = 1.0f;
        if (pathPos < 1.0f) alpha = Math.clamp(pathPos, 0.0f, 1.0f);
        else if (pathPos > segs - 1) alpha = Math.clamp(segs - pathPos, 0.0f, 1.0f);

        emitArrow(poseStack, mc, consumer, model, camera, segFrom, segTo, segFrac,
                color[0], color[1], color[2], alpha);
    }

    /** Computes color for a path segment based on distance and type. */
    private static float[] pathColor(PipePath path, int segIndex) {
        float t = Math.min(1.0f, (float) path.distances.get(segIndex + 1) / Math.max(1, path.maxDistance));
        if (path.isGravity) {
            return new float[]{0.2f * t, 0.4f * (1.0f - t), 1.0f - 0.5f * t};
        } else {
            return new float[]{t, 1.0f - t, 0.0f};
        }
    }

    /** Computes fade alpha for per-segment mode. */
    private static float segmentAlpha(PipePath path, int segIndex, float slide) {
        int spawnSeg = path.isPull ? path.positions.size() - 2 : 0;
        int despawnSeg = path.isPull ? 0 : path.positions.size() - 2;
        if (segIndex == spawnSeg) return Math.clamp(slide / 0.2f, 0.0f, 1.0f);
        if (segIndex == despawnSeg) return Math.clamp((1.0f - slide) / 0.2f, 0.0f, 1.0f);
        return 1.0f;
    }

    /** Emits a single arrow model at the interpolated position within a segment. */
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
                segTo.getZ() - segFrom.getZ()
        );

        poseStack.pushPose();
        poseStack.translate(x - camera.x - 0.5, y - camera.y - 0.5, z - camera.z - 0.5);
        poseStack.translate(0.5, 0.5, 0.5);
        applyDirectionRotation(poseStack, dir);
        poseStack.translate(-0.5, -0.5, -0.5);

        for (BakedQuad quad : model.getQuads(null, null, mc.level.random)) {
            consumer.putBulkData(poseStack.last(), quad, r, g, b, alpha,
                    0xF000F0, OverlayTexture.NO_OVERLAY);
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
            default -> {
                // SOUTH is default facing
            }
        }
    }

    /**
     * Traces gravity flow paths from a pipe. BFS to find the network,
     * identify the source (highest fluid handler), and trace paths downward.
     */
    private static List<PipePath> traceGravityPaths(Level level, BlockPos startPos) {
        // BFS to discover the full gravity network
        Set<BlockPos> network = new LinkedHashSet<>();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parentMap = new LinkedHashMap<>();
        frontier.add(startPos);

        BlockPos highestPipe = startPos;
        int highestY = startPos.getY();

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            if (!network.add(current)) continue;
            if (!level.isLoaded(current)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) { network.remove(current); continue; }

            // Track highest pipe as the source
            if (current.getY() > highestY) {
                highestY = current.getY();
                highestPipe = current;
            }

            BlockState currentState = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos neighbor = current.relative(face);
                if (network.contains(neighbor)) continue;
                if (level.getBlockState(neighbor).getBlock() instanceof PumpBlock) continue;
                if (FluidPropagator.getPipe(level, neighbor) != null) {
                    if (!parentMap.containsKey(neighbor)) parentMap.put(neighbor, current);
                    frontier.add(neighbor);
                }
            }
        }

        if (network.size() < 2) return Collections.emptyList();

        // BFS from the highest pipe (source) to build ordered paths
        Set<BlockPos> visited = new LinkedHashSet<>();
        Queue<BlockPos> bfs = new ArrayDeque<>();
        Map<BlockPos, BlockPos> flowParent = new LinkedHashMap<>();
        Map<BlockPos, Integer> distMap = new HashMap<>();
        bfs.add(highestPipe);
        distMap.put(highestPipe, 0);

        float friction = PipesNPhysicsConfig.PIPE_FRICTION_PER_BLOCK.get().floatValue();
        float maxPressure = PipesNPhysicsConfig.MAX_GRAVITY_PRESSURE.get().floatValue();
        int maxRange = friction > 0.001f ? (int)(maxPressure / friction) : 999;

        while (!bfs.isEmpty()) {
            BlockPos current = bfs.poll();
            if (!visited.add(current)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) continue;

            BlockState currentState = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos next = current.relative(face);
                if (!network.contains(next) || visited.contains(next)) continue;
                flowParent.put(next, current);
                distMap.put(next, distMap.getOrDefault(current, 0) + 1);
                bfs.add(next);
            }
        }

        // Extract branches from source to leaves
        Set<BlockPos> hasChildren = new HashSet<>(flowParent.values());
        List<BlockPos> leaves = new ArrayList<>();
        for (BlockPos pos : visited) {
            if (!pos.equals(highestPipe) && !hasChildren.contains(pos)) leaves.add(pos);
        }
        if (leaves.isEmpty() && visited.size() > 1) {
            List<BlockPos> visitedList = new ArrayList<>(visited);
            leaves.add(visitedList.get(visitedList.size() - 1));
        }

        List<PipePath> paths = new ArrayList<>();
        for (BlockPos leaf : leaves) {
            List<BlockPos> branch = new ArrayList<>();
            BlockPos cur = leaf;
            while (cur != null) {
                branch.add(cur);
                cur = flowParent.get(cur);
            }
            Collections.reverse(branch);
            if (branch.size() > 1) {
                List<Integer> distances = new ArrayList<>();
                for (BlockPos pos : branch) distances.add(distMap.getOrDefault(pos, 0));
                paths.add(new PipePath(branch, distances, maxRange, false, true));
            }
        }
        return paths;
    }

    /** A traced pipe path with direction and distance information. */
    private record PipePath(List<BlockPos> positions, List<Integer> distances, int maxDistance,
                            boolean isPull, boolean isGravity) {}
}
