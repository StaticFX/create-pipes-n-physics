package de.devin.pipesnphysics.mixin;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.CreateFluidCompat;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Create: Fluid's pressure distribution while the engine is enabled and
 * wakes the network when the centrifugal pump's push/pull assignment changes.
 */
@Mixin(targets = "com.adonis.fluid.block.CentrifugalPump.CentrifugalPumpBlockEntity", remap = false)
public abstract class CentrifugalPumpBlockEntityMixin {
    @Unique
    private Direction pipesnphysics$lastPush = null;

    @Unique
    private BlockEntity pipesnphysics$self() {
        return (BlockEntity) (Object) this;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void pipesnphysics$detectPortChange(CallbackInfo ci) {
        BlockEntity self = pipesnphysics$self();
        Level world = self.getLevel();
        if (world == null || world.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        BlockState state = self.getBlockState();
        if (!CreateFluidCompat.isCentrifugalPump(state)) return;

        BlockPos pos = self.getBlockPos();
        Direction push = CreateFluidCompat.getPushSide(world, pos, state);
        if (push == null) return;
        if (pipesnphysics$lastPush != null && pipesnphysics$lastPush != push) {
            CreateFluidCompat.PumpPorts ports = CreateFluidCompat.getPumpPorts(world, pos, state);
            if (ports != null) {
                EngineTickHandler.markChanged(world, pos.relative(ports.push()));
                EngineTickHandler.markChanged(world, pos.relative(ports.pull()));
            }
        }
        pipesnphysics$lastPush = push;
    }

    @Inject(method = "distributePressureTo", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$replacePressureDistribution(Direction side, CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        BlockEntity self = pipesnphysics$self();
        Level world = self.getLevel();
        if (world != null && !world.isClientSide()) {
            EngineTickHandler.markChanged(world, self.getBlockPos().relative(side));
        }
        ci.cancel();
    }
}
