package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.OpenEndedPipe;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects an open-ended pipe's fluid spill/intake to the projected REAL-WORLD
 * position when the pipe sits on a Sable sub-level.
 *
 * Sable stores a contraption's blocks at logical "plot" coordinates inside the host
 * level and renders/simulates it at a posed physical position in that same level, so
 * Create's {@code outputPos} is a plot coordinate the player never sees.
 *
 * We do the world interaction OURSELVES at the projected position and CANCEL Create's
 * method (HEAD inject, {@code cancellable}). Do NOT "simplify" this into swapping
 * {@code outputPos} and delegating back into Create's {@code provideFluidToSpace}:
 * Sable installs its own mixin on this class whose {@code sable$preventInWorldPlace}
 * is a {@code @Redirect} on the {@code world.setBlock(...)} INSIDE provideFluidToSpace,
 * which eats the placement for sub-level pipes (Sable keeps the fluid in the sub-level).
 * Letting Create's method run therefore drops the spill silently — the exact bug that
 * regressed this feature. Cancelling at HEAD means Sable's redirect never executes, so
 * our real-world placement wins.
 *
 * Off a sub-level (or with the companion absent, or the feature disabled)
 * {@link #pipesnphysics$getWorldOutputPos} returns null and Create's stock behavior
 * (and Sable's redirects) run untouched.
 */
@Mixin(value = OpenEndedPipe.class, remap = false)
public class OpenEndedPipeMixin {
    @Shadow private Level world;
    @Shadow private BlockPos outputPos;

    @Inject(method = "provideFluidToSpace", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$provideFluidToWorld(FluidStack fluid, boolean simulate,
                                                   CallbackInfoReturnable<Boolean> cir) {
        BlockPos worldBlockPos = pipesnphysics$getWorldOutputPos();
        if (worldBlockPos == null) return;

        if (world == null || !world.isLoaded(worldBlockPos)) {
            cir.setReturnValue(false);
            return;
        }
        if (fluid.isEmpty() || !(fluid.getFluid() instanceof FlowingFluid)) {
            cir.setReturnValue(false);
            return;
        }

        BlockState state = world.getBlockState(worldBlockPos);
        FluidState fluidState = state.getFluidState();
        if (!state.canBeReplaced()) {
            cir.setReturnValue(false);
            return;
        }
        if (fluidState.isSource()) {
            cir.setReturnValue(false);
            return;
        }
        if (simulate) {
            cir.setReturnValue(true);
            return;
        }

        world.setBlock(worldBlockPos, fluid.getFluid()
                .defaultFluidState()
                .createLegacyBlock(), Block.UPDATE_ALL);
        cir.setReturnValue(true);
    }

    @Inject(method = "removeFluidFromSpace", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$removeFluidFromWorld(boolean simulate,
                                                    CallbackInfoReturnable<FluidStack> cir) {
        BlockPos worldBlockPos = pipesnphysics$getWorldOutputPos();
        if (worldBlockPos == null) return;

        if (world == null || !world.isLoaded(worldBlockPos)) {
            cir.setReturnValue(FluidStack.EMPTY);
            return;
        }

        BlockState state = world.getBlockState(worldBlockPos);
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isSource()) {
            cir.setReturnValue(FluidStack.EMPTY);
            return;
        }

        if (!simulate) {
            world.setBlock(worldBlockPos, fluidState.createLegacyBlock(), Block.UPDATE_ALL);
        }
        cir.setReturnValue(new FluidStack(fluidState.getType(), 1000));
    }

    /**
     * The projected real-world block this open end should interact with, or null to
     * leave Create's stock behavior in place: off a sub-level (the projection equals
     * the logical position), without the Sable companion, or when the feature is
     * disabled in config.
     */
    @Unique
    private BlockPos pipesnphysics$getWorldOutputPos() {
        if (world == null || outputPos == null) return null;
        if (!SableCompat.isCompanionLoaded()) return null;
        if (!PipesNPhysicsConfig.ENABLE_OPEN_END_WORLD_PLACEMENT.get()) return null;

        Vec3 worldPos = SableCompat.getWorldPos(world, outputPos);
        BlockPos worldBlockPos = BlockPos.containing(worldPos);

        if (worldBlockPos.equals(outputPos)) return null;
        return worldBlockPos;
    }
}
