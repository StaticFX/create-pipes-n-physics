package de.devin.pipesnphysics.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import de.devin.pipesnphysics.client.ponder.PipesNPhysicsPonderTags;
import de.devin.pipesnphysics.client.ponder.PipesNPhysicsPonders;
import net.createmod.ponder.foundation.PonderTag;
import net.createmod.ponder.foundation.registration.PonderTagRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mixin(value = PonderTagRegistry.class, remap = false)
public class PonderTagRegistryMixin {

    @ModifyReturnValue(method = "getListedTags()Ljava/util/List;", at = @At("RETURN"))
    private List<PonderTag> pipesnphysics$filterListedTags(List<PonderTag> original) {
        if (PipesNPhysicsPonders.useModPonders()) return original;
        List<PonderTag> filtered = new ArrayList<>(original.size());
        for (PonderTag tag : original) {
            if (!PipesNPhysicsPonderTags.PIPE_PHYSICS.equals(tag.getId())) {
                filtered.add(tag);
            }
        }
        return filtered;
    }

    @ModifyReturnValue(method = "getTags(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Set;",
            at = @At("RETURN"))
    private Set<PonderTag> pipesnphysics$filterItemTags(Set<PonderTag> original, ResourceLocation item) {
        if (PipesNPhysicsPonders.useModPonders()) return original;
        return original.stream()
                .filter(tag -> !PipesNPhysicsPonderTags.PIPE_PHYSICS.equals(tag.getId()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
