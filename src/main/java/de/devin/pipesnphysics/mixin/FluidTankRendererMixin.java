package de.devin.pipesnphysics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankRenderer;
import de.devin.pipesnphysics.client.render.TiltedTankFluid;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands a Create tank on a Sable sub-level to {@link TiltedTankFluid}, whose surface stays level
 * with the WORLD as the contraption rolls, and suppresses Create's own fluid when it does.
 *
 * A tank DERIVATIVE that ships its own block-entity renderer never reaches this — Create: Connected's
 * fluid vessel is one — so the same call is made a layer down at the render dispatcher
 * ({@code BlockEntityRenderDispatcherMixin}). This hook stays because it is the cheaper path and the
 * one that runs for the block the feature was built for.
 */
@Mixin(value = FluidTankRenderer.class, remap = false)
public class FluidTankRendererMixin {
    @Inject(method = "renderSafe", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$renderTiltedFluid(FluidTankBlockEntity be, float partialTicks, PoseStack ms,
                                                 MultiBufferSource buffer, int light, int overlay,
                                                 CallbackInfo ci) {
        if (TiltedTankFluid.render(be, partialTicks, ms, buffer, light)) ci.cancel();
    }
}
