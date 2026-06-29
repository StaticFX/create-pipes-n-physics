package de.devin.pipesnphysics.client.ponder;

import de.devin.pipesnphysics.PipesNPhysics;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public final class PipesNPhysicsPonderPlugin implements PonderPlugin {
    @Override
    public String getModId() {
        return PipesNPhysics.ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PipesNPhysicsPonderScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PipesNPhysicsPonderTags.register(helper);
    }
}
