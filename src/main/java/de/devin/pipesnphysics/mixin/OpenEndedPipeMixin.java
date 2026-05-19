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

@Mixin(value = OpenEndedPipe.class, remap = false)
public class OpenEndedPipeMixin {

    @Shadow private Level world;
    @Shadow private BlockPos outputPos;

    @Inject(method = "provideFluidToSpace", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$provideFluidToWorld(FluidStack fluid, boolean simulate, CallbackInfoReturnable<Boolean> cir) {
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
    private void pipesnphysics$removeFluidFromWorld(boolean simulate, CallbackInfoReturnable<FluidStack> cir) {
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
