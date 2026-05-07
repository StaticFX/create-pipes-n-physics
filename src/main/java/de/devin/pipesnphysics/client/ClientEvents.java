package de.devin.pipesnphysics.client;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Mod bus client events — registers additional models that aren't attached to items/blocks.
 */
public class ClientEvents {

    public static final ModelResourceLocation ARROW_MODEL =
            new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(PipesNPhysics.ID, "pipe_arrow"), "standalone");
    public static final ModelResourceLocation ARROW_TRAVELING_MODEL =
            new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(PipesNPhysics.ID, "pipe_arrow_traveling"), "standalone");

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(ARROW_MODEL);
        event.register(ARROW_TRAVELING_MODEL);
    }
}
