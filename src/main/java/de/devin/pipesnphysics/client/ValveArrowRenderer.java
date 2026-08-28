package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SubLevelFrame;
import de.devin.pipesnphysics.engine.valve.ValveDirectionBehaviour;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * A sliding arrow through every ONE-WAY valve, showing the direction it lets fluid pass — the
 * dial on the block is only an icon, so the world carries the arrow. Shown while the player
 * wears goggles OR holds a wrench (the "show me the engineering" signals; scanning a wall of
 * valves shows every direction at once), for the valves {@link ValveArrowClient} tracks within
 * a short range. The behaviour is re-resolved per frame, so a broken, unloaded, or dialed-back
 * valve drops out on its own.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class ValveArrowRenderer {
    /** Full slides across the valve per second; a calm drift, not the pump arrows' rush. */
    private static final float SLIDE_SPEED = 0.75f;
    /** Fraction of the slide spent fading in/out at the valve's rims. */
    private static final float FADE_FRACTION = 0.25f;
    private static final double MAX_DISTANCE_SQ = 48 * 48;
    private static final float[] COLOR = { 0.25f, 1.0f, 0.35f }; // the pump PUSH green: "flow goes this way"

    private static final MultiBufferSource.BufferSource OWN_BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(256));

    private ValveArrowRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_PARTICLES like the pump reach sleeve: draw over the in-pipe fluid, not behind it.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PipesNPhysicsConfig.SHOW_VALVE_DIRECTION_ARROWS.get()) return;
        if (!engineerMode(mc.player)) return;

        BakedModel model = ArrowRender.model(mc);
        if (model == null) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float time = (AnimationTickHolder.getTicks() + AnimationTickHolder.getPartialTicks()) / 20f;
        float slide = (time * SLIDE_SPEED) % 1.0f;
        float alpha = Math.min(slide / FADE_FRACTION, (1.0f - slide) / FADE_FRACTION);
        alpha = Math.clamp(alpha, 0.0f, 1.0f) * 0.9f;

        VertexConsumer consumer = OWN_BUFFER.getBuffer(PnpRenderTypes.ARROWS);
        PoseStack poseStack = event.getPoseStack();
        for (BlockPos pos : ValveArrowClient.positions()) {
            // Range-gate on where the valve is DRAWN, not on its raw coordinates: a valve on a
            // contraption stands at plot coordinates far outside the world, which fails any
            // distance test and hid every arrow on a ship.
            SubLevelFrame frame = SubLevelDraw.frameAt(pos);
            Vec3 drawnAt = SubLevelDraw.project(frame, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (drawnAt.distanceToSqr(camera) > MAX_DISTANCE_SQ) continue;
            ValveDirectionBehaviour dial =
                    BlockEntityBehaviour.get(mc.level, pos, ValveDirectionBehaviour.TYPE);
            Direction dir = dial == null ? null : dial.oneWayFlow();
            if (dir == null) {
                ValveArrowClient.untrack(pos); // broken, replaced, or dialed back to both ways
                continue;
            }
            // The arrow slides once through the valve block along its allowed direction — in the
            // valve's OWN frame, so on a ship it stays inside the pipe as the ship turns. Emitted
            // block-local around the valve, which is the origin of that frame (SubLevelDraw).
            double along = slide - 0.5;
            poseStack.pushPose();
            SubLevelDraw.cameraRelative(poseStack, camera, frame, pos);
            ArrowRender.emit(poseStack, mc, consumer, model,
                    0.5 + dir.getStepX() * along,
                    0.5 + dir.getStepY() * along,
                    0.5 + dir.getStepZ() * along,
                    dir, COLOR[0], COLOR[1], COLOR[2], alpha);
            poseStack.popPose();
        }
        OWN_BUFFER.endBatch();
    }

    /** Goggles on the head or a wrench in either hand — the player is asking to see settings. */
    private static boolean engineerMode(LocalPlayer player) {
        return GogglesItem.isWearingGoggles(player)
                || AllItems.WRENCH.isIn(player.getMainHandItem())
                || AllItems.WRENCH.isIn(player.getOffhandItem());
    }
}
