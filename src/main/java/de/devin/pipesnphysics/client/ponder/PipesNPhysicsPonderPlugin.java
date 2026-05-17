package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.AllBlocks;
import de.devin.pipesnphysics.PipesNPhysics;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public class PipesNPhysicsPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return PipesNPhysics.ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(AllBlocks.FLUID_PIPE.getId())
                .addStoryBoard("friction_new", PipesNPhysicsScenes::frictionIntro)
                .addStoryBoard("pressure_2", PipesNPhysicsScenes::pressureIntro)
                .addStoryBoard("l_shape_drop", PipesNPhysicsScenes::lShapeExample)
                .addStoryBoard("uphill", PipesNPhysicsScenes::pumpUphill);

        helper.forComponents(AllBlocks.MECHANICAL_PUMP.getId())
                .addStoryBoard("pump", PipesNPhysicsScenes::pumpBasics)
                .addStoryBoard("pressure_2", PipesNPhysicsScenes::pressureIntro)
                .addStoryBoard("uphill", PipesNPhysicsScenes::pumpUphill);
    }
}
