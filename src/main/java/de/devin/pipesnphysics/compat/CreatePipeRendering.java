package de.devin.pipesnphysics.compat;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import de.devin.pipesnphysics.engine.Edge;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.Graph;
import de.devin.pipesnphysics.engine.Node;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bridges the engine's per-edge fluid into Create's windowed-pipe rendering.
 *
 * Create's transport tick is cancelled ({@code GravityFlowMixin}), so the engine
 * owns the {@code PipeConnection.Flow} objects Create draws. After each solve we
 * set flows on carrying cells and clear the rest.
 *
 * A FLOWING edge fills as a travelling front ({@link #chargeEdge}), seeded along the flow
 * direction up to the front; within each cell the inbound half fills first, then the outbound.
 * Fill speed SCALES WITH THE FLOW RATE ({@link #flowPressure}): a brisk pump fills the run fast,
 * a trickle fills it slowly, so the visible fill tracks how hard fluid is actually moving.
 * Delivery stays IN STEP — {@link #deliveryReady} releases the endpoint transfer only once this
 * front reaches the sink, so visual and actual delivery line up. The front is stateless (read
 * back each tick from the cells' own flow-completeness), so it survives reloads and edits; a flow
 * restart no longer re-crawls a long run from scratch because the {@code isBackedUp}/drainDeadEdge
 * guards keep the charged cells through a transient (the long-pipe "delivery in bursts" fix).
 *
 * A RESTING edge (full but not flowing, e.g. equalized tanks) is shown full at once
 * on each cell that sits below the connected fluid surface. Every other network cell is
 * swept clear, which also wipes flows stranded by an edit since each such cell re-ticks
 * its own network.
 */
public final class CreatePipeRendering {
    /** Fill-speed knob (a Create "pressure") per mB/t of flow: faster flow fills the run faster. */
    private static final float FILL_PRESSURE_PER_MBPT = 0.6f;
    private static final float MIN_FILL_PRESSURE = 1f;
    private static final float MAX_FILL_PRESSURE = 128f;

    /** One stranded cell drains per this many ticks, so an equalized hump recedes. */
    private static final int DRAIN_INTERVAL_TICKS = 4;

    /** Waterline deadband: a cell at the surface stays full instead of flickering. */
    private static final double SUBMERSION_EPS = 0.05;

    private CreatePipeRendering() {}

    /**
     * Reflect one solve's per-edge fluid into Create's pipe Flow objects. Returns
     * true while an equalized hump is still receding — the caller must keep ticking
     * the (otherwise idle) network so the drain animation can finish instead of
     * freezing the instant flow stops.
     */
    public static boolean apply(Level level, Graph graph, Solution solution) {
        Set<BlockPos> filled = new HashSet<>();
        boolean draining = false;

        for (Edge edge : graph.edges()) {
            // A stall whose source is DRY is phantom flow: the solve pressurizes the
            // branch (so it carries a direction and a sampled fluid) but nothing can
            // actually feed it, so there is no fluid to draw. Render nothing — let the
            // sweep clear it. This is the one no-flow case that stays empty; a sink-full
            // or head-short stall keeps its fluid (it is genuinely backed up in the pipe),
            // as do equalized/valved runs. Edges that move fluid in another pass are not
            // in stalledEdges, so a real carrier is never blanked.
            if (solution.stalledEdges().contains(edge.index())
                    && solution.edgeReasons().get(edge.index()) == Solution.Reason.SOURCE_DRY) {
                continue;
            }

            EdgeFlow flow = solution.edgeFlows().get(edge.index());
            FluidStack flowing = solution.edgeFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
            if (!flowing.isEmpty() && flow.direction() != EdgeFlow.Direction.NONE) {
                chargeEdge(level, graph, edge, flowing,
                        flow.direction() == EdgeFlow.Direction.A_TO_B, flow.mbPerTick(), filled);
                continue;
            }

            // A run backed up against a full sink (SINK_FULL stall) or one a pump cannot
            // out-lift (a dead-headed NO_HEAD edge) carries no flow THIS tick — it rounds
            // to zero, so there is no direction to chargeEdge — yet it is genuinely full of
            // fluid pressed against the blockage. Preserve its already-charged cells: without
            // this the sweep blanks them, and when the sink makes room the front has to
            // re-travel the whole pipe, which reads as the flow being delayed all over again.
            if (isBackedUp(solution, edge)) {
                filled.addAll(edge.pipes());
                continue;
            }

            FluidStack resting = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
            Double headA = solution.nodeHeads().get(edge.a());
            Double headB = solution.nodeHeads().get(edge.b());
            if (!resting.isEmpty() && headA != null && headB != null) {
                draining |= restEdge(level, graph, edge, resting, headA, headB, filled,
                        level.getGameTime(), edge.index());
            } else {
                // A run that is no longer solved (no flow, and no settled waterline to hold a
                // resting column) yet still holds rendered fluid: let it RECEDE gradually
                // instead of being swept this tick. This covers a tank-to-tank run whose upper
                // supply drained, AND a PUMP run whose source briefly ran dry — e.g. a basin fed
                // in recipe-sized chunks, which empties between outputs. Without it the charged
                // pipe is blanked the instant the source dips empty, so the travelling front has
                // to re-crawl the ENTIRE run when the source refills; on a long pipe that reads as
                // delivery arriving in bursts ("pumps every N ticks, then a big slug"). Preserving
                // the cells lets flow resume mid-pipe with no re-crawl (the same reason the
                // sink-full {@code isBackedUp} guard keeps its charged cells). A genuinely empty
                // run has no wet cells, so {@code drainDeadEdge} is a no-op for it.
                draining |= drainDeadEdge(level, edge, filled, level.getGameTime());
            }
        }

        for (BlockPos cell : graph.coverage()) {
            if (!filled.contains(cell)) clearCell(level, cell);
        }
        return draining;
    }

    /**
     * Whether this edge is full of fluid pressed against a blockage rather than empty: a
     * sink-full stall, or a pump dead-headed by a sink it cannot out-lift. Such a run
     * carries no flow this tick but must keep its charged cells (vs a dry-source stall,
     * already handled above, which renders empty).
     */
    private static boolean isBackedUp(Solution solution, Edge edge) {
        if (solution.noHeadEdges().contains(edge.index())) return true;
        return solution.stalledEdges().contains(edge.index())
                && solution.edgeReasons().get(edge.index()) == Solution.Reason.SINK_FULL;
    }

    /**
     * Whether the visual fluid front has reached the SINK of a planned transfer — i.e.
     * the pipe directly feeding the sink is charged all the way to it. The engine uses
     * this to hold an endpoint transfer until the animated fluid actually arrives, so a
     * freshly started flow fills the source-side pipe before the sink begins to fill.
     *
     * Returns true (deliver now) when there is no trackable in-travel feeder into the
     * sink: a directly-adjacent endpoint, an already-charged run (steady flow, or a
     * primed pipe that merely reversed direction), or a topology we cannot follow.
     * Delivery therefore never stalls on an untrackable path — the gate only ADDS the
     * one-time travel delay while a run first fills.
     */
    public static boolean deliveryReady(Level level, Graph graph, Solution solution,
                                        Solution.Transfer transfer) {
        Node sink = graph.nodeAt(transfer.to());
        if (sink == null) return true;

        boolean travellingFeeder = false;
        for (Edge edge : graph.edgesOf(sink.index())) {
            EdgeFlow flow = solution.edgeFlows().get(edge.index());
            if (flow.direction() == EdgeFlow.Direction.NONE) continue;
            boolean towardSink = edge.b() == sink.index()
                    ? flow.direction() == EdgeFlow.Direction.A_TO_B
                    : flow.direction() == EdgeFlow.Direction.B_TO_A;
            if (!towardSink) continue;

            FluidStack carried = solution.edgeFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
            if (!carried.isEmpty()
                    && !FluidStack.isSameFluidSameComponents(carried, transfer.fluid())) {
                continue;
            }
            if (edge.pipes().isEmpty()) return true;           // adjacent: nothing to travel
            if (frontReachedNode(level, graph, edge, sink.index())) return true;
            travellingFeeder = true;
        }
        return !travellingFeeder;
    }

    /** Whether this edge's charged front has arrived at the given endpoint node. */
    private static boolean frontReachedNode(Level level, Graph graph, Edge edge, int nodeIndex) {
        List<BlockPos> pipes = edge.pipes();
        BlockPos endCell = edge.b() == nodeIndex ? pipes.get(pipes.size() - 1) : pipes.get(0);
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, endCell);
        if (pipe == null) return true;                          // can't track — don't stall
        Direction towardNode = direction(endCell, graph.node(nodeIndex).pos());
        if (towardNode == null) return true;
        PipeConnection conn = pipe.getConnection(towardNode);
        return conn == null || isComplete(conn);                // the front has exited toward the node
    }

    /**
     * Advance the travelling front of a flowing edge by one tick, at a fill speed that SCALES
     * WITH THE FLOW RATE ({@link #flowPressure}): a brisk pump fills the run fast, a trickle fills
     * it slowly, so the visible fill tracks how hard fluid is moving. Delivery stays in step —
     * {@link #deliveryReady} releases the endpoint transfer only once this front reaches the sink.
     * (A long pipe no longer re-crawls from scratch on a flow restart: the {@code isBackedUp} /
     * drainDeadEdge guards keep the charged cells through a transient, so the front resumes where
     * it left off instead of re-travelling — that was the "delivery in bursts" symptom.)
     */
    private static void chargeEdge(Level level, Graph graph, Edge edge, FluidStack fluid,
                                   boolean flowFromA, int mbPerTick, Set<BlockPos> filled) {
        List<BlockPos> pipes = edge.pipes();
        if (pipes.isEmpty()) return;
        List<BlockPos> order = flowFromA ? pipes : pipes.reversed();
        BlockPos upstream = (flowFromA ? graph.node(edge.a()) : graph.node(edge.b())).pos();
        BlockPos downstream = (flowFromA ? graph.node(edge.b()) : graph.node(edge.a())).pos();
        float pressure = flowPressure(mbPerTick);

        boolean reached = true; // the upstream node always feeds the first cell
        for (int j = 0; j < order.size() && reached; j++) {
            BlockPos cell = order.get(j);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe == null) break;

            BlockPos up = j == 0 ? upstream : order.get(j - 1);
            BlockPos down = j == order.size() - 1 ? downstream : order.get(j + 1);
            Direction inDir = direction(cell, up);
            Direction outDir = direction(cell, down);
            if (inDir == null || outDir == null) break;

            PipeConnection inC = pipe.getConnection(inDir);
            PipeConnection outC = pipe.getConnection(outDir);
            if (inC == null || outC == null) break; // can't draw a continuous front here

            // Fill the inbound half first; only once the fluid has reached the centre
            // does the outbound half start — otherwise the two halves grow at once and
            // read as a broken pair of fronts.
            boolean changed = seedCharging(inC, true, fluid, pressure);
            if (isComplete(inC)) {
                changed |= seedCharging(outC, false, fluid, pressure);
            } else {
                changed |= clearFlow(outC); // hide the exit half until fluid arrives
            }
            if (changed) pipe.blockEntity.notifyUpdate();
            filled.add(cell);

            // The next cell only starts once BOTH halves of this one are full.
            reached = isComplete(inC) && isComplete(outC);
        }
    }

    /**
     * Show a resting (non-flowing) edge full on every cell below the fluid surface.
     * Between two TANKS, fluid stranded above the settled waterline (an equalized
     * hump) recedes gradually instead of vanishing: it is held in place and its
     * highest remaining cell is released on a slow heartbeat. Other no-flow cases
     * keep the instant behaviour (the sweep clears above-surface cells at once).
     *
     * @return true if a stranded column is still receding (keep the network awake)
     */
    private static boolean restEdge(Level level, Graph graph, Edge edge, FluidStack fluid,
                                    double headA, double headB, Set<BlockPos> filled,
                                    long gameTime, int edgeIndex) {
        List<BlockPos> pipes = edge.pipes();
        BlockPos aEnd = graph.node(edge.a()).pos();
        BlockPos bEnd = graph.node(edge.b()).pos();
        // Gas head is a pressure value, not an elevation, so the waterline test below
        // is meaningless for it — a resting gas run simply fills every cell.
        boolean gas = fluid.getFluid().getFluidType().isLighterThanAir();
        boolean equalizing = graph.node(edge.a()).isHandler() && graph.node(edge.b()).isHandler();
        List<BlockPos> stranded = new ArrayList<>();

        for (int i = 0; i < pipes.size(); i++) {
            BlockPos cell = pipes.get(i);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe == null) continue;

            boolean submerged = gas;
            if (!gas) {
                double frac = (i + 1.0) / (edge.length() + 1);
                double headHere = headA + (headB - headA) * frac;
                // A pipe holds fluid once the waterline is above its BOTTOM, not its
                // centre — two level tanks settle with the surface inside the
                // connecting cell, and that cell is still full. (Tanks only draw
                // while their surface is above the pipe mouth = the cell bottom, so
                // an equalized run never settles below it.)
                double cellBottom = SableCompat.getWorldY(level, cell) - 0.5;
                submerged = headHere + SUBMERSION_EPS >= cellBottom;
            }

            if (!submerged) {
                // Above the waterline: only tank-to-tank fluid recedes; the rest is
                // left for the sweep to clear this tick.
                if (equalizing && hasFluid(pipe)) stranded.add(cell);
                continue;
            }

            BlockPos aSide = i == 0 ? aEnd : pipes.get(i - 1);
            BlockPos bSide = i == pipes.size() - 1 ? bEnd : pipes.get(i + 1);
            Direction towardA = direction(cell, aSide);
            Direction towardB = direction(cell, bSide);
            if (towardA == null || towardB == null) continue;

            // Orient the resting fill the same way the live flow would: fluid enters from
            // the higher-head endpoint (chargeEdge keys inbound off the sign of the same
            // solved head difference). Holding ONE orientation across the flowing/resting
            // boundary is what stops the next charge from flipping inbound and receding a
            // full pipe — the "equalized pipe drains and refills" revert, which only bit
            // edges whose flow ran toward node a (this used to hardcode a as the inbound rim).
            boolean aInbound = headA >= headB;
            boolean changed = seedComplete(pipe.getConnection(towardA), aInbound, fluid);
            changed |= seedComplete(pipe.getConnection(towardB), !aInbound, fluid);
            if (changed) pipe.blockEntity.notifyUpdate();
            filled.add(cell);
        }

        drainColumn(level, stranded, filled, gameTime, edgeIndex);
        return !stranded.isEmpty();
    }

    /**
     * Recede the leftover fluid in a no-longer-solved run, top-down: a drained tank-to-tank
     * run, or a pump run whose source briefly ran dry. Holds the wet cells (so a refill resumes
     * mid-pipe instead of re-crawling) and releases the highest one per heartbeat so a run that
     * stays unfed empties gradually rather than freezing full.
     */
    private static boolean drainDeadEdge(Level level, Edge edge, Set<BlockPos> filled, long gameTime) {
        List<BlockPos> wet = new ArrayList<>();
        for (BlockPos cell : edge.pipes()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe != null && hasFluid(pipe)) wet.add(cell);
        }
        drainColumn(level, wet, filled, gameTime, edge.index());
        return !wet.isEmpty();
    }

    /**
     * Hold a stranded column full, releasing only its highest cell on the heartbeat.
     * The heartbeat is offset by the edge index so several equalizing runs recede
     * staggered rather than all dropping a cell on the same tick.
     */
    private static void drainColumn(Level level, List<BlockPos> stranded,
                                    Set<BlockPos> filled, long gameTime, int edgeIndex) {
        if (stranded.isEmpty()) return;
        BlockPos top = null;
        if ((gameTime + edgeIndex) % DRAIN_INTERVAL_TICKS == 0) {
            for (BlockPos cell : stranded) {
                if (top == null
                        || SableCompat.getWorldY(level, cell) > SableCompat.getWorldY(level, top)) {
                    top = cell;
                }
            }
        }
        for (BlockPos cell : stranded) {
            if (!cell.equals(top)) filled.add(cell); // keep; the top (if any) drains via the sweep
        }
    }

    private static boolean hasFluid(FluidTransportBehaviour pipe) {
        for (Direction dir : Direction.values()) {
            if (pipe.getConnection(dir) instanceof PipeConnectionAccessor accessor
                    && accessor.pipesnphysics$getFlow().isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Seed an incomplete (still-filling) Flow; leaves an existing one for the animation. */
    private static boolean seedCharging(PipeConnection conn, boolean inbound,
                                        FluidStack fluid, float pressure) {
        if (!(conn instanceof PipeConnectionAccessor accessor)) return false;
        Optional<PipeConnection.Flow> current = accessor.pipesnphysics$getFlow();
        if (current.isPresent()) {
            PipeConnection.Flow flow = current.get();
            boolean sameFluid = FluidStack.isSameFluidSameComponents(flow.fluid, fluid);
            if (flow.inbound == inbound && sameFluid) {
                return false; // tickFlowProgress owns its progress — don't reset it
            }
            if (sameFluid && flow.complete) {
                // A FULL pipe whose flow merely reversed direction is still full: flip the
                // orientation but do NOT recede it to a fresh front (progress 0). There is
                // no front to travel when the pipe was already charged, and replaying the
                // fill from empty is exactly the visible "revert".
                flow.inbound = inbound;
                conn.wipePressure();
                conn.addPressure(inbound, pressure);
                return true;
            }
            flow.inbound = inbound;
            flow.fluid = fluid.copy();
            flow.progress.startWithValue(0);
            flow.complete = false;
        } else {
            accessor.pipesnphysics$setFlow(Optional.of(conn.new Flow(inbound, fluid.copy())));
        }
        // Reset the fill-speed knob to this flow's value on this side every (re)seed,
        // so a flipped direction or a fluid swap never animates at a stale speed.
        conn.wipePressure();
        conn.addPressure(inbound, pressure);
        return true;
    }

    /** Seed a finished (full) Flow immediately — for flowing and resting pipes alike. */
    private static boolean seedComplete(PipeConnection conn, boolean inbound, FluidStack fluid) {
        if (!(conn instanceof PipeConnectionAccessor accessor)) return false;
        Optional<PipeConnection.Flow> current = accessor.pipesnphysics$getFlow();
        if (current.isPresent()) {
            PipeConnection.Flow flow = current.get();
            if (flow.inbound == inbound && flow.complete
                    && FluidStack.isSameFluidSameComponents(flow.fluid, fluid)) {
                return false;
            }
            flow.inbound = inbound;
            flow.fluid = fluid.copy();
            flow.progress.startWithValue(1);
            flow.complete = true;
            return true;
        }
        PipeConnection.Flow flow = conn.new Flow(inbound, fluid.copy());
        flow.progress.startWithValue(1);
        flow.complete = true;
        accessor.pipesnphysics$setFlow(Optional.of(flow));
        return true;
    }

    /** A missing or empty connection is NOT complete — the front must not skip past it. */
    private static boolean isComplete(PipeConnection conn) {
        if (!(conn instanceof PipeConnectionAccessor accessor)) return false;
        Optional<PipeConnection.Flow> flow = accessor.pipesnphysics$getFlow();
        return flow.isPresent() && flow.get().complete;
    }

    private static void clearCell(Level level, BlockPos cell) {
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
        if (pipe == null) return;
        boolean changed = false;
        for (Direction dir : Direction.values()) {
            changed |= clearFlow(pipe.getConnection(dir));
        }
        if (changed) pipe.blockEntity.notifyUpdate();
    }

    private static boolean clearFlow(PipeConnection conn) {
        if (conn instanceof PipeConnectionAccessor accessor
                && accessor.pipesnphysics$getFlow().isPresent()) {
            accessor.pipesnphysics$setFlow(Optional.empty());
            conn.wipePressure(); // reset the fill-speed knob so a later re-seed is clean
            return true;
        }
        return false;
    }

    /**
     * Fill-speed knob (a Create "pressure") scaling with the FLOW RATE, so the front crawls fast
     * under a brisk pump and slowly under a trickle — the visible fill tracks the actual flow.
     * (Replaced the old viscosity scaling, which made a fast pump down a long pipe crawl as slowly
     * as a trickle and read as bursty delivery.)
     */
    private static float flowPressure(int mbPerTick) {
        return (float) Math.clamp(
                Math.abs(mbPerTick) * FILL_PRESSURE_PER_MBPT, MIN_FILL_PRESSURE, MAX_FILL_PRESSURE);
    }

    private static Direction direction(BlockPos from, BlockPos to) {
        return Direction.fromDelta(
                to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }
}
