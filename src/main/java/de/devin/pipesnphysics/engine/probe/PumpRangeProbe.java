package de.devin.pipesnphysics.engine.probe;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import de.devin.pipesnphysics.engine.graph.GraphCache;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.net.PumpRangePayload;
import de.devin.pipesnphysics.engine.pump.Pumps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes how far a pump can reach for the in-world range indicator.
 *
 * Reach in this engine is governed by elevation, not distance: a pump lifts fluid up
 * to a ceiling above it and draws it up from no deeper than a floor below it.
 * Horizontal runs only slow flow down.
 *
 * BOTH limits come from the engine's own machinery rather than being re-derived here,
 * which is the whole reason the overlay can be trusted against the goggle and the
 * pump's own behaviour:
 *
 * - the PUSH ceiling is {@code nodeCeilings} (§6), the friction-free potential the
 *   pipe goggle prints as "Lift left". NOT {@code pumpY + boost}, which is only right
 *   when the pump sits AT its supply — a pump lifting out of a tank below it then
 *   promised reach it did not have (a 16 RPM pump 4 blocks over a tank at 56.59 reads
 *   a ceiling of 60.59, not 64.5, so a sink at 62 is out of reach and really NO_HEAD).
 * - the PULL floor is {@link FlowSolver#drawableFloorAt}, the solve's crest gate read cell by
 *   cell and folded with max as the walk advances (a supply here must be lifted over
 *   everything already crossed, so the floor only tightens). NOT
 *   {@code pumpY − SUCTION_LIMIT}, which ignores priming — a pump above the waterline
 *   with a DRY riser reaches only as deep as the small share of head it can spend
 *   sucking ({@link FlowSolver#pumpPrimeAllowance}, a tenth of its lift by default),
 *   however deep the nominal limit would allow.
 *
 * The walk starts at the pump, branches through junctions, and stops at other pumps
 * and at endpoints. Each visited cell carries its reach MARGIN in blocks — how far
 * inside its side's limit it sits, negative once past it — and the client paints only
 * the part that is past. The walk continues a suction limit beyond the limit so the
 * out-of-reach run is drawn, then stops.
 */
public final class PumpRangeProbe {
    private static final double REACH_TOLERANCE = 0.25;
    private static final int MAX_CELLS = 512;
    /** Max age of the engine's own per-tick solution to reuse — the request throttle cadence. */
    private static final int SOLUTION_MAX_AGE_TICKS = 4;

    private PumpRangeProbe() {}

    /**
     * Builds the range paths for the pump at {@code pumpPos}. Cache-first like the goggle
     * probe: reuses the engine's cached graph and its recent per-tick solution when fresh
     * enough, otherwise builds and solves fresh. A stopped pump (or a position that is no
     * pump at all) answers with no paths.
     */
    public static PumpRangePayload probe(ServerLevel level, BlockPos pumpPos) {
        long now = level.getGameTime();
        Graph graph = GraphCache.get(level, pumpPos, now);
        Solution cached = graph == null ? null
                : GraphCache.recentSolution(level, graph, now, SOLUTION_MAX_AGE_TICKS);
        if (graph == null) {
            graph = GraphBuilder.build(level, pumpPos);
            GraphCache.store(level, graph, now);
        }
        Node pump = graph.nodeAt(pumpPos);
        if (pump == null || !pump.isPump() || pump.pumpFacing() == null) {
            return new PumpRangePayload(pumpPos, List.of());
        }

        double rpm = Pumps.strength(level, pumpPos);
        if (rpm < 0.01) return new PumpRangePayload(pumpPos, List.of());
        double pumpHead = rpm * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();
        double suction = PipesNPhysicsConfig.SUCTION_LIMIT.get();

        Solution solution = cached != null ? cached : FlowSolver.solve(level, graph);
        // A gas ceiling lives in buoyancy units, not world elevation (§4), so no elevation
        // margin can be read off it — the pipe goggle suppresses its lift/reach line for the
        // same reason, and a sleeve painted from that number would be confident nonsense.
        if (carriesGas(graph, solution, pump)) return new PumpRangePayload(pumpPos, List.of());

        Double known = solution.nodeHeads().get(pump.index());
        double supplyHead = known != null ? known : pump.worldY();
        // The fallback's anchor is NaN, NOT the pump's own head: an anchor is a SUPPLY SURFACE, and
        // when no reservoir seeded the field there is no surface — only the self-anchor fiction at
        // the pump's own centre (§6). Saying "here" would make the push side hide every cell that
        // stands below the PUMP as gravity's work, which is the same fiction that once capped the
        // pull side's paint at one block. NaN fails the paint's supply test open, so a run with
        // nothing supplying it is painted by its reach alone. The push CEILING still seeds from
        // supplyHead — that one is a real elevation either way.
        Reach fallback = new Reach(
                pump.worldY(), Double.NaN, supplyHead + pumpHead, pump.worldY() - suction);

        Walker walker = new Walker(level, graph, solution, fallback, suction,
                FlowSolver.pumpPrimeAllowance(pumpHead));
        for (Edge edge : graph.edgesOf(pump.index())) {
            BlockPos toward = PipeGeometry.adjacentCell(graph, edge, pump.index());
            boolean push = toward.equals(pumpPos.relative(pump.pumpFacing()));
            boolean pull = toward.equals(pumpPos.relative(pump.pumpFacing().getOpposite()));
            if (!push && !pull) continue;

            // The cell the pump actually draws through seeds the floor — for a zero-length edge
            // that is the JUNCTION on its flank, which belongs to no run and so has no crest of
            // its own. Without it the pump's own reach reads as the ungated fallback while the
            // cells one hop away read the real gate (see FlowSolver.drawableFloorAt).
            double seedFloor = FlowSolver.drawableFloorAt(level, toward, walker.primeAllowance);
            Reach seedReach = walker.reachOn(edge, seedFloor);
            List<PumpRangePayload.RangeCell> seedPath = new ArrayList<>();
            seedPath.add(new PumpRangePayload.RangeCell(pumpPos.asLong(),
                    seedReach.marginAt(pump.worldY(), pull),
                    seedReach.aboveSupplyAt(pump.worldY()), false));
            walker.walk(edge, pump.index(), seedPath, pull, seedFloor);
        }
        return new PumpRangePayload(pumpPos, walker.paths);
    }

    /** Whether the pump's own branches carry a lighter-than-air fluid, resting or flowing. */
    public static boolean carriesGas(Graph graph, Solution solution, Node pump) {
        for (Edge edge : graph.edgesOf(pump.index())) {
            if (PipeProbe.isGas(solution.edgeFluids().getOrDefault(edge.index(), FluidStack.EMPTY))
                    || PipeProbe.isGas(
                            solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The elevations one branch's reach is bounded by, both measured so the pump itself is the
     * inside of the range: the {@code pushCeiling} it can lift fluid up to, and the
     * {@code pullFloor} — the deepest a supply may sit and still be drawn ({@link
     * FlowSolver#drawableFloorAt}, the solve's own crest gate, not a second copy of it),
     * which the walk tightens per cell through {@link #withFloor}.
     */
    private record Reach(double pumpY, double anchor, double pushCeiling, double pullFloor) {
        /** The same reach with a tightened pull floor — the walk lowers no floor, only raises it. */
        Reach withFloor(double floor) {
            return floor == pullFloor ? this : new Reach(pumpY, anchor, pushCeiling, floor);
        }

        /**
         * Blocks of reach left at elevation {@code y}: how far under the push ceiling climbing,
         * or how far above the pull floor descending. Negative past the limit.
         */
        float marginAt(double y, boolean pull) {
            return (float) (pull ? y - pullFloor : pushCeiling - y);
        }

        /**
         * How far {@code y} stands ABOVE the supply surface — the head the pump is actually
         * paying for there (§6: consumed = lift above the anchor). Negative below it, where
         * gravity moves the fluid and the pump's reach is not being spent at all. NaN when no
         * reservoir anchored the field at all: there is then no surface to be above or below, and
         * the paint must not pretend the pump's own elevation is one.
         */
        float aboveSupplyAt(double y) {
            return (float) (y - anchor);
        }
    }

    private static final class Walker {
        final ServerLevel level;
        final Graph graph;
        final Solution solution;
        /** Used where the solve recorded no ceiling — the pump's own elevation is all there is. */
        final Reach fallback;
        final double suction;
        /** This pump's share of head for sucking, spent establishing through a DRY suction line. */
        final double primeAllowance;
        final Set<Integer> visited = new HashSet<>();
        final List<PumpRangePayload.RangePath> paths = new ArrayList<>();
        /** Visited-cell budget: once {@code MAX_CELLS} are consumed the walk emits what it has and stops. */
        int visitedCells;

        Walker(ServerLevel level, Graph graph, Solution solution, Reach fallback, double suction,
               double primeAllowance) {
            this.level = level;
            this.graph = graph;
            this.solution = solution;
            this.fallback = fallback;
            this.suction = suction;
            this.primeAllowance = primeAllowance;
        }

        /**
         * This branch's reach: the push ceiling from its higher endpoint — the same winner rule
         * the pipe goggle's lift line uses, so the two readouts agree cell for cell — over the
         * pull floor the walk already stands on, which the cells then tighten one at a time.
         */
        Reach reachOn(Edge edge, double pullFloor) {
            Double a = solution.nodeCeilings().get(edge.a());
            Double b = solution.nodeCeilings().get(edge.b());
            if (a == null && b == null) {
                return new Reach(fallback.pumpY(), fallback.anchor(),
                        fallback.pushCeiling(), pullFloor);
            }
            int winner = b != null && (a == null || b > a) ? edge.b() : edge.a();
            Double supply = solution.nodeAnchors().get(winner);
            return new Reach(fallback.pumpY(), supply != null ? supply : fallback.anchor(),
                    solution.nodeCeilings().get(winner), pullFloor);
        }

        void walk(Edge edge, int fromNode, List<PumpRangePayload.RangeCell> path, boolean pull,
                  double floorSoFar) {
            Reach reach = reachOn(edge, floorSoFar);
            List<BlockPos> ordered = fromNode == edge.a()
                    ? edge.pipes()
                    : edge.pipes().reversed();
            // The pull floor is a RUNNING quantity: a supply here has to be lifted over every
            // cell already crossed, so it tightens cell by cell and never relaxes. Read per EDGE
            // instead — one crest applied to all of its cells — a riser came out with its LOWER
            // half unpainted and its top painted: the cells below the crest were measured against
            // a floor their own crest sets, which sits ABOVE them ("the pipe in the middle is not
            // painted at all", 2026-08-26; the tell was `margin -0.47 of 0.52` on a cell standing
            // a block ABOVE the pump). You cannot reach the top of a run but not its middle.
            double floor = floorSoFar;
            for (BlockPos cell : ordered) {
                if (visitedCells++ > MAX_CELLS) {
                    emit(path, pull);
                    return;
                }
                floor = Math.max(floor, FlowSolver.drawableFloorAt(level, cell, primeAllowance));
                Reach here = reach.withFloor(floor);
                double cellY = SableCompat.getWorldY(level, cell);
                float margin = here.marginAt(cellY, pull);
                path.add(new PumpRangePayload.RangeCell(
                        cell.asLong(), margin, here.aboveSupplyAt(cellY), true));
                if (beyondAllowance(margin, pull)) {
                    emit(path, pull);
                    return;
                }
            }

            int farIndex = edge.other(fromNode);
            Node far = graph.node(farIndex);
            // A junction or a shut valve IS a pipe cell and paints — and gates, being one more
            // cell the column has to come up through; a tank, pump, or open end is neither.
            boolean farIsCell = far.isJunction() || far.isClosedGate();
            if (farIsCell) {
                floor = Math.max(floor, FlowSolver.drawableFloorAt(level, far.pos(), primeAllowance));
            }
            Reach atFar = reach.withFloor(floor);
            float farMargin = atFar.marginAt(far.worldY(), pull);
            path.add(new PumpRangePayload.RangeCell(far.pos().asLong(), farMargin,
                    atFar.aboveSupplyAt(far.worldY()), farIsCell));

            if (far.isJunction() && !beyondAllowance(farMargin, pull) && visited.add(farIndex)) {
                boolean branched = false;
                double onward = floor;
                for (Edge next : graph.edgesOf(farIndex)) {
                    if (next.index() == edge.index()) continue;
                    walk(next, farIndex, new ArrayList<>(path), pull, onward);
                    branched = true;
                }
                if (branched) return;
            }
            emit(path, pull);
        }

        /**
         * Past the point where any liquid column could exist, so the walk stops — but only well
         * past it, on BOTH sides: the sleeve has to keep painting a while beyond the limit for
         * the red to read as "this run is out of reach" rather than as the overlay simply
         * stopping. The pull side used to cut off AT its limit, which was survivable while that
         * limit sat a full suction limit below the pump and is not now that the crest gate can
         * put it right under the pump's flank.
         */
        boolean beyondAllowance(float margin, boolean pull) {
            return margin < -(suction + REACH_TOLERANCE);
        }

        void emit(List<PumpRangePayload.RangeCell> path, boolean pull) {
            if (path.size() < 2) return;
            paths.add(new PumpRangePayload.RangePath(List.copyOf(path), pull));
        }
    }
}
