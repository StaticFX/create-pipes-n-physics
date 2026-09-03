package de.devin.pipesnphysics.engine.graph;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.VanillaFluidTargets;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.boundary.FluidCaps;
import de.devin.pipesnphysics.engine.boundary.FluidTankGeometry;
import de.devin.pipesnphysics.engine.boundary.HandlerRoles;
import de.devin.pipesnphysics.engine.pump.Pumps;
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Builds a contracted {@link Graph} from a Create pipe network in the world.
 *
 * Algorithm:
 *   1. BFS over Create pipe blocks starting at startPos. Pumps are pipes too, so
 *      they are discovered automatically and recorded as PUMP candidates.
 *   2. While walking, record adjacent blocks that expose IFluidHandler as HANDLER
 *      candidates (tanks, basins, drains, portable interfaces, etc.).
 *   3. Classify each pipe cell:
 *        - exactly 2 pipe/handler connections → pass-through, becomes part of an edge,
 *        - anything else → JUNCTION node.
 *   4. Walk each junction's outgoing connections along pass-through pipes until
 *      another node is reached. Each such walk is contracted into a single Edge.
 *   5. Pumps split their pipe run into two edges (one per side); the pump itself
 *      is a node so flow naturally has to pass through it.
 *
 * The resulting Graph is connected, immutable, and Minecraft-independent except
 * for its BlockPos references and the world-Y coordinates baked in at construction.
 */
public final class GraphBuilder {
    private GraphBuilder() {}

    private static boolean isConduit(Level level, BlockPos pos) {
        return HandlerRoles.isConduit(level, pos);
    }

    /**
     * Whether a cell is a fully-SHUT fluid valve — a fluid-independent closure (it rejects every
     * fluid, both directions), so it can safely become a wall in the shared topology. A partially
     * open valve still conducts (throttled), so it stays a normal pipe cell. Reads the engine's own
     * {@link ValveThrottle} angle (0 = shut); inert when the throttle feature is off (returns 1).
     */
    private static boolean isClosedGate(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ValveThrottle valve
                && valve.pipesnphysics$valveThrottle() <= 0f;
    }

    /**
     * The single allowed flow direction of an OPEN one-way valve at this cell, or null (a plain
     * cell, a both-ways valve, the feature off). Like a shut valve it is forced to a node so the
     * run splits there — but a CONDUCTING one: the solver walls only the reverse direction.
     */
    private static Direction oneWayFlow(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ValveThrottle valve
                ? valve.pipesnphysics$oneWayFlow() : null;
    }

    /**
     * Build a graph containing the network reachable from startPos.
     *
     * If startPos is not a pipe, pump, or handler, BFS extends one block outward
     * to try to find one. Returns an empty graph if no network is found.
     */
    public static Graph build(Level level, BlockPos startPos) {
        BlockPos seed = findSeed(level, startPos);
        if (seed == null) return new Graph(List.of(), List.of(), Set.of());

        // Step 1+2: discover all pipe cells and adjacent handler positions.
        Discovery discovery = discover(level, seed);
        if (discovery.pipes.isEmpty() && discovery.pumps.isEmpty()) return new Graph(List.of(), List.of(), Set.of());

        // Step 3: identify which pipe cells are nodes (not 2-connection pass-throughs).
        Set<BlockPos> nodePositions = new LinkedHashSet<>();
        nodePositions.addAll(discovery.pumps);
        nodePositions.addAll(discovery.handlers);
        nodePositions.addAll(discovery.openEnds.keySet());
        for (BlockPos pipe : discovery.pipes) {
            int conns = discovery.connections.getOrDefault(pipe, List.of()).size();
            // A fully-shut valve is forced to a node so the run SPLITS there (a wall): the
            // supply side holds its head up to the valve, the far side settles. The graph
            // stays connected (the gate bridges two edges) — only the solver treats it as
            // non-conducting — so coverage/dedupe/wake are unaffected. An OPEN one-way valve
            // is forced to a node too — a conducting one whose reverse direction the solve
            // AND the settle wall (the settle's only cross-node path is the node slot).
            if (conns != 2 || isClosedGate(level, pipe) || oneWayFlow(level, pipe) != null) {
                nodePositions.add(pipe);
            }
        }
        // If no junctions/handlers/pumps exist, treat the start as the single node.
        if (nodePositions.isEmpty()) nodePositions.add(discovery.pipes.iterator().next());

        // Build Node list with stable indices.
        List<Node> nodes = new ArrayList<>();
        Map<BlockPos, Integer> indexOf = new HashMap<>();
        for (BlockPos pos : nodePositions) {
            Node.Kind kind;
            Direction facing = null;
            Direction openFace = null;
            Direction accessFace = null;
            Direction gateFlow = null;
            if (discovery.pumps.contains(pos)) {
                kind = Node.Kind.PUMP;
                facing = Pumps.pushSide(level, pos, level.getBlockState(pos));
            } else if (discovery.handlers.contains(pos)) {
                kind = Node.Kind.HANDLER;
                accessFace = discovery.handlerFaces.get(pos); // non-null only for a side-specific handler
            } else if (discovery.openEnds.containsKey(pos)) {
                kind = Node.Kind.OPEN_END;
                openFace = discovery.openEnds.get(pos);
            } else if (isClosedGate(level, pos)) {
                kind = Node.Kind.CLOSED_GATE; // a shut one-way valve is just shut — the wall wins
            } else {
                kind = Node.Kind.JUNCTION;
                gateFlow = oneWayFlow(level, pos); // non-null only for an open one-way valve
            }
            int idx = nodes.size();
            nodes.add(new Node(idx, pos, kind, SableCompat.getWorldY(level, pos), facing, openFace,
                    accessFace, gateFlow));
            indexOf.put(pos, idx);
        }

        // Step 4: contract pipe runs between nodes into edges.
        List<Edge> edges = new ArrayList<>();
        Set<EdgeKey> seenEdgeKeys = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos nodePos : nodePositions) {
            int fromIdx = indexOf.get(nodePos);
            for (BlockPos neighbor : discovery.connections.getOrDefault(nodePos, List.of())) {
                // Direct node-to-node: pipe between two adjacent nodes, or a node touching another node.
                if (nodePositions.contains(neighbor)) {
                    int toIdx = indexOf.get(neighbor);
                    addEdgeIfNew(edges, seenEdgeKeys, fromIdx, toIdx, List.of());
                    continue;
                }
                // Walk the pipe run starting at neighbor until we hit another node.
                if (!discovery.pipes.contains(neighbor) || visited.contains(neighbor)) continue;

                List<BlockPos> path = new ArrayList<>();
                BlockPos prev = nodePos;
                BlockPos cur = neighbor;
                while (cur != null && !nodePositions.contains(cur)) {
                    path.add(cur);
                    visited.add(cur);
                    BlockPos next = null;
                    for (BlockPos c : discovery.connections.getOrDefault(cur, List.of())) {
                        if (!c.equals(prev)) { next = c; break; }
                    }
                    prev = cur;
                    cur = next;
                }
                if (cur == null) continue; // walk ran off the end (shouldn't happen on a closed graph)
                int toIdx = indexOf.get(cur);
                if (toIdx == fromIdx) {
                    splitLoop(level, nodes, indexOf, edges, seenEdgeKeys, fromIdx, path);
                    continue;
                }
                addEdgeIfNew(edges, seenEdgeKeys, fromIdx, toIdx, path);
            }
        }

        Set<BlockPos> coverage = new HashSet<>();
        coverage.addAll(discovery.pipes);
        coverage.addAll(discovery.pumps);
        coverage.addAll(discovery.handlers);
        coverage.addAll(discovery.openEnds.keySet());
        return new Graph(List.copyOf(nodes), List.copyOf(edges), Set.copyOf(coverage));
    }

    /** If startPos isn't a pipe, look one block in each direction for one. Returns null if none found. */
    public static BlockPos findSeed(Level level, BlockPos startPos) {
        if (isPipeLike(level, startPos)) return startPos;
        for (Direction d : Direction.values()) {
            BlockPos adj = startPos.relative(d);
            if (isPipeLike(level, adj)) return adj;
        }
        return null;
    }

    private static boolean isPipeLike(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        return FluidPropagator.getPipe(level, pos) != null || isConduit(level, pos);
    }

    /** BFS state. */
    private static final class Discovery {
        final Set<BlockPos> pipes = new LinkedHashSet<>();   // pure pipe cells (incl. straight bits that pumps share via FluidTransportBehaviour are excluded — pumps are tracked separately)
        final Set<BlockPos> pumps = new LinkedHashSet<>();   // pump positions
        final Set<BlockPos> handlers = new LinkedHashSet<>(); // adjacent IFluidHandler positions
        final Map<BlockPos, Direction> handlerFaces = new HashMap<>(); // side-specific handler -> its face toward the pipe
        final Map<BlockPos, Direction> openEnds = new LinkedHashMap<>(); // space pos -> face back toward its pipe
        final Map<BlockPos, List<BlockPos>> connections = new HashMap<>();
    }

    private static Discovery discover(Level level, BlockPos start) {
        Discovery discovery = new Discovery();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        frontier.add(start.immutable());

        while (!frontier.isEmpty()) {
            BlockPos cur = frontier.poll();
            if (!visited.add(cur)) continue;
            if (!level.isLoaded(cur)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cur);
            if (pipe == null) {
                if (isConduit(level, cur)) discoverConduit(level, cur, discovery, frontier);
                continue;
            }

            BlockState curState = level.getBlockState(cur);
            boolean isPump = Pumps.isPump(level, cur, curState);
            if (isPump) discovery.pumps.add(cur);
            else discovery.pipes.add(cur);

            List<BlockPos> conns = new ArrayList<>();
            for (Direction face : FluidPropagator.getPipeConnections(curState, pipe)) {
                classifyNeighbor(level, discovery, frontier, conns, cur, face);
            }
            mergeConnections(discovery, cur, conns);
        }

        return discovery;
    }

    /**
     * Classify the block a pipe cell opens toward on one face — pump, fluid handler, reciprocating
     * pipe, or open end — recording it into the discovery state, linking it into {@code conns}, and
     * queueing further BFS where the neighbor is traversable.
     */
    private static void classifyNeighbor(Level level, Discovery discovery, Queue<BlockPos> frontier,
                                         List<BlockPos> conns, BlockPos cur, Direction face) {
        BlockPos neighbor = cur.relative(face);
        if (!level.isLoaded(neighbor)) return;
        BlockState nState = level.getBlockState(neighbor);

        if (Pumps.isPump(level, neighbor, nState)) {
            discovery.pumps.add(neighbor.immutable());
            conns.add(neighbor.immutable());
            frontier.add(neighbor.immutable());
            return;
        }

        var handler = FluidCaps.at(level, neighbor, face.getOpposite());
        // A cauldron (and the other vanilla fluid targets) now exposes a NeoForge
        // fluid-handler capability, but its CauldronWrapper only drains in whole
        // 1000 mB increments — far above MAX_FLOW_PER_ENDPOINT — so the generic
        // handler path reads it as empty and a pump beside it never pulls. Create
        // itself drains these through the open-end (VanillaFluidTargets) path, so
        // let them fall through to the OPEN_END branch below, exactly as Create's
        // own isOpenEnd does (it returns true for canProvideFluidWithoutCapability).
        // ignore_fluid_handler blocks (a relay that corrupts on both drain AND fill) are
        // skipped as if they held no fluid — they fall through to the open-end / dead-end
        // path below instead of joining the network as a tank node.
        if (handler != null && !VanillaFluidTargets.canProvideFluidWithoutCapability(nState)
                && !HandlerRoles.isIgnored(level, neighbor)) {
            BlockPos handlerPos = neighbor.immutable();
            boolean firstSight = discovery.handlers.add(handlerPos);
            conns.add(handlerPos);
            backLink(discovery, handlerPos, cur);
            var sideAgnosticCap = FluidCaps.at(level, neighbor, null);
            // SIDE-SPECIFIC when the face this pipe meets exposes a DIFFERENT handler than the null
            // side — a distinct per-face tank the engine must read through THIS face rather than
            // couple. A block with no null cap is trivially side-specific; a block whose null side
            // and this face return the SAME handler object is side-agnostic and couples. Create's
            // own tank/basin providers ignore the side and hand back one shared handler, so identity
            // keeps them coupled; a block that hands back a DIFFERENT handler per side (TFMG's coke
            // oven: secondary CO2 tank on top, primary creosote tank on the sides + null) is the
            // per-face case — else the engine resolves the null side and reads the wrong (usually
            // empty) tank, so a pump on top of a coke oven never pulls its CO2. `handler` is already
            // the face handler (getCapability(face.getOpposite())).
            boolean sideSpecific = sideAgnosticCap == null || handler != sideAgnosticCap;
            // A conduit handler is traversed THROUGH so its own chain is discovered.
            if (isConduit(level, neighbor)) {
                frontier.add(handlerPos);
            } else if (sideSpecific) {
                // Record the face this pipe meets it on so the endpoint resolves and transfers
                // through that exact tank, and do NOT couple its other faces — those are DIFFERENT
                // tanks and belong to their own networks. face is the handler's face toward this
                // pipe (opposite the pipe's opening direction).
                discovery.handlerFaces.putIfAbsent(handlerPos, face.getOpposite());
            } else if (firstSight && !HandlerRoles.hasSeparatePorts(nState)) {
                // A side-agnostic tank/basin couples EVERY run that touches it — fluid flows
                // run→tank→run through the shared reservoir — so discover the OTHER runs on its
                // footprint into this same graph. Without this a tank with two connections split
                // into two independent networks, each solving the tank's fill blind to the other,
                // so a full pass-through tank wrongly reported "destination full" on its inflow run.
                //
                // A block DECLARED multi-port is the exception: its connections are separate ports
                // into separate internal tanks, not one body of fluid, so coupling them is what
                // lets a fluid enter by one port and leave by another. It keeps a null access face
                // (its handler really is the same object on every side — only the topology differs),
                // so its endpoint resolves exactly as before and each run reaches it on its own.
                exploreHandlerRuns(level, neighbor, frontier);
            }
            return;
        }

        // Link to a neighbouring pipe ONLY if it opens back toward us. `getPipeConnections`
        // reports the faces THIS pipe opens on (one-sided); Create's own propagation also
        // checks the target's reciprocal opening. On the main world the two states are kept
        // mutually consistent so the check is moot, but a Sable sub-level never re-runs the
        // connection update (its BEs don't tick), so a stale one-sided opening would
        // otherwise bridge two pipes that are not actually connected (a phantom edge).
        var neighborPipe = FluidPropagator.getPipe(level, neighbor);
        if (neighborPipe != null) {
            if (neighborPipe.canHaveFlowToward(nState, face.getOpposite())) {
                conns.add(neighbor.immutable());
                frontier.add(neighbor.immutable());
            }
            return;
        }

        // An open pipe end facing air, a fluid, or a vanilla fluid target
        // (cauldron etc.) becomes an OPEN_END boundary node at the space block.
        if (FluidPropagator.isOpenEnd(level, cur, face)) {
            BlockPos mouthPos = neighbor.immutable();
            discovery.openEnds.putIfAbsent(mouthPos, face.getOpposite());
            conns.add(mouthPos);
            backLink(discovery, mouthPos, cur);
        }
    }

    /**
     * Record the reverse link into a boundary (handler or open end) the moment its discoverer links
     * forward, so the contraction walk traverses the chain in both directions. Boundaries never
     * enumerate their own faces, so without this a tank or mouth would have no way back to the
     * pipes that found it. (This replaces a post-BFS pass that rescanned every connection list per
     * boundary — quadratic on tank-heavy networks.)
     */
    private static void backLink(Discovery discovery, BlockPos boundary, BlockPos from) {
        List<BlockPos> conns = discovery.connections.computeIfAbsent(boundary, k -> new ArrayList<>());
        if (!conns.contains(from)) conns.add(from);
    }

    /**
     * Register a cell's discovered connections, MERGING with any back-links already recorded under
     * it: a conduit (or a handler that is also a pipe) may have received back-links from earlier
     * cells before its own faces were enumerated, and those must survive.
     */
    private static void mergeConnections(Discovery discovery, BlockPos cur, List<BlockPos> conns) {
        List<BlockPos> existing = discovery.connections.get(cur);
        if (existing == null) {
            discovery.connections.put(cur, conns);
            return;
        }
        for (BlockPos c : conns) {
            if (!existing.contains(c)) existing.add(c);
        }
    }

    /**
     * Queue every pipe run connected to a handler's footprint, so all runs sharing a tank/basin land
     * in ONE network — they are hydraulically coupled through the shared reservoir. Only a pipe that
     * actually opens back toward the footprint is followed (not one merely passing by).
     */
    private static void exploreHandlerRuns(Level level, BlockPos handlerPos, Queue<BlockPos> frontier) {
        for (BlockPos block : handlerExtent(level, handlerPos)) {
            for (Direction face : Direction.values()) {
                BlockPos neighbor = block.relative(face);
                if (!level.isLoaded(neighbor)) continue;
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, neighbor);
                if (pipe == null) continue;
                BlockState pipeState = level.getBlockState(neighbor);
                if (FluidPropagator.getPipeConnections(pipeState, pipe).contains(face.getOpposite())) {
                    frontier.add(neighbor.immutable());
                }
            }
        }
    }

    /** The block(s) a handler occupies: a multiblock tank's whole footprint, or just the single block. */
    private static List<BlockPos> handlerExtent(Level level, BlockPos pos) {
        return FluidTankGeometry.footprint(level, pos);
    }

    /**
     * Explore a fluid-conduit block: a fluid-holding node the BFS traverses THROUGH,
     * linking it to adjacent pumps, pipes, and other conduits — so a row of conduits
     * (e.g. chained liquid burners) becomes one connected network the solver equalizes,
     * the way Create's transport used to feed them. Plain reservoirs (tanks, basins)
     * are NOT auto-linked here; they join only through an actual pipe opening, so a
     * tank stacked on a burner does not gravity-drain into it.
     */
    private static void discoverConduit(Level level, BlockPos cur, Discovery discovery, Queue<BlockPos> frontier) {
        discovery.handlers.add(cur);
        List<BlockPos> conns = new ArrayList<>();
        for (Direction face : Direction.values()) {
            BlockPos neighbor = cur.relative(face);
            if (!level.isLoaded(neighbor)) continue;

            if (Pumps.isPump(level, neighbor, level.getBlockState(neighbor))) {
                discovery.pumps.add(neighbor.immutable());
                conns.add(neighbor.immutable());
                frontier.add(neighbor.immutable());
                continue;
            }
            if (isConduit(level, neighbor)) {
                BlockPos conduitPos = neighbor.immutable();
                discovery.handlers.add(conduitPos);
                conns.add(conduitPos);
                backLink(discovery, conduitPos, cur);
                frontier.add(conduitPos);
                continue;
            }
            var neighborPipe = FluidPropagator.getPipe(level, neighbor);
            if (neighborPipe != null) {
                BlockState nState = level.getBlockState(neighbor);
                if (neighborPipe.canHaveFlowToward(nState, face.getOpposite())) {
                    conns.add(neighbor.immutable());
                    frontier.add(neighbor.immutable());
                }
            }
        }
        mergeConnections(discovery, cur, conns);
    }

    /**
     * A run that loops from a node back to ITSELF — a ring main fed by one junction, or a pure
     * pipe ring — cannot be one edge: a self-loop is meaningless to the solver, and dropping it
     * silently lost its cells from the graph (no flow, no settle, invisible to /pipegraph). Split
     * it instead: promote the run's middle cell to a JUNCTION node and record the two halves as
     * parallel edges — the same trick a shut valve uses to wall a run mid-span.
     */
    private static void splitLoop(Level level, List<Node> nodes, Map<BlockPos, Integer> indexOf,
                                  List<Edge> edges, Set<EdgeKey> seenEdgeKeys, int origin,
                                  List<BlockPos> path) {
        int mid = path.size() / 2;
        BlockPos midPos = path.get(mid);
        int midIdx = nodes.size();
        nodes.add(new Node(midIdx, midPos, Node.Kind.JUNCTION, SableCompat.getWorldY(level, midPos),
                null, null, null));
        indexOf.put(midPos, midIdx);
        addEdgeIfNew(edges, seenEdgeKeys, origin, midIdx, path.subList(0, mid));
        addEdgeIfNew(edges, seenEdgeKeys, midIdx, origin, path.subList(mid + 1, path.size()));
    }

    /**
     * Add an edge unless this exact run was already added from its other end. The
     * key includes the run's terminal pipe cells so PARALLEL runs between the same
     * two nodes stay distinct edges (the same physical run walked from either end
     * yields the same key; a different run between the same nodes does not).
     */
    private static void addEdgeIfNew(List<Edge> edges, Set<EdgeKey> seenEdgeKeys,
                                     int nodeA, int nodeB, List<BlockPos> pipes) {
        if (nodeA == nodeB) return;
        if (!seenEdgeKeys.add(EdgeKey.of(nodeA, nodeB, pipes))) return;
        edges.add(new Edge(edges.size(), nodeA, nodeB, List.copyOf(pipes)));
    }

    /** A run's dedup identity: node pair and terminal pipe cells, both order-canonicalized. */
    private record EdgeKey(int lowNode, int highNode, long lowEndCell, long highEndCell) {
        private static final long DIRECT = Long.MIN_VALUE;

        static EdgeKey of(int nodeA, int nodeB, List<BlockPos> pipes) {
            long firstCell = pipes.isEmpty() ? DIRECT : pipes.get(0).asLong();
            long lastCell = pipes.isEmpty() ? DIRECT : pipes.get(pipes.size() - 1).asLong();
            return new EdgeKey(Math.min(nodeA, nodeB), Math.max(nodeA, nodeB),
                    Math.min(firstCell, lastCell), Math.max(firstCell, lastCell));
        }
    }
}
