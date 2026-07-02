package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Wakes pipe networks when a chunk loads. {@code GraphBuilder}'s BFS stops at an unloaded chunk and
 * drops the edge that crossed it, so the still-loaded half solves a truncated topology; nothing else
 * re-checks when the far chunk returns. Marking the loaded chunk's pipe cells re-solves any network
 * that reaches into it, healing a run that a chunk unload had severed.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID)
public final class ChunkWakeHandler {
    private ChunkWakeHandler() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        for (BlockPos pos : event.getChunk().getBlockEntitiesPos()) {
            if (FluidPropagator.getPipe(level, pos) != null) EngineTickHandler.markChanged(level, pos);
        }
    }
}
