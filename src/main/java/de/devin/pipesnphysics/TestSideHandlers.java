package de.devin.pipesnphysics;

import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * DEV/TEST-ONLY side-specific fluid handler, so the per-face endpoint path (one block serving a
 * DIFFERENT fluid on each side, CLAUDE.md §2 / {@link de.devin.pipesnphysics.engine.HandlerRoles})
 * can be GameTested — no block in the pack is genuinely side-specific (the docking connector is
 * side-agnostic), so the feature is otherwise untestable.
 *
 * Two shapes, on two inert blocks, for the two per-face cases {@code GraphBuilder} must handle:
 *  - {@link Blocks#SPONGE} — a separate {@link FluidTank} on each HORIZONTAL face and NOTHING on the
 *    {@code null} side: the no-null-cap side-specific shape.
 *  - {@link Blocks#WET_SPONGE} — the shape of TFMG's coke oven: ONE shared PRIMARY tank on the
 *    {@code null} side and every non-top face, plus a DIFFERENT SECONDARY tank on {@link Direction#UP}.
 *    A null cap exists, so the engine only reads the top tank if it treats "a face whose handler differs
 *    from the null side" as side-specific (identity, not null-cap-absence).
 *  - {@link Blocks#OBSIDIAN} — the shape of TFMG's blast stove: a provider that THROWS on the
 *    {@code null}-side query its lambda never expected (NeoForge allows null there), serving a tank
 *    only on real faces. The engine must degrade it to side-specific, never crash the tick
 *    ({@code FluidCaps}).
 *  - {@link Blocks#DIAMOND_BLOCK} — the shape of TFMG's engines: SIDE-AGNOSTIC (one combined handler
 *    on the null side and every face) but multi-PORT — a fill-only INPUT tank the machine never gives
 *    back and a drain-only OUTPUT tank. Nothing in stock Create has it (a spout is fill-only but
 *    single-tank, a basin drains everything it holds), and it is the shape that reads as
 *    "incompatible with its own supply" to a naive drain probe.
 * Backing tanks are per BlockPos so a test can pre-fill a side and read it back through the engine.
 * Registration is gated to {@code !production} by the caller, so it never ships.
 */
public final class TestSideHandlers {
    public static final int TANK_CAPACITY = 16000;
    private static final Map<BlockPos, EnumMap<Direction, FluidTank>> TANKS = new HashMap<>();
    private static final Map<BlockPos, FluidTank> PRIMARY = new HashMap<>();
    private static final Map<BlockPos, FluidTank> SECONDARY = new HashMap<>();
    private static final Map<BlockPos, FluidTank> MACHINE_INPUT = new HashMap<>();
    private static final Map<BlockPos, FluidTank> MACHINE_OUTPUT = new HashMap<>();
    private static final Map<BlockPos, IFluidHandler> MACHINE = new HashMap<>();

    private TestSideHandlers() {}

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, be, side) -> {
            if (side == null || side.getAxis().isVertical()) return null; // side-specific: N/E/S/W only
            return tankAt(pos, side);
        }, Blocks.SPONGE);
        // Coke-oven shape: null + non-top faces share ONE handler (identity holds, like a Create tank),
        // the top exposes a DIFFERENT one — so it is side-specific only under the identity discriminator.
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, be, side) ->
                side == Direction.UP ? secondaryAt(pos) : primaryAt(pos), Blocks.WET_SPONGE);
        // Blast-stove shape: the lambda dereferences the side unconditionally, so the (legal)
        // null-side query throws — the crash a foreign provider once caused inside the graph BFS.
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, be, side) -> {
            if (side.getAxis().isVertical()) return null;
            return primaryAt(pos);
        }, Blocks.OBSIDIAN);
        // Engine shape: one combined handler on every side (so it couples like a tank), holding an
        // input tank it only ever accepts into and an output tank it only ever gives from.
        event.registerBlock(Capabilities.FluidHandler.BLOCK,
                (level, pos, state, be, side) -> machineAt(pos), Blocks.DIAMOND_BLOCK);
    }

    /** The backing tank for one face — a test fills this, then reads it through the engine. */
    public static FluidTank tankAt(BlockPos pos, Direction side) {
        return TANKS.computeIfAbsent(pos.immutable(), p -> new EnumMap<>(Direction.class))
                .computeIfAbsent(side, s -> new FluidTank(TANK_CAPACITY));
    }

    /** The coke-oven PRIMARY (creosote) tank — one object shared by the null side and every non-top face. */
    public static FluidTank primaryAt(BlockPos pos) {
        return PRIMARY.computeIfAbsent(pos.immutable(), p -> new FluidTank(TANK_CAPACITY));
    }

    /** The coke-oven SECONDARY (CO2) tank — the distinct handler on the top face only. */
    public static FluidTank secondaryAt(BlockPos pos) {
        return SECONDARY.computeIfAbsent(pos.immutable(), p -> new FluidTank(TANK_CAPACITY));
    }

    /** The engine-shaped machine's OUTPUT tank — the only thing it will hand back out. */
    public static FluidTank machineOutputAt(BlockPos pos) {
        return MACHINE_OUTPUT.computeIfAbsent(pos.immutable(), p -> new FluidTank(TANK_CAPACITY) {
            @Override
            public int fill(FluidStack resource, FluidAction action) {
                return 0;
            }
        });
    }

    /** The engine-shaped machine's INPUT tank — it consumes what it takes and never gives it back. */
    public static FluidTank machineInputAt(BlockPos pos) {
        return MACHINE_INPUT.computeIfAbsent(pos.immutable(), p -> new FluidTank(TANK_CAPACITY) {
            @Override
            public FluidStack drain(FluidStack resource, FluidAction action) {
                return FluidStack.EMPTY;
            }

            @Override
            public FluidStack drain(int maxDrain, FluidAction action) {
                return FluidStack.EMPTY;
            }
        });
    }

    /** The machine's one side-agnostic capability: output first, exactly as Create's basin orders its own. */
    public static IFluidHandler machineAt(BlockPos pos) {
        return MACHINE.computeIfAbsent(pos.immutable(),
                p -> new CombinedTankWrapper(machineOutputAt(p), machineInputAt(p)));
    }

    public static void clear() {
        TANKS.clear();
        PRIMARY.clear();
        SECONDARY.clear();
        MACHINE_INPUT.clear();
        MACHINE_OUTPUT.clear();
        MACHINE.clear();
    }
}
