package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    private final List<Integer> memberNodes = new ArrayList<>();

    private BoundaryColumn(BlockPos identity, BlockPos accessPos, double baseY,
                           int heightBlocks, int capacityMb, FluidStack contents, int contentMb,
                           Direction openFace, boolean infiniteSource) {
        this.identity = identity;
        this.accessPos = accessPos;
        this.baseY = baseY;
        this.heightBlocks = Math.max(1, heightBlocks);
        this.capacityMb = capacityMb;
        this.contents = contents;
        this.contentMb = contentMb;
        this.openFace = openFace;
        this.infiniteSource = infiniteSource;
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
            FluidStack fluid = inventory.getFluid();
            return new BoundaryColumn(
                    controller.getBlockPos(), pos,
                    SableCompat.getWorldY(level, controller.getBlockPos()) - 0.5,
                    height, inventory.getCapacity(), fluid.copy(), fluid.getAmount(), null, false);
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
                        PULLEY_SOURCE_CAPACITY_MB, null, true);
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
                SableCompat.getWorldY(level, pos) - 0.5, 1, capacity, found, amount, null, false);
    }

    /** A Create hose pulley block, whose handler drains/fills a world fluid body. */
    private static boolean isHosePulley(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof HosePulleyBlockEntity;
    }

    /**
     * The world behind an open pipe end as a column. An open end is a ONE-WAY outlet:
     * it spills fluid into the world (the network's head exceeds its mouth, see
     * {@code columnHead}) but never reclaims it. It is therefore always receive-only.
     *
     * Modelling a source block in front as a brimming supply — whether a lake or the
     * block we just spilled — makes the engine drain its own spill straight back into
     * the network, which oscillates forever (place block → read as full → drain →
     * place → ...). Create's {@code provideFluidToSpace} already refuses to spill into
     * an occupied space, so once a block sits in front the open end simply drops out
     * of the solve and the spill settles. Trade-off: draining a lake through an open
     * pipe (intake) is not supported — that feature stays deferred.
     */
    public static BoundaryColumn forOpenEnd(Level level, Node openEndNode) {
        BlockPos space = openEndNode.pos();
        double bottom = SableCompat.getWorldY(level, space) - 0.5;
        return new BoundaryColumn(space, space, bottom, 1, OPEN_END_CAPACITY_MB,
                FluidStack.EMPTY, 0, openEndNode.openFace(), false);
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
     * drawing from a fluid body. It is exempt from the lip rule (its intake is the
     * submerged hose, not a tank opening) and never settles like a finite reservoir.
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
