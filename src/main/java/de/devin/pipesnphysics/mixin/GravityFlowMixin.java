package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Create's fluid transport tick on every pipe while the engine is enabled,
 * and marks the network as dirty so the engine picks it up on the next server tick.
 *
 * The cancel happens on both server and client so Create's visual flow progress
 * doesn't fight the engine's overlay.
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public abstract class GravityFlowMixin extends BlockEntityBehaviour {

    private GravityFlowMixin() { super(null); }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$cancelCreateTransport(CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        if (blockEntity.isVirtual()) return; // Ponder scenes & schematics keep Create's animation
        Level level = blockEntity.getLevel();
        if (level == null) return;
        if (!level.isClientSide()) {
            EngineTickHandler.markDirty(level, blockEntity.getBlockPos());
        }
        ci.cancel();
    }
}
