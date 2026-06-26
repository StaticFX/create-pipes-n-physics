package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.OpenEndedPipe;
import de.devin.pipesnphysics.mixin.OpenEndedPipeAccessor;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
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

    /**
     * Game-time of the last spill out of each open mouth (keyed by its space pos). Used to
     * keep a network from sucking a finite source straight back after spilling it — see
     * {@link #recentlySpilled}. In-memory (rebuilt as the world runs); a settled spill
     * sits at the mouth with no suction, so a cleared map never reclaims one on reload.
     */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> SPILL_TICKS = new HashMap<>();

    private OpenEndPipes() {}

    /** Record that an open mouth just spilled into the world (called when a spill executes). */
    public static void markSpilled(Level level, BlockPos spacePos) {
        SPILL_TICKS.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .put(spacePos.immutable(), level.getGameTime());
    }

    /**
     * Whether this mouth spilled within the last {@code cooldown} ticks. Stale entries are
     * pruned on read so the map stays bounded to recently-active mouths.
     */
    public static boolean recentlySpilled(Level level, BlockPos spacePos, int cooldown) {
        Map<BlockPos, Long> ticks = SPILL_TICKS.get(level.dimension());
        if (ticks == null) return false;
        Long when = ticks.get(spacePos);
        if (when == null) return false;
        if (level.getGameTime() - when < cooldown) return true;
        ticks.remove(spacePos);
        return false;
    }

    /** Get or create the handler for an open end discovered by the graph builder. */
    public static IFluidHandler handler(Level level, BlockPos spacePos, Direction faceTowardPipe) {
        OpenEndedPipe pipe = CACHE
                .computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(spacePos.immutable(), k -> new OpenEndedPipe(
                        new BlockFace(spacePos.relative(faceTowardPipe), faceTowardPipe.getOpposite())));
        pipe.manageSource(level, null);
        return pipe.provideHandler().getCapability();
    }

    /**
     * Fluid a prior INTAKE left buffered in this mouth and still owes the network, or
     * EMPTY. Create drains a source atomically (a whole 1000 mB cauldron) but the engine
     * delivers at most {@code MAX_FLOW_PER_ENDPOINT} per tick, so the surplus sits in the
     * pipe's internal tank until later ticks flush it. Gated on {@code wasPulling} so the
     * SAME internal tank used for spill accumulation (toward placing a block) is never
     * mistaken for intake residual — that would flip a spilling mouth into a source and
     * break spill entirely.
     */
    public static FluidStack bufferedIntake(Level level, BlockPos spacePos) {
        Map<BlockPos, OpenEndedPipe> pipes = CACHE.get(level.dimension());
        if (pipes == null) return FluidStack.EMPTY;
        OpenEndedPipe pipe = pipes.get(spacePos);
        if (pipe == null || !((OpenEndedPipeAccessor) pipe).pipesnphysics$wasPulling()) {
            return FluidStack.EMPTY;
        }
        pipe.manageSource(level, null);
        return pipe.provideHandler().getCapability().getFluidInTank(0);
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
        SPILL_TICKS.clear();
    }
}
