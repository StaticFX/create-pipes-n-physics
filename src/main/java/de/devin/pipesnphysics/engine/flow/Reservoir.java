package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.api.EndpointApi;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * One fluid endpoint for one executed tick: a tank/basin/machine, an open pipe mouth, or a hose
 * pulley, wrapped so the rest of the executor can just {@link #drain} and {@link #fill}.
 *
 * All the endpoint rules of the old transfer layer live HERE and nowhere else: the shared
 * per-tick {@code MAX_FLOW_PER_ENDPOINT} budgets (one give and one take budget per physical
 * reservoir — a multiblock tank reached from several nodes shares one {@code Reservoir}), the
 * lip drain cap a flowing pass installs ({@link #capDrawAtLip}), SIMULATE-then-EXECUTE on
 * every handler exchange (a stale plan degrades to a smaller move, never an error), the
 * open-end intake yield clamp, and the spill/pulley-output latches stamped on delivery.
 */
public final class Reservoir {
    /** The last few mB may leave in one go, so columns drain to genuinely empty (shared dregs rule). */
    static final int DREGS_MB = 4;

    private final Level level;
    private final BoundaryColumn column;
    private int giveRemaining;
    private int takeRemaining;
    private double drawLipCapMb = Double.MAX_VALUE;
    private double drawLipY = Double.NEGATIVE_INFINITY;

    Reservoir(Level level, BoundaryColumn column, int maxFlowPerEndpoint) {
        this.level = level;
        this.column = column;
        this.giveRemaining = maxFlowPerEndpoint;
        this.takeRemaining = maxFlowPerEndpoint;
    }

    /**
     * Draw up to {@code amount} of {@code fluid} out of this reservoir, returning what really
     * came out. A one-way SINK gives nothing. An open end gives only its own precomputed per-tick
     * yield (Create's drain would return the REQUESTED amount even when the body holds less —
     * fluid from nothing) and only its own fluid (a foreign-fluid probe corrupts the mouth buffer).
     */
    int drain(FluidStack fluid, int amount) {
        if (column.isBottomlessSink() || vetoed(fluid)) return 0;
        if (column.isOpenEnd()) {
            if (!FluidStack.isSameFluidSameComponents(column.contents(), fluid)) return 0;
            amount = Math.min(amount, column.contentMb());
        }
        int budget = (int) Math.min(amount, Math.min(giveRemaining, drawLipCapMb));
        if (budget <= 0) return 0;
        IFluidHandler handler = column.handler(level);
        if (handler == null) return 0;
        FluidStack probed = BoundaryColumn.drainMatching(handler,
                fluid.copyWithAmount(budget), FluidAction.SIMULATE);
        int drainable = Math.min(budget, probed.getAmount());
        if (drainable <= 0) return 0;
        FluidStack moved = BoundaryColumn.drainMatching(handler,
                fluid.copyWithAmount(drainable), FluidAction.EXECUTE);
        if (moved.isEmpty()) return 0;
        giveRemaining -= moved.getAmount();
        drawLipCapMb -= moved.getAmount(); // the lip cap is per TICK, however many draws share it
        if (!level.isClientSide()) RelayDetector.recordApplied(column.accessPos(), -moved.getAmount());
        return moved.getAmount();
    }

    /**
     * Pour up to {@code amount} of {@code fluid} into this reservoir, returning what it really
     * accepted. A one-way SOURCE takes nothing. A delivery into an open end is a spill (latches
     * the anti-reclaim cooldown); one into a hose pulley pins it as a one-way output sink.
     */
    int fill(FluidStack fluid, int amount) {
        if (column.isInfiniteSource() || vetoed(fluid)) return 0;
        amount = Math.min(amount, takeRemaining);
        if (amount <= 0) return 0;
        IFluidHandler handler = column.handler(level);
        if (handler == null) return 0;
        int accept = handler.fill(fluid.copyWithAmount(amount), FluidAction.SIMULATE);
        int want = Math.min(amount, accept);
        if (want <= 0) return 0;
        int filled = handler.fill(fluid.copyWithAmount(want), FluidAction.EXECUTE);
        if (filled <= 0) return 0;
        takeRemaining -= filled;
        if (!level.isClientSide()) RelayDetector.recordApplied(column.accessPos(), filled);
        if (column.isOpenEnd()) {
            OpenEndPipes.markSpilled(level, column.accessPos());
        } else if (column.isHosePulley()) {
            OpenEndPipes.markPulleyOutput(level, column.accessPos());
        }
        return filled;
    }

    /**
     * How much of this fluid the column could still give, ignoring the per-tick budgets — a
     * budget-free SIMULATE probe (like {@link #rejects}) for "will more ever come" questions.
     * A self-refilling source (intake mouth, pulley, relay) always answers in full; a plain
     * outlet mouth never supplies. A LIP-gated column answers only the volume above its draw
     * lip — the raw handler knows nothing of the lip, so a tank resting at its aperture read
     * as a bottomless supply and {@code columnFullyArrived} never fired: the depth gate then
     * parked the lip-cap dribble in the head cell forever while the sink starved (the
     * "flows shortly, stops" limit cycle at the lip equilibrium).
     */
    int probeSupply(FluidStack fluid, int amount) {
        if (vetoed(fluid)) return 0;
        if (column.isInfiniteSource()) return amount;
        if (column.isBottomlessSink()) return 0;
        IFluidHandler handler = column.handler(level);
        if (handler == null) return 0;
        int supply = BoundaryColumn.drainMatching(handler,
                fluid.copyWithAmount(amount), FluidAction.SIMULATE).getAmount();
        if (drawLipY == Double.NEGATIVE_INFINITY) return supply;
        int aboveLip = (int) Math.max(0, column.capacitance() * (surface() - drawLipY));
        return Math.min(supply, aboveLip);
    }

    /** How much the sink side would still accept this tick (probe, no fluid moves). */
    int probeFill(FluidStack fluid, int amount) {
        if (column.isInfiniteSource() || vetoed(fluid)) return 0; // kept in step with fill
        amount = Math.min(amount, takeRemaining);
        if (amount <= 0) return 0;
        IFluidHandler handler = column.handler(level);
        if (handler == null) return 0;
        return Math.min(amount, handler.fill(fluid.copyWithAmount(amount), FluidAction.SIMULATE));
    }

    /**
     * Refund fluid a two-phase move could not place after all (never void silently). This
     * deliberately bypasses the take budget and the spill/pulley latches — the fluid never
     * really left — and it must UNDO the drain's bookkeeping: the give budget comes back, and
     * the relay detector is told, or the refund would read as a spontaneous gain and demote an
     * innocent block to a relay endpoint.
     */
    int refund(FluidStack fluid, int amount) {
        IFluidHandler handler = column.handler(level);
        if (handler == null) return 0;
        int filled = handler.fill(fluid.copyWithAmount(amount), FluidAction.EXECUTE);
        if (filled > 0) {
            giveRemaining += filled;
            if (!level.isClientSide()) RelayDetector.recordApplied(column.accessPos(), filled);
        }
        return filled;
    }

    /**
     * Whether an addon's {@link EndpointApi} filter vetoes this fluid at this endpoint. Enforced
     * HERE, where fluid crosses the boundary, and not only in the solve's participation test: the
     * settle phases move fluid the solve never planned by design (a pump packing a dead-headed
     * line, a run pouring back into its tank), so a solve-only veto would leak through them —
     * the same lesson as the one-way columns and the pipe gates. A refund is exempt on purpose:
     * it puts back fluid that never really left.
     */
    private boolean vetoed(FluidStack fluid) {
        return !EndpointApi.allows(level, column.identity(), fluid);
    }

    /**
     * Cap how fast this reservoir may be drawn down this tick — the lip rule: at most half the
     * volume above the lowest out-flowing opening at {@code lipY} (last {@link #DREGS_MB} at
     * once), reaching zero exactly at the lip, so a tank settles at the opening instead of
     * flapping across it. Installed by the flowing pass (which knows the openings) and consumed
     * by every drain this tick, the settle phase included — same physical opening, same rule.
     */
    void capDrawAtLip(double lipY) {
        double aboveLip = column.capacitance() * (surface() - lipY);
        double cap = aboveLip <= 0 ? 0 : Math.max(Math.min(aboveLip, DREGS_MB), 0.5 * aboveLip);
        drawLipCapMb = Math.min(drawLipCapMb, cap);
        drawLipY = lipY; // remembered for probeSupply — callers pre-merge to the lowest opening
    }

    /**
     * The fluid surface elevation — this reservoir's own contents as world-read when the
     * {@code FlowNetwork} was built, constant within the tick (which is why the settle pass
     * needs its hysteresis band) and never a solved head. This is the RENDERED surface (a
     * Create tank draws its fluid inset), so a settled pipe's waterline meets the tank's
     * VISIBLE fluid rather than the full-block {@code baseY + fill} the solver equalizes.
     */
    double surface() {
        return column.renderedSurface();
    }

    /** The GAS interface elevation — where a lighter-than-air content ends, hanging from the top. */
    double gasSurface() {
        return column.gasSurface();
    }

    /** A real finite tank/basin (equalized, lip-gated), as opposed to an open mouth or pulley. */
    boolean isFiniteReservoir() {
        return column.isFiniteReservoir();
    }

    /** An open pipe mouth — a spill outlet, or a one-way intake when it faces a drinkable body. */
    boolean isOpenMouth() {
        return column.isOpenEnd();
    }

    /** A one-way bottomless supplier (intake mouth, drainable hose pulley, giving relay). */
    boolean isInfiniteSource() {
        return column.isInfiniteSource();
    }

    /** Whether the column held any fluid when this tick's world read was taken. */
    boolean holdsFluid() {
        return !column.isEmpty();
    }

    /** The column's representative contents — the fluid a settle step asks it for. */
    FluidStack contents() {
        return column.contents();
    }

    /**
     * Whether this reservoir can take NO more of {@code fluid} — a brimming tank, a one-way
     * source, an endpoint an addon filter vetoes. Budget-free like {@link #rejects}: it asks the
     * live handler, so an endpoint whose per-tick take budget is already spent still answers for
     * what it physically IS rather than for what is left of this tick.
     */
    boolean takesNothing(FluidStack fluid) {
        if (fluid.isEmpty()) return false;
        if (column.isInfiniteSource() || vetoed(fluid)) return true;
        IFluidHandler handler = column.handler(level);
        return handler != null && handler.fill(fluid.copyWithAmount(1), FluidAction.SIMULATE) <= 0;
    }

    /**
     * Whether this reservoir is HARD-incompatible with {@code fluid} — its handler can NEITHER
     * accept it (a lava tank refusing water) NOR already hold it (so a tank OF that fluid, or a
     * multi-fluid basin carrying it, is not a collision). This is the crossing-the-streams test at
     * a tank↔pipe boundary; like Create it checks no fill level, only compatibility. Budget-free:
     * it probes the live handler, so a reservoir whose per-tick budgets are spent still reads true.
     */
    boolean rejects(FluidStack fluid) {
        if (fluid.isEmpty()) return false;
        IFluidHandler handler = column.handler(level);
        if (handler == null) return false; // can't tell → never break a pipe on a hunch
        if (handler.fill(fluid.copyWithAmount(1), FluidAction.SIMULATE) > 0) return false;
        return BoundaryColumn.drainMatching(handler, fluid.copyWithAmount(1), FluidAction.SIMULATE)
                .isEmpty();
    }
}
