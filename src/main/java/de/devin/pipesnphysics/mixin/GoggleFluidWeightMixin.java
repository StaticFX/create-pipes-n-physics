package de.devin.pipesnphysics.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.physics.TankMassFormulas;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Appends the contained fluid's weight to the goggle tooltip of ANY Create tank — the same mass the
 * Sable dynamic-tank-mass feature applies to sub-level physics, so a player can see what their cargo
 * weighs. Gated at runtime by that feature's own config.
 *
 * Hooked where the tooltip is COLLECTED rather than on {@code FluidTankBlockEntity.addToGoggleTooltip},
 * because a derivative may override that method and never call {@code super} — Create: Connected's
 * fluid vessel does, so it silently lost the line (issue #79). Every block's tooltip passes through
 * here, whoever wrote it, so one hook covers every tank derivative there will ever be.
 */
@Mixin(value = GoggleOverlayRenderer.class, remap = false)
public class GoggleFluidWeightMixin {
    @ModifyExpressionValue(method = "renderOverlay", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/api/equipment/goggles/IHaveGoggleInformation;"
                    + "addToGoggleTooltip(Ljava/util/List;Z)Z"))
    private static boolean pipesnphysics$appendFluidWeight(boolean added, @Local BlockEntity be,
                                                           @Local List<Component> tooltip) {
        if (!PipesNPhysicsConfig.ENABLE_DYNAMIC_TANK_MASS.get()) return added;
        if (!(be instanceof FluidTankBlockEntity tank)) return added;

        FluidTankBlockEntity controller = tank.getControllerBE();
        if (controller == null) return added;
        FluidTank inventory = ((FluidTankAccessor) (Object) controller).pipesnphysics$getTankInventory();
        FluidStack fluid = inventory.getFluid();
        if (fluid.isEmpty()) return added;

        int density = fluid.getFluid().getFluidType().getDensity(fluid);
        boolean lift = PipesNPhysicsConfig.ENABLE_GAS_BUOYANCY.get()
                && fluid.getFluid().getFluidType().isLighterThanAir();
        double netKg = TankMassFormulas.netMassKg(fluid.getAmount(), density, lift,
                PipesNPhysicsConfig.FLUID_MASS_PER_BUCKET.get(),
                PipesNPhysicsConfig.FLUID_LIFT_PER_BUCKET.get());

        // A lighter-than-air gas reads as upward LIFT (aqua), a liquid as downward weight; the number
        // is the magnitude either way, so the sign lives in the label instead of a stray minus.
        boolean buoyant = netKg < 0;
        pipesnphysics$lang(buoyant ? "gui.goggles.fluid_lift" : "gui.goggles.fluid_weight")
                .style(ChatFormatting.GRAY)
                .add(new LangBuilder(PipesNPhysics.ID)
                        .text(LangNumberFormat.format(Math.abs(netKg)))
                        .style(buoyant ? ChatFormatting.AQUA : ChatFormatting.WHITE))
                .add(pipesnphysics$lang("gui.goggles.kg").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip);
        return true; // the box must open even for a tank whose own tooltip added nothing
    }

    @Unique
    private static LangBuilder pipesnphysics$lang(String key) {
        return new LangBuilder(PipesNPhysics.ID).translate(key);
    }
}
