package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Create's pump pressure distribution while the engine is enabled (the
 * engine routes fluid itself) and wakes the network whenever the pump's facing
 * changes. When the engine is disabled in config, Create's logic runs untouched.
 */
@Mixin(value = PumpBlockEntity.class, remap = false)
public abstract class PumpBlockEntityMixin extends KineticBlockEntity {

    @Unique
    private Direction pipesnphysics$lastFacing = null;

    private PumpBlockEntityMixin() { super(null, null, null); }

    @Inject(method = "tick", at = @At("HEAD"))
    private void pipesnphysics$detectFlip(CallbackInfo ci) {
        Level world = level;
        if (world == null || world.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof PumpBlock)) return;
        Direction front = state.getValue(PumpBlock.FACING);
        if (pipesnphysics$lastFacing != null && pipesnphysics$lastFacing != front) {
            EngineTickHandler.markChanged(world, worldPosition);
            EngineTickHandler.markChanged(world, worldPosition.relative(front));
            EngineTickHandler.markChanged(world, worldPosition.relative(front.getOpposite()));
        }
        pipesnphysics$lastFacing = front;
    }

    @Inject(method = "distributePressureTo", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$replacePressureDistribution(Direction side, CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        PumpBlockEntity self = (PumpBlockEntity) (Object) this;
        if (self.getLevel() != null && !self.getLevel().isClientSide()) {
            EngineTickHandler.markChanged(self.getLevel(), self.getBlockPos().relative(side));
        }
        ci.cancel();
    }
}
