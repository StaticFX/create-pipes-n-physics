package de.devin.pipesnphysics.mixin;

import com.google.common.collect.Multimap;
import com.simibubi.create.AllBlocks;
import de.devin.pipesnphysics.PipesNPhysics;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * After all ponder plugins register, remove Create's vanilla pipe/pump storyboards
 * so only our physics tutorials remain on those items.
 */
@Mixin(PonderIndex.class)
public class PonderIndexMixin {
    private static final Set<ResourceLocation> OVERRIDDEN = Set.of(
            AllBlocks.FLUID_PIPE.getId(),
            AllBlocks.MECHANICAL_PUMP.getId()
    );

    @Shadow
    @Final
    private static PonderSceneRegistry SCENES;

    @Inject(method = "registerAll", at = @At("RETURN"))
    private static void pipesnphysics$stripCreateFluidScenes(CallbackInfo ci) {
        Multimap<ResourceLocation, StoryBoardEntry> scenes =
                ((PonderSceneRegistryAccessor) SCENES).pipesnphysics$getScenes();

        int removed = 0;
        for (ResourceLocation component : OVERRIDDEN) {
            var boards = scenes.get(component);
            if (boards.isEmpty()) continue;

            List<StoryBoardEntry> strip = new ArrayList<>();
            for (StoryBoardEntry entry : boards) {
                if ("create".equals(entry.getNamespace())) {
                    strip.add(entry);
                }
            }
            for (StoryBoardEntry entry : strip) {
                scenes.remove(component, entry);
                removed++;
            }
        }

        if (removed > 0) {
            PipesNPhysics.LOGGER.debug("Removed {} Create ponder scene(s) from overridden fluid components", removed);
        }
    }
}
