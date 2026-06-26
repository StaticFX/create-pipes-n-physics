package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.OpenEndedPipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads an open-ended pipe's {@code wasPulling} flag, which Create sets true on every
 * drain (intake) and false on every fill (spill). It is what tells the engine whether
 * fluid buffered in the pipe's internal tank is intake residual (to keep delivering) or
 * spill accumulation toward placing a block (to leave alone).
 */
@Mixin(value = OpenEndedPipe.class, remap = false)
public interface OpenEndedPipeAccessor {
    @Accessor("wasPulling") boolean pipesnphysics$wasPulling();
}
