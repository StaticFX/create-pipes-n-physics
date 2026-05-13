package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Allows shift+right-clicking a pump on a pipe to replace the pipe with the pump.
 */
public class PipeSwapHandler {

    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK) return;

        Player player = event.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return;

        Level level = event.getLevel();
        if (level.isClientSide()) return;

        ItemStack held = player.getItemInHand(event.getHand());
        if (!(held.getItem() instanceof BlockItem bi && bi.getBlock() instanceof PumpBlock pumpBlock)) return;

        BlockPos pos = event.getPos();
        BlockState pipeState = level.getBlockState(pos);

        // Don't replace pumps with pumps
        if (pipeState.getBlock() instanceof PumpBlock) return;

        // Check if target is a pipe
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
        if (pipe == null) return;

        // Determine pump facing from the pipe's connections
        List<Direction> connections = new ArrayList<>();
        for (Direction d : FluidPropagator.getPipeConnections(pipeState, pipe)) {
            connections.add(d);
        }

        Direction pumpFacing;
        if (connections.size() == 2 && connections.get(0).getOpposite() == connections.get(1)) {
            // Straight pipe — align pump along pipe axis
            pumpFacing = connections.get(0);
        } else {
            // Non-straight pipe — use player look direction
            pumpFacing = player.getDirection();
        }

        // Get the pipe item to give back
        ItemStack pipeItem = new ItemStack(pipeState.getBlock().asItem());

        // Replace pipe with pump
        BlockState pumpState = pumpBlock.defaultBlockState().setValue(PumpBlock.FACING, pumpFacing);
        level.removeBlockEntity(pos);
        level.setBlock(pos, pumpState, Block.UPDATE_ALL);

        // Consume pump from hand (unless creative)
        if (!player.isCreative()) {
            held.shrink(1);
        }

        // Give pipe item to player
        if (!pipeItem.isEmpty() && !player.isCreative()) {
            if (!player.getInventory().add(pipeItem)) {
                player.drop(pipeItem, false);
            }
        }

        level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);

        // Trigger pipe network update
        FluidPropagator.propagateChangedPipe(level, pos, pumpState);

        event.cancelWithResult(ItemInteractionResult.SUCCESS);
    }
}
