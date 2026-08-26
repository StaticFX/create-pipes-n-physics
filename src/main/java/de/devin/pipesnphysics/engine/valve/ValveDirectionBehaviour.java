package de.devin.pipesnphysics.engine.valve;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter.ScrollOptionSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import de.devin.pipesnphysics.client.DialSlot;
import de.devin.pipesnphysics.client.ValveArrowClient;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;


/**
 * The fluid valve's flow-direction dial: BOTH ways (the default) or ONE WAY toward either end of
 * its pipe axis — a check valve. A {@link ScrollOptionBehaviour} over {@link ValveFlowMode}, so
 * the value box shows the mode's ICON (double-arrow / single arrows) instead of text. It rides
 * beside the throttle as a second scroll behaviour, so it carries its OWN {@link BehaviourType}
 * (SmartBlockEntity keys behaviours by type; sharing ScrollValueBehaviour.TYPE would displace the
 * throttle) and its own NBT key (every stock ScrollValueBehaviour writes the shared "ScrollValue"
 * tag the throttle owns). The stored value is the mode ordinal: 0 = both ways, 1/2 = one way
 * toward the positive/negative end of the pipe axis. The drag board keeps the icons but renders
 * each notch's label as the RESOLVED world direction ("One-way → East") — the static enum names
 * would leave the choice axis-sign guesswork on a block with no arrow.
 */
public class ValveDirectionBehaviour extends ScrollOptionBehaviour<ValveFlowMode> {
    public static final BehaviourType<ValveDirectionBehaviour> TYPE = new BehaviourType<>();
    private static final String NBT_KEY = "OneWayFlow";

    public ValveDirectionBehaviour(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
        super(ValveFlowMode.class, label, be, slot);
    }

    /** The single allowed flow direction, or null for both ways (or a broken blockstate). */
    @Nullable
    public Direction oneWayFlow() {
        return directionFor(blockEntity.getBlockState(), getValue());
    }

    @Nullable
    public static Direction directionFor(BlockState state, int value) {
        if (value == ValveFlowMode.BOTH_WAYS.ordinal()
                || !(state.getBlock() instanceof FluidValveBlock)) {
            return null;
        }
        return Direction.fromAxisAndDirection(FluidValveBlock.getPipeAxis(state),
                value == ValveFlowMode.ONE_WAY_FORWARD.ordinal()
                        ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
    }

    /** "Both ways" or "One-way → East" — the drag board's per-notch text and the goggle value. */
    public static MutableComponent boxText(BlockState state, int value) {
        Direction direction = directionFor(state, value);
        if (direction == null) {
            return Component.translatable(ValveFlowMode.BOTH_WAYS.getTranslationKey());
        }
        return Component.translatable("pipesnphysics.gui.valve.direction.one_way",
                directionName(direction));
    }

    /** The shared "→ East"-style label — the same keys the goggle flow line uses. */
    public static Component directionName(Direction direction) {
        return Component.translatable("pipesnphysics.direction." + direction.getName());
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        BlockState state = blockEntity.getBlockState();
        // A ScrollOptionSettingsFormatter so the board runs in icon mode (and the cursor carries
        // the mode icon), with the per-notch text swapped for the resolved world direction.
        ScrollOptionSettingsFormatter formatter = new ScrollOptionSettingsFormatter(ValveFlowMode.values()) {
            @Override
            public MutableComponent format(ValueSettings valueSettings) {
                return boxText(state, valueSettings.value());
            }
        };
        return new ValueSettingsBoard(label, max, 1,
                ImmutableList.of(Component.translatable("pipesnphysics.gui.valve.direction.row")),
                formatter);
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        nbt.putInt(NBT_KEY, value); // NOT super.write: that key belongs to the throttle behaviour
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        value = nbt.getInt(NBT_KEY); // absent reads 0 = both ways, the correct default
        if (clientPacket) announceArrowHint();
    }

    @Override
    public void initialize() {
        super.initialize();
        announceArrowHint();
    }

    /**
     * Tell the client's arrow overlay about this valve ({@code ValveArrowClient} carries no
     * client-only imports, so this is safe from common code); the renderer prunes stale
     * entries itself, so only tracking needs announcing.
     */
    private void announceArrowHint() {
        Level world = getWorld();
        if (world == null || !world.isClientSide()) return;
        if (oneWayFlow() != null) {
            ValveArrowClient.track(getPos());
        } else {
            ValveArrowClient.untrack(getPos());
        }
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    /**
     * {@code ValueSettingsPacket} routes a board selection by {@code netId}, NOT by behaviour
     * type, and every behaviour defaults to 0 — so without a distinct id the dial's selection
     * landed on the THROTTLE (the first 0-id match), cranking it to 0-2° while the direction
     * never changed. Create's own coexisting behaviours do exactly this (FilteringBehaviour = 1,
     * factory panels 2+); 13 stays clear of both should Create ever add more to the valve.
     */
    @Override
    public int netId() {
        return 13;
    }

    @Override
    public String getClipboardKey() {
        return "ValveDirection";
    }

    /** Stand aside for Create's right-click placement, exactly as the pump's dial does. */
    @Override
    public boolean bypassesInput(ItemStack mainhandItem) {
        return DialSlot.wouldPlace(mainhandItem, blockEntity.getBlockState());
    }
}
