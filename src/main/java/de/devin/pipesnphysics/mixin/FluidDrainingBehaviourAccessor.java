package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reads a hose pulley drainer's readiness WITHOUT the side effects of the public capability:
 * {@code getFluidInTank}/{@code drain} re-run {@code pullNext} and play a fill effect, so probing
 * them every tick would spam sound. {@code fluid} is the validated body fluid (null until the
 * multi-tick flood search finds one); {@code isSearching} is true while that search is still walking
 * the body. A drainer with a fluid and no active search is ready to supply the network.
 */
@Mixin(value = FluidDrainingBehaviour.class, remap = false)
public interface FluidDrainingBehaviourAccessor {
    @Accessor("fluid") Fluid pipesnphysics$getFluid();

    @Invoker("isSearching") boolean pipesnphysics$isSearching();
}
