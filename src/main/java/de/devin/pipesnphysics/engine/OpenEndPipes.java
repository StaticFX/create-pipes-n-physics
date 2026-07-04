package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.FluidPropagator;
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

    /**
     * Game-time of the last deposit out of each hose pulley (keyed by the pulley pos). A pulley
     * that just pushed fluid into the world reads its own fresh block as a drainable body the next
     * tick; under drain-priority that would flip it back to a source and reclaim its output. While
     * a pulley is recently-deposited it is held as a one-way sink instead — see
     * {@code BoundaryColumn.resolve}. Same latch idea as {@link #recentlySpilled}, one node down.
     */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> PULLEY_DEPOSIT_TICKS = new HashMap<>();

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

    /** Record that a hose pulley just took fluid to deposit into the world (called when a fill executes). */
    public static void markPulleyDeposited(Level level, BlockPos pulleyPos) {
        PULLEY_DEPOSIT_TICKS.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .put(pulleyPos.immutable(), level.getGameTime());
    }

    /** Whether this pulley deposited within the last {@code cooldown} ticks (stale entries pruned on read). */
    public static boolean pulleyRecentlyDeposited(Level level, BlockPos pulleyPos, int cooldown) {
        Map<BlockPos, Long> ticks = PULLEY_DEPOSIT_TICKS.get(level.dimension());
        if (ticks == null) return false;
        Long when = ticks.get(pulleyPos);
        if (when == null) return false;
        if (level.getGameTime() - when < cooldown) return true;
        ticks.remove(pulleyPos);
        return false;
    }

    /** Get or create the handler for an open end discovered by the graph builder. */
    public static IFluidHandler handler(Level level, BlockPos spacePos, Direction faceTowardPipe) {
        Map<BlockPos, OpenEndedPipe> map = CACHE.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        BlockPos expectedPipe = spacePos.relative(faceTowardPipe);
        OpenEndedPipe pipe = map.get(spacePos);
        // A cached instance bakes its BlockFace at creation. If a differently-oriented pipe now faces
        // this space (the old mouth was broken and rebuilt from another side), that geometry is stale,
        // so discard the instance and rebuild it for the current mouth.
        if (pipe != null && !pipe.getPos().equals(expectedPipe)) {
            flush(level, pipe);
            pipe = null;
        }
        if (pipe == null) {
            pipe = build(spacePos, faceTowardPipe);
            map.put(spacePos.immutable(), pipe);
        }
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
        // Transfer time has no face argument, so recover the current mouth by scanning the space's
        // neighbours; a mouth rebuilt facing a new direction must not be served through the stale face.
        Direction currentFace = currentMouthFace(level, spacePos);
        if (currentFace != null && !pipe.getPos().equals(spacePos.relative(currentFace))) {
            flush(level, pipe);
            pipe = build(spacePos, currentFace);
            pipes.put(spacePos.immutable(), pipe);
        }
        pipe.manageSource(level, null);
        return pipe.provideHandler().getCapability();
    }

    /**
     * A pipe cell was removed; drop any cached mouth whose pipe was there so its buffer is not
     * leaked and a rebuilt mouth starts clean. A completed source unit (≥ 1000 mB) is flushed first
     * so it is not silently voided; the sub-1000 transient buffer has no vanilla-granular destination
     * on a severed network and is lost, matching Create's own open-end buffer semantics. A no-op for a
     * break that is not a cached mouth's pipe (a tank, or the block the mouth opens into).
     */
    public static void onPipeRemoved(Level level, BlockPos brokenPipePos) {
        Map<BlockPos, OpenEndedPipe> pipes = CACHE.get(level.dimension());
        if (pipes == null) return;
        for (Direction d : Direction.values()) {
            BlockPos space = brokenPipePos.relative(d);
            OpenEndedPipe pipe = pipes.get(space);
            if (pipe == null || !pipe.getPos().equals(brokenPipePos)) continue;
            flush(level, pipe);
            pipes.remove(space);
        }
    }

    private static OpenEndedPipe build(BlockPos spacePos, Direction faceTowardPipe) {
        return new OpenEndedPipe(
                new BlockFace(spacePos.relative(faceTowardPipe), faceTowardPipe.getOpposite()));
    }

    /** The face from this space toward the pipe whose open mouth currently opens into it, or null. */
    private static Direction currentMouthFace(Level level, BlockPos spacePos) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = spacePos.relative(d);
            if (FluidPropagator.getPipe(level, neighbor) != null
                    && FluidPropagator.isOpenEnd(level, neighbor, d.getOpposite())) {
                return d;
            }
        }
        return null;
    }

    /** Best-effort: let Create place a completed source unit rather than void it on prune/rebuild. */
    private static void flush(Level level, OpenEndedPipe pipe) {
        if (pipe.provideHandler().getCapability().getFluidInTank(0).getAmount() >= 1000) {
            pipe.manageSource(level, null);
        }
    }

    /** Discard everything — called on server stop. */
    public static void clear() {
        CACHE.clear();
        SPILL_TICKS.clear();
        PULLEY_DEPOSIT_TICKS.clear();
    }
}
