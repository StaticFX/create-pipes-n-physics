package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.EncasedPipeBlock;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.IAxisPipe;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shift + right-click a pipe element (pump, valve, smart fluid pipe, pipe) held in hand onto another
 * pipe element to replace it in place, refunding the old block's drops. Toggled by the client-side
 * {@code enablePipeSwap} config.
 */
public class PipeSwapHandler {
    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK) return;

        Player player = event.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return;
        // A CLIENT toggle: honored on the integrated (singleplayer) server, which shares the client's
        // JVM. A dedicated server can't read a per-client preference, so it always allows the swap.
        if (FMLEnvironment.dist == Dist.CLIENT && !PipesNPhysicsConfig.ENABLE_PIPE_SWAP.get()) return;

        Level level = event.getLevel();
        if (level.isClientSide()) return;

        ItemStack held = player.getItemInHand(event.getHand());
        if (!(held.getItem() instanceof BlockItem bi)) return;
        Block heldBlock = bi.getBlock();
        if (!isPipingBlock(heldBlock)) return;

        BlockPos pos = event.getPos();
        BlockState targetState = level.getBlockState(pos);
        // Only replace another pipe element, and never with the same block (a no-op that would eat the item).
        if (!isPipingBlock(targetState.getBlock()) || targetState.is(heldBlock)) return;

        BlockState placed = placementState(heldBlock, level, pos, targetState, player, event.getUseOnContext());
        if (placed == null) return;

        // Refund the old block's REAL loot (an encased pipe's casing, a smart pipe's filter) straight into
        // the player's inventory — a synthesized asItem() stack is AIR for an encased pipe. Skipped in
        // creative, like a creative break. Collect the drops BEFORE setBlock removes the old BE below (do
        // NOT pre-remove it, or the loot loses its BE context); overflow that won't fit is dropped by the
        // player.
        if (!player.isCreative()) {
            if (level instanceof ServerLevel serverLevel) {
                for (ItemStack drop : Block.getDrops(targetState, serverLevel, pos, level.getBlockEntity(pos))) {
                    ItemHandlerHelper.giveItemToPlayer(player, drop);
                }
            }
            held.shrink(1);
        }
        level.setBlock(pos, placed, Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);

        FluidPropagator.propagateChangedPipe(level, pos, placed);
        EngineTickHandler.markChanged(level, pos);

        event.cancelWithResult(ItemInteractionResult.SUCCESS);
    }

    /** Every block that is a fluid-network element: pumps, valves, smart pipes, glass/encased/plain pipes. */
    private static boolean isPipingBlock(Block block) {
        return block instanceof PumpBlock
                || block instanceof FluidPipeBlock
                || block instanceof EncasedPipeBlock
                || block instanceof IAxisPipe; // glass pipe, smart fluid pipe, fluid valve
    }

    /**
     * The state to place. Every block but the pump is oriented through its own {@code getStateForPlacement}
     * (which aligns to neighbouring pipes), placed AT the target cell. The pump is oriented manually along
     * the run instead: its own placement inverts under a held shift key (the very key this swap requires),
     * which would face it by look direction rather than the pipe axis.
     */
    private static BlockState placementState(Block heldBlock, Level level, BlockPos pos, BlockState targetState,
                                             Player player, UseOnContext useOn) {
        if (heldBlock instanceof PumpBlock pump) {
            return pump.defaultBlockState().setValue(PumpBlock.FACING, pumpFacing(level, pos, targetState, player));
        }
        BlockPlaceContext ctx = new BlockPlaceContext(useOn) {
            { this.replaceClicked = true; } // getClickedPos() then resolves to the target cell, not its neighbour
        };
        BlockState placed = heldBlock.getStateForPlacement(ctx);
        return placed != null ? placed : heldBlock.defaultBlockState();
    }

    /** A pump replacing a straight run faces along its axis; otherwise it faces the player's look direction. */
    private static Direction pumpFacing(Level level, BlockPos pos, BlockState targetState, Player player) {
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
        if (pipe != null) {
            List<Direction> connections = new ArrayList<>();
            for (Direction d : FluidPropagator.getPipeConnections(targetState, pipe)) {
                connections.add(d);
            }
            if (connections.size() == 2 && connections.get(0).getOpposite() == connections.get(1)) {
                return connections.get(0);
            }
        }
        return player.getDirection();
    }
}
