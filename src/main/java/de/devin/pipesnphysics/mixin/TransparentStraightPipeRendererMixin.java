package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pipes.TransparentStraightPipeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * The fallback (no-Flywheel) twin of {@link GlassPipeVisualMixin}: widens Create's hairline
 * open-end fill inset so a fluid front at an open mouth stops z-fighting the pipe rim.
 */
@Mixin(value = TransparentStraightPipeRenderer.class, remap = false)
public class TransparentStraightPipeRendererMixin {
    @ModifyConstant(method = "renderSafe", constant = @Constant(floatValue = 1e-6f))
    private float pipesnphysics$widenOpenEndInset(float original) {
        return CreatePipeRenderConstants.OPEN_END_INSET;
    }
}
