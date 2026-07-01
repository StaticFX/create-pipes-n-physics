package de.devin.pipesnphysics.compat;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.Edge;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.Graph;
import de.devin.pipesnphysics.engine.Node;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;
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

    /**
     * In-pipe LEVEL render (experimental spike, {@code EXPERIMENTAL_PIPE_LEVEL_RENDER}). When on, a
     * wet cell's solved WATERLINE (and, when flowing, the FLOW DIRECTION) is stamped onto the pipe's
     * {@code FluidTransportBehaviour} as a dedicated, client-synced-but-not-saved {@link PipeLevelData}
     * int: {@code 0} = not rendered; else {@code (flowDir+1)·}{@link #DIR_STRIDE}{@code + frac·}{@link
     * #LEVEL_SCALE}{@code + 1}, where flowDir is the downstream {@code Direction.get3DDataValue} (0..5)
     * or -1 when resting. {@code GlassPipeVisualMixin}/the BER mixin skip a cell whose behaviour holds
     * level data (so Create draws nothing) and {@code client.PipeLevelRenderer} draws the partial fill
     * instead, scrolling the flowing texture along the encoded direction. (Earlier this rode the flow's
     * FluidStack amount, which risked stock Create reading it as a real volume — see {@link PipeLevelData}.)
     */
    private static final int LEVEL_SCALE = 1_000;
    private static final int DIR_STRIDE = 2_000; // > LEVEL_SCALE, so the fraction never overflows the direction band

    /** Create's {@code FluidTankRenderer} cap + puddle insets — used to match the pipe waterline to the tank's RENDERED surface. */
    private static final double TANK_CAP = 1 / 4d;
    private static final double TANK_PUDDLE = 1 / 16d;

    private CreatePipeRendering() {}

    /**
     * Map a node's hydraulic head to the surface Create actually RENDERS for a fluid tank there, so
     * the pipe waterline lines up with the tank's visible fluid. Create's {@code FluidTankRenderer}
     * insets the fluid by a top cap and a bottom puddle, so a tank's rendered surface is
     * {@code controllerBottom + (cap+puddle) + fill·(height − 2·cap − puddle)} — NOT the true head
     * ({@code controllerBottom + fill·height}); the two diverge most for a 1-block tank. Returns the
     * head unchanged for anything that is not a fluid tank (a pump, open end, basin, machine).
     */
    public static double displaySurface(Level level, BlockPos pos, double head) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return head;
        FluidTankBlockEntity controller = tank.getControllerBE();
        if (controller == null) return head;
        int height = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        double controllerBottom = SableCompat.getWorldY(level, controller.getBlockPos()) - 0.5;
        double fill = Math.clamp((head - controllerBottom) / height, 0.0, 1.0);
        return controllerBottom + (TANK_CAP + TANK_PUDDLE) + fill * (height - 2 * TANK_CAP - TANK_PUDDLE);
    }

    /**
     * Encode a 0..1 cell-fill fraction + flow direction as a {@link PipeLevelData} int (always &gt;= 1,
     * so 0 stays reserved for "not rendered"). {@code flowDir} is the downstream {@code
     * Direction.get3DDataValue} (0..5) for a flowing cell, or -1 for a resting cell (still fill).
     */
    public static int encodeLevel(double fraction, int flowDir) {
        int f = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * LEVEL_SCALE);
        return (flowDir + 1) * DIR_STRIDE + f + 1;
    }

    /** The 0..1 cell-fill fraction encoded in a {@link PipeLevelData} value. */
    public static float levelFraction(int data) {
        return Math.clamp(((data - 1) % DIR_STRIDE) / (float) LEVEL_SCALE, 0f, 1f);
    }

    /** The downstream {@code Direction.get3DDataValue} of a FLOWING cell, or -1 if resting. */
    public static int levelFlowDir(int data) {
        return (data - 1) / DIR_STRIDE - 1;
    }

    /**
     * Whether Create's own pipe renderers should SKIP drawing this cell because the level renderer owns
     * it (flag on + the pipe behaviour holds level data). Shared by both pipe-render mixins.
     */
    public static boolean hidesFromCreate(FluidTransportBehaviour pipe) {
        return pipe instanceof PipeLevelData data
                && data.pipesnphysics$getLevelData() != 0
                && PipesNPhysicsConfig.EXPERIMENTAL_PIPE_LEVEL_RENDER.get();
    }

    /**
     * Reflect one solve's per-edge fluid into Create's pipe Flow objects. Returns
     * true while an equalized hump is still receding — the caller must keep ticking
     * the (otherwise idle) network so the drain animation can finish instead of
     * freezing the instant flow stops.
     */
    public static boolean apply(Level level, Graph graph, Solution solution) {
        return apply(level, graph, solution, levelRenderEnabled());
    }

    /**
     * Whether the in-pipe LEVEL render spike is on. The toggle is a CLIENT config (a single global
     * file, trivially flippable), but the waterline is encoded here on the server side; in
     * singleplayer the integrated server shares the JVM with the client and reads it directly. Guarded
     * on a client being present so a dedicated server never touches the unloaded client spec — the
     * spike is singleplayer-only for now.
     */
    public static boolean levelRenderEnabled() {
        return FMLEnvironment.dist.isClient() && PipesNPhysicsConfig.EXPERIMENTAL_PIPE_LEVEL_RENDER.get();
    }

    /**
     * As {@link #apply(Level, Graph, Solution)} but with the in-pipe level-render flag passed
     * explicitly, so a GameTest can exercise the waterline encoding without mutating live config.
     */
    public static boolean apply(Level level, Graph graph, Solution solution, boolean levelRender) {
        Set<BlockPos> filled = new HashSet<>();
        // Standing fluid the travelling front hasn't reached yet: kept visible but rendered STILL (no
        // scroll) until the front arrives and it joins the flow. Only the level renderer reads it, so
        // it stays an immutable empty when the flag is off — the default, and always on a dedicated
        // server — and is only mutated on the levelRender-gated paths below.
        Set<BlockPos> standing = levelRender ? new HashSet<>() : Set.of();
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
                // When a run starts flowing, the travelling front charges from the upstream end — but
                // STANDING fluid already sits at the lower (downstream) end. Preserve those settled
                // cells so the sweep doesn't clear them ahead of the front (which despawns the fluid
                // and makes the run visibly re-crawl). Only reservoir-supported cells are kept, so a
                // genuinely empty run still fills as a clean front. Level-render only.
                if (levelRender) preserveStandingFluid(level, graph, edge, solution, filled, standing);
                continue;
            }

            // A run backed up against a blockage carries no flow THIS tick — it rounds to zero,
            // so there is no direction to chargeEdge — yet it is genuinely full of fluid pressed
            // against the stop. Preserve its already-charged cells so the head doesn't reset and
            // flow resumes with no re-crawl when the stop clears. Two shapes: a full sink / a
            // pump that cannot out-lift ({@code isBackedUp}: SINK_FULL / NO_HEAD), and a pump
            // dead-heading a SHUT VALVE ({@code heldEdges} — the run SPLIT at the valve, so this
            // feed segment ends AT the valve and its cells ARE the held column up to it).
            if (isBackedUp(solution, edge) || solution.heldEdges().contains(edge.index())) {
                filled.addAll(edge.pipes());
                continue;
            }

            FluidStack resting = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
            Double headA = solution.nodeHeads().get(edge.a());
            Double headB = solution.nodeHeads().get(edge.b());
            // A gas head is a pressure value, not an elevation, so the liquid tank-surface anchor and
            // the min-flatten below are meaningless for it (restEdge branches on gas itself). Skip
            // both for gas, matching stampWaterlines/preserveStandingFluid.
            boolean gas = !resting.isEmpty() && resting.getFluid().getFluidType().isLighterThanAir();
            // With the level renderer on, anchor a tank node's head to the surface Create RENDERS
            // (its cap/puddle inset) so the seeded resting cells reach the tank's VISIBLE fluid, not
            // the lower true head. Off → raw heads, so the binary render is byte-identical to stock.
            if (levelRender && !gas) {
                if (headA != null) headA = displaySurface(level, graph.node(edge.a()).pos(), headA);
                if (headB != null) headB = displaySurface(level, graph.node(edge.b()).pos(), headB);
            }
            // A closed-gate endpoint (a shut valve) is a wall with no head of its own. A SETTLING
            // run touching it takes the opposite endpoint's head so it settles to that reservoir —
            // but ONLY if the opposite end is a real reservoir (a HANDLER). An OPEN END is air / a
            // spill threshold, not a water surface, so a gate↔open-end segment has no supply and
            // must stay DRY (its mouth head would otherwise read as a full waterline). A pump-fed
            // feed segment is held above, not here.
            if (graph.node(edge.a()).isClosedGate() && graph.node(edge.b()).isHandler() && headB != null) {
                headA = headB;
            } else if (graph.node(edge.b()).isClosedGate() && graph.node(edge.a()).isHandler() && headA != null) {
                headB = headA;
            }
            // LEVEL render: a RESTING run is flat at the settled water level (the LOWER surface), so a
            // higher stranded/empty endpoint — whose head is just its floor — can't pull a phantom
            // waterline up the run toward it. (Interpolating between the two node heads invents water
            // up a riser to an empty tank.) Flowing runs keep their gradient (handled in chargeEdge).
            if (levelRender && !gas && headA != null && headB != null) {
                double waterline = Math.min(headA, headB);
                headA = waterline;
                headB = waterline;
            }
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

        // A shut valve is a NODE, so it sits in no edge and would render empty — a one-cell gap
        // between the held feed and the settled downstream. Fill it: the held column presses fluid
        // right up to the valve and the far side sits settled against it, so the valve cell is full.
        // Closing this gap is also what stops the downstream from "despawning" on reopen — otherwise
        // the merged run's front stalls at the empty valve cell and the sweep wipes the settled
        // downstream before the front reaches it.
        for (Node node : graph.nodes()) {
            if (node.isClosedGate()) fillGateCell(level, graph, node, solution, filled);
        }

        // LEVEL render (spike): stamp every wet cell's solved waterline onto its pipe behaviour (see
        // PipeLevelData), so the custom renderer can draw a partial fill. One pass over all wet cells —
        // resting, FLOWING, held, backed-up alike — so the waterline shows whether or not fluid moves.
        Set<BlockPos> levelCells = levelRender ? new HashSet<>() : Set.of();
        if (levelRender) stampWaterlines(level, graph, solution, standing, levelCells);

        for (BlockPos cell : graph.coverage()) {
            // Sweep each covered cell in ONE pipe lookup: drop a flow orphaned by an edit (any cell we
            // did not fill), AND clear a stale render field on a cell we did not stamp this tick
            // (drained, gas, or edited) so no stale waterline lingers and Create resumes drawing it.
            boolean dropFlow = !filled.contains(cell);
            boolean resetLevel = levelRender && !levelCells.contains(cell);
            if (dropFlow || resetLevel) sweepCell(level, cell, dropFlow, resetLevel);
        }
        return draining;
    }

    /** Drop a covered cell's orphaned flow and/or stale {@link PipeLevelData}, resolving the pipe once. */
    private static void sweepCell(Level level, BlockPos cell, boolean dropFlow, boolean resetLevel) {
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
        if (pipe == null) return;
        boolean changed = false;
        if (dropFlow) {
            for (Direction dir : Direction.values()) changed |= clearFlow(pipe.getConnection(dir));
        }
        if (resetLevel && pipe instanceof PipeLevelData data && data.pipesnphysics$getLevelData() != 0) {
            data.pipesnphysics$setLevelData(0);
            changed = true;
        }
        if (changed) pipe.blockEntity.notifyUpdate();
    }

    /**
     * Keep the SETTLED standing fluid of a just-started flow from being swept before the travelling
     * front reaches it. Preserves only cells submerged below the run's lower (min) surface — i.e.
     * reservoir-supported standing fluid — so a genuinely empty run still fills as a clean front and
     * transient leftover above the surface is still swept. Mirrors the apply/stampWaterlines surface
     * mapping (tank-render anchor + flat min). Gas is skipped (its waterline semantics differ).
     */
    private static void preserveStandingFluid(Level level, Graph graph, Edge edge, Solution solution,
                                              Set<BlockPos> filled, Set<BlockPos> standing) {
        Double rawA = solution.nodeHeads().get(edge.a());
        Double rawB = solution.nodeHeads().get(edge.b());
        if (rawA == null || rawB == null) return;
        FluidStack rep = solution.edgeFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (!rep.isEmpty() && rep.getFluid().getFluidType().isLighterThanAir()) return;
        double waterline = Math.min(displaySurface(level, graph.node(edge.a()).pos(), rawA),
                displaySurface(level, graph.node(edge.b()).pos(), rawB));
        List<BlockPos> pipes = edge.pipes();
        for (int i = 0; i < pipes.size(); i++) {
            BlockPos cell = pipes.get(i);
            // The front already reached this cell (chargeEdge added it) → it's flowing, leave it.
            if (filled.contains(cell)) continue;
            if (!restingCellSubmerged(level, graph, edge, i, waterline, waterline, false)) continue;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe != null && hasFluid(pipe)) {
                filled.add(cell);
                standing.add(cell); // keep it, but render it STILL until the front arrives
            }
        }
    }

    /**
     * Stamp each wet pipe cell's solved waterline onto its behaviour ({@link PipeLevelData}, encoded by
     * {@link #encodeLevel}), so {@code client.PipeLevelRenderer} draws the partial fill and the
     * pipe-render mixins hide Create's binary fill for it. Runs for every edge with display heads, on
     * whatever cells currently carry fluid — flowing cells included — so the level shows whether or not
     * fluid moves. Every stamped cell is added to {@code levelCells} so the caller can reset the field
     * on cells it did NOT stamp. Gas has no waterline (it fills by the mirror test), so a gas edge is
     * left to Create.
     */
    private static void stampWaterlines(Level level, Graph graph, Solution solution,
                                        Set<BlockPos> standing, Set<BlockPos> levelCells) {
        for (Edge edge : graph.edges()) {
            // A SOURCE_DRY stall is phantom flow apply skips entirely (its cells are swept, not
            // charged), so it must not be stamped either — the classify below would otherwise read it
            // as backed-up and stamp a full waterline on a run that renders empty by design.
            if (solution.stalledEdges().contains(edge.index())
                    && solution.edgeReasons().get(edge.index()) == Solution.Reason.SOURCE_DRY) {
                continue;
            }
            Double rawA = solution.nodeHeads().get(edge.a());
            Double rawB = solution.nodeHeads().get(edge.b());
            // A CLOSED_GATE node carries no nodeHeads entry (null); take the opposite reservoir's head
            // BEFORE the null bail so a shut-valve↔tank resting edge — which apply/restEdge DO fill —
            // is stamped, not left unmarked (which would leave that one cell rendered by Create).
            if (graph.node(edge.a()).isClosedGate() && graph.node(edge.b()).isHandler() && rawB != null) rawA = rawB;
            else if (graph.node(edge.b()).isClosedGate() && graph.node(edge.a()).isHandler() && rawA != null) rawB = rawA;
            if (rawA == null || rawB == null) continue;
            // Anchor tank nodes to Create's RENDERED surface (cap/puddle inset), matching apply's
            // resting seeding, so the encoded waterline meets the tank's visible fluid.
            double headA = displaySurface(level, graph.node(edge.a()).pos(), rawA);
            double headB = displaySurface(level, graph.node(edge.b()).pos(), rawB);

            // Match the head flattening restEdge / apply use: a shut gate or an open end has no
            // surface of its own, so the cell reads the opposite reservoir's head.
            if (graph.node(edge.a()).isClosedGate() && graph.node(edge.b()).isHandler()) headA = headB;
            else if (graph.node(edge.b()).isClosedGate() && graph.node(edge.a()).isHandler()) headB = headA;
            // The idle min uses the DISPLAY surfaces BEFORE the open-end flatten, matching apply (which
            // mins the display heads first, then lets restEdge handle the open end): a run spilled to a
            // low open mouth then rests at the MOUTH, not pulled up to the reservoir surface. The
            // open-end flatten below feeds only the MOVING/backed-up gradient.
            double restA = headA;
            double restB = headB;
            boolean aOpen = graph.node(edge.a()).isOpenEnd();
            boolean bOpen = graph.node(edge.b()).isOpenEnd();
            if (aOpen && !bOpen) headA = headB;
            else if (bOpen && !aOpen) headB = headA;

            FluidStack rep = solution.edgeFluids().getOrDefault(edge.index(),
                    solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY));
            if (!rep.isEmpty() && rep.getFluid().getFluidType().isLighterThanAir()) continue;
            int index = edge.index();
            EdgeFlow.Direction dir = solution.edgeFlows().get(index).direction();
            boolean flowFromA = dir == EdgeFlow.Direction.A_TO_B;
            // An edge MOVES fluid (gets the scrolling flow marker + head gradient) only if it actually
            // transfers — a pressurized-but-blocked run (stalled SINK_FULL, blocked valve/crest, a
            // pump too weak / dead-heading a gate) carries a solved DIRECTION but no real flow, so it
            // must render at REST (flat min waterline, no scroll). Otherwise a backed-up run scrolls
            // with "no flow but the fluid is moving".
            // Three no-/with-flow states render differently:
            //   BACKED-UP — pressurized but blocked (stalled SINK_FULL, a pump too weak / dead-heading
            //               a gate): the fluid is trapped FULL where it advanced, so render it full
            //               with NO scroll (it carries a direction but moves nothing).
            //   MOVING    — genuinely flowing: keep the head gradient + scrolling flow marker.
            //   IDLE      — settled (incl. a shut valve / broken crest, which drain to a level): flat
            //               at the LOWER surface, so a higher stranded/empty endpoint can't pull a
            //               phantom waterline up the run (matches apply). SOURCE_DRY was skipped above.
            boolean backedUp = solution.stalledEdges().contains(index)
                    || solution.noHeadEdges().contains(index)
                    || solution.heldEdges().contains(index);
            boolean moving = dir != EdgeFlow.Direction.NONE && !backedUp;
            boolean idle = !moving && !backedUp;
            if (idle) {
                double waterline = Math.min(restA, restB);
                headA = waterline;
                headB = waterline;
            }

            List<BlockPos> pipes = edge.pipes();
            for (int i = 0; i < pipes.size(); i++) {
                BlockPos cell = pipes.get(i);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
                if (pipe == null) continue;
                double frac = (i + 1.0) / (edge.length() + 1);
                // The downstream Direction the fluid moves toward (for the scrolling texture), or -1
                // when the run is at rest / backed up. pipes() is ordered a→b, so downstream is the
                // next cell toward b when flow runs A→B, else toward a.
                // STANDING fluid the front hasn't reached yet renders STILL (no scroll) until the
                // front arrives and the cell leaves `standing`, even on a moving edge.
                int flowDir = -1;
                if (moving && !standing.contains(cell)) {
                    BlockPos downstream = flowFromA
                            ? (i < pipes.size() - 1 ? pipes.get(i + 1) : graph.node(edge.b()).pos())
                            : (i > 0 ? pipes.get(i - 1) : graph.node(edge.a()).pos());
                    Direction d = direction(cell, downstream);
                    if (d != null) flowDir = d.get3DDataValue();
                }
                // BACKED-UP cells are full where the fluid reached. IDLE settles to the interpolated
                // (flat-min) surface and reads DRY above it. A MOVING cell whose interpolated surface
                // sits BELOW it is a pressurized/climbing pipe (pump riser, primed siphon crest) that
                // is carrying flow, so it is FULL — not invisible; a moving cell with a free surface
                // inside it still shows a partial waterline.
                double interp = headA + (headB - headA) * frac - (SableCompat.getWorldY(level, cell) - 0.5);
                double cellFrac = backedUp ? 1.0
                        : moving && interp <= 0 ? 1.0
                        : interp;
                // Only a cell whose waterline reaches it (cellFrac > 0) is stamped. A cell ABOVE the
                // waterline (cellFrac <= 0 — only IDLE runs reach here; backed-up/climbing cells floor
                // to 1.0) is left UNSTAMPED so Create keeps drawing it: that is the stranded fluid of a
                // receding hump, which Create drains cell-by-cell (drainDeadEdge). Stamping it hides
                // Create yet the renderer draws nothing below its own cell, so the hump would blank
                // instantly instead of receding. Its Flow carries the fluid type; the field the level.
                if (cellFrac > 0 && hasFluid(pipe) && pipe instanceof PipeLevelData holder) {
                    levelCells.add(cell);
                    int data = encodeLevel(cellFrac, flowDir);
                    if (holder.pipesnphysics$getLevelData() != data) {
                        holder.pipesnphysics$setLevelData(data);
                        pipe.blockEntity.notifyUpdate();
                    }
                }
            }
        }
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

        // Flatten an open-end side to the reservoir surface for the fill ORIENTATION below
        // (the per-cell waterline does the same inside restingCellSubmerged). An open end has
        // no surface of its own — its node head is pinned at the mouth — so a resting run reads
        // the connected reservoir.
        if (!gas) {
            boolean aOpen = graph.node(edge.a()).isOpenEnd();
            boolean bOpen = graph.node(edge.b()).isOpenEnd();
            if (aOpen && !bOpen) headA = headB;
            else if (bOpen && !aOpen) headB = headA;
        }

        // A pump endpoint orients a RESTING run by its push/pull side even when the heads tie.
        Boolean pumpRest = pumpRestOrientation(graph, edge);

        for (int i = 0; i < pipes.size(); i++) {
            BlockPos cell = pipes.get(i);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe == null) continue;

            if (!restingCellSubmerged(level, graph, edge, i, headA, headB, gas)) {
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
            // On a TIE (heads settled equal — e.g. a suction line once the pump's draw-down
            // vanishes), the head sign no longer marks a real direction, so KEEP whatever the
            // cell already shows rather than flipping it (which scrolls the fluid the wrong way).
            PipeConnection cA = pipe.getConnection(towardA);
            PipeConnection cB = pipe.getConnection(towardB);
            boolean aInbound;
            if (Math.abs(headA - headB) > SUBMERSION_EPS) {
                aInbound = headA >= headB;
            } else if (pumpRest != null) {
                // A pump endpoint fixes a tied run by its push/pull side: a pump pulling from a tank
                // shows the column leaving the TANK toward the pump, never flowing into it. This is
                // order-independent, unlike the headA>=headB fallback that keyed off graph node order.
                aInbound = pumpRest;
            } else {
                // Two settled reservoirs: no real direction, so keep what the cell shows (don't flip).
                aInbound = existingInbound(cA, headA >= headB);
            }
            boolean changed = seedComplete(cA, aInbound, fluid);
            changed |= seedComplete(cB, !aInbound, fluid);
            if (changed) pipe.blockEntity.notifyUpdate();
            filled.add(cell);
        }

        drainColumn(level, stranded, filled, gameTime, edgeIndex);
        return !stranded.isEmpty();
    }

    /**
     * Whether a RESTING (non-flowing) edge holds fluid at cell {@code i}: a liquid cell is wet
     * once it sits below the connected fluid surface; a gas cell once it sits above the gas's
     * lower boundary (the mirror test). An open end is a VENT pinned at its mouth (a spill/intake
     * threshold), NOT a surface, so for a resting run an open-end side takes the OPPOSITE
     * endpoint's head — a resting run never spills, so the reservoir surface is the real level.
     *
     * Shared by the renderer ({@link #restEdge}) and the goggle probe ({@code PipeProbe}) so a dry
     * riser cell above the waterline never renders fluid NOR reports "settled, levels balanced".
     */
    public static boolean restingCellSubmerged(Level level, Graph graph, Edge edge, int i,
                                               double headA, double headB, boolean gas) {
        BlockPos cell = edge.pipes().get(i);
        double frac = (i + 1.0) / (edge.length() + 1);
        if (gas) {
            // A gas pools at the TOP and won't sink, so a cell holds it only if it is ABOVE the
            // gas's lower boundary. Per reservoir column that boundary is `height − gasHead`
            // (gasHead = fillHeight − baseY); a pump/open end holds no gas, so it imposes no floor.
            Double floorA = graph.node(edge.a()).isHandler()
                    ? columnHeight(level, graph.node(edge.a()).pos()) - headA : null;
            Double floorB = graph.node(edge.b()).isHandler()
                    ? columnHeight(level, graph.node(edge.b()).pos()) - headB : null;
            if (floorA == null && floorB == null) return true; // conduit-only run: fill all
            double floor = floorA != null && floorB != null
                    ? floorA + (floorB - floorA) * frac
                    : floorA != null ? floorA : floorB;
            return SableCompat.getWorldY(level, cell) + 0.5 + SUBMERSION_EPS >= floor;
        }
        boolean aOpen = graph.node(edge.a()).isOpenEnd();
        boolean bOpen = graph.node(edge.b()).isOpenEnd();
        if (aOpen && !bOpen) headA = headB;
        else if (bOpen && !aOpen) headB = headA;
        double headHere = headA + (headB - headA) * frac;
        // Between two reservoirs (communicating vessels) a cell fills as soon as the waterline clears
        // its BOTTOM face, so two tanks settling with the surface inside the connecting cell keep it
        // full (the regression `flatEqualizedPipeKeepsFluid` guards). But a run DEAD-ENDING at a
        // non-reservoir — a pump capped by a solid block, a capped pipe stub — has water tapering in
        // from one side only; there the waterline must reach the pipe's CENTRE to fill it, so a
        // near-empty supply does not paint a full pipe (and a dry pipe shows no false "Reach limit").
        boolean deadEnd = graph.node(edge.a()).isPump() || graph.node(edge.a()).isJunction()
                || graph.node(edge.b()).isPump() || graph.node(edge.b()).isJunction();
        double cellWorldY = SableCompat.getWorldY(level, cell);
        double threshold = deadEnd ? cellWorldY : cellWorldY - 0.5;
        return headHere + SUBMERSION_EPS >= threshold;
    }

    /**
     * Render a shut-valve (CLOSED_GATE) cell full of the fluid held against it. The gate is a node
     * in no edge, so nothing else fills it; left empty it is a one-cell gap that strands the settled
     * downstream when the valve reopens (the merged run's front stalls there and the sweep wipes the
     * downstream). Seeded from an incident run's resting/held fluid; a dry shut valve (no fluid on
     * either side) is left empty.
     */
    private static void fillGateCell(Level level, Graph graph, Node gate, Solution solution,
                                     Set<BlockPos> filled) {
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, gate.pos());
        if (pipe == null) return;
        boolean changed = false;
        boolean anyWet = false;
        for (Edge edge : graph.edgesOf(gate.index())) {
            // Fill the gate's connection toward this run ONLY if the run actually holds fluid at
            // the valve: a held feed, or a settled neighbour cell the edge pass kept (in `filled`).
            // A dry side — a shut valve facing an open end / an empty run — stays empty, so the
            // valve never paints phantom water on a sourceless side.
            BlockPos adj = adjacentCell(graph, edge, gate.index());
            boolean wet = solution.heldEdges().contains(edge.index()) || filled.contains(adj);
            if (!wet) continue;
            FluidStack fluid = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
            if (fluid.isEmpty()) continue;
            Direction dir = direction(gate.pos(), adj);
            if (dir == null) continue;
            changed |= seedComplete(pipe.getConnection(dir), true, fluid);
            anyWet = true;
        }
        if (changed) pipe.blockEntity.notifyUpdate();
        if (anyWet) filled.add(gate.pos());
    }

    /** The cell on {@code edge} nearest {@code nodeIndex} (its first/last pipe, or the far node if no pipes). */
    private static BlockPos adjacentCell(Graph graph, Edge edge, int nodeIndex) {
        List<BlockPos> pipes = edge.pipes();
        if (pipes.isEmpty()) return graph.node(edge.other(nodeIndex)).pos();
        return edge.a() == nodeIndex ? pipes.get(0) : pipes.get(pipes.size() - 1);
    }

    /**
     * The resting fill orientation a PUMP endpoint forces on a tied run, or null if neither end is a
     * pump. Fluid leaves a pump's PUSH side and is drawn into its PULL side, so even with equal heads
     * the resting column reads the way live flow would — a pump pulling from a tank shows the fluid
     * leaving the TANK toward the pump, not flowing into the tank. Returns whether the A side is the
     * inbound (source) rim.
     */
    private static Boolean pumpRestOrientation(Graph graph, Edge edge) {
        Boolean a = pumpSideInbound(graph, edge, edge.a(), true);
        return a != null ? a : pumpSideInbound(graph, edge, edge.b(), false);
    }

    /**
     * Whether the A side is inbound given that {@code nodeIndex} (if a pump) sits on this edge. A
     * pump's PUSH side is the source rim (fluid leaves the pump → the pump rim is inbound); its PULL
     * side is the sink rim (fluid is drawn toward the pump → the pump rim is outbound). Returns null
     * when the node is not a pump (or its facing is unresolved).
     */
    private static Boolean pumpSideInbound(Graph graph, Edge edge, int nodeIndex, boolean nodeIsA) {
        Node node = graph.node(nodeIndex);
        if (!node.isPump() || node.pumpFacing() == null) return null;
        Direction towardEdge = direction(node.pos(), adjacentCell(graph, edge, nodeIndex));
        if (towardEdge == null) return null;
        boolean pumpRimInbound = towardEdge == node.pumpFacing(); // push side: fluid leaves the pump
        return nodeIsA == pumpRimInbound;
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

    /** Block height of a reservoir column at {@code pos} (a multiblock tank's controller height), 1 otherwise. */
    private static int columnHeight(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank) {
            FluidTankBlockEntity controller = tank.getControllerBE();
            if (controller != null) return ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        }
        return 1;
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

    /** The inbound flag a connection's current flow shows, or {@code fallback} if it has none. */
    private static boolean existingInbound(PipeConnection conn, boolean fallback) {
        if (conn instanceof PipeConnectionAccessor accessor) {
            Optional<PipeConnection.Flow> flow = accessor.pipesnphysics$getFlow();
            if (flow.isPresent()) return flow.get().inbound;
        }
        return fallback;
    }

    /** A missing or empty connection is NOT complete — the front must not skip past it. */
    private static boolean isComplete(PipeConnection conn) {
        if (!(conn instanceof PipeConnectionAccessor accessor)) return false;
        Optional<PipeConnection.Flow> flow = accessor.pipesnphysics$getFlow();
        return flow.isPresent() && flow.get().complete;
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
