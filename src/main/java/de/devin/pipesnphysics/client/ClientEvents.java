package de.devin.pipesnphysics.client;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Mod bus client events — registers additional models that aren't attached to items/blocks.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class ClientEvents {
    public static final ModelResourceLocation ARROW_MODEL =
            new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(PipesNPhysics.ID, "pipe_arrow"), "standalone");

    private ClientEvents() {}

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(ARROW_MODEL);
    }
}
