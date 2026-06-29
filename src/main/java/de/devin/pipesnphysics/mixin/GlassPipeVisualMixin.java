package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pipes.GlassPipeVisual;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Pulls a pipe's fluid surface back from an OPEN mouth so it stops z-fighting the rim.
 *
 * At a fluid front with nothing ahead (an open end or a dead end) Create insets the
 * rendered fill by a hairline {@code 1e-6}, which leaves the surface quad coplanar with
 * the pipe opening — they fight for depth and flicker. Widening that inset to a real gap
 * advances the fluid a touch less and separates the two faces. Only the no-continuation
 * fronts hit this constant; a pipe whose flow continues into a neighbour keeps progress at
 * 1 and renders seamless. Mirrors {@link TransparentStraightPipeRendererMixin}.
 */
@Mixin(value = GlassPipeVisual.class, remap = false)
public class GlassPipeVisualMixin {
    @ModifyConstant(method = "beginFrame", constant = @Constant(floatValue = 1e-6f))
    private float pipesnphysics$widenOpenEndInset(float original) {
        return CreatePipeRenderConstants.OPEN_END_INSET;
    }
}
