package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FluidTankBlockEntity.class, remap = false)
public interface FluidTankAccessor {
    @Accessor("window") boolean pipesnphysics$isWindow();
    @Accessor("width") int pipesnphysics$getWidth();
    @Accessor("height") int pipesnphysics$getHeight();
    @Accessor("tankInventory") FluidTank pipesnphysics$getTankInventory();
}
