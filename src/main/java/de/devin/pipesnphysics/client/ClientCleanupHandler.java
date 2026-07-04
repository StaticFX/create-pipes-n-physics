package de.devin.pipesnphysics.client;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.engine.net.PipeStatusClient;
import de.devin.pipesnphysics.engine.render.GraphOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Objects;

/**
 * Clears the client-side render/holder caches when the player leaves a world or crosses into another
 * dimension. Those holders key data by BlockPos and time it against the world's game-time, so without
 * this a rejoin or a nether portal would render ghost pump-range arrows, /pipegraph overlays, goggle
 * throttles, or waterline fades from the previous world — the old coordinates and a non-monotonic
 * clock leaking through. One hook clears every holder, so a new holder only needs a {@code clear()}.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class ClientCleanupHandler {
    private static ResourceKey<Level> lastDimension;

    private ClientCleanupHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        ResourceKey<Level> dimension = level == null ? null : level.dimension();
        if (Objects.equals(dimension, lastDimension)) return;
        lastDimension = dimension;
        clearAll();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lastDimension = null;
        clearAll();
    }

    private static void clearAll() {
        PumpRangeClient.clear();
        PipeStatusClient.clear();
        GraphOverlay.clear();
        PipeLevelRenderer.clear();
    }
}
