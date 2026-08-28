package de.devin.pipesnphysics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankRenderer;
import de.devin.pipesnphysics.client.render.TiltedTankFluid;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the world-level fluid surface ({@link TiltedTankFluid}) to EVERY Create tank on a Sable
 * contraption, not only the ones Create itself renders.
 *
 * A tank derivative may ship its own block-entity renderer instead of inheriting Create's — Create:
 * Connected's fluid vessel does — and then a hook on {@code FluidTankRenderer} can never fire, so its
 * fluid kept the mod's own block-local draw and stayed glued to the hull while the ship rolled
 * (issue #79). Every renderer, whoever wrote it, is dispatched from here, and the pose at this point
 * is the block's own — the same frame a renderer is handed — so the draw is identical either way.
 *
 * Tanks Create renders are left to the cheaper {@code FluidTankRendererMixin}: taking them here as
 * well would draw the surface twice.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Shadow
    public abstract <E extends BlockEntity> BlockEntityRenderer<E> getRenderer(E blockEntity);

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$renderTiltedTankFluid(
            BlockEntity blockEntity, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,
            CallbackInfo ci) {
        if (!(blockEntity instanceof FluidTankBlockEntity tank)) return;
        Object renderer = getRenderer(blockEntity);
        if (renderer instanceof FluidTankRenderer) return;
        if (tank.getLevel() == null) return;
        int light = LevelRenderer.getLightColor(tank.getLevel(), tank.getBlockPos());
        if (TiltedTankFluid.render(tank, partialTicks, poseStack, buffer, light)) ci.cancel();
    }
}
