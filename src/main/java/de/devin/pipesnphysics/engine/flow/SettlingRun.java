package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeGates;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.store.PipeWindow;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * One idle (no solved flow) edge settling its stored fluid toward rest.
 *
 * The resting shape is a per-cell TARGET profile ({@link #hydrostaticTargets}): the flat
 * waterline at the lower connected surface — a finite reservoir's LIVE surface, never the display
 * head, which spreads a source's head across zero-flow branches and would paint a phantom surface
 * on an empty sink — or, on a CREST-broken run (a broken siphon), each leg's barometric column.
 * One {@link #settle()} then moves fluid toward that profile a rate-limited step per tick.
 * {@link #levelToTargets} lets excess flow to an adjacent deficit, cell to cell.
 * {@link #fallAndSpread} lets above-target fluid FALL into lower cells with room and SPREAD
 * between same-height cells (a crest arch drains off its corners), skipped for a HELD column
 * that pressure pins in place. {@link #exchangeWithReservoirs} has the run communicate with each
 * end reservoir through the CONDUCTING prefix of at-target cells (the shared waterline): the
 * first below-target cell past it draws in, the first above-target one pours out; the DRAW
 * side's hysteresis band keeps the tank-surface↔target feedback from ping-ponging (pours are
 * self-stabilizing and act on any excess), and dregs leave in one go.
 * {@link #primeFromPumps} lets a running pump pack its dead-headed line from its supply side and,
 * once that line is at the waterline, deliver on through it into the sink (its solved steady-state
 * flow is 0, so nothing else would move either). {@link #gravityPool} is
 * the fallback with no solve data at all (every reservoir gone or empty): plain gravity trickles
 * contents downhill and out of an open mouth at/below, so fluid pools in the dips instead of
 * hanging frozen in a riser.
 *
 * A HELD/backed-up run (a pump pressing a shut gate or a full sink, a dead conduit against a full
 * tank) settles FILL-ONLY: it may draw toward its reachable CEILING but never gives anything
 * back. Only genuine traps — a U-dip below every outlet — keep fluid at rest.
 */
final class SettlingRun {
    /** Per-boundary settle rate floor (mB/t); the working rate is a quarter cell, whichever is larger. */
    private static final int MIN_SETTLE_MB = 8;

    /** The shared per-boundary settle rate in mB/t — one definition for runs AND node slots. */
    static int settleRate(int cellCapacity) {
        return Math.max(cellCapacity / 4, MIN_SETTLE_MB);
    }

    /**
     * Whether this content is a lighter-than-air gas, which settles in the MIRRORED elevation
     * frame — buoyancy is gravity upside down, so the same target/walk machinery runs with world
     * Y negated (the frame wrappers below) and the gas pools UP and pours into the vessel ABOVE.
     * Never mix a gas with the solve's display heads: a gas head is INVERTED (fill − baseY, §4),
     * not an elevation, which is why the mirrored frame reads live surfaces only.
     */
    static boolean lighterThanAir(FluidStack fluid) {
        return !fluid.isEmpty() && fluid.getFluid().getFluidType().isLighterThanAir();
    }
    /**
     * DRAW-side hysteresis (fraction of a cell): drawing from a tank lowers its surface, which
     * lowers the targets, which would pour the same fluid straight back — so draws act only on a
     * deficit beyond this band. Pours need no band (raising the tank raises the target, so they
     * self-stabilize), and banding them left a visible film standing in every near-empty cell.
     */
    private static final double SETTLE_BAND = 0.1;
    /**
     * Small elevation epsilon (blocks) for cell-HEIGHT and seal/pour comparisons only. It is NOT
     * added to a fill target's waterline: divided by the narrow bore it becomes a 13% distortion,
     * settling a pipe visibly off the tank it equalized with. The mB-based {@link #SETTLE_BAND} is
     * the anti-flap deadband.
     */
    static final double SURFACE_EPS = 0.05;
    /** The target profile of a zero-cell wire: it has no cells, so there is nothing to aim at. */
    private static final int[] NO_TARGETS = new int[0];

    private final FlowNetwork network;
    private final FlowLedger ledger;
    private final Solution solution;
    private final Edge edge;
    private final List<BlockPos> cells;
    /** Held/backed-up: pressure packs the line toward its ceiling but never lets it drain out. */
    private final boolean fillOnly;
    private final int rate;
    private final int hysteresisMb;
    /** Settling a lighter-than-air gas: every elevation below reads through the mirrored frame. */
    private boolean mirrored;
    /** The fluid this run is settling — what the pipe GATES along it are asked about. */
    private FluidStack medium = FluidStack.EMPTY;

    SettlingRun(FlowNetwork network, FlowLedger ledger, Solution solution, Edge edge, boolean fillOnly) {
        this.network = network;
        this.ledger = ledger;
        this.solution = solution;
        this.edge = edge;
        this.cells = edge.pipes();
        this.fillOnly = fillOnly;
        this.rate = settleRate(network.cellCapacity);
        this.hysteresisMb = (int) Math.ceil(SETTLE_BAND * network.cellCapacity);
    }

    /** One settle step; returns whether anything moved (the network then stays awake). */
    boolean settle() {
        // Crossing the streams with NO flow: a tank joined to the run holds a fluid the mouth
        // cell's resting fluid is incompatible with — the two meet at the boundary exactly as
        // Create pulls a tank's fluid into a pipe already carrying another. The brigade never
        // catches this on two idle tanks (each fluid's pass bails with a single participant, the
        // opposite endpoint walling it), so a water pipe touching a lava tank would just sit.
        // Checked BEFORE the gas/sealed bails, which would otherwise skip a full primed run.
        if (reactToBoundaryCollision()) return false;

        // A lighter-than-air gas settles in the MIRRORED frame: the wrappers below negate world
        // Y, so the SAME target/walk machinery pools it upward and pours it into the vessel
        // ABOVE. A held (fill-only) gas column stays frozen — its packing target is the display
        // CEILING field, which mixes heads with elevations the mirror cannot read.
        medium = settleMedium();
        mirrored = lighterThanAir(medium);
        if (mirrored && fillOnly) return false;

        // A ZERO-CELL edge is a wire: it holds no column, so there is no profile to settle toward.
        // The one thing that still has to happen across it is a running pump wedged flush against
        // its sink delivering on through ({@link #primeFromPumps} degenerates to a bare
        // {@link #deliverThroughPump} — no line to pack, so it pushes straight out).
        if (cells.isEmpty()) return primeFromPumps(NO_TARGETS);

        // A sealed primed column holds: with every cell FULL and both end reservoirs still
        // reaching their openings, no air can enter the run, so an idle siphon keeps its prime
        // (a real sealed siphon holds its column indefinitely). Without this, the waterline
        // recede below drained the crest on every pause — invisible while a dry crest could
        // self-prime, a permanent break now that it cannot.
        if (sealedPrimedColumn()) return false;

        Double lineA = restingLine(edge.a(), edge.b());
        Double lineB = restingLine(edge.b(), edge.a());
        if (lineA == null && lineB == null) return gravityPool();
        double headA = emptyFloorCap(edge.a(), lineA != null ? lineA : lineB);
        double headB = emptyFloorCap(edge.b(), lineB != null ? lineB : lineA);

        // Two profiles: what the run may RETAIN (a previously-primed siphon leg holds a barometric
        // column up to surface + suction limit — a vacuum gap at the crest supports it) versus what
        // it may DRAW from a reservoir (never above the surface: with air at the broken crest,
        // nothing pushes water UP an open leg). On an unbroken run the two are the same waterline.
        int[] retain = retentionTargets(headA, headB);
        int[] draw = isCrestBroken() ? drawTargets(headA, headB) : retain;
        boolean moved = levelToTargets(retain);
        if (!fillOnly) moved |= fallAndSpread(retain);
        moved |= exchangeWithReservoirs(retain, draw, headA, headB);
        moved |= primeFromPumps(retain);
        return moved;
    }

    /**
     * The top-up a FLOWING run gets alongside the brigade: cells below the waterline draw from
     * the end reservoirs toward the hydrostatic profile, source-side-first (the conducting-prefix
     * walk), so a submerged run fills up WHILE it flows — the plug rules alone only ever top the
     * tail cell (delivery gates on a full tail; every upstream cell nets zero), freezing the run
     * fullest-at-the-sink ("the pipes get increasingly more fluid toward the sink" report).
     * STRICTLY fill-only and no internal redistribution: the brigade owns the moving column, and
     * leveling a flowing edge toward its resting profile would drain a working siphon's crest and
     * break the column. Bare-surface targets (no suction allowance) — never draws above the line.
     */
    boolean topUp() {
        if (cells.isEmpty()) return false;
        // A flowing GAS run tops up in the mirrored frame: its cells pack downward from the bore
        // top toward the interface profile while the brigade flows — without this, flowing gas
        // rode at plug-flow depth and its hanging fill visibly missed the tank's interface until
        // the flow stopped ("the gas heights inside the pipe and the tank don't match").
        medium = settleMedium();
        mirrored = lighterThanAir(medium);
        Double lineA = restingLine(edge.a(), edge.b());
        Double lineB = restingLine(edge.b(), edge.a());
        if (lineA == null && lineB == null) return false;
        double headA = emptyFloorCap(edge.a(), lineA != null ? lineA : lineB);
        double headB = emptyFloorCap(edge.b(), lineB != null ? lineB : lineA);
        int[] draw = drawTargets(headA, headB);
        boolean moved = drawFromReservoir(draw, false);
        moved |= drawFromReservoir(draw, true);
        return moved;
    }

    /**
     * The drain dual of {@link #topUp} for a run that REALLY carried fluid this tick: cells
     * standing above the solved hydraulic grade line — the display heads interpolated along the
     * run, the same line the goggle's gauge pressure reads — shed their excess into the end
     * reservoirs, so a run packed full by an earlier fast phase tracks the falling line while two
     * tanks equalize instead of staying full until the flow dies ("the pipes stay full of fluid
     * until it has settled"). Strictly bounded: no cell drops below the flow depth the brigade's
     * plug needs, pours ride {@link #pourIntoReservoir}'s walk gated on each end's OWN surface
     * (nothing climbs, open mouths get nothing), the shed runs ONLY while the line wets every
     * cell's window end to end — a cell the line cannot reach means SUCTION is carrying fluid
     * there (a working siphon's leg or crest), and draining any of that column would break the
     * prime, so such a run is left entirely alone — and pump-adjacent edges never shed (their
     * column is pump-driven, not gravity-shaped). A gas run keeps the plain top-up (a gas head
     * is not an elevation — deferred mirror).
     */
    boolean shed(int depthFloorMb) {
        if (cells.isEmpty()) return false;
        medium = settleMedium();
        mirrored = lighterThanAir(medium);
        if (mirrored) return false;
        // A pump-adjacent run is pressure/suction-driven, not a gravity conduit: a discharge
        // line legitimately packs full-bore and shedding a suction line back into its source
        // would fight the pump's own pull. The grade line is a GRAVITY construct — pumped
        // edges keep their column.
        if (network.graph.node(edge.a()).isPump() || network.graph.node(edge.b()).isPump()) {
            return false;
        }
        // The grade line hangs between the ends' RESTING lines — a finite reservoir's RENDERED
        // surface (the settle's datum: the pipe must track the fluid the player sees, not the
        // liquid head riding up to a quarter block above a Create tank's visible fill), a solved
        // head only at a junction/pump end. A mouth or empty end contributes none: no line, no
        // shedding.
        Double lineA = restingLine(edge.a(), edge.b());
        Double lineB = restingLine(edge.b(), edge.a());
        if (lineA == null || lineB == null) return false;
        int[] toA = floodedTargets(lineA, lineB, depthFloorMb, edge.a());
        int[] toB = floodedTargets(lineA, lineB, depthFloorMb, edge.b());
        if (toA == null || toB == null) return false;
        boolean moved = pourIntoReservoir(toA, false);
        moved |= pourIntoReservoir(toB, true);
        return moved;
    }

    /**
     * Shed targets for pours into one end: the grade line interpolated at each cell — raised to
     * the receiving reservoir's own surface, so the pour stays a downhill act — mapped onto the
     * cell's window and floored at the flow depth. Null (no shedding at all) as soon as the LINE
     * misses one cell's window: the conduit is not flooded end to end, and what stands in the
     * unreached cells is suction-held column.
     */
    private int[] floodedTargets(double headA, double headB, int depthFloorMb, int endIndex) {
        Reservoir end = network.reservoirAt(endIndex);
        double endSurface = end != null && end.isFiniteReservoir()
                ? surfaceOf(end) : Double.NEGATIVE_INFINITY;
        int[] target = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            double line = headA + (headB - headA) * ((i + 1.0) / (cells.size() + 1));
            if (windowFillFrac(cells.get(i), line) <= 0) return null;
            double frac = windowFillFrac(cells.get(i), Math.max(line, endSurface));
            target[i] = Math.max((int) Math.round(frac * network.cellCapacity), depthFloorMb);
        }
        return target;
    }

    // ------------------------------------------------------------ the elevation frame
    // A gas is a liquid under inverted gravity, so its rest state is the SAME math run in a
    // MIRRORED frame: every elevation negated, min/max meanings preserved. All settle logic
    // reads elevations exclusively through these wrappers; with {@code mirrored} false they
    // are the identity, so the liquid paths are bit-for-bit unchanged.

    /** A reservoir's resting line in-frame: the rendered liquid surface, or the gas interface. */
    private double surfaceOf(Reservoir reservoir) {
        return mirrored ? -reservoir.gasSurface() : reservoir.surface();
    }

    /** The low edge of a cell's fluid window in-frame (liquid: window bottom; gas: minus its top). */
    private double windowLow(BlockPos pos) {
        return mirrored ? -(network.windowBottomY(pos) + network.windowHeight(pos))
                : network.windowBottomY(pos);
    }

    /** Fraction of a cell's window past the in-frame line — the one fill↔height conversion. */
    private double windowFillFrac(BlockPos pos, double line) {
        return Math.clamp((line - windowLow(pos)) / network.windowHeight(pos), 0, 1);
    }

    /** A cell's low block edge in-frame (fall/spread comparisons: gas "falls" upward). */
    private double cellLow(BlockPos pos) {
        return mirrored ? -(network.cellBottomY(pos) + 1) : network.cellBottomY(pos);
    }

    /** A cell's centre in-frame. */
    private double cellMid(BlockPos pos) {
        return mirrored ? -network.cellCenterY(pos) : network.cellCenterY(pos);
    }

    /** The medium this run settles as: its content, the solved rest fluid, or an end reservoir's. */
    private FluidStack settleMedium() {
        FluidStack present = presentFluid();
        if (!present.isEmpty()) return present;
        FluidStack rest = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (!rest.isEmpty()) return rest;
        Reservoir a = network.reservoirAt(edge.a());
        if (a != null && a.isFiniteReservoir() && a.holdsFluid()) return a.contents();
        Reservoir b = network.reservoirAt(edge.b());
        if (b != null && b.isFiniteReservoir() && b.holdsFluid()) return b.contents();
        return FluidStack.EMPTY;
    }

    private boolean isCrestBroken() {
        // The solve's crest data is a LIQUID quantity (suction/cavitation over a high point);
        // a gas run always settles as unbroken — its "crest" would be a dip, a deferred mirror.
        return !mirrored && solution.isCrestBroken(edge.index());
    }

    /**
     * Register Create's crossing-the-streams for an idle run: each end reservoir presses its own
     * fluid down its side of the run (a tank joined to a pipe pushes its fluid at the mouth — no
     * flow needed), and where that column meets an INCOMPATIBLE fluid the two react, exactly as
     * Create pulls a tank's fluid into a pipe already carrying another. The meeting point is the
     * MOUTH cell for a uniform run into a rejecting tank, or an interface DEEP in the run where two
     * tanks' columns touch — water settled in from the water end, lava from the lava end, meeting
     * mid-run (each mouth cell then matches its OWN tank, so the old end-cell-only check saw no
     * collision and the fluids just sat there touching). Two foreign pipe cells with no reservoir
     * driving them still just block, as two idle fluids do.
     */
    private boolean reactToBoundaryCollision() {
        // Non-short-circuit `|`: a run walled by a rejecting tank at BOTH ends reacts at both.
        return pressColumn(edge.a(), false) | pressColumn(edge.b(), true);
    }

    /**
     * Walk in from one end reservoir through the cells its own fluid fills — the column it presses
     * into the run — and react at the first cell holding a fluid it REJECTS (can neither accept nor
     * supply). Stops at a dry cell (a gap it would simply fill: no contact yet) or a compatible
     * foreign fluid (a multi-fluid tank carrying it — not a collision). No fill-level gate, exactly
     * as Create's own collision checks none.
     */
    private boolean pressColumn(int nodeIndex, boolean fromB) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir == null || !reservoir.isFiniteReservoir() || !reservoir.holdsFluid()) {
            return false;
        }
        FluidStack pressed = reservoir.contents();
        for (int step = 0; step < cells.size(); step++) {
            int i = fromB ? cells.size() - 1 - step : step;
            BlockPos pos = cells.get(i);
            if (!conductsInto(i, fromB, pressed)) return false;                // gated: never meet
            PipeStore.Store cell = network.cellAt(pos);
            if (cell == null || cell.amount() <= 0) return false;              // dry gap: no contact
            if (FluidStack.isSameFluidSameComponents(cell.fluid(), pressed)) continue; // own column
            if (!reservoir.rejects(cell.fluid())) return false;               // compatible: no react
            return network.collides(pos, cell, pressed);                      // crossing the streams
        }
        return false;
    }

    /**
     * Whether this run is a sealed, fully primed column: every cell FULL and each end reservoir
     * wet up to its opening (so no air can enter), with the crest within the suction limit of
     * both surfaces (higher would cavitate at the top and collapse). Such a column is what a
     * working siphon leaves behind when it goes idle — it must be RETAINED, not receded to the
     * waterline, because a dry crest can no longer self-prime.
     */
    private boolean sealedPrimedColumn() {
        if (mirrored) return false; // barometric gas columns: a deferred mirror
        Reservoir a = network.reservoirAt(edge.a());
        Reservoir b = network.reservoirAt(edge.b());
        if (!sealsItsEnd(a, cells.getFirst()) || !sealsItsEnd(b, cells.getLast())) return false;
        double crestY = Double.NEGATIVE_INFINITY;
        for (BlockPos pos : cells) {
            PipeStore.Store cell = network.cellAt(pos);
            if (cell == null || cell.amount() < network.cellCapacity) return false;
            crestY = Math.max(crestY, network.cellCenterY(pos));
        }
        double limit = PipesNPhysicsConfig.SUCTION_LIMIT.get();
        return crestY <= a.surface() + limit && crestY <= b.surface() + limit;
    }

    /**
     * An end is sealed while its finite reservoir's live surface still wets the end cell's BORE
     * opening — the block bottom is not enough: a waterline in the gap below the bore leaves the
     * opening in the tank's head space, air enters, and the column must recede (a full run
     * between two low tanks held its 250 mB forever — "the pipes hold 250 instead of equalizing").
     */
    private boolean sealsItsEnd(Reservoir reservoir, BlockPos endCell) {
        return reservoir != null && reservoir.isFiniteReservoir() && reservoir.holdsFluid()
                && reservoir.surface() > network.windowBottomY(endCell) + SURFACE_EPS;
    }

    /**
     * The resting surface an endpoint contributes: a finite reservoir's LIVE surface; an open end
     * defers to the far side (its mouth is a spill threshold, not a surface); anything else (a
     * pump or junction) uses the solved head — or, for a fill-only run, the CEILING field
     * (reservoir anchors + pump boosts: how high the line can be packed).
     *
     * An EMPTY reservoir has no surface and defers too — its floor only CAPS the far side's line
     * ({@link #emptyFloorCap}). Anchoring the line at the floor froze runs solid: an empty tank
     * up a riser set targets ABOVE the run's whole content, so nothing was ever "excess" and the
     * pour/fall/spread machinery never engaged — a dreg beside an open mouth just sat there.
     * With no live surface or head at either end the run is headless and gravity-pools.
     */
    private Double restingLine(int nodeIndex, int farNodeIndex) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null && reservoir.isFiniteReservoir()) {
            if (!reservoir.holdsFluid()) return null;
            // In the gas frame a vessel holding a LIQUID has no gas interface to contribute —
            // its line would be phantom; the exchange walks still fill it if it accepts the gas.
            if (mirrored && !lighterThanAir(reservoir.contents())) return null;
            return surfaceOf(reservoir);
        }
        if (reservoir != null && reservoir.isOpenMouth()) return null; // read the far side instead
        // Display heads/ceilings are LIQUID elevations; the gas frame cannot read them (a gas
        // head is fill − baseY). A pump/junction end contributes no gas line.
        if (mirrored) return null;
        Double head = solution.nodeHeads().get(nodeIndex);
        if (!fillOnly) return head;
        Double ceiling = solution.nodeCeilings().get(nodeIndex);
        return ceiling != null ? ceiling : head;
    }

    /** An empty reservoir's floor still caps its side of the line: fluid drains down toward it
     *  (in the gas frame its CEILING caps upward: gas rises toward the empty vessel above). */
    private double emptyFloorCap(int nodeIndex, double line) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null && reservoir.isFiniteReservoir() && !reservoir.holdsFluid()) {
            return Math.min(line, surfaceOf(reservoir));
        }
        return line;
    }

    /** What the run may RETAIN: waterline plus the barometric allowance on a broken siphon's legs. */
    private int[] retentionTargets(double headA, double headB) {
        return hydrostaticTargets(headA, headB, PipesNPhysicsConfig.SUCTION_LIMIT.get());
    }

    /** What the run may DRAW from a reservoir: never above the surface (air sits at a broken crest). */
    private int[] drawTargets(double headA, double headB) {
        return hydrostaticTargets(headA, headB, 0);
    }

    private int[] hydrostaticTargets(double headA, double headB, double suctionAllowance) {
        boolean crestBroken = isCrestBroken();
        // A barometric leg is supported by the VACUUM in the broken crest's gap, which exists
        // only while the tube is sealed against air at BOTH ends (air entering either end rises
        // into the gap and both legs fall to their bare surfaces). A wet-but-unsealed end — a
        // tank whose surface sits below its end cell's bore — is an air path like an empty one:
        // per-leg "endpoint holds fluid" kept a run's sink leg hanging full in mid-air forever
        // beside a drained source.
        boolean sealed = crestBroken && suctionAllowance > 0
                && sealsItsEnd(network.reservoirAt(edge.a()), cells.getFirst())
                && sealsItsEnd(network.reservoirAt(edge.b()), cells.getLast());
        int crest = 0;
        if (crestBroken) {
            double crestY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < cells.size(); i++) {
                double y = network.cellCenterY(cells.get(i));
                if (y > crestY) {
                    crestY = y;
                    crest = i;
                }
            }
        }
        int[] target = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            double line;
            if (crestBroken) {
                double head = i <= crest ? headA : headB;
                line = sealed ? head + suctionAllowance : head;
            } else {
                line = Math.min(headA, headB);
            }
            // Map the line onto the cell's drawn fluid window (bore for a horizontal cell, the
            // full block for a vertical riser), so a settled pipe's surface lands exactly on the
            // tank waterline it equalized with — whichever way the cell renders. The line is NOT
            // nudged: a 0.05-block bump is 13% of the 6/16 bore and would settle the pipe visibly
            // ABOVE the tank (and dead-zone a run whose waterline sits just above the bore floor);
            // the mB-based SETTLE_BAND hysteresis is the anti-flap deadband, not a world-Y nudge.
            target[i] = (int) Math.round(
                    windowFillFrac(cells.get(i), line) * network.cellCapacity);
        }
        return target;
    }

    /** Excess flows to an adjacent deficit, strictly cell to cell, both sweeps. */
    private boolean levelToTargets(int[] target) {
        boolean moved = false;
        for (int i = 0; i < cells.size() - 1; i++) {
            moved |= moveTowardTargets(cells.get(i), cells.get(i + 1), target[i], target[i + 1]);
        }
        for (int i = cells.size() - 2; i >= 0; i--) {
            moved |= moveTowardTargets(cells.get(i + 1), cells.get(i), target[i + 1], target[i]);
        }
        return moved;
    }

    /**
     * Above-target fluid also FALLS and SPREADS: a column hovering over the waterline (its cells
     * all target 0, so no pair sees a deficit) still runs downhill into whatever room is below —
     * the receiver may transiently exceed its own target and pours onward next sweep — and a
     * horizontal cell's water runs toward an emptier same-height neighbour (a crest arch drains
     * off its corners). Without these a riser drained at the bottom kept its fluid hanging
     * mid-air. A fill-only (held) column is pinned by pressure and never falls.
     */
    private boolean fallAndSpread(int[] target) {
        boolean moved = false;
        for (int i = 0; i < cells.size() - 1; i++) {
            moved |= fallDownhill(cells.get(i), cells.get(i + 1), target[i]);
            moved |= fallDownhill(cells.get(i + 1), cells.get(i), target[i + 1]);
            moved |= spreadLevel(cells.get(i), cells.get(i + 1), target[i]);
            moved |= spreadLevel(cells.get(i + 1), cells.get(i), target[i + 1]);
        }
        return moved;
    }

    /**
     * The run communicates with each end reservoir through the CONDUCTING prefix of at-target
     * cells at that end (the shared waterline): fluid enters the first below-target cell past it
     * and leaves from the first above-target one — everything between just passes it through.
     * Strictly hydraulic: no exchange past a dry gap. Excess may also pour out of an open mouth
     * sitting at or below the run. Fill-only runs never give anything back.
     *
     * POURS gate on each end's OWN line, never the flattened profile: pouring into a reservoir
     * is a gravity act, so only fluid standing ABOVE that reservoir's surface may enter it. The
     * min-flattened retain targets read a film beside the HIGHER tank as excess and poured it
     * back UP into it — with the flow pass pulling the same film out through the lip's dregs
     * allowance, fluid ping-ponged tank↔head-cell at 4 mB forever while the true sink starved
     * (the "flows shortly, stops" limit cycle at the lip equilibrium).
     */
    private boolean exchangeWithReservoirs(int[] retain, int[] draw, double headA, double headB) {
        boolean moved = drawFromReservoir(draw, false);
        moved |= drawFromReservoir(draw, true);
        if (!fillOnly) {
            moved |= pourIntoReservoir(pourTargets(retain, pourLine(edge.a(), headA)), false);
            moved |= pourIntoReservoir(pourTargets(retain, pourLine(edge.b(), headB)), true);
            moved |= pourOutOpenEnd(false);
            moved |= pourOutOpenEnd(true);
        }
        return moved;
    }

    /**
     * The line a pour into this end gates on: the end's OWN surface, an EMPTY reservoir
     * included (its floor — in the gas frame its ceiling). The flattened fallback substituted
     * the FAR side's line at an empty end, which read fluid resting beside the empty vessel as
     * excess it could jump INTO it across the opening — for a gas, pouring DOWN into an empty
     * tank below the run, the exact inversion of buoyancy.
     */
    private double pourLine(int nodeIndex, double flattenedFallback) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null && reservoir.isFiniteReservoir() && !reservoir.holdsFluid()) {
            return surfaceOf(reservoir);
        }
        return flattenedFallback;
    }

    /**
     * The pour gate for one end: on an unbroken run, the profile of that end's OWN line. A
     * crest-broken run keeps the retain targets — they are already per-leg, and a collapsing
     * barometric leg must pour against its retention allowance, not the bare surface.
     */
    private int[] pourTargets(int[] retain, double endHead) {
        if (isCrestBroken()) return retain;
        int[] target = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            target[i] = (int) Math.round(
                    windowFillFrac(cells.get(i), endHead) * network.cellCapacity);
        }
        return target;
    }

    /**
     * A below-target cell past the conducting prefix DRAWS straight from the end reservoir — but
     * only while the reservoir's LIVE surface actually reaches the opening at its end cell (the
     * draw lip), so an empty tank can never be asked to supply a phantom column.
     */
    private boolean drawFromReservoir(int[] target, boolean fromB) {
        Reservoir reservoir = network.reservoirAt(fromB ? edge.b() : edge.a());
        if (reservoir == null || !reservoir.isFiniteReservoir() || !reservoir.holdsFluid()) return false;
        BlockPos endCell = fromB ? cells.getLast() : cells.getFirst();
        if (surfaceOf(reservoir) <= windowLow(endCell)) return false;
        FluidStack supplied = settleFluid(reservoir);
        for (int step = 0; step < cells.size(); step++) {
            int i = fromB ? cells.size() - 1 - step : step;
            PipeStore.Store cell = network.cellAt(cells.get(i));
            if (cell == null || target[i] <= 0) return false; // dry-target cell: stops conducting
            // What would cross this boundary: the cell's own column where it has one, else what
            // the reservoir would put in it. A pipe gate rejecting it walls the walk right here.
            FluidStack want = cell.amount() > 0 ? cell.fluid() : supplied;
            if (!conductsInto(i, fromB, want)) return false;
            int deficit = target[i] - cell.amount();
            // The hysteresis band guards PARTIAL targets (they wobble with the tank's own
            // surface); a full-cell target is clamped and cannot wobble, so it tops off exactly —
            // otherwise a run whose flow stopped mid-fill sits forever a few mB short.
            if (deficit > hysteresisMb || (target[i] >= network.cellCapacity && deficit > 0)) {
                if (want.isEmpty() || cell.room(want) <= 0) return false;
                int got = reservoir.drain(want, Math.min(deficit, rate));
                if (got <= 0) return false;
                cell.insert(want, got);
                ledger.moved(edge, got);
                return true;
            }
        }
        return false;
    }

    /**
     * The mirror walk: the first ABOVE-target cell past the conducting prefix pours straight into
     * the end reservoir (a broken siphon's crest collapsing, a hump receding through a full
     * riser). Pours act on ANY excess — no hysteresis: pouring in RAISES the tank's surface and
     * with it the target, so this direction is self-stabilizing, and the DRAW side's band alone
     * breaks the draw↔pour loop. Sharing the band here left a visible ~10%-of-a-cell film
     * standing above the waterline in every cell beside a near-empty tank ("the flagged pipe
     * still holds fluid and does not flow into the tank").
     */
    private boolean pourIntoReservoir(int[] target, boolean fromB) {
        Reservoir reservoir = network.reservoirAt(fromB ? edge.b() : edge.a());
        if (reservoir == null || !reservoir.isFiniteReservoir()) return false;
        for (int step = 0; step < cells.size(); step++) {
            int i = fromB ? cells.size() - 1 - step : step;
            PipeStore.Store cell = network.cellAt(cells.get(i));
            if (cell == null) return false;
            if (!conductsInto(i, fromB, cell.fluid())) return false; // a gate walls the pour here
            int excess = cell.amount() - target[i];
            if (excess > 0) {
                int move = excess <= Reservoir.DREGS_MB ? excess : Math.min(excess, rate);
                int poured = reservoir.fill(cell.fluid(), move);
                if (poured <= 0) return false;
                cell.extract(poured);
                ledger.moved(edge, poured);
                return true;
            }
            // A wet at-target cell conducts (the shared waterline); a dry-target cell is a gap.
            if (target[i] <= 0 || cell.amount() < target[i] - hysteresisMb) return false;
        }
        return false;
    }

    /**
     * A running pump at an endpoint whose PUSH side faces this run PACKS the line: pull from the
     * pump's other side (a directly-adjacent reservoir, or the pump-adjacent cell of its supply
     * run, which its own settle keeps refilling) into this run's end cell, up to the cell's
     * target. This is how a dead-headed line — a pump against a shut valve or an over-high sink —
     * fills with real fluid even though its steady-state solved flow is 0. A line already AT its
     * target is packed as far as it goes, and the pump then {@link #deliverThroughPump delivers
     * through} it into the sink instead.
     */
    private boolean primeFromPumps(int[] target) {
        // A dead-headed GAS line stays with the brigade: pump packing reads ceiling-field
        // quantities the gas frame cannot, and the flow passes already move powered gas.
        if (mirrored) return false;
        // Non-short-circuit `|`: BOTH ends must attempt priming, not just the first that moves.
        return pumpPrime(target, edge.a()) | pumpPrime(target, edge.b());
    }

    private boolean pumpPrime(int[] target, int nodeIndex) {
        Node pump = network.graph.node(nodeIndex);
        if (!pump.isPump() || !FlowSolver.isPumpRunning(network.level, pump)) return false;
        BlockPos toward = PipeGeometry.adjacentCell(network.graph, edge, nodeIndex);
        if (toward == null || !toward.equals(pump.pushCell())) return false;
        // A wire outlet has no column of its own: nothing to pack, so the pump delivers straight
        // across it, carrying whatever its suction side offers.
        if (cells.isEmpty()) return deliverThroughPump(pump, FluidStack.EMPTY);

        boolean atA = nodeIndex == edge.a();
        BlockPos endCell = atA ? cells.getFirst() : cells.getLast();
        PipeStore.Store cell = network.cellAt(endCell);
        if (cell == null) return false;
        int want = Math.min((atA ? target[0] : target[target.length - 1]) - cell.amount(), rate);
        // Packed to the waterline already: the pump can put nothing more IN the pipe, so it
        // delivers on through into the sink instead. Priming first and delivering second is the
        // physical order — a pump fills its outlet line before it pushes anything out of it.
        if (want <= 0) return cell.amount() > 0 && deliverThroughPump(pump, cell.fluid());

        for (Edge supply : network.graph.edgesOf(nodeIndex)) {
            if (supply.index() == edge.index()) continue;
            int got = 0;
            FluidStack fluid;
            if (supply.pipes().isEmpty()) {
                Reservoir source = network.reservoirAt(supply.other(nodeIndex));
                if (source == null || !pumpMayDraw(source, supply, nodeIndex)) continue;
                fluid = cell.amount() > 0 ? cell.fluid() : source.contents();
                if (fluid.isEmpty() || cell.room(fluid) <= 0) continue;
                if (!endOpens(!atA, fluid)) continue;
                got = source.drain(fluid, want);
            } else {
                BlockPos flank = PipeGeometry.adjacentCell(network.graph, supply, nodeIndex);
                PipeStore.Store feed = network.cellAt(flank);
                if (feed == null || feed.amount() <= 0) continue;
                fluid = feed.fluid();
                // A gate on either flank walls the pump exactly as it walls a run: it can neither
                // lift a fluid its suction pipe rejects nor pack one its outlet pipe rejects.
                if (!crossesPump(pump, flank, fluid) || !endOpens(!atA, fluid)) continue;
                // A running pump packing its supply fluid into an outlet cell holding a DIFFERENT one
                // is crossing the streams (a dead-headed pump has no brigade run to catch it).
                if (network.collides(endCell, cell, fluid)) return true;
                if (cell.room(fluid) <= 0) continue;
                if (feed.amount() >= network.cellCapacity) {
                    // The supply column has arrived: it CONDUCTS — draw from the reservoir
                    // behind it, so a held suction line never dips while the pump packs its
                    // outlet. Only when nothing is behind (tank empty, below its lip) does the
                    // pump drain its own suction line forward.
                    Reservoir behind = network.reservoirAt(supply.other(nodeIndex));
                    if (behind != null && behind.isFiniteReservoir() && behind.holdsFluid()
                            && pumpMayDraw(behind, supply, nodeIndex)
                            && FluidStack.isSameFluidSameComponents(behind.contents(), fluid)) {
                        got = behind.drain(fluid, want);
                    }
                }
                if (got <= 0) got = feed.extract(Math.min(want, feed.amount())).getAmount();
            }
            if (got > 0) {
                cell.insert(fluid, got);
                ledger.moved(edge, got);
                return true;
            }
        }
        return false;
    }

    /**
     * The other half of {@link #pumpPrime}: with its outlet run already at the resting waterline
     * the pump delivers THROUGH that column into the sink at the far end, still drawing from its
     * own suction side, one {@link FlowSolver#pumpFlowCapMb} step per tick.
     *
     * Without this a running pump strands the primed suction column the retention rules
     * deliberately keep, every time the solve assembles no branch for want of a participating
     * source — the drained item drain, a tank broken off, a contraption undocked. Nothing else
     * can finish the job: the settle only ever packs the outlet PIPE to its waterline, and
     * pouring on into the tank is a gravity act a pipe already AT that waterline never satisfies,
     * so delivery into a reservoir otherwise happens exclusively in the solve-driven brigade.
     *
     * The outlet run itself is left untouched — at profile it is a conducting column, so what the
     * pump lifts off the suction side is what the sink receives (the wire remnant of
     * {@code FlowingRun.deliverThroughWire}, which this mirrors two-phase refund and all). Drawing
     * from the suction side rather than the run's own sink-end cell is what keeps it from
     * circling: that cell would just be topped back up out of the same tank next tick.
     *
     * {@code column} is the outlet run's own fluid — what physically stands between the pump and
     * the sink — or EMPTY across a zero-cell WIRE outlet (a pump flush against its tank), which
     * has no column and so carries whatever the suction side holds. Without the wire case a pump
     * with no pipe on its push side could never deliver at all: {@link #pumpPrime} needs an outlet
     * cell to pack, and a zero-cell edge has none ("why does this pump not drain the pipe behind
     * it empty" — a full suction line, a sink with room, and a spinning pump between them).
     */
    private boolean deliverThroughPump(Node pump, FluidStack column) {
        Reservoir sink = network.reservoirAt(edge.other(pump.index()));
        if (sink == null) return false;
        int cap = FlowSolver.pumpFlowCapMb(network.level, pump);

        for (Edge supply : network.graph.edgesOf(pump.index())) {
            if (supply.index() == edge.index()) continue;
            Reservoir source = supply.pipes().isEmpty()
                    ? network.reservoirAt(supply.other(pump.index())) : null;
            if (source != null && !pumpMayDraw(source, supply, pump.index())) continue;
            PipeStore.Store feed = source != null ? null : feedCell(supply, pump.index());
            FluidStack fluid = column.isEmpty() ? offeredBy(source, feed) : column;
            if (fluid.isEmpty()) continue;
            // Delivering ON THROUGH the outlet column crosses every one of its boundaries, so a
            // gate anywhere along it stops the pump as hard as a shut valve would.
            if (!conductsThroughRun(fluid)) continue;
            int want = sink.probeFill(fluid, cap);
            if (want <= 0) return false;
            int got;
            if (source != null) {
                got = source.drain(fluid, want);
            } else if (feed != null && FluidStack.isSameFluidSameComponents(feed.fluid(), fluid)) {
                got = feed.extract(Math.min(want, feed.amount())).getAmount();
            } else {
                continue;
            }
            if (got <= 0) continue;
            int delivered = sink.fill(fluid, got);
            // Two-phase: a sink accepting less than it simulated gets the remainder put straight
            // back where it was lifted from, which by construction still has the room for it.
            if (delivered < got) {
                int leftover = got - delivered;
                if (source != null) source.refund(fluid, leftover);
                else feed.insert(fluid, leftover);
            }
            if (delivered <= 0) continue;
            ledger.moved(edge, delivered);
            return true;
        }
        return false;
    }

    /**
     * The cell a pump lifts from on one supply run: the first one still holding fluid, walking in
     * from the pump. Reading only the ADJACENT cell left the run's last dregs stranded one cell
     * behind it — the pump empties the cell at its flank every tick, and the anti-slosh gate then
     * refuses to hand the remainder across ("why does this pump not drain the pipe behind it
     * empty" ended at 4 mB in the far cell). The walk is {@link #firstWetCell}'s: it crosses
     * emptied cells but never a dry rise — nor a shut gate, here or at the pump's own flank.
     */
    private PipeStore.Store feedCell(Edge supply, int pumpIndex) {
        List<BlockPos> pipes = supply.pipes();
        BlockPos wet = firstWetCell(pumpIndex == supply.a() ? pipes : pipes.reversed());
        if (wet == null) return null;
        PipeStore.Store cell = network.cellAt(wet);
        BlockPos flank = PipeGeometry.adjacentCell(network.graph, supply, pumpIndex);
        return crossesPump(network.graph.node(pumpIndex), flank, cell.fluid()) ? cell : null;
    }

    /** Whether {@code fluid} may pass between a pump and the cell on one of its flanks. */
    private boolean crossesPump(Node pump, BlockPos flank, FluidStack fluid) {
        return flank != null && PipeGates.admits(network.level, flank, pump.pos(), fluid);
    }

    /**
     * Whether a pump may lift out of a supply RESERVOIR at all: the draw-lip wall the solve applies
     * ({@code FluidPass.canDrawFrom}), which these settle paths bypassed entirely — they drain the
     * handler straight, and no lip cap is installed on a tick that solved no flow. A pump therefore
     * packed its outlet run and delivered on out of a tank whose visible surface stood more than a
     * block BELOW its own opening ("why does this pump pipe? the surface of the fluid does not reach
     * the pump yet"), with the solve's own wall — the reason nothing solved — sitting right there.
     *
     * A pump PULLING keeps the opening cell's BLOCK floor rather than the aperture lip (its suction
     * takes the puddle under the pipe too, the shared {@link PipeWindow#drawLipY} datum), and
     * {@code PUMP_DRAIN_ANY_LEVEL} removes the wall outright — a dip tube lifts from any level. A
     * mouth or bottomless source is exempt, exactly as in the solve: its opening is submerged by
     * construction.
     */
    private boolean pumpMayDraw(Reservoir source, Edge supply, int pumpIndex) {
        if (source.isOpenMouth() || source.isInfiniteSource()) return true;
        if (PipesNPhysicsConfig.PUMP_DRAIN_ANY_LEVEL.get()) return true;
        BlockPos opening = PipeGeometry.adjacentCell(
                network.graph, supply, supply.other(pumpIndex));
        return opening != null
                && source.surface() > PipeWindow.drawLipY(network.level, opening, true);
    }

    /** What one supply side of a pump has to offer: its reservoir's contents, or its feed cell's. */
    private static FluidStack offeredBy(Reservoir source, PipeStore.Store feed) {
        if (source != null) return source.contents();
        return feed != null && feed.amount() > 0 ? feed.fluid() : FluidStack.EMPTY;
    }

    /**
     * No solve data at all (every reservoir gone or empty — a run whose tank was broken away):
     * plain gravity still acts. Contents trickle downhill cell-to-cell, spread level, pour out of
     * an open mouth at/below, and equalize with an adjacent reservoir by live surfaces — so fluid
     * pools in the dips instead of hanging frozen in a riser.
     */
    private boolean gravityPool() {
        boolean moved = false;
        for (int i = 0; i < cells.size() - 1; i++) {
            moved |= trickleDownhill(cells.get(i), cells.get(i + 1));
            moved |= trickleDownhill(cells.get(i + 1), cells.get(i));
            moved |= spreadLevel(cells.get(i), cells.get(i + 1), 0);
            moved |= spreadLevel(cells.get(i + 1), cells.get(i), 0);
        }
        moved |= pourOutOpenEnd(false);
        moved |= pourOutOpenEnd(true);
        moved |= equalizeWithReservoir(false);
        moved |= equalizeWithReservoir(true);
        return moved;
    }

    // ------------------------------------------------------------------- the pipe gates
    // A cell that REJECTS a fluid — a smart pipe's filter, a shut valve, any pipe with an opinion
    // of its own — is a WALL for it. The solve refuses to assemble a branch through such a run
    // ({@code FluidPass.runAcceptsFluid}); the settle knows only elevations, so without the same
    // wall it walked a rejected fluid straight through the filter cell and, with an open mouth at
    // the far end, poured it into the world ("I put the diesel in the basin and it went through
    // the smart pipe"). Every move here crosses a boundary, and every boundary asks this.

    /** The position at run index {@code i}: a cell, or the end NODE just past either end. */
    private BlockPos at(int i) {
        if (i < 0) return network.graph.node(edge.a()).pos();
        if (i >= cells.size()) return network.graph.node(edge.b()).pos();
        return cells.get(i);
    }

    /**
     * Whether {@code fluid} may cross between the adjacent run positions {@code i} and {@code j}.
     * A boundary onto an END node is one-sided — only the run's own cell carries a gate there.
     */
    private boolean conducts(int i, int j, FluidStack fluid) {
        if (i < 0 || i >= cells.size()) return PipeGates.admits(network.level, at(j), at(i), fluid);
        if (j < 0 || j >= cells.size()) return PipeGates.admits(network.level, at(i), at(j), fluid);
        return PipeGates.conducts(network.level, at(i), at(j), fluid);
    }

    /** Whether {@code fluid} may cross INTO cell {@code i} from the end the walk came in from. */
    private boolean conductsInto(int i, boolean fromB, FluidStack fluid) {
        return conducts(fromB ? i + 1 : i - 1, i, fluid);
    }

    /** Whether {@code fluid} may cross the opening between one END node and its own end cell. */
    private boolean endOpens(boolean fromB, FluidStack fluid) {
        return conductsInto(fromB ? cells.size() - 1 : 0, fromB, fluid);
    }

    /** Whether {@code fluid} may travel this whole run, end node to end node. */
    private boolean conductsThroughRun(FluidStack fluid) {
        for (int boundary = 0; boundary <= cells.size(); boundary++) {
            if (!conducts(boundary - 1, boundary, fluid)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- single moves

    /** Move from an above-target cell into an adjacent below-target one, rate-limited. */
    private boolean moveTowardTargets(BlockPos fromPos, BlockPos toPos, int fromTarget, int toTarget) {
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        int excess = from.amount() - fromTarget;
        int deficit = toTarget - to.amount();
        if (excess <= 0 || deficit <= 0) return false;
        int move = Math.min(Math.min(excess, deficit), rate);
        if (excess <= Reservoir.DREGS_MB) move = Math.min(excess, deficit); // dregs leave at once
        return moveBetween(fromPos, toPos, move);
    }

    /** Let a cell's above-target fluid fall into a strictly LOWER in-frame neighbour with room. */
    private boolean fallDownhill(BlockPos fromPos, BlockPos toPos, int fromTarget) {
        if (cellLow(fromPos) <= cellLow(toPos) + SURFACE_EPS) return false;
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        int excess = from.amount() - fromTarget;
        if (excess <= 0) return false;
        return moveBetween(fromPos, toPos, Math.min(excess, rate));
    }

    /** Level out above-target fluid between SAME-HEIGHT neighbours (water runs flat). */
    private boolean spreadLevel(BlockPos fromPos, BlockPos toPos, int fromTarget) {
        if (Math.abs(cellLow(fromPos) - cellLow(toPos)) > SURFACE_EPS) {
            return false;
        }
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        int excess = from.amount() - fromTarget;
        int diff = from.amount() - to.amount();
        if (excess <= 0 || diff <= Reservoir.DREGS_MB) return false;
        return moveBetween(fromPos, toPos, Math.min(Math.min(excess, diff / 2), rate));
    }

    /** A headless run's plain-gravity trickle (no targets: anything runs downhill in-frame). */
    private boolean trickleDownhill(BlockPos fromPos, BlockPos toPos) {
        if (cellLow(fromPos) <= cellLow(toPos) + SURFACE_EPS) return false;
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        return moveBetween(fromPos, toPos, Math.min(from.amount(), rate));
    }

    /**
     * Pour the run's fluid out of an open mouth at or below it (the spill of a dying run). The
     * walk crosses EMPTY cells in from the mouth to the first one holding fluid — the last dregs
     * otherwise strand one cell short forever, because {@link #spreadLevel}'s anti-slosh gate
     * refuses to push the final {@code DREGS_MB} across a level pair and nothing else moves them.
     */
    private boolean pourOutOpenEnd(boolean fromB) {
        int nodeIndex = fromB ? edge.b() : edge.a();
        Reservoir mouth = network.reservoirAt(nodeIndex);
        if (mouth == null || !mouth.isOpenMouth() || mouth.isInfiniteSource()) return false;
        int i = firstWetCellFrom(fromB);
        if (i < 0) return false;
        BlockPos pos = cells.get(i);
        PipeStore.Store cell = network.cellAt(pos);
        if (!endOpens(fromB, cell.fluid())) return false;
        double mouthY = cellMid(network.graph.node(nodeIndex).pos());
        if (mouthY > cellMid(pos) + SURFACE_EPS) {
            return false; // the mouth sits above in-frame: gravity keeps the fluid in the pipe
        }
        int move = cell.amount() <= Reservoir.DREGS_MB ? cell.amount() : Math.min(cell.amount(), rate);
        int poured = mouth.fill(cell.fluid(), move);
        if (poured <= 0) return false;
        cell.extract(poured);
        ledger.moved(edge, poured);
        return true;
    }

    /**
     * Even with no solve data, the fluid IN the pipes still communicates with an adjacent
     * reservoir: pour the first wet cell in from that end into it while the cell's own surface
     * sits above the reservoir's — a wet run beside an emptied tank drains back in instead of
     * hanging forever. Walks like {@link #pourOutOpenEnd} so dregs cannot strand behind an empty
     * end cell.
     */
    private boolean equalizeWithReservoir(boolean fromB) {
        Reservoir reservoir = network.reservoirAt(fromB ? edge.b() : edge.a());
        if (reservoir == null || !reservoir.isFiniteReservoir()) return false;
        int i = firstWetCellFrom(fromB);
        if (i < 0) return false;
        BlockPos pos = cells.get(i);
        PipeStore.Store cell = network.cellAt(pos);
        if (!endOpens(fromB, cell.fluid())) return false;
        double cellSurface = windowLow(pos)
                + cell.amount() / (double) network.cellCapacity * network.windowHeight(pos);
        if (cellSurface <= surfaceOf(reservoir) + SURFACE_EPS) return false;
        int move = cell.amount() <= Reservoir.DREGS_MB ? cell.amount() : Math.min(cell.amount(), rate);
        int poured = reservoir.fill(cell.fluid(), move);
        if (poured <= 0) return false;
        cell.extract(poured);
        ledger.moved(edge, poured);
        return true;
    }

    /**
     * Index of the first cell of THIS run holding fluid, walking in from the given end — or -1
     * with nothing to find.
     */
    private int firstWetCellFrom(boolean fromB) {
        BlockPos wet = firstWetCell(fromB ? cells.reversed() : cells);
        return wet == null ? -1 : cells.indexOf(wet);
    }

    /**
     * The first cell holding fluid along {@code path}, or null. Empty cells are CROSSED: the
     * anti-slosh gate in {@link #spreadLevel} refuses to hand the last {@code DREGS_MB} across a
     * level pair, so anything reading only the cell at its own end strands a film one cell short
     * of itself forever. The walk never climbs, though — an empty cell whose floor sits above the
     * wet cell it leads to is a dry rise the fluid cannot cross (an air gap, not a channel).
     * Neither does it cross a shut GATE: an empty cell that rejects the fluid it would carry (a
     * smart pipe's filter) walls the walk off exactly as a full one would.
     */
    private BlockPos firstWetCell(List<BlockPos> path) {
        double pathFloor = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < path.size(); i++) {
            BlockPos pos = path.get(i);
            PipeStore.Store cell = network.cellAt(pos);
            if (cell == null) return null;
            if (cell.amount() > 0) {
                if (pathFloor > windowLow(pos) + SURFACE_EPS) return null;
                return PipeGates.conductsAlong(network.level, path.subList(0, i + 1), cell.fluid())
                        ? pos : null;
            }
            pathFloor = Math.max(pathFloor, windowLow(pos));
        }
        return null;
    }

    /** The one cell-to-cell move: rate already decided, the boundary's own gate the last word. */
    private boolean moveBetween(BlockPos fromPos, BlockPos toPos, int amount) {
        if (!PipeGates.conducts(network.level, fromPos, toPos, medium)) return false;
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        return from != null && to != null && from.moveInto(to, amount) > 0;
    }

    /** The fluid this run settles with: its own content, the solved rest fluid, or the reservoir's. */
    private FluidStack settleFluid(Reservoir reservoir) {
        FluidStack present = presentFluid();
        if (!present.isEmpty()) return present;
        FluidStack rest = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (!rest.isEmpty()) return rest;
        return reservoir.contents();
    }

    private FluidStack presentFluid() {
        for (BlockPos pos : cells) {
            PipeStore.Store cell = network.cellAt(pos);
            if (cell != null && cell.amount() > 0) return cell.fluid();
        }
        return FluidStack.EMPTY;
    }
}
