package de.devin.pipesnphysics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the player at the screen is holding, asked of Create's placement registry — the client half
 * of {@link DialSlot}, kept in its own class so the common one carries no client type at all (a
 * dedicated server would resolve it while building a pump's block entity and crash).
 *
 * Only ever called from the render path, where the local player is the only one whose hand can
 * matter.
 */
public final class HeldPlacement {
    private HeldPlacement() {}

    /** Whether the local player's held item would place a block on this one. */
    public static boolean wouldPlace(BlockState state) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && DialSlot.wouldPlace(player.getMainHandItem(), state);
    }
}
