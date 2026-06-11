package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.OpenEndedPipe;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the live {@link OpenEndedPipe} behind each OPEN_END node, keyed by the
 * world-space block the pipe opens into. Create's open ends interact with the
 * world (placing and picking up fluid, filling cauldrons, watering farmland) and
 * buffer partial amounts while doing so, so the instances must survive across
 * ticks — a fresh instance each tick would forget the 999 mB it had collected
 * toward placing a source block.
 */
public final class OpenEndPipes {

    private static final Map<ResourceKey<Level>, Map<BlockPos, OpenEndedPipe>> CACHE = new HashMap<>();

    private OpenEndPipes() {}

    /** Get or create the handler for an open end discovered by the graph builder. */
    public static IFluidHandler handler(Level level, BlockPos spacePos, Direction faceTowardPipe) {
        OpenEndedPipe pipe = CACHE
                .computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(spacePos.immutable(), k -> new OpenEndedPipe(
                        new BlockFace(spacePos.relative(faceTowardPipe), faceTowardPipe.getOpposite())));
        pipe.manageSource(level, null);
        return pipe.provideHandler().getCapability();
    }

    /** The handler for an already-discovered open end, or null. Used at transfer time. */
    public static IFluidHandler existing(Level level, BlockPos spacePos) {
        Map<BlockPos, OpenEndedPipe> pipes = CACHE.get(level.dimension());
        if (pipes == null) return null;
        OpenEndedPipe pipe = pipes.get(spacePos);
        if (pipe == null) return null;
        pipe.manageSource(level, null);
        return pipe.provideHandler().getCapability();
    }

    /** Discard everything — called on server stop. */
    public static void clear() {
        CACHE.clear();
    }
}
