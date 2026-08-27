package de.devin.pipesnphysics.api;

import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Register a block as a TURBINE the engine drives: it sits on a pipe run, the engine takes its rated
 * head out of the line, and every tick it is handed the fluid that fell through it.
 *
 * There is deliberately no block tag for this, unlike pumps: a turbine nobody can hand power to is
 * just a restriction in the pipe, so declaring one always means supplying the {@link TurbineAdapter}
 * that accepts its output. Call from your mod's setup, after your blocks are registered.
 */
public final class TurbineApi {
    private static final Map<Block, TurbineAdapter> TURBINES = new ConcurrentHashMap<>();

    private TurbineApi() {}

    /** Declare a block a turbine and say how its power is delivered; a later call replaces it. */
    public static void registerTurbine(Block block, TurbineAdapter adapter) {
        TURBINES.put(block, adapter);
    }

    /** Undo a registration, leaving the block an ordinary part of the pipe run again. */
    public static void clearTurbine(Block block) {
        TURBINES.remove(block);
    }

    /** The adapter registered for a block, or null if it is not a turbine. */
    public static TurbineAdapter adapter(Block block) {
        return TURBINES.get(block);
    }

    /** Whether any adapter is registered for this block — identity only, not "is it turning". */
    public static boolean isTurbineBlock(Block block) {
        return TURBINES.containsKey(block);
    }
}
