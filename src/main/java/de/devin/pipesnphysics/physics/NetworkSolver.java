package de.devin.pipesnphysics.physics;

import java.util.*;

/**
 * Solves pressure, flow, and burst state for a pipe network.
 *
 * Algorithm overview:
 * 1. Discover all pressure sources (gravity tanks, pump push/pull)
 * 2. Build a directed flow graph via BFS from each source
 * 3. Propagate pressure through the directed graph in topological order
 *    - Merge junctions: sum incoming pressures
 *    - Split junctions: divide pressure evenly
 *    - Gravity adds pressure on downhill edges, reduces on uphill
 *    - Friction reduces pressure on non-vertical segments
 * 4. For cycles, iterate until convergence
 * 5. Detect bursting pipes
 */
public final class NetworkSolver {

    private final PipeFormulas formulas;

    public NetworkSolver(PipeFormulas formulas) {
        this.formulas = formulas;
    }

    public SolverResult solve(PipeGraph graph) {
        return solve(graph, 1.0f, 1.0f);
    }

    public SolverResult solve(PipeGraph graph, float viscosityMultiplier) {
        return solve(graph, viscosityMultiplier, 1.0f);
    }

    /**
     * Solve the entire network: pressure propagation, flow, and burst detection.
     *
     * @param graph the pipe network graph
     * @param viscosityMultiplier fluid viscosity scaling for friction
     * @param gravityDirection +1 for liquids (flow down), -1 for gases (flow up)
     * @return solver result with pressures, flows, and burst events
     */
    public SolverResult solve(PipeGraph graph, float viscosityMultiplier, float gravityDirection) {
        List<PressureSource> sources = discoverSources(graph, gravityDirection);
        if (sources.isEmpty()) {
            return emptySolverResult(sources);
        }

        DirectedFlowGraph flowGraph = buildFlowGraph(graph, sources, viscosityMultiplier);
        flowGraph.gravityDirection = gravityDirection;
        if (flowGraph.nodeOrder.isEmpty()) {
            return emptySolverResult(sources);
        }

        return buildResult(flowGraph, graph, viscosityMultiplier, sources);
    }

    /**
     * Find all pressure sources in the network.
     * For liquids: sources are endpoints where the handler is above the pipe.
     * For gases: sources are endpoints where the handler is below the pipe.
     */
    List<PressureSource> discoverSources(PipeGraph graph, float gravityDirection) {
        List<PressureSource> sources = new ArrayList<>();
        boolean isGas = gravityDirection < 0;

        for (NetworkEndpoint ep : graph.endpoints()) {
            boolean validSource = isGas ? !ep.isHandlerAbovePipe() : ep.isHandlerAbovePipe();
            if (!validSource) continue;
            float gravity = formulas.gravityDelta(ep.handlerWorldY(), ep.pipeWorldY(), gravityDirection);
            if (gravity > 0) {
                sources.add(new PressureSource(
                        ep.pipeNode(), PressureSource.Kind.GRAVITY, gravity, ep.handlerWorldY()));
            }
        }

        return sources;
    }

    /**
     * Solve with additional external pressure sources (pump push/pull from handler layer).
     */
    public SolverResult solve(PipeGraph graph, float viscosityMultiplier,
                              List<PressureSource> externalSources) {
        return solve(graph, viscosityMultiplier, 1.0f, externalSources);
    }

    public SolverResult solve(PipeGraph graph, float viscosityMultiplier,
                              float gravityDirection, List<PressureSource> externalSources) {
        List<PressureSource> sources = new ArrayList<>(discoverSources(graph, gravityDirection));
        sources.addAll(externalSources);

        if (sources.isEmpty()) {
            return emptySolverResult(sources);
        }

        DirectedFlowGraph flowGraph = buildFlowGraph(graph, sources, viscosityMultiplier);
        flowGraph.gravityDirection = gravityDirection;
        if (flowGraph.nodeOrder.isEmpty()) {
            return emptySolverResult(sources);
        }

        return buildResult(flowGraph, graph, viscosityMultiplier, sources);
    }

    private SolverResult buildResult(DirectedFlowGraph flowGraph, PipeGraph graph,
                                      float viscosityMultiplier, List<PressureSource> sources) {
        Map<NodeId, Float> pressures = propagatePressure(flowGraph, graph, viscosityMultiplier);
        equalizeSegments(flowGraph, pressures);

        Set<NodeId> activePipes = new LinkedHashSet<>();
        Map<NodeId, FlowState> flowStates = new HashMap<>();
        Map<NodeId, Integer> inboundFaces = new HashMap<>();
        Map<NodeId, Set<Integer>> outboundFaces = new HashMap<>();

        for (var entry : pressures.entrySet()) {
            if (entry.getValue() > 0) {
                NodeId nodeId = entry.getKey();
                activePipes.add(nodeId);
                float pressure = entry.getValue();
                float flowRate = formulas.flowFromPressure(pressure);
                int inFace = flowGraph.inboundFace.getOrDefault(nodeId, -1);
                int outFace = pickOutflowFace(flowGraph, nodeId);
                flowStates.put(nodeId, new FlowState(pressure, flowRate, inFace, outFace));

                if (inFace >= 0) inboundFaces.put(nodeId, inFace);

                Set<Integer> outFaces = new HashSet<>();
                for (DirectedEdge edge : flowGraph.outgoing.getOrDefault(nodeId, List.of())) {
                    if (activePipes.contains(edge.to()) || pressures.getOrDefault(edge.to(), 0f) > 0) {
                        outFaces.add(edge.originalEdge().faceIndex());
                    }
                }
                if (!outFaces.isEmpty()) outboundFaces.put(nodeId, outFaces);
            }
        }

        // Second pass: fill outbound faces for nodes whose downstream may not have been processed yet
        for (NodeId nodeId : activePipes) {
            if (outboundFaces.containsKey(nodeId)) continue;
            Set<Integer> outFaces = new HashSet<>();
            for (DirectedEdge edge : flowGraph.outgoing.getOrDefault(nodeId, List.of())) {
                outFaces.add(edge.originalEdge().faceIndex());
            }
            if (!outFaces.isEmpty()) outboundFaces.put(nodeId, outFaces);
        }

        List<BurstEvent> burstEvents = detectBursts(pressures);

        Map<NodeId, PressureBreakdown> breakdowns = buildBreakdowns(
                flowGraph, pressures, graph, viscosityMultiplier);

        return new SolverResult(pressures, flowStates, activePipes, burstEvents,
                breakdowns, sources, inboundFaces, outboundFaces);
    }

    // -- Directed flow graph construction --

    /**
     * Internal representation of the directed flow graph.
     * Edges point in the flow direction (from source toward sinks).
     */
    static final class DirectedFlowGraph {
        final Map<NodeId, List<DirectedEdge>> outgoing = new HashMap<>();
        final Map<NodeId, List<DirectedEdge>> incoming = new HashMap<>();
        final Map<NodeId, Integer> inboundFace = new HashMap<>();
        final Map<NodeId, Float> sourceContributions = new HashMap<>();
        final List<NodeId> nodeOrder = new ArrayList<>();
        final Set<NodeId> cycleNodes = new HashSet<>();
        float gravityDirection = 1.0f;
    }

    record DirectedEdge(NodeId from, NodeId to, PipeEdge originalEdge) {}

    /**
     * Build a directed flow graph by BFS from each source.
     * Flow direction is determined by pressure gradient: flow goes from
     * higher pressure (sources, downhill) to lower pressure (sinks, uphill).
     */
    DirectedFlowGraph buildFlowGraph(PipeGraph graph, List<PressureSource> sources,
                                     float viscosityMultiplier) {
        DirectedFlowGraph flowGraph = new DirectedFlowGraph();
        Set<NodeId> visited = new HashSet<>();
        Queue<NodeId> queue = new ArrayDeque<>();

        for (PressureSource source : sources) {
            NodeId startNode = source.node();
            if (!graph.contains(startNode)) continue;

            flowGraph.sourceContributions.merge(startNode, source.basePressure(), Float::sum);

            if (visited.add(startNode)) {
                queue.add(startNode);
            }
        }

        // BFS from all sources simultaneously
        while (!queue.isEmpty()) {
            NodeId current = queue.poll();

            for (PipeEdge edge : graph.adjacency().getOrDefault(current, List.of())) {
                NodeId neighbor = edge.to();
                PipeNode neighborNode = graph.node(neighbor);
                if (neighborNode == null || !neighborNode.isPipe()) continue;

                if (visited.contains(neighbor)) {
                    // Already visited — check if this is a merge (valid) or a back-edge (cycle).
                    // If the neighbor already has a directed edge TO current, it's a cycle.
                    boolean isBackEdge = flowGraph.outgoing.getOrDefault(neighbor, List.of()).stream()
                            .anyMatch(e -> e.to().equals(current));
                    if (isBackEdge) {
                        flowGraph.cycleNodes.add(neighbor);
                        flowGraph.cycleNodes.add(current);
                    } else {
                        // Merge: add the incoming edge so this node receives pressure from both paths
                        DirectedEdge directedEdge = new DirectedEdge(current, neighbor, edge);
                        flowGraph.outgoing.computeIfAbsent(current, k -> new ArrayList<>()).add(directedEdge);
                        flowGraph.incoming.computeIfAbsent(neighbor, k -> new ArrayList<>()).add(directedEdge);
                    }
                    continue;
                }

                DirectedEdge directedEdge = new DirectedEdge(current, neighbor, edge);
                flowGraph.outgoing.computeIfAbsent(current, k -> new ArrayList<>()).add(directedEdge);
                flowGraph.incoming.computeIfAbsent(neighbor, k -> new ArrayList<>()).add(directedEdge);
                flowGraph.inboundFace.put(neighbor, reverseFace(edge.faceIndex()));

                visited.add(neighbor);
                queue.add(neighbor);
            }
        }

        // Build topological order via Kahn's algorithm
        buildTopologicalOrder(flowGraph, visited);

        return flowGraph;
    }

    private void buildTopologicalOrder(DirectedFlowGraph flowGraph, Set<NodeId> allNodes) {
        Map<NodeId, Integer> inDegree = new HashMap<>();
        for (NodeId node : allNodes) {
            inDegree.put(node, 0);
        }
        for (var edges : flowGraph.incoming.values()) {
            for (DirectedEdge edge : edges) {
                inDegree.merge(edge.to(), 1, Integer::sum);
            }
        }

        Queue<NodeId> zeroIn = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0 && allNodes.contains(entry.getKey())) {
                zeroIn.add(entry.getKey());
            }
        }

        while (!zeroIn.isEmpty()) {
            NodeId node = zeroIn.poll();
            flowGraph.nodeOrder.add(node);
            for (DirectedEdge edge : flowGraph.outgoing.getOrDefault(node, List.of())) {
                int newDeg = inDegree.merge(edge.to(), -1, Integer::sum);
                if (newDeg == 0) {
                    zeroIn.add(edge.to());
                }
            }
        }

        // Nodes not in topological order are part of cycles
        for (NodeId node : allNodes) {
            if (!flowGraph.nodeOrder.contains(node)) {
                flowGraph.cycleNodes.add(node);
                flowGraph.nodeOrder.add(node);
            }
        }
    }

    // -- Pressure propagation --

    private Map<NodeId, Float> propagatePressure(DirectedFlowGraph flowGraph, PipeGraph graph,
                                                  float viscosityMultiplier) {
        Map<NodeId, Float> pressures = new HashMap<>();
        Map<NodeId, Float> edgePressures = new HashMap<>();

        if (flowGraph.cycleNodes.isEmpty()) {
            propagateAcyclic(flowGraph, graph, viscosityMultiplier, pressures, edgePressures);
        } else {
            propagateWithCycles(flowGraph, graph, viscosityMultiplier, pressures, edgePressures);
        }

        return pressures;
    }

    private void propagateAcyclic(DirectedFlowGraph flowGraph, PipeGraph graph,
                                   float viscosityMultiplier,
                                   Map<NodeId, Float> pressures,
                                   Map<NodeId, Float> edgePressures) {
        for (NodeId nodeId : flowGraph.nodeOrder) {
            float nodePressure = computeNodePressure(nodeId, flowGraph, graph,
                    viscosityMultiplier, edgePressures);
            pressures.put(nodeId, nodePressure);
            distributeToOutgoing(nodeId, nodePressure, flowGraph, edgePressures);
        }
    }

    private void propagateWithCycles(DirectedFlowGraph flowGraph, PipeGraph graph,
                                      float viscosityMultiplier,
                                      Map<NodeId, Float> pressures,
                                      Map<NodeId, Float> edgePressures) {
        int maxIterations = formulas.config().maxCycleIterations();
        float epsilon = 0.01f;

        for (int iter = 0; iter < maxIterations; iter++) {
            float maxChange = 0;

            for (NodeId nodeId : flowGraph.nodeOrder) {
                float oldPressure = pressures.getOrDefault(nodeId, 0f);
                float newPressure = computeNodePressure(nodeId, flowGraph, graph,
                        viscosityMultiplier, edgePressures);
                newPressure = Math.min(newPressure, formulas.config().maxPressure());

                pressures.put(nodeId, newPressure);
                distributeToOutgoing(nodeId, newPressure, flowGraph, edgePressures);

                maxChange = Math.max(maxChange, Math.abs(newPressure - oldPressure));
            }

            if (maxChange < epsilon) break;
        }
    }

    private float computeNodePressure(NodeId nodeId, DirectedFlowGraph flowGraph,
                                       PipeGraph graph, float viscosityMultiplier,
                                       Map<NodeId, Float> edgePressures) {
        List<DirectedEdge> incomingEdges = flowGraph.incoming.getOrDefault(nodeId, List.of());
        List<Float> incomingPressures = new ArrayList<>();

        for (DirectedEdge edge : incomingEdges) {
            float upstreamOutgoing = edgePressures.getOrDefault(edge.from(), 0f);
            PipeEdge original = edge.originalEdge();
            float delivered = formulas.edgeDeliveredPressure(
                    upstreamOutgoing,
                    original.fromWorldY(), original.toWorldY(),
                    original.elevationAngleDegrees(), viscosityMultiplier,
                    flowGraph.gravityDirection);
            if (delivered > 0) {
                incomingPressures.add(delivered);
            }
        }

        float merged = formulas.junctionMergePressure(incomingPressures);
        float sourceContribution = flowGraph.sourceContributions.getOrDefault(nodeId, 0f);
        return merged + sourceContribution;
    }

    private void distributeToOutgoing(NodeId nodeId, float nodePressure,
                                       DirectedFlowGraph flowGraph,
                                       Map<NodeId, Float> edgePressures) {
        List<DirectedEdge> outgoing = flowGraph.outgoing.getOrDefault(nodeId, List.of());
        int fanOut = outgoing.size();
        float perEdge = formulas.junctionSplitPressure(nodePressure, fanOut);
        edgePressures.put(nodeId, perEdge);
    }

    // -- Segment equalization --

    /**
     * In a single unbranched pipe segment, flow must be uniform.
     * A segment is a maximal chain of nodes where each node (except the last)
     * has exactly 1 outgoing edge whose target has exactly 1 incoming edge.
     * All nodes in a segment get the exit (last) node's pressure.
     */
    private void equalizeSegments(DirectedFlowGraph flowGraph, Map<NodeId, Float> pressures) {
        Set<NodeId> equalized = new HashSet<>();

        for (NodeId startNode : flowGraph.nodeOrder) {
            if (equalized.contains(startNode)) continue;

            // Try to start a chain from this node
            List<NodeId> chain = new ArrayList<>();
            chain.add(startNode);

            NodeId current = startNode;
            while (true) {
                List<DirectedEdge> out = flowGraph.outgoing.getOrDefault(current, List.of());
                if (out.size() != 1) break;

                NodeId next = out.get(0).to();
                List<DirectedEdge> nextIn = flowGraph.incoming.getOrDefault(next, List.of());
                if (nextIn.size() != 1) break;

                if (equalized.contains(next)) break;
                chain.add(next);
                current = next;
            }

            if (chain.size() < 2) continue;

            NodeId exitNode = chain.get(chain.size() - 1);
            float exitPressure = pressures.getOrDefault(exitNode, 0f);
            for (NodeId node : chain) {
                pressures.put(node, exitPressure);
            }
            equalized.addAll(chain);
        }
    }

    // -- Burst detection --

    private List<BurstEvent> detectBursts(Map<NodeId, Float> pressures) {
        List<BurstEvent> events = new ArrayList<>();
        for (var entry : pressures.entrySet()) {
            float pressure = entry.getValue();
            if (formulas.wouldBurst(pressure)) {
                events.add(new BurstEvent(entry.getKey(), pressure, formulas.config().burstThreshold()));
            }
        }
        return events;
    }

    // -- Breakdown computation --

    private Map<NodeId, PressureBreakdown> buildBreakdowns(DirectedFlowGraph flowGraph,
                                                            Map<NodeId, Float> pressures,
                                                            PipeGraph graph,
                                                            float viscosityMultiplier) {
        Map<NodeId, PressureBreakdown> breakdowns = new HashMap<>();
        Set<NodeId> handled = new HashSet<>();

        for (NodeId startNode : flowGraph.nodeOrder) {
            if (handled.contains(startNode)) continue;
            float net = pressures.getOrDefault(startNode, 0f);
            if (net <= 0) { handled.add(startNode); continue; }

            // Walk the equalized chain from this node
            List<NodeId> chain = new ArrayList<>();
            chain.add(startNode);
            NodeId current = startNode;
            while (true) {
                List<DirectedEdge> out = flowGraph.outgoing.getOrDefault(current, List.of());
                if (out.size() != 1) break;
                NodeId next = out.get(0).to();
                if (flowGraph.incoming.getOrDefault(next, List.of()).size() != 1) break;
                if (handled.contains(next)) break;
                chain.add(next);
                current = next;
            }

            // Accumulate gravity and friction across the entire chain
            float gravityTotal = 0;
            float frictionTotal = 0;
            for (NodeId node : chain) {
                for (DirectedEdge edge : flowGraph.incoming.getOrDefault(node, List.of())) {
                    PipeEdge original = edge.originalEdge();
                    float gravity = formulas.gravityDelta(original.fromWorldY(), original.toWorldY(),
                            flowGraph.gravityDirection);
                    float friction = formulas.segmentFriction(
                            original.elevationAngleDegrees(), viscosityMultiplier);
                    gravityTotal += Math.max(0, gravity);
                    frictionTotal += friction;
                }
            }

            // Source contributions across the chain
            float sourceContrib = 0;
            for (NodeId node : chain) {
                sourceContrib += flowGraph.sourceContributions.getOrDefault(node, 0f);
            }
            gravityTotal += sourceContrib;

            // Merge at the chain entry
            float mergeExtra = 0;
            NodeId entryNode = chain.get(0);
            List<DirectedEdge> entryInEdges = flowGraph.incoming.getOrDefault(entryNode, List.of());
            if (entryInEdges.size() > 1) {
                List<Float> inPressures = new ArrayList<>();
                for (DirectedEdge edge : entryInEdges) {
                    float upstreamOut = pressures.getOrDefault(edge.from(), 0f);
                    int fanOut = flowGraph.outgoing.getOrDefault(edge.from(), List.of()).size();
                    float perEdge = formulas.junctionSplitPressure(upstreamOut, fanOut);
                    PipeEdge original = edge.originalEdge();
                    float delivered = formulas.edgeDeliveredPressure(perEdge,
                            original.fromWorldY(), original.toWorldY(),
                            original.elevationAngleDegrees(), viscosityMultiplier,
                            flowGraph.gravityDirection);
                    inPressures.add(delivered);
                }
                float maxSingle = inPressures.stream().max(Float::compareTo).orElse(0f);
                float sum = (float) inPressures.stream().mapToDouble(f -> f).sum();
                mergeExtra = sum - maxSingle;
            }

            // Split at the chain exit
            float splitPenalty = 0;
            NodeId exitNode = chain.get(chain.size() - 1);
            List<DirectedEdge> exitOutEdges = flowGraph.outgoing.getOrDefault(exitNode, List.of());
            if (exitOutEdges.size() > 1) {
                splitPenalty = net - (net / exitOutEdges.size());
            }

            boolean capped = net >= formulas.config().maxPressure();
            boolean bursting = formulas.wouldBurst(net);

            PressureBreakdown bd = new PressureBreakdown(
                    gravityTotal, 0, mergeExtra, splitPenalty,
                    frictionTotal, net, capped, bursting,
                    EdgePhase.FLOWING, 1.0f, 0, 0, 0, 0);

            for (NodeId node : chain) {
                breakdowns.put(node, bd);
            }
            handled.addAll(chain);
        }

        return breakdowns;
    }

    // -- Helpers --

    private int pickOutflowFace(DirectedFlowGraph flowGraph, NodeId nodeId) {
        List<DirectedEdge> outgoing = flowGraph.outgoing.getOrDefault(nodeId, List.of());
        if (outgoing.isEmpty()) return -1;
        return outgoing.get(0).originalEdge().faceIndex();
    }

    private static int reverseFace(int faceIndex) {
        return (faceIndex % 2 == 0) ? faceIndex + 1 : faceIndex - 1;
    }

    private SolverResult emptySolverResult(List<PressureSource> sources) {
        return new SolverResult(
                Map.of(), Map.of(), Set.of(), List.of(), Map.of(), sources, Map.of(), Map.of());
    }
}
