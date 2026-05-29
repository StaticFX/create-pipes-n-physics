package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.handler.FluidTransportHandler;
import de.devin.pipesnphysics.physics.PipeFlowData;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Create's fluid transport tick on pipes managed by our engine.
 * Must cancel on BOTH server and client — Create's client-side tickFlowProgress
 * advances flow progress independently, overriding our visual state.
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public abstract class GravityFlowMixin extends BlockEntityBehaviour {

    @Shadow public FluidTransportBehaviour.UpdatePhase phase;

    private GravityFlowMixin() { super(null); }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cancelCreateTransport(CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_GRAVITY_FLOW.get()) return;
        Level level = blockEntity.getLevel();
        if (level == null) return;

        boolean managed;
        if (!level.isClientSide()) {
            managed = FluidTransportHandler.isManaged(blockEntity.getBlockPos());
        } else {
            // Client: use the synced breakdown as a marker (set by server, synced via NBT)
            managed = this instanceof PipeFlowData data && data.pipesnphysics$getBreakdown() != null;
        }

        if (managed) {
            if (!level.isClientSide()) {
                int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
                if (level.getGameTime() % recheckTicks == 0) {
                    if (SableCompat.hasSubLevelRotated(level, blockEntity.getBlockPos())) {
                        FluidTransportHandler.clearCooldown(blockEntity.getBlockPos());
                    }
                    FluidTransportHandler.scheduleCheck(level, blockEntity.getBlockPos());
                }
            }
            ci.cancel();
            return;
        }

        if (!level.isClientSide()) {
            int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
            if (level.getGameTime() % recheckTicks == 0) {
                FluidTransportHandler.scheduleCheck(level, blockEntity.getBlockPos());
            }
        }
    }
}
