package de.devin.pipesnphysics.api;

import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declare in code what role the engine should give a fluid-handler block — the programmatic
 * counterpart of the role block tags. Say a block is a tank, a relay, or one to ignore, and the engine
 * treats it that way without the caller needing a datapack. Call from your mod's setup, after your
 * blocks are registered. A matching block tag still takes precedence, so a pack can override; either
 * kind of explicit role overrides the engine's automatic relay detection.
 */
public final class FluidHandlerApi {
    private static final Map<Block, FluidHandlerRole> ROLES = new ConcurrentHashMap<>();
    private static final Set<Block> SEPARATE_PORTS = ConcurrentHashMap.newKeySet();

    private FluidHandlerApi() {}

    /**
     * Declare that this block's pipe connections are separate PORTS rather than one shared body of
     * fluid — the code twin of the {@code pipesnphysics:separate_ports} block tag, for a machine
     * that keeps each port in its own internal tank behind one combined capability (a fuel inlet,
     * an air inlet, an exhaust outlet).
     *
     * The engine otherwise treats every pipe touching a handler as joined through it, the way two
     * pipes on a tank really are, and one line's fluid can then leave by another line's port.
     * Nothing needs declaring if your faces already expose DIFFERENT handler objects — that is
     * detected on its own. Call from your mod's setup, after your blocks are registered.
     */
    public static void declareSeparatePorts(Block block) {
        SEPARATE_PORTS.add(block);
    }

    /** Undo {@link #declareSeparatePorts}, falling the block back to the tag (and to coupling). */
    public static void clearSeparatePorts(Block block) {
        SEPARATE_PORTS.remove(block);
    }

    /** Whether a block was declared multi-port in code; the engine merges this with the tag. */
    public static boolean hasSeparatePorts(Block block) {
        return !SEPARATE_PORTS.isEmpty() && SEPARATE_PORTS.contains(block);
    }

    /** Declare a block's role in the pipe network; a later call for the same block overwrites it. */
    public static void setRole(Block block, FluidHandlerRole role) {
        ROLES.put(block, role);
    }

    /** Remove a block's code role, falling it back to tags / automatic detection. */
    public static void clearRole(Block block) {
        ROLES.remove(block);
    }

    /** The code-registered role for a block, or null if none — the engine merges this under the tags. */
    public static FluidHandlerRole role(Block block) {
        return ROLES.get(block);
    }
}
