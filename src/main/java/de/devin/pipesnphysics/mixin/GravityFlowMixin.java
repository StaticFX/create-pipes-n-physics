package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.createmod.ponder.api.level.PonderLevel;
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
 *
 * Ponder scenes that demonstrate the engine ({@link PonderLevel} while the engine is on)
 * take the same cancel path — {@code showFlow} drives rendering each tick, so letting Create
 * transport run underneath would fight it. Schematics and Create's vanilla ponders (engine
 * off) keep the early return below.
 *
 * EXCEPT cells the in-pipe LEVEL renderer owns ({@code CreatePipeRendering.ownsAnimation}):
 * their front is integrated by the engine into a dedicated synced field, and letting Create
 * advance its Flow progress underneath would run a second, disagreeing integrator. Skipping
 * the call also skips its client cosmetics (the idle rim drip particles) on those cells —
 * acceptable, the renderer owns them. Stock-rendered cells (flag off, gas, junctions) keep
 * the tick unchanged.
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public abstract class GravityFlowMixin extends BlockEntityBehaviour {
    private GravityFlowMixin() { super(null); }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$cancelCreateTransport(CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        Level level = blockEntity.getLevel();
        if (level == null) return;
        if (blockEntity.isVirtual() && !(level instanceof PonderLevel)) return;
        if (!level.isClientSide()) {
            EngineTickHandler.markDirty(level, blockEntity.getBlockPos());
        }

        FluidTransportBehaviour self = (FluidTransportBehaviour) (Object) this;
        BlockPos pos = blockEntity.getBlockPos();
        if (!CreatePipeRendering.ownsAnimation(self)) {
            for (Direction dir : Direction.values()) {
                PipeConnection conn = self.getConnection(dir);
                if (conn != null) conn.tickFlowProgress(level, pos);
            }
        }
        ci.cancel();
    }
}
