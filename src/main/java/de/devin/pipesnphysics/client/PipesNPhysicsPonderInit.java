package de.devin.pipesnphysics.client;

import de.devin.pipesnphysics.client.ponder.PipesNPhysicsPonderPlugin;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Registers ponder scenes with PonderIndex. Create addon mods must call
 * {@link PonderIndex#addPlugin} explicitly during client setup (the service
 * loader entry alone is not picked up by Ponder).
 */
public final class PipesNPhysicsPonderInit {
    private PipesNPhysicsPonderInit() {}

    public static void register(FMLClientSetupEvent event) {
        PonderIndex.addPlugin(new PipesNPhysicsPonderPlugin());
    }
}
