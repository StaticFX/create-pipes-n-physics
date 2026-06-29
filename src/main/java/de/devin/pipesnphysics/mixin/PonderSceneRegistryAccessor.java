package de.devin.pipesnphysics.mixin;

import com.google.common.collect.Multimap;
import com.simibubi.create.AllBlocks;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PonderSceneRegistry.class)
public interface PonderSceneRegistryAccessor {
    @Accessor("scenes")
    Multimap<ResourceLocation, StoryBoardEntry> pipesnphysics$getScenes();
}
