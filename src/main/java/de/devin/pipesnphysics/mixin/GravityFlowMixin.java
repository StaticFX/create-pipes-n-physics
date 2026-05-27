package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.handler.FluidTransportHandler;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Create's fluid transport tick on pipes managed by our engine.
 * This prevents Create from clearing our Flow objects or interfering with our sim.
 * Also provides the periodic scheduling trigger for our engine.
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public abstract class GravityFlowMixin extends BlockEntityBehaviour {

    @Shadow public FluidTransportBehaviour.UpdatePhase phase;

    private GravityFlowMixin() { super(null); }

    /**
     * Cancel Create's entire transport tick for pipes in our networks.
     * Without this, Create clears Flow objects we set and interferes with our sim.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cancelCreateTransport(CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_GRAVITY_FLOW.get()) return;
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide()) return;

        // If this pipe is in one of our networks, cancel Create's tick.
        // Our engine handles all flow/rendering via FluidTransportHandler.
        if (FluidTransportHandler.isManaged(blockEntity.getBlockPos())) {
            // Still do our periodic scheduling
            int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
            if (level.getGameTime() % recheckTicks == 0) {
                if (SableCompat.hasSubLevelRotated(level, blockEntity.getBlockPos())) {
                    FluidTransportHandler.clearCooldown(blockEntity.getBlockPos());
                }
                FluidTransportHandler.scheduleCheck(level, blockEntity.getBlockPos());
            }
            ci.cancel();
            return;
        }

        // Pipe not in our network yet — schedule a check so we discover it
        int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
        if (level.getGameTime() % recheckTicks == 0) {
            FluidTransportHandler.scheduleCheck(level, blockEntity.getBlockPos());
        }
    }
}
