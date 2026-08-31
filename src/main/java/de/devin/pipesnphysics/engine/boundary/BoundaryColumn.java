package de.devin.pipesnphysics.engine.boundary;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.fluids.pipes.VanillaFluidTargets;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.mixin.accessor.FlowingFluidAccessor;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.motion.CentrifugeField;
import de.devin.pipesnphysics.engine.motion.MomentumField;
import de.devin.pipesnphysics.engine.solve.NetworkSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
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
     * Capacity stand-in for a hose pulley bridging the pipe network to a world fluid body:
     * large enough that its head holds steady within a tick (the pulley lifts / deposits at
     * its own level under kinetic power, so it reads as a fixed reservoir at the pulley),
     * while the actual per-tick volume is still clamped by what Create's drainer hands over
     * (as a brimming SOURCE) or filler accepts (as a bottomless SINK).
     */
    private static final int PULLEY_CAPACITY_MB = 4_000_000;

    private final BlockPos identity;
    private final BlockPos accessPos;
    private final double baseY;
    private final int heightBlocks;
    private final int capacityMb;
    private final FluidStack contents;
    private final int contentMb;
    /** Every distinct drainable fluid this column holds (representative first); drives the per-fluid passes. */
    private List<FluidStack> heldFluids;
    private final Direction openFace;
    private final boolean infiniteSource;
    private final boolean finiteReservoir;
    private final double fillScale;
    private final List<Integer> memberNodes = new ArrayList<>();
    /** Face to resolve/transfer a SIDE-SPECIFIC handler through; null = side-agnostic (use {@code null} side). */
    private Direction accessFace;
    /** Classified once here at resolve time, so the executor never re-probes the block entity. */
    private boolean hosePulley;
    /** A Create fluid tank, whose fluid is DRAWN inset (see {@link #renderedSurface()}). */
    private boolean tankRender;
    /** An open bowl (a Create basin): it GIVES from any level (see {@link #drawSurface()}). */
    private boolean openBowl;

    private BoundaryColumn(BlockPos identity, BlockPos accessPos, double baseY,
                           int heightBlocks, int capacityMb, FluidStack contents, int contentMb,
                           Direction openFace, boolean infiniteSource, boolean finiteReservoir,
                           double fillScale) {
        this.identity = identity;
        this.accessPos = accessPos;
        this.baseY = baseY;
        this.heightBlocks = Math.max(1, heightBlocks);
        this.capacityMb = capacityMb;
        this.contents = contents;
        this.contentMb = contentMb;
        // A single-fluid column holds exactly its representative contents; a multi-fluid handler
        // overrides this with its full list (see resolveGenericHandler / heldFluids).
        this.heldFluids = contents.isEmpty() || contentMb <= 0
                ? List.of() : List.of(contents.copyWithAmount(contentMb));
        this.openFace = openFace;
        this.infiniteSource = infiniteSource;
        this.finiteReservoir = finiteReservoir;
        this.fillScale = fillScale;
    }

    /**
     * Drain a SPECIFIC fluid, tolerant of handlers that only implement the amount-based drain.
     * NeoForge's {@code IFluidHandler} has two drains: {@code drain(FluidStack)} (this exact fluid) and
     * {@code drain(int)} (any fluid up to an amount). Some handlers override only the amount variant and
     * leave {@code drain(FluidStack)} on the {@code FluidTank} template default, which reads an unrelated
     * internal field and returns EMPTY — create-aeronautics' docking-connector wrapper does exactly this,
     * so it reads drainable through the int API (and to Create) but not through the fluid API we use, and
     * the solver saw it as an undrainable source (a SOURCE_DRY stall). Try the fluid variant first; if it
     * gives nothing, fall back to the amount variant but accept the result ONLY when it is the fluid we
     * asked for, so a different fluid is never drained. A correct handler never reaches the fallback.
     */
    public static FluidStack drainMatching(IFluidHandler handler, FluidStack wanted, FluidAction action) {
        FluidStack drained = handler.drain(wanted, action);
        if (!drained.isEmpty()) return drained;
        FluidStack byAmount = handler.drain(wanted.getAmount(), action);
        return FluidStack.isSameFluidSameComponents(byAmount, wanted) ? byAmount : FluidStack.EMPTY;
    }

    /**
     * Find the fluid capability at a position: the side-agnostic ({@code null}) handler first, then —
     * for a SIDE-SPECIFIC block that exposes no {@code null} handler — a face that a pipe/pump actually
     * connects on, and only then any remaining face. Preferring a connecting face means a block that
     * exposes DIFFERENT handlers per side (an input tank on one face, an output on another) is read
     * through the face the network is plumbed into, not an arbitrary side. It is derived purely from
     * world geometry, so the solve and the later {@code apply} resolve the SAME handler with nothing
     * threaded between them. (This does NOT yet let one block serve two different fluids on two faces at
     * once — that needs per-face endpoints; it only fixes reading through the wrong side.)
     */
    public static IFluidHandler findHandler(Level level, BlockPos pos) {
        return findHandler(level, pos, null);
    }

    /**
     * Find the fluid capability, resolving a SIDE-SPECIFIC handler through a specific {@code face} when
     * given (the face the network connects on, from {@link Node#accessFace}). A handler that exposes a
     * DIFFERENT tank per side is then read through the correct one. {@code face == null} is the ordinary
     * side-agnostic resolution ({@code null} side, then a connecting face, then any).
     */
    public static IFluidHandler findHandler(Level level, BlockPos pos, Direction face) {
        if (face != null) {
            IFluidHandler sided = FluidCaps.at(level, pos, face);
            if (sided != null) return sided;
        }
        IFluidHandler sideAgnostic = FluidCaps.at(level, pos, null);
        if (sideAgnostic != null) return sideAgnostic;
        IFluidHandler anyFace = null;
        for (Direction side : Direction.values()) {
            IFluidHandler faceCap = FluidCaps.at(level, pos, side);
            if (faceCap == null) continue;
            if (FluidPropagator.getPipe(level, pos.relative(side)) != null) return faceCap; // a connecting face
            if (anyFace == null) anyFace = faceCap;
        }
        return anyFace;
    }

    /**
     * Resolve the column behind a HANDLER graph node, or null if the position no
     * longer exposes a usable fluid capability. Dispatches on what the block IS:
     * a relay endpoint, a Create multiblock tank, a hose pulley, or a generic handler.
     */
    public static BoundaryColumn resolve(Level level, Node handlerNode) {
        BlockPos pos = handlerNode.pos();
        Direction face = handlerNode.accessFace(); // side-specific handler face, or null
        IFluidHandler cap = findHandler(level, pos, face);
        if (cap == null) return null;

        // A relay endpoint (a docking connector, a hose) moves fluid through its own logic, so modelling
        // its tiny buffer as a surface-elevation capacitor makes the solver call it "balanced" and refuse
        // to drain it (the equalization stall). Resolve it drain-priority and bottomless instead — exactly
        // like a hose pulley — so it is a one-way SOURCE while it holds fluid and a one-way SINK while
        // empty. See HandlerRoles#isRelayEndpoint.
        if (HandlerRoles.isRelayEndpoint(level, pos)) return relayEndpoint(level, pos, face, cap);
        // The Create-tank path applies only to blocks that BEHAVE like Create tanks: one shared
        // side-agnostic handler over the multiblock inventory. A SIDE-SPECIFIC derivative (TFMG's
        // blast stove extends FluidTankBlockEntity but serves a different two-tank combined wrapper
        // per axis) must resolve through its FACE handler instead — the tank path would model the
        // internal gauge inventory, drop the access face, and read/equalize tanks the plumbed port
        // cannot reach. accessFace != null is exactly "GraphBuilder saw per-face handlers" (§2).
        if (face == null && level.getBlockEntity(pos) instanceof FluidTankBlockEntity tankBe) {
            return resolveTank(level, pos, tankBe);
        }
        if (level.getBlockEntity(pos) instanceof HosePulleyBlockEntity) {
            return resolveHosePulley(level, pos, cap);
        }
        return resolveGenericHandler(level, pos, face, cap);
    }

    /** A Create multiblock tank as one column, resolved through its controller. */
    private static BoundaryColumn resolveTank(Level level, BlockPos pos, FluidTankBlockEntity tankBe) {
        FluidTankBlockEntity controller = tankBe.getControllerBE();
        if (controller == null) return null; // multiblock mid-assembly or controller unloaded
        FluidTank inventory = controller.getTankInventory();
        int height = FluidTankGeometry.columnHeightBlocks(controller);
        BlockPos controllerPos = controller.getBlockPos();
        FluidStack fluid = inventory.getFluid();
        double baseY = FluidTankGeometry.columnBaseY(level, controllerPos, controller)
                - CentrifugeField.headOffset(level, controllerPos, level.getGameTime())
                + MomentumField.headOffset(level, controllerPos);
        return finiteReservoir(controllerPos, pos, baseY, height, inventory.getCapacity(),
                fluid.copy(), fluid.getAmount(), SableCompat.getUpProjectionY(level, controllerPos))
                .tankRendered();
    }

    /**
     * A hose pulley bridges the pipe network to a world fluid body through its hose.
     * Model it as a fixed reservoir at the pulley's elevation rather than its tiny
     * 1,500 mB buffer — the buffer would equalize and stall like any small reservoir,
     * and its opening lip would gate flow by where the pipe meets the pulley. Create's
     * drainer/filler clamps the real per-tick volume either way.
     *
     * DRAIN-PRIORITY: when its handler advertises a drainable body, it is a brimming,
     * one-way SOURCE (draw a lake, unchanged). Otherwise it is a bottomless, one-way
     * SINK — the network pushes fluid out through it and Create deposits it into the
     * world (the "can't push out of a pulley" gap). Once a pulley has deposited it is
     * pinned in OUTPUT mode and stays a sink even though the body it just filled now
     * reads drainable: otherwise drain-priority would flip it to a source and suck its
     * own output straight back (the reclaim oscillation, the same class the open-end
     * spill latch guards) — and, if the supply pauses, refuse to resume filling when it
     * returns. The role is sticky (persists across supply pauses, no timer) and clears
     * only when the pulley is removed; break-and-replace turns it back into a drainer.
     */
    private static BoundaryColumn resolveHosePulley(Level level, BlockPos pos, IFluidHandler cap) {
        double baseY = SableCompat.getWorldY(level, pos) - 0.5;
        FluidStack drainable = cap.getFluidInTank(0);
        boolean drainableBody = !drainable.isEmpty()
                && !cap.drain(drainable.copyWithAmount(1), FluidAction.SIMULATE).isEmpty();
        if (drainableBody && !OpenEndPipes.isPulleyOutput(level, pos)) {
            return brimmingSource(pos, baseY, drainable).asHosePulley();
        }
        return bottomlessSink(pos, baseY).asHosePulley();
    }

    /** Any other {@code IFluidHandler} (basin, spout, machine) as a one-block finite column. */
    private static BoundaryColumn resolveGenericHandler(Level level, BlockPos pos, Direction face,
                                                        IFluidHandler cap) {
        int capacity = 0;
        int total = 0;
        FluidStack found = FluidStack.EMPTY;
        // Every DISTINCT drainable fluid, each with its total mB — a multi-fluid basin (water + milk
        // for builder's tea) keeps each ingredient in its own segment, and ALL of them must be
        // enumerated so each gets a solve pass and can be drained, not only the representative found.
        List<FluidStack> held = new ArrayList<>();
        for (int i = 0; i < cap.getTanks(); i++) {
            capacity += cap.getTankCapacity(i);
            FluidStack inTank = cap.getFluidInTank(i);
            if (inTank.isEmpty()) continue;
            // The column surface is set by ALL the fluid in the block, not just the representative:
            // for a multi-fluid basin the water and lava together determine how high the fluid sits,
            // so gating the head on the representative alone under-counts the level and a full basin
            // reads as barely filled — its fluid then "can't reach" a side pipe (a phantom crest /
            // draw-lip wall). contentMb is therefore the TOTAL held volume.
            total += inTank.getAmount();
            boolean drainable = !cap.drain(inTank.copyWithAmount(1), FluidAction.SIMULATE).isEmpty();
            if (found.isEmpty() && drainable) {
                found = inTank.copy();
            }
            if (drainable) accumulate(held, inTank);
        }
        if (capacity <= 0) return null;

        // Side-specific handlers resolve HERE regardless of their block entity's ancestry (a real
        // Create tank/pulley/relay is side-agnostic; a tank-derived machine with per-face ports is
        // not a tank) — so this is where the access face rides onto the column for the later
        // transfer, and capacity/contents come from the plumbed FACE's own wrapper.
        double baseY = SableCompat.getColumnBaseY(level, pos, 1, 1)
                - CentrifugeField.headOffset(level, pos, level.getGameTime())
                + MomentumField.headOffset(level, pos);
        BoundaryColumn column = finiteReservoir(pos, pos, baseY, 1, capacity, found, total,
                SableCompat.getUpProjectionY(level, pos)).accessFace(face).heldFluids(held);
        // A basin is an OPEN BOWL: whatever it holds is scoopable from the top, so a side pipe may
        // always DRAW it and it never stalls on the aperture lip the way a closed tank does (owner
        // decision). A give permission only — the fluid still stands where it stands. Side-specific
        // ports (a basin-derived machine resolved per face) keep the plain rules.
        //
        // Its SETTLE datum is the tank's, though — see renderedSurface(): the settle equalizes the
        // surfaces it is given, so those surfaces must be one shared function of fill across every
        // endpoint type, or a basin and a tank equalize to two different states and circulate
        // between them forever.
        if (face == null && level.getBlockEntity(pos) instanceof BasinBlockEntity) {
            column.openBowl = true;
            column.tankRender = true;
        }
        return column;
    }

    /** Merge a tank's stack into a distinct-fluid list, summing amounts for a fluid already present. */
    private static void accumulate(List<FluidStack> into, FluidStack stack) {
        for (int i = 0; i < into.size(); i++) {
            if (FluidStack.isSameFluidSameComponents(into.get(i), stack)) {
                into.set(i, into.get(i).copyWithAmount(into.get(i).getAmount() + stack.getAmount()));
                return;
            }
        }
        into.add(stack.copy());
    }

    /** A real tank/basin/machine column the solver equalizes and lip-gates. */
    private static BoundaryColumn finiteReservoir(BlockPos identity, BlockPos accessPos, double baseY,
                                                  int heightBlocks, int capacityMb, FluidStack contents,
                                                  int contentMb, double fillScale) {
        return new BoundaryColumn(identity, accessPos, baseY, heightBlocks, capacityMb,
                contents, contentMb, null, false, true, fillScale);
    }

    /** A brimming one-way SOURCE at a fixed elevation: always full of {@code fluid}, never receives. */
    private static BoundaryColumn brimmingSource(BlockPos pos, double baseY, FluidStack fluid) {
        return new BoundaryColumn(pos, pos, baseY, 1, PULLEY_CAPACITY_MB,
                fluid.copyWithAmount(PULLEY_CAPACITY_MB), PULLEY_CAPACITY_MB, null, true, false, 1.0);
    }

    /** A bottomless one-way SINK at a fixed elevation: always empty, so it only ever receives. */
    private static BoundaryColumn bottomlessSink(BlockPos pos, double baseY) {
        return new BoundaryColumn(pos, pos, baseY, 1, PULLEY_CAPACITY_MB,
                FluidStack.EMPTY, 0, null, false, false, 1.0);
    }

    /**
     * A relay endpoint (docking connector, hose) as a drain-priority bottomless column at its own
     * elevation — the hose-pulley model applied to any relay handler. When it can give fluid
     * ({@code drain} SIMULATE) it is a brimming one-way SOURCE; otherwise a bottomless, one-way, empty
     * SINK. Either way it is NOT a finite reservoir, so it never surface-equalizes or lip-gates — the
     * engine drains a receiving connector and fills a sending one on demand, and the real per-tick
     * volume is clamped later by the handler's own drain/fill (which enforces the mod's pairing gate).
     */
    private static BoundaryColumn relayEndpoint(Level level, BlockPos pos, Direction face, IFluidHandler cap) {
        double baseY = SableCompat.getWorldY(level, pos) - 0.5;
        FluidStack drainable = cap.drain(Integer.MAX_VALUE, FluidAction.SIMULATE);
        // The access face MUST ride onto the column: a relay can be side-specific (a block that produces a
        // fluid on ONE face — a coke oven's CO2 on top — while its null side exposes a different, empty tank).
        // Without it, the column reads the right fluid through `cap` here but `handler(level)` later resolves
        // the null side and drains nothing (a SOURCE_DRY stall — solved flow, no transfer).
        if (!drainable.isEmpty()) {
            return brimmingSource(pos, baseY, drainable).accessFace(face);
        }
        return bottomlessSink(pos, baseY).accessFace(face);
    }

    /**
     * The world behind an open pipe end as a column, pinned at its MOUTH ({@code baseY +
     * 0.5}, see {@code columnHead}). The mouth is the single threshold separating spill
     * from intake: the network spills out when its head rises above the mouth and may
     * draw in when its head falls below it ("vacuum").
     *
     * By default an open end is an EMPTY, receive-only outlet — it spills but never
     * reclaims. When {@link #intakeFluid intake} is enabled, the mouth faces a drinkable
     * body, AND the mouth may {@link #drawsInThrough draw in} at all, it becomes a ONE-WAY
     * intake {@link #isInfiniteSource source} instead: it supplies that fluid whenever the
     * network sits below the mouth (a "vacuum"), exactly like a hose pulley but fixed at
     * the mouth. {@code networkSpilled}
     * = some open end on this network spilled within the cooldown; while true, a FINITE
     * source is not pulled (so the network cannot suck a block it just spat out, including
     * one that flowed to a sibling mouth) — lakes/cauldrons are unaffected.
     *
     * An UNPUMPED horizontal mouth never draws in — it is a spill outlet only. A sideways
     * mouth sits at the elevation of whatever it spills, so drinking would just reclaim its
     * own spilled block, tick after tick (the "why can a horizontal pipe suck in water?"
     * oscillation). Unpowered intake is therefore a vertical act: a riser dipping DOWN into
     * a body, or opening UP beneath one. {@code underSuction} — a running pump PULLS on this
     * mouth ({@code MouthConditions}) — lifts that: a pump cannot spill out of its own
     * suction flank, so a mouth it evacuates is not a mouth it feeds, and the oscillation the
     * vertical rule guards against is structurally impossible there. The cooldown gate below
     * is the finer, finite-source guard on the mouths that DO intake.
     *
     * One-wayness plus the consume-safe check are what keep the v1 oscillation dead.
     * Modelling a source as a two-way brimming reservoir made the engine drain its own
     * spill straight back (place block → read as full → drain → place → ...). A one-way
     * source can never reclaim, and the consume-safe check excludes the isolated block a
     * network just spilled (draining it would convert it to flowing, inviting a re-spill)
     * — only genuine bodies that survive a drain are pulled.
     */
    public static BoundaryColumn forOpenEnd(Level level, Node openEndNode, boolean networkSpilled,
                                            boolean underSuction) {
        BlockPos space = openEndNode.pos();
        double bottom = SableCompat.getWorldY(level, space) - 0.5;
        FluidStack intake = intakeFluid(level, space, networkSpilled,
                drawsInThrough(openEndNode.openFace()) || underSuction);
        boolean canIntake = !intake.isEmpty();
        // Capacity (and so capacitance) is the atmospheric stand-in — a stiff boundary at
        // the mouth — but contentMb carries the HONEST per-tick yield ({@code intake}'s
        // amount: 250 for a honey block, 1000 for a cauldron / lake), so transfer planning
        // never asks the world for more than it can give this tick (Create's drain
        // over-reports a partial body, which would otherwise duplicate a few mB).
        return new BoundaryColumn(space, space, bottom, 1, OPEN_END_CAPACITY_MB,
                canIntake ? intake : FluidStack.EMPTY,
                canIntake ? intake.getAmount() : 0, openEndNode.openFace(), canIntake, false, 1.0);
    }

    /**
     * Only a VERTICAL mouth may draw a fluid BLOCK in UNPUMPED — a horizontal one is a spill
     * outlet until a pump sucks on it (see {@link #forOpenEnd}). Non-spillable targets are
     * exempt entirely: see {@link #drinkableSource}.
     */
    private static boolean drawsInThrough(Direction openFace) {
        return openFace != null && openFace.getAxis().isVertical();
    }

    /**
     * The fluid an open mouth may draw IN, or EMPTY to keep it a one-way spill outlet. Checks the
     * block the pipe faces on its OWN level FIRST — a main-level source, or one placed on a Sable
     * sub-level (at its plot coords) — then, for a contraption mouth hovering over the host world, the
     * PROJECTED world block (mirroring spill, which goes to the world); and finally, when
     * {@code ENABLE_CROSS_LEVEL_PIPING} is on, the corresponding block on any OTHER Sable level
     * whose bounds overlap the mouth (ship A drinking a source on ship B, or a contraption over the
     * dimension, or the dimension over a contraption). Residual already
     * pulled into the pipe's buffer short-circuits everything. Finite intake works on sub-levels and
     * across contraptions too: the drain (OpenEndedPipeMixin) consumes a finite source and leaves a
     * lake, so it can no longer mint fluid.
     */
    private static FluidStack intakeFluid(Level level, BlockPos space, boolean networkSpilled,
                                          boolean verticalMouth) {
        if (!PipesNPhysicsConfig.ENABLE_OPEN_END_INTAKE.get()) return FluidStack.EMPTY;
        FluidStack residual = OpenEndPipes.bufferedIntake(level, space);
        if (!residual.isEmpty()) return residual;
        FluidStack local = drinkableSource(level, space, networkSpilled, verticalMouth);
        if (!local.isEmpty()) return local;
        BlockPos out = worldOutputPos(level, space);
        if (!out.equals(space)) {
            FluidStack world = drinkableSource(level, out, networkSpilled, verticalMouth);
            if (!world.isEmpty()) return world;
        }
        if (PipesNPhysicsConfig.ENABLE_CROSS_LEVEL_PIPING.get()) {
            FluidStack other = SableCompat.atOverlappingContraptions(level, space, (l, p) -> {
                FluidStack found = drinkableSource(l, p, networkSpilled, verticalMouth);
                return found.isEmpty() ? null : found; // null keeps Sable's traversal searching
            });
            if (other != null) return other;
        }
        return FluidStack.EMPTY;
    }

    /**
     * The fluid drinkable from the block at {@code pos}, or EMPTY: a VANILLA FLUID TARGET (a
     * beehive/bee nest brimming with honey, a full water/lava cauldron — Create's own
     * {@code VanillaFluidTargets}); a self-regenerating lake (Create's own
     * {@code getNewLiquid}-on-drained-to-14 discriminator); or a finite/hand-placed source — the
     * last one UNLESS the network recently spilled (its own spit) or the block is {@link #contested}
     * between two mouths (a broken run's gap, which drinking teleports across).
     *
     * A vanilla fluid target may be drunk through ANY face; only a FLUID BLOCK needs the vertical
     * mouth. The vertical rule exists solely to stop a sideways mouth reclaiming its own spill (it
     * sits at the elevation of whatever it spills), and Create's {@code provideFluidToSpace} bails
     * on {@code !state.canBeReplaced()} — so a hive or a cauldron can never RECEIVE our spill and
     * has nothing to reclaim. Keeping them vertical-only just made them awkward to plumb: a hive
     * usually wants a side tap, its underside being where the campfire goes.
     */
    private static FluidStack drinkableSource(Level level, BlockPos pos, boolean networkSpilled,
                                              boolean verticalMouth) {
        BlockState state = level.getBlockState(pos);
        FluidStack drainable = VanillaFluidTargets.drainBlock(level, pos, state, true);
        if (!drainable.isEmpty()) return drainable;
        if (!verticalMouth) return FluidStack.EMPTY;
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isSource()) return FluidStack.EMPTY;
        if (survivesDrain(level, pos, fluidState)) {
            return new FluidStack(fluidState.getType(), 1000);
        }
        if (!networkSpilled && !contested(level, pos)) {
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

    /** The live handler that can give or take this column's fluid (through its side-specific face if any). */
    public IFluidHandler handler(Level level) {
        return isOpenEnd()
                ? OpenEndPipes.handler(level, accessPos, openFace)
                : findHandler(level, accessPos, accessFace);
    }

    private BoundaryColumn accessFace(Direction face) {
        this.accessFace = face;
        return this;
    }

    private BoundaryColumn heldFluids(List<FluidStack> fluids) {
        this.heldFluids = fluids;
        return this;
    }

    /**
     * Every distinct DRAINABLE fluid this column holds, representative first, each with its total
     * mB — the fluids that get their own solve pass. A single-fluid tank/open-end/pulley reports
     * just its {@link #contents()}; a MULTI-FLUID basin reports all of them, so the engine can
     * drain each one (not only the representative {@code contents()}). Empty when the column holds
     * nothing drainable.
     */
    public List<FluidStack> heldFluids() { return heldFluids; }

    /** The face a side-specific handler is resolved/transferred through, or null for side-agnostic. */
    public Direction accessFace() { return accessFace; }

    /** Whether this column is the world behind an open pipe mouth rather than a block's handler. */
    public boolean isOpenEnd() { return openFace != null; }

    /**
     * A boundary that supplies fluid one-way without ever receiving it: a hose pulley
     * drawing from a fluid body, or an open pipe mouth drawing from a lake / cauldron
     * (see {@link #forOpenEnd}). It is exempt from the lip rule (its intake is the
     * submerged hose / mouth, not a tank opening) and never settles like a finite
     * reservoir.
     */
    public boolean isInfiniteSource() { return infiniteSource; }

    /**
     * The DUAL of {@link #isInfiniteSource}: a boundary that receives fluid one-way without ever
     * supplying it — a hose pulley/relay in its bottomless SINK role, or a receive-only outlet
     * mouth. Every boundary is one of the two (a bottomless column is either brimming or empty);
     * only a {@link #isFiniteReservoir finite reservoir} goes both ways.
     */
    public boolean isBottomlessSink() { return !finiteReservoir && !infiniteSource; }

    /** Attach a graph node to this column; called only while the solve collects its columns. */
    public void addMemberNode(int graphNodeIndex) {
        memberNodes.add(graphNodeIndex);
    }

    /** Stable key for deduplicating multiblock connections (the controller position). */
    public BlockPos identity() { return identity; }

    /** A position whose block capability reaches this column's fluid (for transfers). */
    public BlockPos accessPos() { return accessPos; }

    /** World elevation of the column's bottom (block base − 0.5), momentum/spin tilt applied. */
    public double baseY() { return baseY; }

    /** Scale on the fill height (cos of the sub-level tilt): fluid rises along local-up, not world-up. */
    public double fillScale() { return fillScale; }

    /** Vertical extent the fill spans, in blocks (a lying multiblock tank spans its cross-section). */
    public int heightBlocks() { return heightBlocks; }

    public int capacityMb() { return capacityMb; }

    /** Sample of the drainable contents; EMPTY when the column holds nothing. */
    public FluidStack contents() { return contents; }

    /** Total held mB of the representative fluid — may exceed {@code contents().getAmount()} on a multi-tank handler. */
    public int contentMb() { return contentMb; }

    /** Graph node indices connected to this column (several for a multiblock tank). */
    public List<Integer> memberNodes() { return memberNodes; }

    public boolean isEmpty() { return contents.isEmpty() || contentMb <= 0; }

    /**
     * A real finite tank/basin/machine, as opposed to an atmospheric boundary (open end) or a
     * world-body bridge (hose pulley). Only these carry a capacity CEILING that the solver saturates
     * against, so a full one is clamped GIVE-ONLY (the box-constrained dual of the empty→receive-only
     * wall). Boundaries keep their own one-way rules (infinite-source pin, empty→receive) and are
     * left unbounded; they never sit "at capacity" as a finite reservoir does.
     */
    public boolean isFiniteReservoir() { return finiteReservoir; }

    public double fillFraction() {
        return capacityMb > 0 ? (double) contentMb / capacityMb : 0;
    }

    /** Height of the fluid above {@link #baseY()} in world blocks (tilt-scaled on sub-levels). */
    public double fillHeight() {
        return fillFraction() * heightBlocks * fillScale;
    }

    /** The liquid surface elevation this column holds its fluid at (baseY + fillHeight). */
    public double liquidSurface() {
        return baseY + fillHeight();
    }

    /**
     * The hydraulic head the solver equalizes for this column. A liquid column's head is its
     * surface elevation; a gas column's head rises with fill (compression) and falls with
     * elevation (buoyancy) — an approximation that makes gases seek upward and denser fill
     * push outward.
     *
     * A liquid open end is a fixed boundary at its MOUTH, not a column that rises with
     * whatever block sits in front of it. Modelling a spilled source block as a brimming
     * reservoir (surface at the block top) makes the engine reclaim its own spill — place a
     * block, read it as full, drain it back, place it again, forever. Pinning the head at the
     * mouth gives spill and intake a single threshold, so a broken pipe drains to the mouth
     * level and settles instead of flickering, and an intake mouth (see {@link #forOpenEnd})
     * draws in only while the network sits below the mouth ("vacuum").
     */
    public double head(boolean gas) {
        // The mouth pin holds for BOTH media, mirrored: a gas mouth pins at minus the mouth
        // elevation, so gas vents only while the network's interface reaches DOWN below the
        // mouth — the exact dual of liquid spilling only while its surface stands above it.
        // (Never anchor a gas mouth at the open-end column's top: it is a 4,000,000 mB
        // pseudo-column whose top would read as a bottomless gas sink.)
        if (isOpenEnd()) return gas ? -(baseY + 0.5) : baseY + 0.5;
        // On a tilted sub-level the fill rises along the column's local-up, so it adds only
        // fillHeight·cos(tilt) of world height (fillScale = 1 when level). Without this a tilted
        // tank's surface is over-estimated and spills out an open end that is physically above it.
        return NetworkSolver.surfaceHead(baseY, baseY + heightBlocks * fillScale, fillHeight(), gas);
    }

    /** Whether this endpoint is a Create hose pulley (classified once at resolve time). */
    public boolean isHosePulley() {
        return hosePulley;
    }

    private BoundaryColumn asHosePulley() {
        this.hosePulley = true;
        return this;
    }

    private BoundaryColumn tankRendered() {
        this.tankRender = true;
        return this;
    }

    // Create's FluidTankRenderer draws the fluid INSET: it sits capHeight+minPuddle above the block
    // bottom and capHeight below the top, so it fills only (height − 2·capHeight − minPuddle) of the
    // column. Our own head/surface uses the FULL block range; the two diverge by up to ~0.31 block at
    // low fill (a settled pipe then sits well below the tank's VISIBLE waterline — "the surface level
    // inside the tanks is bumped from Create's model"). The SETTLE/render side tracks the rendered
    // surface below so a pipe meets the fluid the player actually sees; the SOLVE keeps the full-range
    // head for its conservation math (changing it would shift equalization volumes), but its GATES —
    // the draw lip, the lip drain cap, and the weir/cavitation potentials — read the rendered surface
    // too, because the player judges them against the fluid on screen.
    // Public because the client's tank-lip pour test (OpenEndParticles) rebuilds the same visible
    // surface from the tank's synced render state — one definition of the inset, two readers.
    public static final double TANK_RENDER_FLOOR = 1.0 / 4 + 1.0 / 16;   // capHeight + minPuddle
    public static final double TANK_RENDER_LOSS = 2.0 / 4 + 1.0 / 16;    // 2·capHeight + minPuddle
    /** The top inset of the render range — a GAS hangs from here downward (no puddle above). */
    public static final double TANK_RENDER_CEILING = 1.0 / 4;            // capHeight

    /**
     * The surface elevation where the fluid is actually DRAWN — for a Create fluid tank the inset
     * render range (so a settled pipe's waterline meets the tank's visible fluid, which sits above
     * {@code baseY + fill} at low fill), else the plain {@link #liquidSurface()}. This is WHERE THE
     * FLUID STANDS: the settle's datum and the {@code /pipegraph} surface marker — the solve's
     * conservation math keeps {@link #head}, and the give-side gates keep {@link #drawSurface()}.
     *
     * It is also, unavoidably, the datum the settle EQUALIZES two reservoirs on, so it must be ONE
     * shared function of fill for every endpoint type: equal surfaces have to mean equal fill
     * fractions, or the settle and the solve aim at different states and grind between them. A
     * BASIN therefore borrows the tank's inset mapping rather than its own bowl geometry (Create
     * draws a basin's fluid 2/16..14/16 on an eased curve, a third of a block BELOW a tank at the
     * same fill): read literally, a basin beside a tank was always the low side however full it
     * got, so the settle poured the tank back into it while the solve pumped the basin into the
     * tank — a permanent circulation that left the last of a basin's contents shuttling up and
     * down the pipe ("solved=1 actual=1" tick after tick with nothing arriving). The cost is only
     * that a pipe's film beside a basin no longer lines up with the fluid in the bowl, which is a
     * different shape at a different height anyway.
     */
    public double renderedSurface() {
        if (!tankRender) return liquidSurface();
        double renderedFill = TANK_RENDER_FLOOR + fillFraction() * (heightBlocks - TANK_RENDER_LOSS);
        return baseY + renderedFill * fillScale;
    }

    /**
     * The elevation an opening may DRAW this column through — every player-visible GIVE gate (the
     * draw lip, the lip drain cap, the weir-crest potential, the "supply below opening" wording).
     * An OPEN BOWL holding ANY fluid reads at its top: a basin's fluid is reachable from above, so
     * no aperture lip or crest floor ever walls it off (empty stays empty — no phantom permission).
     *
     * This is a give PERMISSION, not an elevation, and the two must not be confused: read as a
     * surface it says a basin's fluid STANDS at the block top, which pressed its contents into
     * every pipe touching it up to full cells (a mixing basin bled its ingredients into the
     * plumbing) and, being above the pipes, refused every pour back IN. Everything asking how high
     * the fluid stands reads {@link #renderedSurface()} instead.
     */
    public double drawSurface() {
        if (openBowl && contentMb > 0) return baseY + heightBlocks * fillScale;
        return renderedSurface();
    }

    /**
     * The MIRROR of {@link #renderedSurface()} for a lighter-than-air content: a gas hangs from
     * the column ceiling, so its interface is where the gas body ENDS, measured down from the
     * top — for a Create tank the inset render range upside down (gas ceiling {@code capHeight}
     * below the top, same compressed span; a full column's interface lands exactly on the liquid
     * render floor), else the plain {@code top − fillHeight}. An EMPTY column's interface is its
     * ceiling: gas entering it pools at the top. This is the surface the GAS settle frame reads
     * (the mirrored {@code SettlingRun}); the solve keeps its inverted {@link #head}.
     */
    public double gasSurface() {
        double top = baseY + heightBlocks * fillScale;
        if (!tankRender) return top - fillHeight();
        double renderedGap = TANK_RENDER_CEILING + fillFraction() * (heightBlocks - TANK_RENDER_LOSS);
        return top - renderedGap * fillScale;
    }

    /** Volume in mB needed to raise this column's surface by one block. */
    public double capacitance() {
        return (double) capacityMb / heightBlocks;
    }
}
