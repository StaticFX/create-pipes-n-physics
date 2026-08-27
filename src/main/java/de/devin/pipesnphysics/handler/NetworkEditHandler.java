package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.boundary.FluidCaps;
import de.devin.pipesnphysics.engine.boundary.FluidTankGeometry;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.graph.GraphCache;
import de.devin.pipesnphysics.engine.pump.Pumps;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Wakes the engine when a block is broken or placed that changes a pipe network's
 * topology.
 *
 * A sleeping network suppresses the routine per-tick {@link EngineTickHandler#markDirty}
 * mark, and a plain block break or place issues no other notification, so without this
 * a topology edit to a settled network — breaking a pipe/pump/valve (which may split it)
 * or a tank, or placing a tank against it — would stay invisible until the idle heartbeat
 * fires (up to {@code IDLE_RECHECK_TICKS} later). Placing a NEW pipe or pump is already
 * picked up (its fresh position is not asleep); the gap this closes is removals and
 * handler-block placements that touch only already-quiet cells.
 *
 * NOT covered: in-place blockstate changes that move no block — most notably a fluid
 * valve toggled OPEN by redstone on a sleeping network; that still waits out the
 * heartbeat (it fires neither event). Same for piston-moved or explosion-cleared pipes.
 *
 * Both events fire before the world settles, but {@link EngineTickHandler#markChanged}
 * only queues an URGENT seed; the actual graph rebuild runs next server tick against the
 * final topology.
 */
public final class NetworkEditHandler {
    private NetworkEditHandler() {}

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide()) {
            // Prune any open-end mouth whose pipe this was, so its buffer is not leaked and a rebuilt
            // mouth starts clean (a no-op for a non-mouth break).
            OpenEndPipes.onPipeRemoved(level, event.getPos());
            // Drop any sticky pulley output role at this pos, so a rebuilt pulley may drain again.
            OpenEndPipes.forgetPulley(level, event.getPos());
            // Drop any learned relay role, so a tank placed here does not inherit the demotion.
            RelayDetector.forget(event.getPos());
            wakeAround(level, event.getPos());
        }
    }

    /**
     * A broken pipe cell SPILLS its stored fluid instead of voiding it: the content is pushed back
     * into adjacent pipe cells and tanks with room, and a remaining bucket's worth becomes a source
     * block at the broken position (honoring Create's place-source config and ultra-warm
     * evaporation). Only the dregs that fit nowhere are lost — a splash, like opening real
     * plumbing. Runs at LOWEST priority and re-checks on the next tick that the pipe really went
     * away, so a cancelled break keeps its fluid.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreakSpill(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        PipeStore.Store store = PipeStore.at(level, event.getPos());
        if (store == null || store.amount() <= 0) return;
        BlockPos pos = event.getPos().immutable();
        FluidStack content = store.fluid().copy();
        level.getServer().execute(() -> spillBrokenPipe(level, pos, content));
    }

    /** Exposed for the GameTest — the BreakEvent that normally schedules this is player-only. */
    public static void spillBrokenPipe(ServerLevel level, BlockPos pos, FluidStack content) {
        if (PipeStore.at(level, pos) != null) return; // break was cancelled; the cell kept its fluid
        // This fluid is leaving as a spill: consume any carry stash for it, so a pipe re-placed
        // within the claim window can never adopt what was already spilled (a dupe).
        PipeContentCarry.claim(level, pos);
        int remaining = spillIntoNeighbors(level, pos, content);

        if (remaining >= FluidType.BUCKET_VOLUME
                && AllConfigs.server().fluids.pipesPlaceFluidSourceBlocks.get()
                && level.getBlockState(pos).canBeReplaced()
                && content.getFluid() instanceof FlowingFluid flowing) {
            if (content.getFluid().getFluidType().isVaporizedOnPlacement(level, pos, content)) {
                content.getFluid().getFluidType().onVaporize(null, level, pos, content);
            } else {
                level.setBlock(pos, flowing.getSource().defaultFluidState().createLegacyBlock(), 3);
            }
        }
        // Whatever neither fit back nor placed is the splash of opening a wet line — gone.
    }

    /**
     * Push content back into the network around pos — adjacent pipe cells first, then adjacent
     * tanks/machines — returning what fit nowhere. A pump also carries the pipe behaviour but
     * stores nothing in the flow model (fluid pushed into it would strand), so it is skipped.
     * A handler deposit is reported to the {@link RelayDetector} like every other engine fill,
     * or the spill would read as a spontaneous gain and strike the receiver toward relay demotion.
     */
    public static int spillIntoNeighbors(ServerLevel level, BlockPos pos, FluidStack content) {
        int remaining = content.getAmount();
        for (Direction dir : Direction.values()) {
            if (remaining <= 0) return 0;
            BlockPos neighborPos = pos.relative(dir);
            if (Pumps.isPump(level, neighborPos, level.getBlockState(neighborPos))) continue;
            PipeStore.Store neighbor = PipeStore.at(level, neighborPos);
            if (neighbor != null) {
                remaining -= neighbor.insert(content, remaining);
                neighbor.flush();
            }
        }
        for (Direction dir : Direction.values()) {
            if (remaining <= 0) return 0;
            BlockPos neighborPos = pos.relative(dir);
            IFluidHandler handler = FluidCaps.at(level, neighborPos, dir.getOpposite());
            if (handler != null) {
                int filled = handler.fill(content.copyWithAmount(remaining), FluidAction.EXECUTE);
                if (filled > 0) {
                    remaining -= filled;
                    RelayDetector.recordApplied(neighborPos, filled);
                }
            }
        }
        return remaining;
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level) wakeAround(level, event.getPos());
    }

    /**
     * Wake the network(s) at pos when the edited block is itself part of one — a
     * pipe/pump, a fluid handler (tank/basin) joining or leaving it, or a cell a cached
     * network covers (an open-end mouth is a coverage cell but neither pipe nor handler,
     * so CAPPING one would otherwise slip past this gate and leave the network solving a
     * mouth that no longer exists; UNCAPPING stays heartbeat-bounded — the break lands on
     * a cell the post-cap graph does not cover). A block merely placed or broken
     * NEXT TO a pipe changes no topology and must not re-solve the network. Marking the
     * six neighbors as well wakes BOTH halves of a run split by the edit, and reaches
     * the pipe beside a tank that was just placed or removed.
     */
    private static void wakeAround(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        if (!isPipe(level, pos) && !hasHandler(level, pos) && !GraphCache.isCovered(level, pos)) return;

        EngineTickHandler.markChanged(level, pos);
        for (Direction dir : Direction.values()) {
            EngineTickHandler.markChanged(level, pos.relative(dir));
        }
        wakeThroughTank(level, pos);
    }

    /**
     * A multiblock tank's single pipe connection can be many blocks from the edited cell — well
     * outside {@link GraphBuilder#findSeed}'s one-block ring — so a break/place on a far corner would
     * never wake the network. Walk the whole controller footprint and mark any pipe adjacent to any of
     * its blocks. A no-op for a non-tank edit or a single-block tank (already covered by the ring).
     */
    public static void wakeThroughTank(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return;
        FluidTankBlockEntity controller = tank.getControllerBE();
        if (controller == null) return;
        for (BlockPos cell : FluidTankGeometry.footprint(controller)) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = cell.relative(dir);
                if (isPipe(level, neighbor)) EngineTickHandler.markChanged(level, neighbor);
            }
        }
    }

    private static boolean isPipe(Level level, BlockPos pos) {
        return level.isLoaded(pos) && FluidPropagator.getPipe(level, pos) != null;
    }

    private static boolean hasHandler(Level level, BlockPos pos) {
        return level.isLoaded(pos) && FluidCaps.at(level, pos, null) != null;
    }
}
