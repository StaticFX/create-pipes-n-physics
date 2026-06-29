package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.pipes.VanillaFluidTargets;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.mixin.accessor.FlowingFluidAccessor;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.ArrayList;
import java.util.List;

/**
 * The hydraulic view of one fluid endpoint: a vertical column of fluid with a base
 * elevation, a height, a capacity, and current contents.
 *
 * Create's multiblock tanks are resolved through their controller so a tank with
 * several pipe connections appears as ONE column (otherwise the solver would treat
 * each connection as a separate reservoir). Every other {@code IFluidHandler}
 * (basins, spouts, drains, other mods' machines) is treated as a one-block column.
 *
 * The column's fluid surface height — {@code baseY + fillFraction · height} — is the
 * head the solver equalizes. Two connected tanks therefore settle at the same
 * surface elevation, not the same volume, which is the communicating-vessels rule.
 */
public final class BoundaryColumn {
    /**
     * Capacity stand-in for the world behind an open pipe end: large enough that
     * its head barely moves within a tick (an atmospheric boundary), small enough
     * to stay well inside integer math.
     */
    private static final int OPEN_END_CAPACITY_MB = 4_000_000;

    /**
     * Capacity stand-in for a hose pulley drawing from a fluid body: large enough that
     * its head holds steady within a tick (the pulley lifts water to its own level under
     * kinetic power, so it reads as a brimming reservoir at the pulley), while the actual
     * per-tick volume is still clamped by what Create's drainer will hand over.
     */
    private static final int PULLEY_SOURCE_CAPACITY_MB = 4_000_000;

    private final BlockPos identity;
    private final BlockPos accessPos;
    private final double baseY;
    private final int heightBlocks;
    private final int capacityMb;
    private final FluidStack contents;
    private final int contentMb;
    private final Direction openFace;
    private final boolean infiniteSource;
    private final double fillScale;
    private final List<Integer> memberNodes = new ArrayList<>();

    private BoundaryColumn(BlockPos identity, BlockPos accessPos, double baseY,
                           int heightBlocks, int capacityMb, FluidStack contents, int contentMb,
                           Direction openFace, boolean infiniteSource, double fillScale) {
        this.identity = identity;
        this.accessPos = accessPos;
        this.baseY = baseY;
        this.heightBlocks = Math.max(1, heightBlocks);
        this.capacityMb = capacityMb;
        this.contents = contents;
        this.contentMb = contentMb;
        this.openFace = openFace;
        this.infiniteSource = infiniteSource;
        this.fillScale = fillScale;
    }

    /**
     * Find the fluid capability at a position, preferring the side-agnostic handler
     * and falling back to any side a side-sensitive block exposes.
     */
    public static IFluidHandler findHandler(Level level, BlockPos pos) {
        IFluidHandler cap = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (cap != null) return cap;
        for (Direction side : Direction.values()) {
            cap = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (cap != null) return cap;
        }
        return null;
    }

    /**
     * Resolve the column behind a HANDLER graph node, or null if the position no
     * longer exposes a usable fluid capability.
     */
    public static BoundaryColumn resolve(Level level, Node handlerNode) {
        BlockPos pos = handlerNode.pos();
        IFluidHandler cap = findHandler(level, pos);
        if (cap == null) return null;

        if (level.getBlockEntity(pos) instanceof FluidTankBlockEntity tankBe) {
            FluidTankBlockEntity controller = tankBe.getControllerBE();
            if (controller == null) return null; // multiblock mid-assembly or controller unloaded
            FluidTank inventory = controller.getTankInventory();
            int height = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
            int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
            BlockPos controllerPos = controller.getBlockPos();
            FluidStack fluid = inventory.getFluid();
            return new BoundaryColumn(
                    controllerPos, pos,
                    SableCompat.getColumnBaseY(level, controllerPos, width, height),
                    height, inventory.getCapacity(), fluid.copy(), fluid.getAmount(), null, false,
                    SableCompat.getUpProjectionY(level, controllerPos));
        }

        // A hose pulley draws from a fluid body through its hose: when its handler
        // advertises a drainable world fluid, model it as a brimming, one-way source
        // at the pulley's elevation rather than its tiny 1,500 mB buffer. The buffer
        // would equalize and stall like any small reservoir, and its opening lip would
        // gate the draw depending on where the pipe meets the pulley. Create's drainer
        // clamps the real per-tick volume and its counterpart bookkeeping stops the
        // pulley from reclaiming fluid it just deposited, so a one-way source is safe.
        // No drainable fluid (pulley over air, or filling) falls through to the generic
        // handler path below, where the buffer behaves as an ordinary fill sink.
        if (isHosePulley(level, pos)) {
            FluidStack drainable = cap.getFluidInTank(0);
            if (!drainable.isEmpty()
                    && !cap.drain(drainable.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()) {
                return new BoundaryColumn(pos, pos,
                        SableCompat.getWorldY(level, pos) - 0.5, 1, PULLEY_SOURCE_CAPACITY_MB,
                        drainable.copyWithAmount(PULLEY_SOURCE_CAPACITY_MB),
                        PULLEY_SOURCE_CAPACITY_MB, null, true, 1.0);
            }
        }

        int capacity = 0;
        FluidStack found = FluidStack.EMPTY;
        int amount = 0;
        for (int i = 0; i < cap.getTanks(); i++) {
            capacity += cap.getTankCapacity(i);
            FluidStack inTank = cap.getFluidInTank(i);
            if (inTank.isEmpty()) continue;
            if (found.isEmpty() && !cap.drain(inTank.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()) {
                found = inTank.copy();
            }
            if (!found.isEmpty() && FluidStack.isSameFluidSameComponents(found, inTank)) {
                amount += inTank.getAmount();
            }
        }
        if (capacity <= 0) return null;

        return new BoundaryColumn(pos, pos,
                SableCompat.getColumnBaseY(level, pos, 1, 1), 1, capacity, found, amount, null, false,
                SableCompat.getUpProjectionY(level, pos));
    }

    /** A Create hose pulley block, whose handler drains/fills a world fluid body. */
    private static boolean isHosePulley(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof HosePulleyBlockEntity;
    }

    /**
     * The world behind an open pipe end as a column, pinned at its MOUTH ({@code baseY +
     * 0.5}, see {@code columnHead}). The mouth is the single threshold separating spill
     * from intake: the network spills out when its head rises above the mouth and may
     * draw in when its head falls below it ("vacuum").
     *
     * <p>By default an open end is an EMPTY, receive-only outlet — it spills but never
     * reclaims. When {@link #intakeFluid intake} is enabled and the world in front holds a
     * drinkable body, the mouth becomes a ONE-WAY intake {@link #isInfiniteSource source}
     * instead: it supplies that fluid whenever the network sits below the mouth (a
     * "vacuum"), exactly like a hose pulley but fixed at the mouth. {@code networkSpilled}
     * = some open end on this network spilled within the cooldown; while true, a FINITE
     * source is not pulled (so the network cannot suck a block it just spat out, including
     * one that flowed to a sibling mouth) — lakes/cauldrons are unaffected.
     *
     * <p>One-wayness plus the consume-safe check are what keep the v1 oscillation dead.
     * Modelling a source as a two-way brimming reservoir made the engine drain its own
     * spill straight back (place block → read as full → drain → place → ...). A one-way
     * source can never reclaim, and the consume-safe check excludes the isolated block a
     * network just spilled (draining it would convert it to flowing, inviting a re-spill)
     * — only genuine bodies that survive a drain are pulled.
     */
    public static BoundaryColumn forOpenEnd(Level level, Node openEndNode, boolean networkSpilled) {
        BlockPos space = openEndNode.pos();
        double bottom = SableCompat.getWorldY(level, space) - 0.5;
        FluidStack intake = intakeFluid(level, space, networkSpilled);
        boolean canIntake = !intake.isEmpty();
        // Capacity (and so capacitance) is the atmospheric stand-in — a stiff boundary at
        // the mouth — but contentMb carries the HONEST per-tick yield ({@code intake}'s
        // amount: 250 for a honey block, 1000 for a cauldron / lake), so transfer planning
        // never asks the world for more than it can give this tick (Create's drain
        // over-reports a partial body, which would otherwise duplicate a few mB).
        return new BoundaryColumn(space, space, bottom, 1, OPEN_END_CAPACITY_MB,
                canIntake ? intake : FluidStack.EMPTY,
                canIntake ? intake.getAmount() : 0, openEndNode.openFace(), canIntake, 1.0);
    }

    /**
     * The world fluid an open mouth may draw IN, or EMPTY to keep it a one-way spill
     * outlet. Eligible bodies:
     *   - residual already pulled into the pipe's buffer (a partly-delivered draw);
     *   - a cauldron / honey block, which drains to a clean empty state;
     *   - a self-regenerating fluid source (a lake), tested with Create's OWN refill check
     *     ({@code getNewLiquid} on the drained-to-14 state equals the source — the exact
     *     discriminator {@code OpenEndedPipe} uses) — always drinkable, on the main level
     *     or projected onto a world lake from a Sable sub-level;
     *   - ANY other source (a finite / hand-placed block) on the MAIN level, UNLESS the
     *     network recently spilled or the block is {@link #contested} between two mouths.
     *     Finite intake is off on Sable sub-levels (the projected coords break the contested
     *     scan and the sub-level spill mixin preserves rather than consumes a source).
     */
    private static FluidStack intakeFluid(Level level, BlockPos space, boolean networkSpilled) {
        if (!PipesNPhysicsConfig.ENABLE_OPEN_END_INTAKE.get()) return FluidStack.EMPTY;
        FluidStack residual = OpenEndPipes.bufferedIntake(level, space);
        if (!residual.isEmpty()) return residual;
        BlockPos out = worldOutputPos(level, space);
        BlockState state = level.getBlockState(out);
        FluidStack drainable = VanillaFluidTargets.drainBlock(level, out, state, true);
        if (!drainable.isEmpty()) return drainable;
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isSource()) return FluidStack.EMPTY;
        if (survivesDrain(level, out, fluidState)) {
            return new FluidStack(fluidState.getType(), 1000); // a lake — always drinkable
        }
        // A finite/hand-placed source: pull it, UNLESS
        //   - this network spilled recently (could be sucking its own spit back), or
        //   - the block is wedged between two pipe mouths — a broken run's spill, drinking
        //     which would teleport fluid across the gap, or
        //   - the mouth is on a Sable sub-level (out != space): the projection breaks the
        //     contested scan, and the sub-level spill mixin PRESERVES a drained source
        //     rather than consuming it, so a finite block there would mint infinite fluid.
        //     Lakes are handled above (survivesDrain reads the real world block), so a
        //     contraption pipe dipped in a world lake still works.
        boolean projected = !out.equals(space);
        if (!projected && !networkSpilled && !contested(level, out)) {
            return new FluidStack(fluidState.getType(), 1000);
        }
        return FluidStack.EMPTY;
    }

    /**
     * Whether two or more open pipe mouths face this block. A lone hand-placed source has
     * one mouth (the intake); a source sitting in the gap of a broken run is flanked by a
     * mouth on each side, and must not be intaked or fluid would cross the break.
     */
    private static boolean contested(Level level, BlockPos pos) {
        int mouths = 0;
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.relative(d);
            if (FluidPropagator.getPipe(level, neighbor) != null
                    && FluidPropagator.isOpenEnd(level, neighbor, d.getOpposite())
                    && ++mouths >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether draining this source would leave it intact rather than convert it to a
     * flowing block — Create's {@code OpenEndedPipe} refill test: a source surrounded
     * enough to immediately regenerate (a lake) survives; an isolated one does not.
     */
    private static boolean survivesDrain(Level level, BlockPos pos, FluidState source) {
        BlockState drained = source.createLegacyBlock().setValue(LiquidBlock.LEVEL, 14);
        return drained.getFluidState().getType() instanceof FlowingFluidAccessor flowing
                && flowing.create$getNewLiquid(level, pos, drained).equals(source);
    }

    /** The real-world block an open mouth opens into, projecting Sable sub-level coords. */
    private static BlockPos worldOutputPos(Level level, BlockPos space) {
        if (!SableCompat.isCompanionLoaded()
                || !PipesNPhysicsConfig.ENABLE_OPEN_END_WORLD_PLACEMENT.get()) {
            return space;
        }
        BlockPos projected = BlockPos.containing(SableCompat.getWorldPos(level, space));
        return projected.equals(space) ? space : projected;
    }

    /** The live handler that can give or take this column's fluid. */
    public IFluidHandler handler(Level level) {
        return isOpenEnd()
                ? OpenEndPipes.handler(level, accessPos, openFace)
                : findHandler(level, accessPos);
    }

    public boolean isOpenEnd() { return openFace != null; }

    /**
     * A boundary that supplies fluid one-way without ever receiving it: a hose pulley
     * drawing from a fluid body, or an open pipe mouth drawing from a lake / cauldron
     * (see {@link #forOpenEnd}). It is exempt from the lip rule (its intake is the
     * submerged hose / mouth, not a tank opening) and never settles like a finite
     * reservoir.
     */
    public boolean isInfiniteSource() { return infiniteSource; }

    void addMemberNode(int graphNodeIndex) {
        memberNodes.add(graphNodeIndex);
    }

    /** Stable key for deduplicating multiblock connections (the controller position). */
    public BlockPos identity() { return identity; }

    /** A position whose block capability reaches this column's fluid (for transfers). */
    public BlockPos accessPos() { return accessPos; }

    public double baseY() { return baseY; }

    /** Scale on the fill height (cos of the sub-level tilt): fluid rises along local-up, not world-up. */
    public double fillScale() { return fillScale; }

    public int heightBlocks() { return heightBlocks; }

    public int capacityMb() { return capacityMb; }

    /** Sample of the drainable contents; EMPTY when the column holds nothing. */
    public FluidStack contents() { return contents; }

    public int contentMb() { return contentMb; }

    public List<Integer> memberNodes() { return memberNodes; }

    public boolean isEmpty() { return contents.isEmpty() || contentMb <= 0; }

    public double fillFraction() {
        return capacityMb > 0 ? (double) contentMb / capacityMb : 0;
    }

    /** Volume in mB needed to raise this column's surface by one block. */
    public double capacitance() {
        return (double) capacityMb / heightBlocks;
    }
}
