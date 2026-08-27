package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.pump.Pumps;
import de.devin.pipesnphysics.engine.store.PipeFluidCell;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.handler.NetworkEditHandler;
import de.devin.pipesnphysics.handler.PipeContentCarry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries stored pipe fluid across in-place block swaps (see {@link PipeContentCarry}): a pipe
 * cell being REMOVED stashes its content, and a fresh pipe cell INITIALIZING at the same
 * position adopts it — covering the shift-swap, Create's wrench window toggle, and encasing with
 * one mechanism instead of a hook per path. The stash hook is {@code remove()}, NOT Create's
 * {@code destroy()}: destroy is dispatched through {@code getBlockEntity}, which refuses a block
 * entity whose type no longer matches the (already swapped) state — exactly the cross-type swap
 * this exists for — while {@code setRemoved → remove()} runs on every genuine removal and skips
 * chunk unloads (the {@code chunkUnloaded} guard), so saved content is untouched. An unclaimed
 * stash expires and the break-spill owns the fluid. A pump also hosts the behaviour but stores
 * nothing in the flow model, so it never adopts ({@link PipeSwapHandler} spills for it).
 * Same target shape as {@link PipeHeartbeatMixin} — SmartBlockEntity declares both methods.
 */
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class PipeContentCarryMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void pipesnphysics$stashPipeContent(CallbackInfo ci) {
        SmartBlockEntity self = (SmartBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide() || self.isVirtual()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        if (!(self.getBehaviour(FluidTransportBehaviour.TYPE) instanceof PipeFluidCell cell)) return;
        FluidStack content = cell.pipesnphysics$content();
        if (!content.isEmpty()) PipeContentCarry.stash(level, self.getBlockPos(), content.copy());
    }

    @Inject(method = "initialize", at = @At("TAIL"))
    private void pipesnphysics$claimPipeContent(CallbackInfo ci) {
        SmartBlockEntity self = (SmartBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide() || self.isVirtual()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        if (Pumps.isPump(level, self.getBlockPos(), self.getBlockState())) return; // pumps store nothing
        if (!(self.getBehaviour(FluidTransportBehaviour.TYPE) instanceof PipeFluidCell)) return;
        PipeStore.Store cell = PipeStore.at(level, self.getBlockPos());
        if (cell == null) return;
        FluidStack carried = PipeContentCarry.claim(level, self.getBlockPos());
        if (carried.isEmpty()) return;
        int kept = cell.insert(carried, carried.getAmount());
        cell.flush();
        int leftover = carried.getAmount() - kept;
        if (leftover > 0 && level instanceof ServerLevel serverLevel) {
            // A different fluid already arrived, or capacity shrank: conserve into the network.
            NetworkEditHandler.spillIntoNeighbors(serverLevel, self.getBlockPos(),
                    carried.copyWithAmount(leftover));
        }
    }
}
