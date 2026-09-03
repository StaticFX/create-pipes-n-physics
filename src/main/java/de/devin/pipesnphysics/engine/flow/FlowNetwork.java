package de.devin.pipesnphysics.engine.flow;

import com.simibubi.create.content.fluids.FluidReactions;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.MouthConditions;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.store.PipeWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One executed tick's object view of a pipe network: every pipe cell as a {@link PipeStore.Store},
 * every endpoint as a {@link Reservoir}, and the junction/shut-valve buffer slots — resolved FRESH
 * from the world (it may have changed since the solve) and shared by the brigade and settle
 * phases, so per-tick endpoint budgets are enforced across both.
 *
 * A multiblock tank reached from several graph nodes resolves to ONE {@code Reservoir} (deduped
 * by column identity), which is what makes its give/take budgets per-tank rather than per-pipe.
 * Built by {@code PipeFlowExecutor.run} once per solved tick and discarded after {@link #flush()}
 * — that lifetime is what makes the budgets per-tick.
 */
public final class FlowNetwork {
    /** Ticks of buffered rate a flowing cell holds — the front crosses ~one cell per this many ticks. */
    private static final int DEPTH_TICKS = 4;
    /** The depth floor as a fraction of a cell (1/this): the thinnest coherent, visible plug. */
    private static final int MIN_DEPTH_DIVISOR = 8;

    /**
     * The column depth a run flowing at {@code solvedRateMb} carries per cell — the gate quantity
     * everywhere plug flow used to require a FULL cell: {@link #DEPTH_TICKS} of buffered rate, so
     * a front's arrival stays bounded regardless of rate, floored at a visible sliver of the bore
     * and never more than a cell. A trickle runs as a shallow stream; full-bore appears only where
     * the line is pressurized (the flowing top-up) or backed up. At and above a quarter cell per
     * tick this IS the old full-cell gate.
     */
    public static int flowDepthMb(int solvedRateMb, int cellCapacity) {
        if (cellCapacity <= 0) return 0;
        return Math.clamp((long) DEPTH_TICKS * solvedRateMb,
                Math.max(cellCapacity / MIN_DEPTH_DIVISOR, 1), cellCapacity);
    }

    final Level level;
    final Graph graph;
    /** mB one pipe cell holds; 0 = pipes store nothing and every run degrades to a wire. */
    final int cellCapacity = PipeStore.capacityMb();

    private final Map<BlockPos, PipeStore.Store> cells = new HashMap<>();
    private final Map<Integer, Reservoir> reservoirs = new HashMap<>();
    /** Cells where two different fluids were driven together this tick — reacted after the flush. */
    private final Map<BlockPos, Collision> collisions = new HashMap<>();

    public FlowNetwork(Level level, Graph graph) {
        this.level = level;
        this.graph = graph;

        // What this network's mouths may drink this tick — the same reading the solve took.
        MouthConditions mouths = MouthConditions.of(level, graph);
        int maxFlow = PipesNPhysicsConfig.MAX_FLOW_PER_ENDPOINT.get();
        Map<BlockPos, Reservoir> reservoirByIdentity = new LinkedHashMap<>();
        for (Node node : graph.nodes()) {
            BoundaryColumn column = node.isHandler() ? BoundaryColumn.resolve(level, node)
                    : node.isOpenEnd() ? mouths.column(level, node)
                    : null;
            if (column == null) continue;
            Reservoir reservoir = reservoirByIdentity.computeIfAbsent(column.identity(),
                    k -> new Reservoir(level, column, maxFlow));
            reservoirs.put(node.index(), reservoir);
        }
    }

    /** The stored-fluid cell at a pipe position, or null where nothing there can hold pipe fluid. */
    PipeStore.Store cellAt(BlockPos pos) {
        return cells.computeIfAbsent(pos.immutable(), k -> PipeStore.at(level, k));
    }

    /** The reservoir behind a graph node, or null for pass-through nodes (pumps, junctions). */
    Reservoir reservoirAt(int nodeIndex) {
        return reservoirs.get(nodeIndex);
    }

    /** The one-cell buffer of a junction or shut-valve node; pumps and open ends hold nothing. */
    PipeStore.Store slotAt(int nodeIndex) {
        if (cellCapacity <= 0) return null;
        Node node = graph.node(nodeIndex);
        if (!node.isJunction() && !node.isClosedGate()) return null;
        return cellAt(node.pos());
    }

    /** A cell's bottom elevation in true world space (Sable-projected). */
    double cellBottomY(BlockPos pos) {
        return SableCompat.getWorldY(level, pos) - 0.5;
    }

    // A pipe cell's stored fluid lives in — and renders in — the vertical WINDOW defined by
    // {@link PipeWindow} (bore for a horizontal cell, full block for a vertical riser). The settle,
    // the draw lip, and the renderer all read that one window, so a settled pipe's surface lands on
    // the tank waterline it equalized with and the solver never conducts through a cell drawn empty.

    /** The bottom of a cell's drawn fluid window in true world space. */
    double windowBottomY(BlockPos pos) {
        return PipeWindow.bottomY(level, pos);
    }

    /** The height of a cell's drawn fluid window: the full block for a riser, else the bore. */
    double windowHeight(BlockPos pos) {
        return PipeWindow.height(level, pos);
    }

    /** The fraction of a cell's fluid window sitting below the given surface line, clamped 0..1. */
    double windowFill(BlockPos pos, double line) {
        return PipeWindow.fill(level, pos, line);
    }

    /** The draw-lip elevation of an opening through a cell (the pipe's outer shell bottom). */
    double lipY(BlockPos pos) {
        return PipeWindow.lipY(level, pos);
    }

    /** A cell's centre elevation in true world space (Sable-projected). */
    double cellCenterY(BlockPos pos) {
        return SableCompat.getWorldY(level, pos);
    }

    /**
     * Give a pump the cosmetic flow stamp of a delivery it drove across a ZERO-CELL edge — a pump
     * wedged flush against a tank or an open mouth. A pump owns no cell of the run (it stores
     * nothing), so such a delivery leaves a stamp NOWHERE, and the client FX layer — which reads
     * stamps — showed no pour at all ("pumps directly adjacent to a tank don't show the spill
     * particles"). Sync-only and volume-free: the pump still holds not one mB, it just says which
     * way it is pushing and how fast. An outlet run WITH cells needs none of this — its own cells
     * carry the stamp — so the pump is cleared there.
     */
    public void stampPumps(FlowLedger ledger) {
        for (Node pump : graph.pumps()) {
            PipeStore.Store store = cellAt(pump.pos());
            if (store == null) continue;
            Edge outlet = outletEdge(pump);
            int moved = outlet == null ? 0 : ledger.edgeMovedMb()[outlet.index()];
            if (moved > 0 && outlet.pipes().isEmpty() && cellCapacity > 0) {
                store.setFlow(pump.pumpFacing(), moved / (double) cellCapacity);
            } else {
                store.clearFlow();
            }
        }
    }

    /** The edge a pump pushes INTO (its FACING flank), or null while its facing is unresolved. */
    private Edge outletEdge(Node pump) {
        BlockPos push = pump.pushCell();
        if (push == null) return null;
        for (Edge edge : graph.edgesOf(pump.index())) {
            if (push.equals(PipeGeometry.adjacentCell(graph, edge, pump.index()))) return edge;
        }
        return null;
    }

    /** Clear the scroll-animation stamps on an idle edge's cells. */
    void clearFlowStamps(Edge edge) {
        for (BlockPos pos : edge.pipes()) {
            PipeStore.Store cell = cellAt(pos);
            if (cell != null) cell.clearFlow();
        }
    }

    /** Send one sync per changed cell — the end of the tick. */
    public void flush() {
        for (PipeStore.Store cell : cells.values()) {
            if (cell != null) cell.flush();
        }
    }

    // ---------------------------------------------------------------- fluid collisions

    /** Two fluids driven together at a pipe cell: the resident one and the one pushed into it. */
    private record Collision(FluidStack resident, FluidStack incoming) {}

    /**
     * Whether pipes may hold more than one fluid instead of breaking — the config's one gate, asked
     * HERE so all four collision sites (a driven run, a junction slot, a pump packing its outlet, a
     * reservoir pressing its column at rest) answer alike. On, two fluids simply stop where they
     * meet: a cell still holds one fluid and a run still carries one at a time, so the second waits
     * behind the first, but sharing plumbing stops being destructive.
     */
    static boolean mixingAllowed() {
        return PipesNPhysicsConfig.ALLOW_MIXED_PIPE_FLUIDS.get();
    }

    /**
     * Record that {@code incoming} was driven into the pipe cell at {@code pos}, which already holds
     * a different {@code resident} fluid — Create's "crossing the streams". Deduped per cell; applied
     * once after the flush ({@link #reactToCollisions}), so no pipe is broken mid-execution.
     */
    void collide(BlockPos pos, FluidStack resident, FluidStack incoming) {
        if (mixingAllowed() || resident.isEmpty() || incoming.isEmpty()
                || FluidStack.isSameFluidSameComponents(resident, incoming)) {
            return;
        }
        collisions.putIfAbsent(pos.immutable(), new Collision(resident.copy(), incoming.copy()));
    }

    /**
     * Whether pushing {@code incoming} into {@code cell} at {@code pos} collides with a different
     * fluid already resting there — records it for the reaction and reports true so the caller
     * moves nothing. Empty or same-fluid destinations are no collision (ordinary flow / back-up).
     */
    boolean collides(BlockPos pos, PipeStore.Store cell, FluidStack incoming) {
        if (mixingAllowed() || incoming.isEmpty() || cell.amount() <= 0
                || FluidStack.isSameFluidSameComponents(cell.fluid(), incoming)) {
            return false;
        }
        collide(pos, cell.fluid(), incoming);
        return true;
    }

    /**
     * Apply every collision recorded this tick through Create's own {@link FluidReactions}: the pipe
     * breaks and a reactive pair leaves its block (water+lava → cobblestone), exactly the
     * crossing-the-streams path our transport-cancel mixin removed. Server-only (world mutation,
     * advancement, {@code PipeCollisionEvent}); each break wakes the network so the now-stale cached
     * graph is re-discovered next tick.
     */
    public void reactToCollisions() {
        if (collisions.isEmpty() || level.isClientSide()) return;
        for (Map.Entry<BlockPos, Collision> entry : collisions.entrySet()) {
            BlockPos pos = entry.getKey();
            FluidReactions.handlePipeFlowCollision(level, pos, entry.getValue().resident(),
                    entry.getValue().incoming());
            EngineTickHandler.markChanged(level, pos);
        }
    }
}
