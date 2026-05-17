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

/**
 * Removes Create's pump flow and speed Ponder scenes, replaced by our
 * physics-aware versions registered in PipesNPhysicsPonderPlugin.
 */
@Mixin(value = PonderSceneRegistry.class, remap = false)
public class PonderSceneRegistryMixin {

    @Inject(method = "compile(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;",
            at = @At("RETURN"))
    private void pipesnphysics$replaceCreatePumpScenes(ResourceLocation componentId,
                                                        CallbackInfoReturnable<List<PonderScene>> cir) {
        if (!componentId.equals(AllBlocks.MECHANICAL_PUMP.getId())) return;

        List<PonderScene> scenes = cir.getReturnValue();
        scenes.removeIf(scene -> {
            ResourceLocation id = scene.getId();
            if (id == null || !"create".equals(scene.getNamespace())) return false;
            String path = id.getPath();
            return path.contains("mechanical_pump");
        });
    }
}
