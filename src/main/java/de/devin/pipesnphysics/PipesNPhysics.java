package de.devin.pipesnphysics;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceLocation;
import de.devin.pipesnphysics.client.ClientEvents;
import de.devin.pipesnphysics.client.PumpRangeRenderer;
import de.devin.pipesnphysics.client.ponder.PipesNPhysicsPonderPlugin;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.handler.DebugGraphCommand;
import de.devin.pipesnphysics.handler.GravityFlowHandler;
import de.devin.pipesnphysics.handler.PipeSwapHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(PipesNPhysics.ID)
public class PipesNPhysics {
    public static final String ID = "pipesnphysics";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(ID)
            .setTooltipModifierFactory(item ->
                    new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE)
                            .andThen(TooltipModifier.mapNull(KineticStats.create(item)))
            );

    public PipesNPhysics(IEventBus modBus, ModContainer container) {
        REGISTRATE.registerEventListeners(modBus);
        container.registerConfig(ModConfig.Type.SERVER, PipesNPhysicsConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, PipesNPhysicsConfig.CLIENT_SPEC);

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);
        modBus.addListener(RegisterGameTestsEvent.class, event ->
                event.register(PipesNPhysicsGameTests.class));

        NeoForge.EVENT_BUS.register(GravityFlowHandler.class);
        NeoForge.EVENT_BUS.register(PipeSwapHandler.class);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                DebugGraphCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            SableCompat.clearCaches();
            GravityFlowHandler.clearAllCooldowns();
        });

        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.register(PumpRangeRenderer.class);
            modBus.register(ClientEvents.class);
        }
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Common setup...");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Client setup...");
        net.createmod.ponder.foundation.PonderIndex.addPlugin(new PipesNPhysicsPonderPlugin());
    }
}
