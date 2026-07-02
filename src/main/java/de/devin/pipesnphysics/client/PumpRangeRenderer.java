package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.net.PumpRangePayload;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Animated reach arrows along the pipes while a goggle-wearing player looks at a
 * pump (and for a configurable grace window afterwards). The paths come from the
 * server ({@link PumpRangeClient}); the visuals show where the pump's head can
 * reach: green arrows flowing out on the push side, blue arrows flowing in on the
 * pull side, red where the run is beyond the pump's reach.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class PumpRangeRenderer {
    private static final float PER_SEGMENT_SPEED = 1.5f;
    private static final float FADE_FRACTION = 0.2f;
    private static final int FULL_BRIGHTNESS = 0xF000F0;

    private static final float[] PUSH_COLOR = { 0.25f, 1.0f, 0.35f };
    private static final float[] PULL_COLOR = { 0.35f, 0.65f, 1.0f };
    private static final float[] STARVED_COLOR = { 1.0f, 0.18f, 0.1f };

    private static final MultiBufferSource.BufferSource OWN_BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(256));

    private PumpRangeRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_PARTICLES (not AFTER_TRANSLUCENT_BLOCKS) so the arrows draw AFTER the in-pipe fluid and
        // sit on top of it as an overlay instead of being hidden behind it.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PipesNPhysicsConfig.SHOW_PUMP_RANGE_ARROWS.get()) return;
        if (!GogglesItem.isWearingGoggles(mc.player)) return;

        long now = mc.level.getGameTime();
        if (mc.hitResult instanceof BlockHitResult blockHit
                && mc.hitResult.getType() == HitResult.Type.BLOCK
                && mc.level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof PumpBlock) {
            PumpRangeClient.looking(blockHit.getBlockPos(), now);
        }

        boolean preserve = PipesNPhysicsConfig.PRESERVE_PUMP_RANGE.get();
        int preserveTicks = PipesNPhysicsConfig.PUMP_RANGE_PRESERVE_SECONDS.get() * 20;
        PumpRangePayload payload = PumpRangeClient.active(now, preserve, preserveTicks);
        if (payload == null || payload.paths().isEmpty()) return;

        float fade = PumpRangeClient.preserveFraction(now, preserveTicks);
        renderArrows(event.getPoseStack(), mc, payload.paths(), Math.max(0.15f, fade));
    }

    private static void renderArrows(PoseStack poseStack, Minecraft mc,
                                     List<PumpRangePayload.RangePath> paths, float fade) {
        BakedModel model = mc.getModelManager().getModel(ClientEvents.ARROW_MODEL);
        if (model == null || model == mc.getModelManager().getMissingModel()) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float time = (AnimationTickHolder.getTicks() + AnimationTickHolder.getPartialTicks()) / 20f;
        float slide = (time * PER_SEGMENT_SPEED) % 1.0f;

        // A no-depth-WRITE type so the arrows don't reject the in-pipe fluid drawn on the same stage.
        VertexConsumer consumer = OWN_BUFFER.getBuffer(PnpRenderTypes.ARROWS);
        for (PumpRangePayload.RangePath path : paths) {
            List<Long> points = path.points();
            if (points.size() < 2) continue;
            for (int i = 0; i < points.size() - 1; i++) {
                BlockPos segFrom = BlockPos.of(points.get(path.pull() ? i + 1 : i));
                BlockPos segTo = BlockPos.of(points.get(path.pull() ? i : i + 1));
                float[] color = segmentColor(path, i);
                float alpha = segmentAlpha(path, i, slide) * fade;
                emitArrow(poseStack, mc, consumer, model, camera, segFrom, segTo, slide,
                        color[0], color[1], color[2], alpha);
            }
        }
        OWN_BUFFER.endBatch();
    }

    /** A segment is starved as soon as its far point is out of reach. */
    private static float[] segmentColor(PumpRangePayload.RangePath path, int segIndex) {
        boolean reachable = path.reachable().get(segIndex + 1);
        if (!reachable) return STARVED_COLOR;
        return path.pull() ? PULL_COLOR : PUSH_COLOR;
    }

    private static float segmentAlpha(PumpRangePayload.RangePath path, int segIndex, float slide) {
        int segments = path.points().size() - 1;
        int spawnSeg = path.pull() ? segments - 1 : 0;
        int despawnSeg = path.pull() ? 0 : segments - 1;
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
            case NORTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180));
            case WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(-90));
            case EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(90));
            case UP -> poseStack.mulPose(Axis.XP.rotationDegrees(-90));
            case DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(90));
            default -> {}
        }
    }
}
