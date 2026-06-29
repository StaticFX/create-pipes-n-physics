package de.devin.pipesnphysics.mixin;

import com.simibubi.create.AllBlocks;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

/**
 * Safety net: if any Create pipe/pump scenes survive registration, drop them when compiled.
 * Scene ids use title keys (e.g. {@code create:fluid_pipe_flow}), not schematic paths.
 */
@Mixin(value = PonderSceneRegistry.class, remap = false)
public class PonderSceneRegistryMixin {

    private static final Set<ResourceLocation> OVERRIDDEN = Set.of(
            AllBlocks.FLUID_PIPE.getId(),
            AllBlocks.MECHANICAL_PUMP.getId()
    );

    @Inject(method = "compile(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;",
            at = @At("RETURN"))
    private void pipesnphysics$stripCreateFluidScenes(ResourceLocation componentId,
                                                      CallbackInfoReturnable<List<PonderScene>> cir) {
        if (!OVERRIDDEN.contains(componentId)) return;
        cir.getReturnValue().removeIf(scene -> "create".equals(scene.getNamespace()));
    }
}
