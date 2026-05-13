package de.devin.pipesnphysics.physics;

import java.util.*;

/**
 * Pure graph algorithms for pipe network physics.
 * Operates on pre-resolved {@link PipeGraph} instances with no Minecraft dependencies.
 *
 * <p>This class deduplicates the BFS + friction + pressure logic previously
 * spread across GravityFlowHandler, PumpBlockEntityMixin, and PipeGoggleInfoMixin.</p>
 */
public final class NetworkSolver {

    private static final double HORIZONTAL_THRESHOLD = 0.1;

    private final PipeFormulas formulas;

    public NetworkSolver(PipeFormulas formulas) {
        this.formulas = formulas;
    }

    /**
     * Compute gravity-driven flow for a pipe network.
     *
     * @return the flow result, or {@code null} if no valid gravity flow exists
     */
    public GravityFlowResult solveGravityFlow(PipeGraph graph) {
        if (graph.hasActivePump()) return null;
        if (graph.endpoints().size() < 2) return null;

        // 1. Find source: highest endpoint where handler is above its pipe
        NetworkEndpoint source = null;
        double sourceWorldY = Double.NEGATIVE_INFINITY;
        for (NetworkEndpoint ep : graph.endpoints()) {
            if (!ep.isHandlerAbovePipe()) continue;
            if (ep.handlerWorldY() > sourceWorldY) {
                source = ep;
                sourceWorldY = ep.handlerWorldY();
            }
        }
        if (source == null) return null;

        // 2. BFS from source accumulating friction
        FrictionBfsResult bfs = frictionBfs(graph, source.pipeNode(), source.faceIndex());

        // 3. Gravity preference: prune horizontal exits when downward exists
        applyGravityPreference(bfs.outboundEdges);

        // 4. Validate sinks: keep endpoints where gravity pressure > 0
        List<NetworkEndpoint> validSinks = new ArrayList<>();
        for (NetworkEndpoint ep : graph.endpoints()) {
            if (ep == source) continue;
            Float friction = bfs.accumulatedFriction.get(ep.pipeNode());
            if (friction == null) continue;
            if (formulas.gravityPressure(sourceWorldY, ep.pipeWorldY(), friction) > 0) {
                validSinks.add(ep);
            }
        }
        if (validSinks.isEmpty()) return null;

        // 5. Prune dead branches
        Set<NodeId> activePipes = pruneDeadBranches(validSinks, bfs.parentNode, source.pipeNode());

        // 6. Compute sink pressures and propagate backward
        Map<NodeId, Float> sinkPressures = new HashMap<>();
        for (NetworkEndpoint sink : validSinks) {
            float friction = bfs.accumulatedFriction.getOrDefault(sink.pipeNode(), 0f);
            float pressure = formulas.gravityPressure(sourceWorldY, sink.pipeWorldY(), friction);
            if (pressure > 0) {
                sinkPressures.merge(sink.pipeNode(), pressure, Math::max);
            }
        }

        Map<NodeId, Float> pipePressures = propagateSinkPressures(
                sinkPressures, bfs.parentNode, source.pipeNode());

        // Build outbound face index map
        Map<NodeId, Set<Integer>> outboundFaceIndices = new HashMap<>();
        for (var entry : bfs.outboundEdges.entrySet()) {
            Set<Integer> faces = new HashSet<>();
            for (PipeEdge edge : entry.getValue()) faces.add(edge.faceIndex());
            outboundFaceIndices.put(entry.getKey(), faces);
        }

        // 7. Build flow states for each active pipe
        Map<NodeId, FlowState> flowStates = new HashMap<>();
        for (NodeId nodeId : activePipes) {
            float pressure = pipePressures.getOrDefault(nodeId, 0f);
            if (pressure <= 0) continue;
            float flowRate = pressure / 2f;
            int inFace = bfs.inboundFaceIndex.getOrDefault(nodeId, -1);
            Set<Integer> outFaces = outboundFaceIndices.getOrDefault(nodeId, Set.of());
            int outFace = outFaces.isEmpty() ? -1 : outFaces.iterator().next();
            flowStates.put(nodeId, new FlowState(flowRate, inFace, outFace));
        }

        return new GravityFlowResult(
                source, sourceWorldY, validSinks, pipePressures,
                bfs.parentNode, bfs.inboundFaceIndex, outboundFaceIndices,
                activePipes, validSinks, flowStates
        );
    }

    /**
     * Compute which pipes a pump can reach, accounting for friction and gravity.
     */
    public PumpFlowResult solvePumpReach(PipeGraph graph, NodeId startNode, int startFace,
                                         float pumpBase, double pumpWorldY, int maxHopDistance) {
        Map<NodeId, Float> accFriction = new HashMap<>();
        Map<NodeId, Float> nodePressures = new HashMap<>();
        Map<NodeId, Integer> hopCounts = new HashMap<>();
        Set<NodeId> reachable = new HashSet<>();

        accFriction.put(startNode, 0f);
        hopCounts.put(startNode, 1);

        Queue<NodeId> frontier = new ArrayDeque<>();
        Set<NodeId> visited = new HashSet<>();
        frontier.add(startNode);

        while (!frontier.isEmpty()) {
            NodeId current = frontier.poll();
            if (!visited.add(current)) continue;

            int currentHops = hopCounts.getOrDefault(current, 0);
            float currentFriction = accFriction.getOrDefault(current, 0f);

            for (PipeEdge edge : graph.adjacency().getOrDefault(current, List.of())) {
                NodeId neighbor = edge.to();
                if (visited.contains(neighbor)) continue;
                PipeNode neighborNode = graph.node(neighbor);
                if (neighborNode == null || !neighborNode.isPipe()) continue;

                float segFric = formulas.segmentFriction(edge.elevationAngleDegrees());
                float nextFriction = currentFriction + segFric;
                float pressure = formulas.pumpPressure(pumpBase, pumpWorldY, edge.toWorldY(), nextFriction);

                int nextHops = currentHops + 1;
                if (pressure <= 0 || nextHops >= maxHopDistance) continue;

                accFriction.put(neighbor, nextFriction);
                nodePressures.put(neighbor, pressure);
                hopCounts.put(neighbor, nextHops);
                reachable.add(neighbor);
                frontier.add(neighbor);
            }
        }

        return new PumpFlowResult(accFriction, nodePressures, hopCounts, reachable);
    }

    /**
     * Compute pressure breakdown for the gravity network containing the target pipe.
     * The breakdown is computed at the sink that determines the actual flow rate —
     * every pipe in a series carries the same flow, so the sink numbers are what matter.
     *
     * @return the breakdown, or {@code null} if this pipe is not in a gravity flow path
     */
    public PressureBreakdown computeBreakdownAt(PipeGraph graph, NodeId targetPipe) {
        GravityFlowResult flow = solveGravityFlow(graph);
        if (flow == null) return null;

        // Check this pipe is actually part of the active flow
        Float appliedPressure = flow.pipePressures().get(targetPipe);
        if (appliedPressure == null || appliedPressure <= 0) return null;

        // Compute breakdown at the sink that determines the flow rate
        FrictionBfsResult bfs = frictionBfs(graph, flow.source().pipeNode(), flow.source().faceIndex());

        // Find the sink with the highest friction (the bottleneck that sets the flow)
        NetworkEndpoint bottleneckSink = null;
        float bottleneckPressure = Float.MAX_VALUE;
        for (NetworkEndpoint sink : flow.validSinks()) {
            Float sinkFriction = bfs.accumulatedFriction.get(sink.pipeNode());
            if (sinkFriction == null) continue;
            float p = formulas.gravityPressure(flow.sourceWorldY(), sink.pipeWorldY(), sinkFriction);
            if (p < bottleneckPressure) {
                bottleneckPressure = p;
                bottleneckSink = sink;
            }
        }
        if (bottleneckSink == null) return null;

        float sinkFriction = bfs.accumulatedFriction.getOrDefault(bottleneckSink.pipeNode(), 0f);
        float head = (float) (flow.sourceWorldY() - bottleneckSink.pipeWorldY()) * formulas.config().gravityPerBlock();
        float unclamped = head - sinkFriction;
        float net = formulas.gravityPressure(flow.sourceWorldY(), bottleneckSink.pipeWorldY(), sinkFriction);
        boolean capped = unclamped > formulas.config().maxPressure();

        return new PressureBreakdown(head, sinkFriction, net, capped);
    }

    private record FrictionBfsResult(
            Map<NodeId, Float> accumulatedFriction,
            Map<NodeId, NodeId> parentNode,
            Map<NodeId, Integer> inboundFaceIndex,
            Map<NodeId, Set<PipeEdge>> outboundEdges
    ) {}

    private FrictionBfsResult frictionBfs(PipeGraph graph, NodeId sourceNode, int sourceFaceIndex) {
        Map<NodeId, Float> friction = new HashMap<>();
        Map<NodeId, NodeId> parent = new LinkedHashMap<>();
        Map<NodeId, Integer> inboundFace = new HashMap<>();
        Map<NodeId, Set<PipeEdge>> outbound = new HashMap<>();

        Queue<NodeId> queue = new ArrayDeque<>();
        queue.add(sourceNode);
        friction.put(sourceNode, 0f);
        inboundFace.put(sourceNode, sourceFaceIndex);

        while (!queue.isEmpty()) {
            NodeId current = queue.poll();
            for (PipeEdge edge : graph.adjacency().getOrDefault(current, List.of())) {
                NodeId neighbor = edge.to();
                PipeNode neighborNode = graph.node(neighbor);
                if (neighborNode == null || !neighborNode.isPipe()) continue;
                if (parent.containsKey(neighbor) || neighbor.equals(sourceNode)) continue;

                parent.put(neighbor, current);
                inboundFace.put(neighbor, reverseFace(edge.faceIndex()));
                outbound.computeIfAbsent(current, id -> new HashSet<>()).add(edge);

                float parentFric = friction.getOrDefault(current, 0f);
                float segFric = formulas.segmentFriction(edge.elevationAngleDegrees());
                friction.put(neighbor, parentFric + segFric);

                queue.add(neighbor);
            }
        }

        return new FrictionBfsResult(friction, parent, inboundFace, outbound);
    }

    private void applyGravityPreference(Map<NodeId, Set<PipeEdge>> outbound) {
        for (var entry : outbound.entrySet()) {
            Set<PipeEdge> edges = entry.getValue();
            if (edges.size() <= 1) continue;
            boolean hasDownward = edges.stream()
                    .anyMatch(edge -> edge.toWorldY() < edge.fromWorldY() - HORIZONTAL_THRESHOLD);
            if (hasDownward) {
                edges.removeIf(edge -> Math.abs(edge.fromWorldY() - edge.toWorldY()) < HORIZONTAL_THRESHOLD);
            }
        }
    }

    private Set<NodeId> pruneDeadBranches(List<NetworkEndpoint> validSinks,
                                         Map<NodeId, NodeId> parentNode, NodeId sourceNode) {
        Set<NodeId> active = new HashSet<>();
        for (NetworkEndpoint sink : validSinks) {
            NodeId trace = sink.pipeNode();
            while (trace != null && active.add(trace)) {
                if (trace.equals(sourceNode)) break;
                trace = parentNode.get(trace);
            }
        }
        return active;
    }

    private Map<NodeId, Float> propagateSinkPressures(Map<NodeId, Float> sinkPressures,
                                                      Map<NodeId, NodeId> parentNode,
                                                      NodeId sourceNode) {
        Map<NodeId, Float> result = new LinkedHashMap<>();
        for (var entry : sinkPressures.entrySet()) {
            float pressure = entry.getValue();
            NodeId trace = entry.getKey();
            while (trace != null) {
                float existing = result.getOrDefault(trace, 0f);
                if (pressure > existing) {
                    result.put(trace, pressure);
                } else {
                    break;
                }
                if (trace.equals(sourceNode)) break;
                trace = parentNode.get(trace);
            }
        }
        return result;
    }

    /** Get the world Y of a node from the graph's node map. */
    private double nodeWorldY(PipeGraph graph, NodeId node) {
        PipeNode pipeNode = graph.node(node);
        if (pipeNode != null) return pipeNode.worldY();
        for (NetworkEndpoint ep : graph.endpoints()) {
            if (ep.pipeNode().equals(node)) return ep.pipeWorldY();
        }
        return 0;
    }

    /** Reverse a face index (Direction ordinals are paired: 0/1, 2/3, 4/5). */
    private static int reverseFace(int faceIndex) {
        return (faceIndex % 2 == 0) ? faceIndex + 1 : faceIndex - 1;
    }
}
