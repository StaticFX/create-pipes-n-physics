package de.devin.pipesnphysics.client;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.client.ponder.PnpPonderPlugin;
import de.devin.pipesnphysics.client.render.flywheel.TankFluidVisualizer;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.common.NeoForge;

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

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            PonderIndex.addPlugin(new PnpPonderPlugin());
            // Item tooltips are a GAME bus event; registered here so the bus is unambiguous.
            NeoForge.EVENT_BUS.register(PumpTooltip.class);
            // A visualizer is a field on the block entity type, so any point past registry freeze does.
            TankFluidVisualizer.register();
        });
    }
}
