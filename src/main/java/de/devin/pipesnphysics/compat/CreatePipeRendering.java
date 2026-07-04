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
import de.devin.pipesnphysics.engine.PipeGeometry;
import de.devin.pipesnphysics.engine.PipeProbe;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bridges the engine's per-edge fluid into Create's windowed-pipe rendering.
 *
 * Create's transport tick is cancelled ({@code GravityFlowMixin}), so the engine
 * owns the {@code PipeConnection.Flow} objects Create draws. After each solve we
 * set flows on carrying cells and clear the rest.
 *
 * A FLOWING edge fills as a travelling front, seeded along the flow direction up to the front;
 * within each cell the inbound half fills first, then the outbound. Fill speed SCALES WITH THE
 * FLOW RATE ({@link #flowPressure}): a brisk pump fills the run fast, a trickle fills it slowly,
 * so the visible fill tracks how hard fluid is actually moving. The front has two drivers by
 * renderer: the stock (binary) path animates it through Create's Flow progress
 * ({@link #chargeEdge} + {@code tickFlowProgress}); the LEVEL-render path OWNS it
 * ({@link #advanceFront}) — the fill state lives in the pipes' own synced {@link PipeLevelData}
 * front field, integrated here from the solved rate, so it no longer depends on Create's transport
 * cosmetics. Delivery stays IN STEP on both — {@link #deliveryReady} releases the endpoint
 * transfer only once the active front reaches the sink, so visual and actual delivery line up.
 * The front is stateless (read back each tick from the cells' own fill state), so it survives
 * reloads and edits; a flow restart no longer re-crawls a long run from scratch because the
 * {@code isBackedUp}/drainDeadEdge guards keep the charged cells through a transient (the
 * long-pipe "delivery in bursts" fix).
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
     * How far the render pressure (the scroll-speed knob) must move before a steady flow re-syncs it.
     * Keeps a settled flow from sending an update every tick while still un-sticking a stale value.
     */
    private static final float PRESSURE_REFRESH_EPS = 8f;

    /**
     * In-pipe LEVEL render ({@code PIPE_LEVEL_RENDER}). When on, a
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

    /**
     * The engine-owned travelling-front state (the {@link PipeLevelData} FRONT field, encoded by
     * {@link #encodeFront}): how far fluid has advanced through a cell along the flow axis, the
     * advance direction, and the advance rate. {@code 0} = untracked. The rate rides along so the
     * client can extrapolate between server stamps (smooth sub-tick fill) and scroll the texture at
     * the real fluid speed.
     */
    private static final int FRONT_SCALE = 1_000;
    private static final int FRONT_FRAC_STRIDE = 1_024; // > FRONT_SCALE, power of two
    private static final int FRONT_RATE_SCALE = 256;    // rate units per cell/tick

    /**
     * Rate jitter (in 1/{@link #FRONT_RATE_SCALE} cells/tick units) a full cell's stamp ignores:
     * a steadily-flowing cell's rate wobbles with the solved mB/t, and without a deadband every
     * wobble would re-sync the cell each tick.
     */
    private static final int FRONT_RATE_EPS = 4;

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
     * Encode one cell's owned-front state as a {@link PipeLevelData} front int (always &gt;= 1, so 0
     * stays reserved for "untracked"): {@code fraction} is how far fluid has advanced through the
     * cell along the flow (0..1), {@code flowDir} the downstream {@code Direction.get3DDataValue}
     * it advances toward (or -1 for fluid at rest), {@code cellsPerTick} the advance rate.
     */
    public static int encodeFront(double fraction, int flowDir, double cellsPerTick) {
        int f = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * FRONT_SCALE);
        int r = Math.clamp(Math.round(cellsPerTick * FRONT_RATE_SCALE), 0, 255);
        return (r * 8 + flowDir + 1) * FRONT_FRAC_STRIDE + f + 1;
    }

    /** The 0..1 advanced fraction encoded in a {@link PipeLevelData} front value. */
    public static float frontFraction(int data) {
        return Math.clamp(((data - 1) % FRONT_FRAC_STRIDE) / (float) FRONT_SCALE, 0f, 1f);
    }

    /** The downstream {@code Direction.get3DDataValue} the front advances toward, or -1 at rest. */
    public static int frontFlowDir(int data) {
        return (data - 1) / FRONT_FRAC_STRIDE % 8 - 1;
    }

    /** The front's advance rate in cells/tick — drives the client's extrapolation and scroll speed. */
    public static float frontRate(int data) {
        return (data - 1) / (FRONT_FRAC_STRIDE * 8) / (float) FRONT_RATE_SCALE;
    }

    /** One cell's owned-front state this tick: advanced fraction, downstream dir (-1 still), cells/tick. */
    private record CellFront(double fraction, int flowDir, double rate) {}

    private static final CellFront FULL_STILL = new CellFront(1, -1, 0);

    /**
     * How far the owned front advances per tick (in cells) at a given fill pressure. Mirrors
     * Create's own per-connection fill speed ({@code PipeConnection.tickFlowProgress}:
     * {@code 1/32 + p/128 · 31/32} per half-cell connection per tick, two halves to a cell), so the
     * owned front fills at exactly the pace the old Flow-progress animation did.
     */
    private static double cellsPerTick(float pressure) {
        return (1 / 32d + Math.clamp(pressure / 128d, 0d, 1d) * 31 / 32d) / 2d;
    }

    /**
     * Whether Create's own pipe renderers should SKIP drawing this cell because the level renderer owns
     * it (flag on + the pipe behaviour holds level data). Shared by both pipe-render mixins.
     */
    public static boolean hidesFromCreate(FluidTransportBehaviour pipe) {
        return pipe instanceof PipeLevelData data
                && data.pipesnphysics$getLevelData() != 0
                && PipesNPhysicsConfig.PIPE_LEVEL_RENDER.get();
    }

    /**
     * Whether the engine owns this cell's fill animation — the level render is on and the cell
     * carries level/front data. {@code GravityFlowMixin} skips Create's {@code tickFlowProgress}
     * for such cells: the owned front is integrated by {@link #advanceFront} from the solved flow
     * rate, and letting Create advance its Flow progress underneath would run a second,
     * disagreeing integrator. (The cell's Create flows are seeded already-complete or held
     * charging as pure bookkeeping, hidden from Create's draw.)
     */
    public static boolean ownsAnimation(FluidTransportBehaviour pipe) {
        return pipe instanceof PipeLevelData data
                && (data.pipesnphysics$getLevelData() != 0 || data.pipesnphysics$getFrontData() != 0)
                && levelRenderEnabled();
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
     * Whether the in-pipe LEVEL render is on. The toggle is a CLIENT config (a single global
     * file, trivially flippable), but the waterline is encoded here on the server side; in
     * singleplayer the integrated server shares the JVM with the client and reads it directly. Guarded
     * on a client being present so a dedicated server never touches the unloaded client spec — the
     * level render is singleplayer-only for now.
     */
    public static boolean levelRenderEnabled() {
        return FMLEnvironment.dist.isClient() && PipesNPhysicsConfig.PIPE_LEVEL_RENDER.get();
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
        // The engine-owned front state advanceFront integrated this tick, per cell — stamped onto
        // the pipes by stampWaterlines. Level-render only, like `standing`.
        Map<BlockPos, CellFront> fronts = levelRender ? new HashMap<>() : Map.of();
        // Level-path flowing edges are DEFERRED and advanced together (advanceChained) so their
        // fronts chain into ONE continuous travel across shared junction/pump nodes, instead of
        // every edge starting its own crawl from its own upstream end at once.
        List<LevelFlow> chained = levelRender ? new ArrayList<>() : List.of();
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
                // Fill/scroll speed tracks the ACTUAL fluid moved across the edge (the goggle rate),
                // NOT the solver's hydraulic flow: a full pass-through tank throttles the throughput
                // below the solved edge flow, so the inflow edge's fast solved rate must not scroll
                // faster than what actually crosses it (the visual would outrun the delivered fluid).
                int movedPerTick = PipeProbe.actualEdgeFlow(graph, solution, edge);
                boolean fromA = flow.direction() == EdgeFlow.Direction.A_TO_B;
                if (levelRender && !flowing.getFluid().getFluidType().isLighterThanAir()) {
                    // The level path OWNS the travelling front: integrate it from the flow rate
                    // into the synced front field (Create's Flow progress is not ticked on owned
                    // cells). Deferred so all flowing edges advance together, chained across
                    // shared nodes (advanceChained, below the loop). Gas stays with Create
                    // end-to-end — the level renderer never draws it.
                    chained.add(new LevelFlow(edge, flowing, fromA, movedPerTick));
                } else {
                    chargeEdge(level, graph, edge, flowing, fromA, movedPerTick, filled);
                    preserveStandingFluid(level, graph, edge, solution, filled, standing, levelRender);
                }
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

        // Advance every level-path flowing edge, CHAINED across shared nodes so the fill reads as
        // one continuous travel. Then keep each run's settled standing fluid: when a run starts
        // flowing, the travelling front charges from the upstream end — but STANDING fluid already
        // sits at the lower (downstream) end. Preserve those settled cells so the sweep doesn't
        // clear them ahead of the front (which despawns the fluid and makes the run visibly
        // re-crawl, re-gating delivery). Only reservoir-supported cells are kept, so a genuinely
        // empty run still fills as a clean front. (The binary/stock renderer runs the same
        // preserve inline in the loop above.)
        advanceChained(level, graph, chained, filled, fronts);
        for (LevelFlow lf : chained) {
            preserveStandingFluid(level, graph, lf.edge(), solution, filled, standing, levelRender);
        }

        // A shut valve is a NODE, so it sits in no edge and would render empty — a one-cell gap
        // between the held feed and the settled downstream. Fill it: the held column presses fluid
        // right up to the valve and the far side sits settled against it, so the valve cell is full.
        // Closing this gap is also what stops the downstream from "despawning" on reopen — otherwise
        // the merged run's front stalls at the empty valve cell and the sweep wipes the settled
        // downstream before the front reaches it.
        for (Node node : graph.nodes()) {
            if (node.isClosedGate()) fillGateCell(level, graph, node, solution, filled);
            else if (node.isJunction()) fillDeadEndCell(level, graph, node, solution, filled);
        }

        // LEVEL render (spike): stamp every wet cell's solved waterline onto its pipe behaviour (see
        // PipeLevelData), so the custom renderer can draw a partial fill. One pass over all wet cells —
        // resting, FLOWING, held, backed-up alike — so the waterline shows whether or not fluid moves.
        Set<BlockPos> levelCells = levelRender ? new HashSet<>() : Set.of();
        if (levelRender) stampWaterlines(level, graph, solution, standing, filled, fronts, levelCells);

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
        if (resetLevel && pipe instanceof PipeLevelData data) {
            if (data.pipesnphysics$getLevelData() != 0) {
                data.pipesnphysics$setLevelData(0);
                changed = true;
            }
            if (data.pipesnphysics$getFrontData() != 0) {
                data.pipesnphysics$setFrontData(0);
                changed = true;
            }
            if (!data.pipesnphysics$getRenderFluid().isEmpty()) {
                data.pipesnphysics$setRenderFluid(FluidStack.EMPTY);
                changed = true;
            }
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
                                              Set<BlockPos> filled, Set<BlockPos> standing,
                                              boolean trackStanding) {
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
                filled.add(cell); // keep it from the sweep — both renderers
                if (trackStanding) standing.add(cell); // render it STILL until the front arrives (level render)
            }
        }
    }

    /**
     * Stamp each wet pipe cell's render state onto its behaviour ({@link PipeLevelData}): the solved
     * waterline ({@link #encodeLevel}), the engine-owned front ({@link #encodeFront}), and the fluid
     * to draw — so {@code client.PipeLevelRenderer} renders entirely from the synced fields and the
     * pipe-render mixins hide Create's binary fill. The single writer of all three fields. Runs for
     * every edge with display heads, on whatever cells currently carry fluid — flowing cells
     * included — so the level shows whether or not fluid moves. Every stamped cell is added to
     * {@code levelCells} so the caller can reset the fields on cells it did NOT stamp. Gas has no
     * waterline (it fills by the mirror test), so a gas edge is left to Create.
     */
    private static void stampWaterlines(Level level, Graph graph, Solution solution,
                                        Set<BlockPos> standing, Set<BlockPos> filled,
                                        Map<BlockPos, CellFront> fronts, Set<BlockPos> levelCells) {
        // Whether a FLOWING run is drawn at its head waterline (rises with the head) or full. Read once
        // here, not per cell; dist-guarded like levelRenderEnabled() because the CLIENT spec is absent on
        // a dedicated server (a GameTest forces stampWaterlines through the explicit-flag apply overload).
        boolean partialFlow = FMLEnvironment.dist.isClient()
                && PipesNPhysicsConfig.PIPE_FLOW_PARTIAL_FILL.get();
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
            if (rawA == null || rawB == null) {
                // No solved waterline — but this edge may still be HELD full by drainDeadEdge through a
                // brief transient (a drained tank-to-tank run, or a pump source that dipped dry): its
                // cells are in `filled` and its Create flows are preserved, it just has no head to seed
                // a waterline. Keep the LEVEL renderer OWNING those held cells (stamp them full & still
                // from the preserved flow) instead of skipping the edge. Skipping cleared the fields, so
                // `ownsAnimation` flipped false — Create's binary fill popped back in and the client
                // began a 6-tick fade; on an oscillating stop (a fall fed in bursts / a top tank hovering
                // near-empty) both cells blip fade→re-stamp in unison, reading as "both pipes recrawl at
                // once and flicker". The binary path already keeps the run charged here (drySourcePump-
                // RunKeepsChargedPipe); this restores the same continuity on the level path.
                stampHeldEdge(level, edge, filled, levelCells);
                continue;
            }
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
            } else if (moving && partialFlow) {
                // Partial-fill: a MOVING run is drawn at its HEAD WATERLINE, so the fill rises as the
                // head rises (the "flowing fluid doesn't raise with the head" report). Flatten to ONE
                // level per run — the endpoint-head AVERAGE — so it tracks the head WITHOUT a per-cell
                // gradient stepping between adjacent windows through the narrow tube band (the "one
                // window half, next full" report). AVERAGE (not the idle min) so a rise at EITHER end
                // lifts it; a cell pressurised ABOVE this line (a pump riser / primed crest, interp <= 0
                // below) still fills FULL.
                double waterline = (headA + headB) / 2.0;
                headA = waterline;
                headB = waterline;
            }

            List<BlockPos> pipes = edge.pipes();
            for (int i = 0; i < pipes.size(); i++) {
                BlockPos cell = pipes.get(i);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
                if (!(pipe instanceof PipeLevelData holder)) continue;
                double frac = (i + 1.0) / (edge.length() + 1);

                // The owned front decides whether fluid has reached the cell at all (the stamp gate)
                // and how far through it the fill has advanced (the renderer's along-axis clip):
                //   MOVING    — advanceFront integrated it this tick; a cell the front hasn't reached
                //               stays unstamped (and is swept).
                //   STANDING  — settled fluid ahead of the front: full but still.
                //   BACKED-UP — trapped where it advanced: the hold COMPLETES its in-flight cell
                //               (fraction floored to 1). The run renders FULL while held anyway
                //               (flowDir -1, no clip), and the old Flow-progress front finished its
                //               charging halves during a stall (tickFlowProgress kept running), so
                //               resuming from full matches it — resuming from the frozen mid-fill
                //               fraction made the cell visibly recede from full before re-filling.
                //   IDLE      — restEdge seeded the submerged cells this tick (resting fills at
                //               once), so `filled` is the fluid-presence bookkeeping.
                CellFront front;
                if (moving && !standing.contains(cell)) {
                    front = fronts.get(cell);
                    if (front == null || front.fraction() <= 0) continue;
                } else if (moving) {
                    front = FULL_STILL;
                } else if (backedUp) {
                    int stored = holder.pipesnphysics$getFrontData();
                    if (stored != 0) front = new CellFront(1, frontFlowDir(stored), 0);
                    else if (hasFluid(pipe)) front = FULL_STILL;
                    else continue;
                } else {
                    if (!filled.contains(cell)) continue;
                    front = FULL_STILL;
                }

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
                    Direction d = PipeGeometry.between(cell, downstream);
                    if (d != null) flowDir = d.get3DDataValue();
                }
                // A BACKED-UP cell renders FULL: pressed against a stop, it is a full cross-section (the
                // head gradient is PRESSURE, not a free surface). A MOVING cell shows its head waterline
                // (interp; headA/headB were flattened to the run average above, so every same-Y cell
                // reads the same and it rises with the head), EXCEPT a cell pressurised ABOVE that line —
                // a pump riser / primed crest (interp <= 0) — which is FULL, not dry, and with partial
                // fill OFF a moving cell stays FULL as before. An IDLE run shows its settled (min)
                // waterline; a cell above it (interp <= 0) is left unstamped below and drawn dry.
                double interp = headA + (headB - headA) * frac - (SableCompat.getWorldY(level, cell) - 0.5);
                double cellFrac;
                if (backedUp) cellFrac = 1.0;
                else if (moving) cellFrac = !partialFlow || interp <= 0 ? 1.0 : interp;
                else cellFrac = interp;
                // Only a cell whose waterline reaches it (cellFrac > 0) is stamped. A cell ABOVE the
                // waterline (cellFrac <= 0 — only IDLE runs reach here; backed-up/moving cells floor
                // to 1.0) is left UNSTAMPED so Create keeps drawing it: that is the stranded fluid of a
                // receding hump, which Create drains cell-by-cell (drainDeadEdge). Stamping it hides
                // Create yet the renderer draws nothing below its own cell, so the hump would blank
                // instantly instead of receding.
                if (cellFrac <= 0) continue;
                // The fluid the renderer draws. A receding run can reach here with an empty rep (no
                // restFluids while drainDeadEdge holds its cells); its own seeded flows still carry
                // the type, so read it back rather than stamping an invisible empty.
                FluidStack cellFluid = rep.isEmpty() ? flowFluid(pipe) : rep;
                if (cellFluid.isEmpty()) continue;

                levelCells.add(cell);
                boolean changed = false;
                int data = encodeLevel(cellFrac, flowDir);
                if (holder.pipesnphysics$getLevelData() != data) {
                    holder.pipesnphysics$setLevelData(data);
                    changed = true;
                }
                int frontData = encodeFront(front.fraction(), front.flowDir(), front.rate());
                if (holder.pipesnphysics$getFrontData() != frontData
                        && !onlyRateJitter(holder.pipesnphysics$getFrontData(), frontData)) {
                    holder.pipesnphysics$setFrontData(frontData);
                    changed = true;
                }
                if (!FluidStack.isSameFluidSameComponents(holder.pipesnphysics$getRenderFluid(), cellFluid)) {
                    holder.pipesnphysics$setRenderFluid(cellFluid.copyWithAmount(1));
                    changed = true;
                }
                if (changed) pipe.blockEntity.notifyUpdate();
            }
        }
    }

    /**
     * Keep the LEVEL renderer owning the cells a headless edge is HELD on ({@code drainDeadEdge}
     * added them to {@code filled}), stamping each full &amp; still from its preserved Create flow.
     * Called instead of skipping an edge with no solved node heads, so a run receding gradually
     * through a transient never blips the render off (which handed it back to Create's binary fill
     * and started a client fade — the flicker). A cell the recede heartbeat already released is not
     * in {@code filled}, so it is left unstamped and fades out, preserving the gradual recede.
     */
    private static void stampHeldEdge(Level level, Edge edge, Set<BlockPos> filled, Set<BlockPos> levelCells) {
        for (BlockPos cell : edge.pipes()) {
            if (!filled.contains(cell)) continue; // only the cells drainDeadEdge is still holding
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (!(pipe instanceof PipeLevelData holder)) continue;
            FluidStack cellFluid = flowFluid(pipe);
            // Gas has no waterline (it fills by the mirror test), so leave a gas run to Create.
            if (cellFluid.isEmpty() || cellFluid.getFluid().getFluidType().isLighterThanAir()) continue;
            // KEEP the last flow direction + rate so the fluid keeps SCROLLING through the brief stop
            // (it is still draining OUT the same way it was flowing) instead of freezing to still — a
            // dead scroll reads as a visual pause in the flow. The scroll DIRECTION lives in the LEVEL
            // field's flowDir and the SPEED in the FRONT field's rate, so both carry it. A cell that was
            // already resting (stored dir -1, or no field) stays still. Idempotent across held ticks:
            // once stamped it re-reads its own preserved dir/rate, so the scroll runs unbroken to resume.
            int stored = holder.pipesnphysics$getFrontData();
            int flowDir = stored != 0 ? frontFlowDir(stored) : -1;
            float rate = stored != 0 ? frontRate(stored) : 0f;
            levelCells.add(cell);
            boolean changed = false;
            int levelData = encodeLevel(1.0, flowDir);
            if (holder.pipesnphysics$getLevelData() != levelData) {
                holder.pipesnphysics$setLevelData(levelData);
                changed = true;
            }
            int frontData = encodeFront(1, flowDir, rate);
            if (holder.pipesnphysics$getFrontData() != frontData
                    && !onlyRateJitter(holder.pipesnphysics$getFrontData(), frontData)) {
                holder.pipesnphysics$setFrontData(frontData);
                changed = true;
            }
            if (!FluidStack.isSameFluidSameComponents(holder.pipesnphysics$getRenderFluid(), cellFluid)) {
                holder.pipesnphysics$setRenderFluid(cellFluid.copyWithAmount(1));
                changed = true;
            }
            if (changed) pipe.blockEntity.notifyUpdate();
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
        return deliveryReady(level, graph, solution, transfer, levelRenderEnabled());
    }

    /**
     * As {@link #deliveryReady(Level, Graph, Solution, Solution.Transfer)} but with the level-render
     * flag passed explicitly, so a GameTest can exercise the owned-front gate without mutating live
     * config (mirrors the {@code apply} overload).
     */
    public static boolean deliveryReady(Level level, Graph graph, Solution solution,
                                        Solution.Transfer transfer, boolean levelRender) {
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
            if (frontReachedNode(level, graph, edge, sink.index(), levelRender)) return true;
            travellingFeeder = true;
        }
        return !travellingFeeder;
    }

    /**
     * Whether this edge's travelling front has arrived at the given endpoint node — i.e. the fill
     * {@link #chargeEdge} animates has crawled the WHOLE run contiguously from the source, not merely
     * lit up the sink-adjacent cell.
     *
     * Checking only the terminal cell decoupled delivery from the drawn fluid: {@link #seedComplete}
     * marks resting/standing cells complete, and {@link #preserveStandingFluid} keeps those complete
     * flows on the sink-side cells alive while a freshly-started flow's front is still crawling a dry
     * upstream gap (an elevated source draining down to a lower sink through a primed lower column).
     * The old terminal-cell check then read that pre-existing standing fluid as "front arrived" and
     * released the endpoint transfer immediately, so the tanks changed while the visible waterline was
     * still creeping. Requiring EVERY trackable cell's downstream (toward-sink) connection to be
     * complete holds the transfer until the front actually merges with the standing column — the air
     * gap must fill before the incompressible column moves, which is also exactly what the renderer
     * draws (its front mirrors {@code chargeEdge}).
     *
     * The per-cell check SKIPS (treats as passed) any cell the front itself can't track — a missing
     * pipe, an untrackable direction, or a face with no interface — mirroring {@code chargeEdge}'s own
     * break conditions, so an odd-geometry cell can never deadlock delivery. A fully-primed continuous
     * column has every cell complete, so it still delivers instantly (and is already drawn full).
     *
     * On the LEVEL-render path the ENGINE-OWNED front field is authoritative wherever stamped: full
     * means the front has passed through the cell ({@code advanceFront} integrates it; a standing
     * column ahead of the front is stamped full+still, so it passes, exactly like its complete flows
     * did). An UNSTAMPED cell — the dry gap the front is still crawling — falls through to the flow
     * check below and holds (its flows are swept). The stock path never stamps the field, so it
     * always takes the flow check unchanged.
     */
    private static boolean frontReachedNode(Level level, Graph graph, Edge edge, int nodeIndex,
                                            boolean levelRender) {
        List<BlockPos> pipes = edge.pipes();
        if (pipes.isEmpty()) return true;
        // Walk source -> sink so each cell's downstream face points at the next cell (or the sink node
        // for the last), the face chargeEdge fills last.
        List<BlockPos> order = edge.b() == nodeIndex ? pipes : pipes.reversed();
        BlockPos sinkPos = graph.node(nodeIndex).pos();
        for (int j = 0; j < order.size(); j++) {
            BlockPos cell = order.get(j);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe == null) continue;                         // can't track — don't stall on it
            if (levelRender && pipe instanceof PipeLevelData data
                    && data.pipesnphysics$getFrontData() != 0) {
                if (frontFraction(data.pipesnphysics$getFrontData()) < 1f) return false;
                continue;
            }
            BlockPos down = j == order.size() - 1 ? sinkPos : order.get(j + 1);
            Direction toward = PipeGeometry.between(cell, down);
            if (toward == null) continue;
            PipeConnection conn = pipe.getConnection(toward);
            if (conn == null) continue;                         // no interface that way — skip
            if (!isComplete(conn)) return false;                // front still crawling toward the sink
        }
        return true;                                            // charged all the way to the node
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
            Direction inDir = PipeGeometry.between(cell, up);
            Direction outDir = PipeGeometry.between(cell, down);
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

    /** A level-path flowing edge queued for the chained front advance. */
    private record LevelFlow(Edge edge, FluidStack fluid, boolean fromA, int moved) {}

    /**
     * Advance every level-path flowing edge's owned front, CHAINED across shared nodes so a
     * multi-edge line fills as ONE continuous travel: an edge whose upstream node is a zero-volume
     * pass-through (a junction or pump) holds its front at rest until every feeder front has
     * ARRIVED at that node — without this, each edge started crawling from its own upstream end
     * the moment the solve flowed, so the runs on either side of a junction visibly filled (and
     * on a reversal re-crawled) independently. A reservoir upstream (tank, basin, open end)
     * never holds: it buffers its own fluid, which is also what breaks the hold cycle of a
     * pump-driven loop through reservoirs. A HELD edge is advanced FROZEN — its already-stamped
     * fill is kept in place (still, rate 0) rather than swept, so a mid-crawl hold neither
     * despawns nor drifts. A pure feeder cycle with no advancing edge behind it (a pump ring
     * through junctions only) force-advances instead of deadlocking.
     */
    private static void advanceChained(Level level, Graph graph, List<LevelFlow> flows,
                                       Set<BlockPos> filled, Map<BlockPos, CellFront> fronts) {
        if (flows.isEmpty()) return;
        boolean[] done = new boolean[flows.size()];
        int remaining = flows.size();

        // Cascade: advance every edge whose upstream is ready; an edge completing its run this
        // tick can release its dependents within the same tick, so a short feeder doesn't add a
        // one-tick stutter at the node.
        boolean progress = true;
        while (remaining > 0 && progress) {
            progress = false;
            for (int i = 0; i < flows.size(); i++) {
                if (done[i]) continue;
                LevelFlow lf = flows.get(i);
                if (!upstreamReady(level, graph, flows, done, fronts, i)) continue;
                advanceFront(level, graph, lf.edge(), lf.fluid(), lf.fromA(), lf.moved(), false,
                        filled, fronts);
                done[i] = true;
                remaining--;
                progress = true;
            }
        }
        if (remaining == 0) return;

        // Whatever remains either genuinely WAITS on a front still crawling toward its upstream
        // node — frozen, transitively — or sits in a pure feeder cycle none of whose members
        // advanced (no reservoir anywhere behind it): force-advance those, a ring must not
        // deadlock its fill forever.
        boolean[] frozen = new boolean[flows.size()];
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < flows.size(); i++) {
                if (done[i] || frozen[i]) continue;
                if (hasWaitingFeeder(level, graph, flows, done, frozen, fronts, i)) {
                    frozen[i] = true;
                    grew = true;
                }
            }
        }
        for (int i = 0; i < flows.size(); i++) {
            if (done[i]) continue;
            LevelFlow lf = flows.get(i);
            advanceFront(level, graph, lf.edge(), lf.fluid(), lf.fromA(), lf.moved(), frozen[i],
                    filled, fronts);
        }
    }

    /**
     * Whether this flow's upstream node can feed it this tick: a reservoir (handler/open end)
     * always can; a pass-through node only once every flowing feeder INTO it has been advanced
     * this tick AND its front has arrived (its whole run is full).
     */
    private static boolean upstreamReady(Level level, Graph graph, List<LevelFlow> flows,
                                         boolean[] done, Map<BlockPos, CellFront> fronts, int self) {
        LevelFlow lf = flows.get(self);
        int up = lf.fromA() ? lf.edge().a() : lf.edge().b();
        Node node = graph.node(up);
        if (node.isHandler() || node.isOpenEnd()) return true;
        for (int j = 0; j < flows.size(); j++) {
            if (j == self) continue;
            LevelFlow f = flows.get(j);
            int downstream = f.fromA() ? f.edge().b() : f.edge().a();
            if (downstream != up) continue;
            if (!done[j] || !frontComplete(level, f.edge(), fronts)) return false;
        }
        return true;
    }

    /**
     * Whether this held flow is (transitively) waiting on a feeder that is really crawling — one
     * advanced this tick with an incomplete front, or one itself frozen behind such a feeder.
     * A held flow with no such feeder is part of a pure cycle and is force-advanced instead.
     */
    private static boolean hasWaitingFeeder(Level level, Graph graph, List<LevelFlow> flows,
                                            boolean[] done, boolean[] frozen,
                                            Map<BlockPos, CellFront> fronts, int self) {
        LevelFlow lf = flows.get(self);
        int up = lf.fromA() ? lf.edge().a() : lf.edge().b();
        for (int j = 0; j < flows.size(); j++) {
            if (j == self) continue;
            LevelFlow f = flows.get(j);
            int downstream = f.fromA() ? f.edge().b() : f.edge().a();
            if (downstream != up) continue;
            if (frozen[j]) return true;
            if (done[j] && !frontComplete(level, f.edge(), fronts)) return true;
        }
        return false;
    }

    /**
     * Whether an edge's front (as advanced THIS tick, in {@code fronts}) has filled its whole run.
     * Untrackable cells (no pipe) are skipped, mirroring the walk's own break conditions, so odd
     * geometry can't hold a dependent edge forever.
     */
    private static boolean frontComplete(Level level, Edge edge, Map<BlockPos, CellFront> fronts) {
        for (BlockPos cell : edge.pipes()) {
            CellFront front = fronts.get(cell);
            if (front != null && front.fraction() >= 1) continue;
            if (FluidPropagator.getPipe(level, cell) == null) continue; // can't track — don't hold on it
            return false;
        }
        return true;
    }

    /**
     * Advance the ENGINE-OWNED travelling front of a flowing edge by one tick — the level-render
     * replacement for {@link #chargeEdge}, which animates the front through Create's Flow progress
     * (advanced by {@code tickFlowProgress}, which owned cells skip). The front state lives in the
     * cells' own synced {@link PipeLevelData} front field, read back each tick — stateless across
     * ticks like the old one, so it survives edits. The field is not SAVED, so on a fresh load a
     * cell re-derives its fill from the PERSISTED Create flows: a steadily-flowing run comes back
     * full instead of re-crawling, a mid-fill run resumes from its last half-cell boundary.
     *
     * The fill speed is {@link #cellsPerTick} of the same {@link #flowPressure} the old front used,
     * so the pace is unchanged: a brisk pump fills the run fast, a trickle slowly. Reversal keeps a
     * FULL cell full (the scroll just flips) but restarts a mid-fill cell from the new upstream
     * end, matching {@link #seedCharging}. Create's flows are seeded alongside at half-cell
     * granularity — complete behind the front, charging (held, never ticked) at it — because they
     * remain the persisted and stock-visible state: the reload re-derivation above, a receding
     * hump's fluid type, and the delivery gate's unstamped-cell fallback all read them.
     *
     * {@code frozen} (a chained edge held at its upstream node, {@link #advanceChained}) walks and
     * re-stamps the existing fill without advancing it — rate 0, so the client neither scrolls
     * nor extrapolates it — keeping a mid-crawl hold in place instead of letting the sweep clear it.
     */
    private static void advanceFront(Level level, Graph graph, Edge edge, FluidStack fluid,
                                     boolean flowFromA, int mbPerTick, boolean frozen,
                                     Set<BlockPos> filled, Map<BlockPos, CellFront> fronts) {
        List<BlockPos> pipes = edge.pipes();
        if (pipes.isEmpty()) return;
        List<BlockPos> order = flowFromA ? pipes : pipes.reversed();
        BlockPos upstream = (flowFromA ? graph.node(edge.a()) : graph.node(edge.b())).pos();
        BlockPos downstream = (flowFromA ? graph.node(edge.b()) : graph.node(edge.a())).pos();
        float pressure = flowPressure(mbPerTick);
        double rate = frozen ? 0 : cellsPerTick(pressure);
        double advance = rate;

        for (int j = 0; j < order.size(); j++) {
            BlockPos cell = order.get(j);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe == null) break;

            BlockPos up = j == 0 ? upstream : order.get(j - 1);
            BlockPos down = j == order.size() - 1 ? downstream : order.get(j + 1);
            Direction inDir = PipeGeometry.between(cell, up);
            Direction outDir = PipeGeometry.between(cell, down);
            if (inDir == null || outDir == null) break;
            PipeConnection inC = pipe.getConnection(inDir);
            PipeConnection outC = pipe.getConnection(outDir);
            if (inC == null || outC == null) break; // can't draw a continuous front here

            int dir = outDir.get3DDataValue();
            double fraction;
            int stored = pipe instanceof PipeLevelData data ? data.pipesnphysics$getFrontData() : 0;
            if (stored != 0) {
                fraction = frontFraction(stored);
                int storedDir = frontFlowDir(stored);
                // Reversal: a full cell stays full (only the scroll flips); a mid-fill cell restarts
                // from the new upstream end. A still (-1) stored dir is a backed-up or standing hold
                // with no direction of record — resume it as-is.
                if (storedDir != -1 && storedDir != dir && fraction < 1) fraction = 0;
            } else {
                // No field (fresh cell, or just loaded — the field is not saved): re-derive the fill
                // from the persisted Create flows, at their half-cell granularity.
                fraction = isComplete(outC) ? 1 : isComplete(inC) ? 0.5 : 0;
            }

            if (fraction < 1) {
                double before = fraction;
                fraction = Math.min(1, fraction + advance);
                advance -= fraction - before;
            }
            if (fraction <= 0) break; // the front stopped before this cell

            fronts.put(cell, new CellFront(fraction, dir, rate));
            filled.add(cell);

            // Keep Create's flows in step at half-cell granularity: the inbound half completes once
            // the fill passes the centre, the outbound once it exits. The in-progress half holds a
            // charging flow (present but never ticked) so the cell still reads wet server-side, and
            // the not-yet-reached exit stays hidden, mirroring chargeEdge.
            boolean changed;
            if (fraction >= 1) {
                changed = seedComplete(inC, true, fluid);
                changed |= seedComplete(outC, false, fluid);
            } else if (fraction >= 0.5) {
                changed = seedComplete(inC, true, fluid);
                changed |= seedCharging(outC, false, fluid, pressure);
            } else {
                changed = seedCharging(inC, true, fluid, pressure);
                changed |= clearFlow(outC);
            }
            if (changed) pipe.blockEntity.notifyUpdate();

            if (fraction < 1) break; // the front ends inside this cell
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
            Direction towardA = PipeGeometry.between(cell, aSide);
            Direction towardB = PipeGeometry.between(cell, bSide);
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
            BlockPos adj = PipeGeometry.adjacentCell(graph, edge, gate.index());
            boolean wet = solution.heldEdges().contains(edge.index()) || filled.contains(adj);
            if (!wet) continue;
            FluidStack fluid = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
            if (fluid.isEmpty()) continue;
            Direction dir = PipeGeometry.between(gate.pos(), adj);
            if (dir == null) continue;
            changed |= seedComplete(pipe.getConnection(dir), true, fluid);
            anyWet = true;
        }
        if (changed) pipe.blockEntity.notifyUpdate();
        if (anyWet) filled.add(gate.pos());
    }

    /**
     * Render a dead-end JUNCTION cell — a pipe capped by a solid block — full of the fluid resting
     * against it. Like a shut valve, a junction is a NODE in no edge, so {@link #restEdge} fills the
     * run only up to its last edge cell and leaves this terminal cell dry: the fluid visibly stops
     * one cell short of the block. Only a TRUE dead end (a single incident run) is handled — a
     * multi-way junction (3–4 connections) stays the known gap. Filled only when that run holds
     * fluid reaching the junction (its adjacent cell is in {@code filled}) and this cell sits below
     * the settled waterline (the same dead-end centre threshold {@code restingCellSubmerged} uses),
     * so a dry or above-surface junction stays empty.
     */
    private static void fillDeadEndCell(Level level, Graph graph, Node junction, Solution solution,
                                        Set<BlockPos> filled) {
        List<Edge> edges = graph.edgesOf(junction.index());
        if (edges.size() != 1) return;
        Edge edge = edges.get(0);
        FluidStack fluid = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (fluid.isEmpty()) fluid = solution.edgeFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (fluid.isEmpty()) return;
        BlockPos adj = PipeGeometry.adjacentCell(graph, edge, junction.index());
        if (adj == null || !filled.contains(adj)) return;
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, junction.pos());
        if (pipe == null) return;
        Double head = solution.nodeHeads().get(junction.index());
        if (head == null) return;
        // A liquid dead end fills once its waterline reaches the cell centre (matching restEdge's
        // dead-end threshold); a gas pools against the cap regardless — the adjacent cell being wet
        // already proved the run holds it.
        if (!fluid.getFluid().getFluidType().isLighterThanAir()
                && head + SUBMERSION_EPS < SableCompat.getWorldY(level, junction.pos())) {
            return;
        }
        Direction dir = PipeGeometry.between(junction.pos(), adj);
        if (dir == null) return;
        if (seedComplete(pipe.getConnection(dir), true, fluid)) pipe.blockEntity.notifyUpdate();
        filled.add(junction.pos());
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
        Direction towardEdge = PipeGeometry.between(node.pos(), PipeGeometry.adjacentCell(graph, edge, nodeIndex));
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

    /** Any non-empty flow's fluid on this pipe (its type), or EMPTY if dry — the stamp's rep fallback. */
    private static FluidStack flowFluid(FluidTransportBehaviour pipe) {
        for (Direction dir : Direction.values()) {
            if (pipe.getConnection(dir) instanceof PipeConnectionAccessor accessor) {
                Optional<PipeConnection.Flow> flow = accessor.pipesnphysics$getFlow();
                if (flow.isPresent() && !flow.get().fluid.isEmpty()) return flow.get().fluid;
            }
        }
        return FluidStack.EMPTY;
    }

    /**
     * Whether two front values differ only by rate jitter within {@link #FRONT_RATE_EPS} — same
     * fraction and direction. A steadily-flowing full cell's rate wobbles with the solved mB/t;
     * without the deadband every wobble would re-sync the cell.
     */
    private static boolean onlyRateJitter(int stored, int fresh) {
        if (stored == 0) return false;
        int band = FRONT_FRAC_STRIDE * 8;
        if ((stored - 1) % band != (fresh - 1) % band) return false;
        return Math.abs((stored - 1) / band - (fresh - 1) / band) <= FRONT_RATE_EPS;
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
                // Same direction + fluid: KEEP the fill progress (tickFlowProgress owns it), but refresh
                // the pressure (the scroll-speed knob) so it tracks the CURRENT flow rate. Without this
                // it stayed frozen at the rate from the first seed, so a transient re-seed — e.g.
                // wrenching a pipe splits then rejoins the run for a tick at low/zero flow — stuck the
                // animation speed low permanently. Only re-sync when it moved, so a steady flow is quiet.
                var p = conn.getPressure();
                if (Math.abs(Math.max(p.getFirst(), p.getSecond()) - pressure) <= PRESSURE_REFRESH_EPS) {
                    return false;
                }
                conn.wipePressure();
                conn.addPressure(inbound, pressure);
                return true;
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

}
