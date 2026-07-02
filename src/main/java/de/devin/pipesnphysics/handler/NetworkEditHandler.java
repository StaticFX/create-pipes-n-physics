package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.OpenEndPipes;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.BlockEvent;

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
            wakeAround(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level) wakeAround(level, event.getPos());
    }

    /**
     * Wake the network(s) at pos when the edited block is itself part of one — a
     * pipe/pump, or a fluid handler (tank/basin) joining or leaving it. A block merely
     * placed or broken NEXT TO a pipe changes no topology and must not re-solve the
     * network. Marking the six neighbors as well wakes BOTH halves of a run split by
     * the edit, and reaches the pipe beside a tank that was just placed or removed.
     */
    private static void wakeAround(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        if (!isPipe(level, pos) && !hasHandler(level, pos)) return;

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
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int height = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        BlockPos base = controller.getBlockPos();
        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = 0; dz < width; dz++) {
                    BlockPos cell = base.offset(dx, dy, dz);
                    for (Direction dir : Direction.values()) {
                        BlockPos neighbor = cell.relative(dir);
                        if (isPipe(level, neighbor)) EngineTickHandler.markChanged(level, neighbor);
                    }
                }
            }
        }
    }

    private static boolean isPipe(Level level, BlockPos pos) {
        return level.isLoaded(pos) && FluidPropagator.getPipe(level, pos) != null;
    }

    private static boolean hasHandler(Level level, BlockPos pos) {
        return level.isLoaded(pos)
                && level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null;
    }
}
