package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.handler.FluidTransportHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces Create's pump pressure distribution.
 * All flow logic (pressure, fluid transfer, gas support) is handled
 * by FluidTransportHandler's unified solver and transfer system.
 */
@Mixin(value = PumpBlockEntity.class, remap = false)
public abstract class PumpBlockEntityMixin extends KineticBlockEntity {

    private PumpBlockEntityMixin() { super(null, null, null); }

    /**
     * @author PipesNPhysics
     * @reason Unified handler manages all pressure and fluid transfer
     */
    @Overwrite
    protected void distributePressureTo(Direction side) {
        PumpBlockEntity self = (PumpBlockEntity) (Object) this;
        if (self.getSpeed() == 0) return;
        if (self.getLevel() == null || self.getLevel().isClientSide()) return;

        BlockPos worldPosition = self.getBlockPos();
        BlockState pumpState = self.getBlockState();
        Direction front = pumpState.getBlock() instanceof PumpBlock
                ? pumpState.getValue(PumpBlock.FACING) : side;
        boolean pull = side != front;

        if (!pull) {
            FluidPropagator.resetAffectedFluidNetworks(self.getLevel(), worldPosition, side.getOpposite());
        }

        BlockPos pipePos = worldPosition.relative(side);
        FluidTransportHandler.clearCooldown(pipePos);
        FluidTransportHandler.scheduleCheck(self.getLevel(), pipePos);
    }
}
