package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.GlassPipeVisual;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Pulls a pipe's fluid surface back from an OPEN mouth so it stops z-fighting the rim.
 *
 * At a fluid front with nothing ahead (an open end or a dead end) Create insets the
 * rendered fill by a hairline {@code 1e-6}, which leaves the surface quad coplanar with
 * the pipe opening — they fight for depth and flicker. Widening that inset to a real gap
 * advances the fluid a touch less and separates the two faces. Only the no-continuation
 * fronts hit this constant; a pipe whose flow continues into a neighbour keeps progress at
 * 1 and renders seamless. Mirrors {@link TransparentStraightPipeRendererMixin}.
 *
 * It also hands the in-pipe LEVEL renderer ({@code EXPERIMENTAL_PIPE_LEVEL_RENDER}) ownership of cells
 * with a solved waterline: when the flag is on, a cell whose pipe behaviour carries level data
 * ({@link de.devin.pipesnphysics.compat.PipeLevelData}, a dedicated synced field — NOT the fluid
 * amount) is hidden from Create (its {@code getFlow} reads null, so Create's loop skips it) and
 * {@code client.PipeLevelRenderer} draws the partial fill instead. Unstamped cells are untouched, so
 * Create keeps drawing them.
 */
@Mixin(value = GlassPipeVisual.class, remap = false)
public class GlassPipeVisualMixin {
    @ModifyConstant(method = "beginFrame", constant = @Constant(floatValue = 1e-6f))
    private float pipesnphysics$widenOpenEndInset(float original) {
        return CreatePipeRenderConstants.OPEN_END_INSET;
    }

    @Redirect(method = "beginFrame", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/fluids/FluidTransportBehaviour;getFlow(Lnet/minecraft/core/Direction;)Lcom/simibubi/create/content/fluids/PipeConnection$Flow;"))
    private PipeConnection.Flow pipesnphysics$hideLevelFlow(FluidTransportBehaviour pipe, Direction side) {
        return CreatePipeRendering.hidesFromCreate(pipe) ? null : pipe.getFlow(side);
    }
}
