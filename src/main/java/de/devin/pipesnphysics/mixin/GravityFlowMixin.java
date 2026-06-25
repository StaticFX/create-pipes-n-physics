package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Create's fluid transport tick on every pipe while the engine is enabled,
 * and marks the network as dirty so the engine picks it up on the next server tick.
 *
 * The cancel happens on both server and client so Create's pressure propagation and
 * flow creation don't fight the engine. The one piece we KEEP is
 * {@link PipeConnection#tickFlowProgress} — pure cosmetics that advances the fill
 * animation Create draws — so engine-seeded fluid fronts visibly travel down a pipe
 * instead of popping full. It moves no fluid and starts no flows on its own.
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

        FluidTransportBehaviour self = (FluidTransportBehaviour) (Object) this;
        BlockPos pos = blockEntity.getBlockPos();
        for (Direction dir : Direction.values()) {
            PipeConnection conn = self.getConnection(dir);
            if (conn != null) conn.tickFlowProgress(level, pos);
        }
        ci.cancel();
    }
}
