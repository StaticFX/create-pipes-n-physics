package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.physics.TankMassFormulas;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Appends the contained fluid's weight to a tank's goggle tooltip — the same mass
 * the Sable dynamic-tank-mass feature applies to sub-level physics, so players can
 * see what their cargo weighs. Applied only when Sable (full) is installed; gated
 * at runtime by the same config as the mass feature itself.
 */
@Mixin(value = FluidTankBlockEntity.class, remap = false)
public abstract class FluidTankWeightGoggleMixin {
    @Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
    private void pipesnphysics$showFluidWeight(List<Component> tooltip, boolean isPlayerSneaking,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!PipesNPhysicsConfig.ENABLE_DYNAMIC_TANK_MASS.get()) return;

        FluidTankBlockEntity self = (FluidTankBlockEntity) (Object) this;
        FluidTankBlockEntity controller = self.getControllerBE();
        if (controller == null) return;

        FluidTank tank = ((FluidTankAccessor) (Object) controller).pipesnphysics$getTankInventory();
        FluidStack fluid = tank.getFluid();
        if (fluid.isEmpty()) return;

        int density = fluid.getFluid().getFluidType().getDensity(fluid);
        double massKg = TankMassFormulas.fluidMassKg(fluid.getAmount(), density,
                PipesNPhysicsConfig.FLUID_MASS_PER_BUCKET.get());

        pipesnphysics$lang("gui.goggles.fluid_weight")
                .style(ChatFormatting.GRAY)
                .add(new LangBuilder(PipesNPhysics.ID)
                        .text(LangNumberFormat.format(massKg)).style(ChatFormatting.WHITE))
                .add(pipesnphysics$lang("gui.goggles.kg").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip);
        cir.setReturnValue(true);
    }

    @Unique
    private static LangBuilder pipesnphysics$lang(String key) {
        return new LangBuilder(PipesNPhysics.ID).translate(key);
    }
}
