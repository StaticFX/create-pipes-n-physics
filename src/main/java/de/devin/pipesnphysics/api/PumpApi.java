package de.devin.pipesnphysics.api;

import net.minecraft.world.level.block.Block;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declare in code that a block is a PUMP — the programmatic counterpart of the
 * {@code pipesnphysics:pumps} block tag, for a pump whose block entity cannot implement
 * {@link FluidPump} (another mod's, most often). Call from your mod's setup, after blocks are
 * registered.
 *
 * A declared block only becomes a pump node where it really sits on Create's pipe network (it
 * carries a {@code FluidTransportBehaviour}); how hard it pumps is then read from the pressure it
 * publishes to that network, or from the shaft turning it.
 */
public final class PumpApi {
    private static final Set<Block> PUMPS = ConcurrentHashMap.newKeySet();

    private PumpApi() {}

    /** Declare a block a pump; declaring the same block twice is harmless. */
    public static void declarePump(Block block) {
        PUMPS.add(block);
    }

    /** Undo a declaration, leaving the block to the tag (or to being a plain pipe). */
    public static void clearPump(Block block) {
        PUMPS.remove(block);
    }

    /** Whether a block was declared a pump in code — the engine merges this with the tag. */
    public static boolean isDeclaredPump(Block block) {
        return PUMPS.contains(block);
    }
}
