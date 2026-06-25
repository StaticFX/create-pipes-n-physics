package de.devin.pipesnphysics;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.OpenEndPipes;
import de.devin.pipesnphysics.engine.command.PipeGraphCommand;
import de.devin.pipesnphysics.engine.net.EnginePackets;
import de.devin.pipesnphysics.handler.NetworkEditHandler;
import de.devin.pipesnphysics.handler.PipeSwapHandler;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
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
        modBus.addListener(EnginePackets::register);

        NeoForge.EVENT_BUS.register(EngineTickHandler.class);
        NeoForge.EVENT_BUS.register(PipeSwapHandler.class);
        NeoForge.EVENT_BUS.register(NetworkEditHandler.class);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                PipeGraphCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            SableCompat.clearCaches();
            EngineTickHandler.clear();
            OpenEndPipes.clear();
        });
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Common setup...");
    }
}
