package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.TransparentStraightPipeRenderer;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The fallback (no-Flywheel) twin of {@link GlassPipeVisualMixin}: widens Create's hairline
 * open-end fill inset so a fluid front at an open mouth stops z-fighting the pipe rim, and hides a
 * cell carrying level data (a dedicated synced field, see {@link CreatePipeRendering#hidesFromCreate})
 * from Create so {@code client.PipeLevelRenderer} owns its partial fill.
 */
@Mixin(value = TransparentStraightPipeRenderer.class, remap = false)
public class TransparentStraightPipeRendererMixin {
    @ModifyConstant(method = "renderSafe", constant = @Constant(floatValue = 1e-6f))
    private float pipesnphysics$widenOpenEndInset(float original) {
        return CreatePipeRenderConstants.OPEN_END_INSET;
    }

    @Redirect(method = "renderSafe", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/fluids/FluidTransportBehaviour;getFlow(Lnet/minecraft/core/Direction;)Lcom/simibubi/create/content/fluids/PipeConnection$Flow;"))
    private PipeConnection.Flow pipesnphysics$hideLevelFlow(FluidTransportBehaviour pipe, Direction side) {
        return CreatePipeRendering.hidesFromCreate(pipe) ? null : pipe.getFlow(side);
    }
}
