package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.handler.GravityFlowHandler;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into FluidTransportBehaviour to trigger gravity flow checks:
 * 1. When wipePressure() is called (network rebuild)
 * 2. Periodically during tick() if the pipe is idle with no pressure
 * 3. When a Sable sub-level rotates (detects orientation change, wipes stale pressure)
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public abstract class GravityFlowMixin extends BlockEntityBehaviour {

    @Shadow public FluidTransportBehaviour.UpdatePhase phase;
    @Shadow public abstract boolean hasAnyPressure();

    /** Guard to prevent recursive wipePressure → scheduleCheck → wipePressure loops. */
    @Unique
    private boolean pipesnphysics$wiping = false;

    private GravityFlowMixin() { super(null); }

    @Inject(method = "wipePressure", at = @At("TAIL"))
    private void onWipePressure(CallbackInfo ci) {
        if (pipesnphysics$wiping) return; // prevent recursion
        if (GravityFlowHandler.suppressWipeReschedule) return; // suppress during reapplication
        Level level = blockEntity.getLevel();
        if (level != null && !level.isClientSide()) {
            GravityFlowHandler.clearCooldown(blockEntity.getBlockPos());
            GravityFlowHandler.scheduleCheck(level, blockEntity.getBlockPos());
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void periodicGravityCheck(CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_GRAVITY_FLOW.get()) return;
        if (phase != FluidTransportBehaviour.UpdatePhase.IDLE) return;

        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide()) return;

        // Stagger checks across ticks (configurable rate)
        int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
        if (level.getGameTime() % recheckTicks != (blockEntity.getBlockPos().hashCode() & 0x7FFFFFFF) % recheckTicks) return;

        // For pipes on sub-levels: check if rotation changed (stale gravity data).
        // Must run even with 0 pressure — rotation can CREATE a height difference that enables flow.
        if (SableCompat.hasSubLevelRotated(level, blockEntity.getBlockPos())) {
            GravityFlowHandler.clearCooldown(blockEntity.getBlockPos());
            if (hasAnyPressure()) {
                pipesnphysics$wiping = true;
                ((FluidTransportBehaviour) (Object) this).wipePressure();
                pipesnphysics$wiping = false;
            }
        }

        // Always schedule — the cooldown in GravityFlowHandler throttles redundant processing.
        // This ensures networks re-evaluate when sinks fill/empty or pipes are added/removed.
        GravityFlowHandler.scheduleCheck(level, blockEntity.getBlockPos());
    }
}
