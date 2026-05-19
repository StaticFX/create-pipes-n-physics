package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SablePhysicsCompat;
import de.devin.pipesnphysics.physics.TankMassFormulas;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = FluidTankBlockEntity.class, remap = false)
public class FluidTankMassMixin implements BlockEntitySubLevelActor {

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (!PipesNPhysicsConfig.ENABLE_DYNAMIC_TANK_MASS.get()) return;

        FluidTankBlockEntity self = (FluidTankBlockEntity) (Object) this;

        if (!self.isController()) return;

        FluidTank tank = ((FluidTankAccessor) self).pipesnphysics$getTankInventory();
        int fluidAmount = tank.getFluidAmount();
        if (fluidAmount <= 0) return;

        int capacity = tank.getCapacity();
        if (capacity <= 0) return;

        FluidStack fluidStack = tank.getFluid();
        int density = fluidStack.getFluid().getFluidType().getDensity(fluidStack);
        double massKg = TankMassFormulas.fluidMassKg(
                fluidAmount, density, PipesNPhysicsConfig.FLUID_MASS_PER_BUCKET.get());
        double fillFraction = TankMassFormulas.fillFraction(
                fluidAmount, capacity);

        int width = ((FluidTankAccessor) self).pipesnphysics$getWidth();
        int height = ((FluidTankAccessor) self).pipesnphysics$getHeight();

        SablePhysicsCompat.applyFluidWeight(
                subLevel, self.getBlockPos(), width, height, fillFraction,
                massKg, timeStep
        );
    }
}
