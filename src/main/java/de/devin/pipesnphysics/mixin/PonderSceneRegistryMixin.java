package de.devin.pipesnphysics.mixin;

import de.devin.pipesnphysics.client.ponder.PipesNPhysicsPonders;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * When the engine is enabled, show mod pipe/pump ponders; when disabled, show Create's vanilla ones.
 */
@Mixin(value = PonderSceneRegistry.class, remap = false)
public class PonderSceneRegistryMixin {

    @Inject(method = "compile(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;",
            at = @At("RETURN"))
    private void pipesnphysics$filterFluidScenes(ResourceLocation componentId,
                                                 CallbackInfoReturnable<List<PonderScene>> cir) {
        if (!PipesNPhysicsPonders.OVERRIDDEN_COMPONENTS.contains(componentId)) return;
        applyFilter(cir.getReturnValue());
    }

    @Inject(method = "compile(Ljava/util/Collection;)Ljava/util/List;",
            at = @At("RETURN"))
    private void pipesnphysics$filterFluidScenes(Collection<StoryBoardEntry> entries,
                                                 CallbackInfoReturnable<List<PonderScene>> cir) {
        List<PonderScene> scenes = cir.getReturnValue();
        boolean affectsOverridden = false;
        for (PonderScene scene : scenes) {
            if (PipesNPhysicsPonders.OVERRIDDEN_COMPONENTS.contains(scene.getLocation())) {
                affectsOverridden = true;
                break;
            }
        }
        if (!affectsOverridden) return;
        applyFilter(scenes);
    }

    /** Never leave zero scenes — other mods (e.g. Simulated) assume at least one exists. */
    private static void applyFilter(List<PonderScene> scenes) {
        if (scenes.isEmpty()) return;
        List<PonderScene> kept = new ArrayList<>(scenes.size());
        for (PonderScene scene : scenes) {
            if (!PipesNPhysicsPonders.shouldHide(scene)) {
                kept.add(scene);
            }
        }
        if (kept.isEmpty()) return;
        scenes.clear();
        scenes.addAll(kept);
    }
}
