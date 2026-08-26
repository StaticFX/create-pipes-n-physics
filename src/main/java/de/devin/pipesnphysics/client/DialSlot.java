package de.devin.pipesnphysics.client;

import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.function.BiPredicate;

/**
 * The value-box slot this mod's own scroll dials sit in: a centred side box that GETS OUT OF THE
 * WAY of Create's right-click placement.
 *
 * Holding a cogwheel and right-clicking a pump is supposed to place a cog beside it, but a value
 * box on that face swallows the click: Create runs its value-settings handler from
 * {@code PlayerInteractEvent.RightClickBlock} and cancels the event, so the item's own
 * {@code onItemUseFirst} — where the placement lives — never runs. The pump is the block that hurt,
 * since its dial covers all four faces square to the pipe axis, which includes both shaft faces.
 *
 * The test is Create's OWN placement registry rather than a list of items: a dial steps aside
 * exactly when some registered {@link IPlacementHelper} matches BOTH the held item and this block,
 * so cogwheels, shafts and any addon's helper are covered without naming one of them.
 *
 * Two halves, because they answer to different callers: {@code bypassesInput} on the BEHAVIOUR lets
 * the click through (the input handler asks it first, on both sides), and {@link #shouldRender}
 * here stops the icon being drawn while such an item is held — Create's own bypass leaves the box
 * standing but passive, which still reads as an interface in the way.
 *
 * This class is COMMON (the behaviour holding it is built on the server too), so it carries no
 * client-only import: the local player lives one class further out in {@link HeldPlacement}, which
 * a dedicated server therefore never loads. Referencing a client TYPE here — even inside a
 * dist-guarded method body — is not enough, because the verifier resolves it when this class is
 * first used, and a server crashes building the pump's block entity. The GameTest suite runs a
 * dedicated server and catches exactly that.
 */
public class DialSlot extends CenteredSideValueBoxTransform {
    public DialSlot(BiPredicate<BlockState, Direction> allowedDirections) {
        super(allowedDirections);
    }

    @Override
    public boolean shouldRender(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!super.shouldRender(level, pos, state)) return false;
        return !FMLEnvironment.dist.isClient() || !HeldPlacement.wouldPlace(state);
    }

    /** Whether any registered placement helper acts on this block with this item in hand. */
    public static boolean wouldPlace(ItemStack held, BlockState state) {
        if (held.isEmpty()) return false;
        for (IPlacementHelper helper : PlacementHelpers.getHelpersView()) {
            if (helper.matchesItem(held) && helper.matchesState(state)) return true;
        }
        return false;
    }
}
