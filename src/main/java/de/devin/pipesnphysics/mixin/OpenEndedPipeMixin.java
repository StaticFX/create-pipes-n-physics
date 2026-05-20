package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.OpenEndedPipe;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
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

    @Unique private BlockPos pipesnphysics$savedOutputPos;

    @Inject(method = "provideFluidToSpace", at = @At("HEAD"))
    private void pipesnphysics$beforeProvide(FluidStack fluid, boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        pipesnphysics$swapToWorldPos();
    }

    @Inject(method = "provideFluidToSpace", at = @At("RETURN"))
    private void pipesnphysics$afterProvide(FluidStack fluid, boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        pipesnphysics$restorePos();
    }

    @Inject(method = "removeFluidFromSpace", at = @At("HEAD"))
    private void pipesnphysics$beforeRemove(boolean simulate, CallbackInfoReturnable<FluidStack> cir) {
        pipesnphysics$swapToWorldPos();
    }

    @Inject(method = "removeFluidFromSpace", at = @At("RETURN"))
    private void pipesnphysics$afterRemove(boolean simulate, CallbackInfoReturnable<FluidStack> cir) {
        pipesnphysics$restorePos();
    }

    @Unique
    private void pipesnphysics$swapToWorldPos() {
        BlockPos worldBlockPos = pipesnphysics$getWorldOutputPos();
        if (worldBlockPos == null) return;
        pipesnphysics$savedOutputPos = outputPos;
        outputPos = worldBlockPos;
    }

    @Unique
    private void pipesnphysics$restorePos() {
        if (pipesnphysics$savedOutputPos != null) {
            outputPos = pipesnphysics$savedOutputPos;
            pipesnphysics$savedOutputPos = null;
        }
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
