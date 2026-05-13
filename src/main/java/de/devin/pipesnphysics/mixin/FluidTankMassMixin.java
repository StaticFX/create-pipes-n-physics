package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SablePhysicsCompat;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Makes fluid tanks contribute dynamic weight to Sable sub-levels.
 * Fuller tanks are heavier, affecting the sub-level's mass, center of gravity,
 * and rotational inertia. Mass scales with the fluid's density (lava weighs 3× water).
 *
 * Only applied when Sable (full) is present — guarded by SableMixinPlugin.
 * Only the controller block of a multi-block tank applies force (no double-counting).
 */
@Mixin(value = FluidTankBlockEntity.class, remap = false)
public class FluidTankMassMixin implements BlockEntitySubLevelActor {

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (!PipesNPhysicsConfig.ENABLE_DYNAMIC_TANK_MASS.get()) return;

        FluidTankBlockEntity self = (FluidTankBlockEntity) (Object) this;

        // Only the controller block applies force for the entire multi-block tank
        if (!self.isController()) return;

        FluidTank tank = ((FluidTankAccessor) self).pipesnphysics$getTankInventory();
        int fluidAmount = tank.getFluidAmount();
        if (fluidAmount <= 0) return;

        int capacity = tank.getCapacity();
        if (capacity <= 0) return;

        FluidStack fluidStack = tank.getFluid();
        int density = fluidStack.getFluid().getFluidType().getDensity(fluidStack);
        double massKg = de.devin.pipesnphysics.physics.TankMassFormulas.fluidMassKg(
                fluidAmount, density, PipesNPhysicsConfig.FLUID_MASS_PER_BUCKET.get());
        double fillFraction = de.devin.pipesnphysics.physics.TankMassFormulas.fillFraction(
                fluidAmount, capacity);

        int width = ((FluidTankAccessor) self).pipesnphysics$getWidth();
        int height = ((FluidTankAccessor) self).pipesnphysics$getHeight();

        SablePhysicsCompat.applyFluidWeight(
                subLevel, self.getBlockPos(), width, height, fillFraction,
                massKg, timeStep
        );
    }
}
