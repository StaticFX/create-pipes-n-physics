package de.devin.pipesnphysics.physics;

import java.util.*;

public final class NetworkSolver {

    private static final double HORIZONTAL_THRESHOLD = 0.1;

    private final PipeFormulas formulas;

    public NetworkSolver(PipeFormulas formulas) {
        this.formulas = formulas;
    }

    public GravityFlowResult solveGravityFlow(PipeGraph graph) {
        return solveGravityFlow(graph, 1.0f);
    }

    public GravityFlowResult solveGravityFlow(PipeGraph graph, float viscosityMultiplier) {
        if (graph.hasActivePump()) return null;
        if (graph.endpoints().size() < 2) return null;

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

        FrictionBfsResult bfs = frictionBfs(graph, source.pipeNode(), source.faceIndex(), viscosityMultiplier);
        applyGravityPreference(bfs.outboundEdges);

        List<NetworkEndpoint> validSinks = new ArrayList<>();
        for (NetworkEndpoint ep : graph.endpoints()) {
            if (ep == source) continue;
            Float friction = bfs.accumulatedFriction.get(ep.pipeNode());
            if (friction == null) continue;
            if (formulas.gravityPressure(sourceWorldY, ep.pipeWorldY(), friction) > 0) {
                validSinks.add(ep);
            }
        }

        Map<NodeId, Float> pipePressures;
        Set<NodeId> activePipes;

        if (!validSinks.isEmpty()) {
            activePipes = pruneDeadBranches(validSinks, bfs.parentNode, source.pipeNode());

            Map<NodeId, Float> sinkPressures = new HashMap<>();
            for (NetworkEndpoint sink : validSinks) {
                float friction = bfs.accumulatedFriction.getOrDefault(sink.pipeNode(), 0f);
                float pressure = formulas.gravityPressure(sourceWorldY, sink.pipeWorldY(), friction);
                if (pressure > 0) {
                    sinkPressures.merge(sink.pipeNode(), pressure, Math::max);
                }
            }
            pipePressures = propagateSinkPressures(sinkPressures, bfs.parentNode, source.pipeNode());
            enforceFlowConservation(pipePressures, bfs.outboundEdges, source.pipeNode(), activePipes);
        } else {
            pipePressures = new LinkedHashMap<>();
            activePipes = new HashSet<>();
            for (var entry : bfs.accumulatedFriction.entrySet()) {
                NodeId nodeId = entry.getKey();
                float friction = entry.getValue();
                PipeNode node = graph.node(nodeId);
                if (node == null || !node.isPipe()) continue;
                float pressure = formulas.gravityPressure(sourceWorldY, node.worldY(), friction);
                if (pressure > 0) {
                    pipePressures.put(nodeId, pressure);
                    activePipes.add(nodeId);
                }
            }
            if (activePipes.isEmpty()) return null;
        }

        Map<NodeId, Set<Integer>> outboundFaceIndices = new HashMap<>();
        for (var entry : bfs.outboundEdges.entrySet()) {
            Set<Integer> faces = new HashSet<>();
            for (PipeEdge edge : entry.getValue()) faces.add(edge.faceIndex());
            outboundFaceIndices.put(entry.getKey(), faces);
        }

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

    public PumpFlowResult solvePumpReach(PipeGraph graph, NodeId startNode, int startFace,
                                         float pumpBase, double pumpWorldY, int maxHopDistance) {
        Map<NodeId, Float> accFriction = new HashMap<>();
        Map<NodeId, Float> nodePressures = new HashMap<>();
        Map<NodeId, Integer> hopCounts = new HashMap<>();
        Map<NodeId, Double> pathPeakY = new HashMap<>();
        Set<NodeId> reachable = new HashSet<>();

        accFriction.put(startNode, 0f);
        hopCounts.put(startNode, 1);
        PipeNode startPipeNode = graph.node(startNode);
        pathPeakY.put(startNode, startPipeNode != null ? Math.max(startPipeNode.worldY(), pumpWorldY) : pumpWorldY);

        Queue<NodeId> frontier = new ArrayDeque<>();
        Set<NodeId> visited = new HashSet<>();
        frontier.add(startNode);

        while (!frontier.isEmpty()) {
            NodeId current = frontier.poll();
            if (!visited.add(current)) continue;

            int currentHops = hopCounts.getOrDefault(current, 0);
            float currentFriction = accFriction.getOrDefault(current, 0f);
            double currentPeakY = pathPeakY.getOrDefault(current, pumpWorldY);

            for (PipeEdge edge : graph.adjacency().getOrDefault(current, List.of())) {
                NodeId neighbor = edge.to();
                if (visited.contains(neighbor)) continue;
                PipeNode neighborNode = graph.node(neighbor);
                if (neighborNode == null || !neighborNode.isPipe()) continue;

                float segFric = formulas.segmentFriction(edge.elevationAngleDegrees());
                float nextFriction = currentFriction + segFric;

                double peakY = Math.max(currentPeakY, edge.toWorldY());
                float reachPressure = formulas.pumpPressure(pumpBase, pumpWorldY, peakY, nextFriction);

                int nextHops = currentHops + 1;
                if (reachPressure <= 0 || nextHops >= maxHopDistance) continue;

                float pressure = formulas.pumpPressure(pumpBase, pumpWorldY, edge.toWorldY(), nextFriction);
                accFriction.put(neighbor, nextFriction);
                nodePressures.put(neighbor, pressure);
                hopCounts.put(neighbor, nextHops);
                pathPeakY.put(neighbor, peakY);
                reachable.add(neighbor);
                frontier.add(neighbor);
            }
        }

        return new PumpFlowResult(accFriction, nodePressures, hopCounts, reachable);
    }

    public Map<NodeId, PressureBreakdown> computeAllBreakdowns(PipeGraph graph, GravityFlowResult flow) {
        return computeAllBreakdowns(graph, flow, 1.0f);
    }

    public Map<NodeId, PressureBreakdown> computeAllBreakdowns(PipeGraph graph, GravityFlowResult flow, float viscosityMultiplier) {
        Map<NodeId, PressureBreakdown> result = new HashMap<>();
        if (flow == null) return result;

        FrictionBfsResult bfs = frictionBfs(graph, flow.source().pipeNode(), flow.source().faceIndex(), viscosityMultiplier);

        if (!flow.validSinks().isEmpty()) {
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
            if (bottleneckSink == null) return result;

            float sinkFriction = bfs.accumulatedFriction.getOrDefault(bottleneckSink.pipeNode(), 0f);
            float head = (float) (flow.sourceWorldY() - bottleneckSink.pipeWorldY()) * formulas.config().gravityPerBlock();
            float unclamped = head - sinkFriction;
            float net = formulas.gravityPressure(flow.sourceWorldY(), bottleneckSink.pipeWorldY(), sinkFriction);
            boolean capped = unclamped > formulas.config().maxPressure();
            for (NodeId nodeId : flow.activePipes()) {
                float pipePressure = flow.pipePressures().getOrDefault(nodeId, 0f);
                if (pipePressure > 0) {
                    result.put(nodeId, new PressureBreakdown(head, sinkFriction, net, capped, pipePressure));
                }
            }
        } else {
            NodeId sourcePipeId = flow.source().pipeNode();
            PipeNode sourceNode = graph.node(sourcePipeId);
            if (sourceNode != null) {
                float sourceFriction = bfs.accumulatedFriction.getOrDefault(sourcePipeId, 0f);
                float head = (float) (flow.sourceWorldY() - sourceNode.worldY()) * formulas.config().gravityPerBlock();
                float unclamped = head - sourceFriction;
                float net = formulas.gravityPressure(flow.sourceWorldY(), sourceNode.worldY(), sourceFriction);
                boolean capped = unclamped > formulas.config().maxPressure();
                for (NodeId nodeId : flow.activePipes()) {
                    float pipePressure = flow.pipePressures().getOrDefault(nodeId, 0f);
                    if (pipePressure > 0) {
                        result.put(nodeId, new PressureBreakdown(head, sourceFriction, net, capped, pipePressure));
                    }
                }
            }
        }

        return result;
    }

    private record FrictionBfsResult(
            Map<NodeId, Float> accumulatedFriction,
            Map<NodeId, NodeId> parentNode,
            Map<NodeId, Integer> inboundFaceIndex,
            Map<NodeId, Set<PipeEdge>> outboundEdges
    ) {}

    private FrictionBfsResult frictionBfs(PipeGraph graph, NodeId sourceNode, int sourceFaceIndex) {
        return frictionBfs(graph, sourceNode, sourceFaceIndex, 1.0f);
    }

    private FrictionBfsResult frictionBfs(PipeGraph graph, NodeId sourceNode, int sourceFaceIndex, float viscosityMultiplier) {
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
                float segFric = formulas.segmentFriction(edge.elevationAngleDegrees(), viscosityMultiplier);
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

    private void enforceFlowConservation(Map<NodeId, Float> pipePressures,
                                          Map<NodeId, Set<PipeEdge>> outboundEdges,
                                          NodeId sourceNode, Set<NodeId> activePipes) {
        // Build children map (only active pipes)
        Map<NodeId, List<NodeId>> children = new HashMap<>();
        for (var entry : outboundEdges.entrySet()) {
            NodeId parent = entry.getKey();
            if (!activePipes.contains(parent) && !parent.equals(sourceNode)) continue;
            List<NodeId> kids = new ArrayList<>();
            for (PipeEdge edge : entry.getValue()) {
                if (activePipes.contains(edge.to())) kids.add(edge.to());
            }
            if (!kids.isEmpty()) children.put(parent, kids);
        }

        // Bottom-up: compute subtree flow demand for each node
        Map<NodeId, Float> subtreeDemand = new HashMap<>();
        computeSubtreeDemand(sourceNode, children, pipePressures, subtreeDemand);

        // Top-down: enforce conservation at junctions
        applyFlowBudget(sourceNode, 1.0f, children, pipePressures, subtreeDemand);
    }

    private float computeSubtreeDemand(NodeId node, Map<NodeId, List<NodeId>> children,
                                        Map<NodeId, Float> pipePressures,
                                        Map<NodeId, Float> subtreeDemand) {
        List<NodeId> kids = children.get(node);
        float nodeFlow = pipePressures.getOrDefault(node, 0f) / 2f;

        if (kids == null || kids.isEmpty()) {
            subtreeDemand.put(node, nodeFlow);
            return nodeFlow;
        }

        float maxChildDemand = 0;
        for (NodeId child : kids) {
            float childDemand = computeSubtreeDemand(child, children, pipePressures, subtreeDemand);
            maxChildDemand = Math.max(maxChildDemand, childDemand);
        }
        subtreeDemand.put(node, maxChildDemand);
        return maxChildDemand;
    }

    private void applyFlowBudget(NodeId node, float scaleFactor,
                                  Map<NodeId, List<NodeId>> children,
                                  Map<NodeId, Float> pipePressures,
                                  Map<NodeId, Float> subtreeDemand) {
        if (scaleFactor < 1.0f) {
            Float pressure = pipePressures.get(node);
            if (pressure != null) {
                pipePressures.put(node, pressure * scaleFactor);
            }
        }

        List<NodeId> kids = children.get(node);
        if (kids == null || kids.size() <= 1) {
            // Single path — just propagate scale factor
            if (kids != null && !kids.isEmpty()) {
                applyFlowBudget(kids.get(0), scaleFactor, children, pipePressures, subtreeDemand);
            }
            return;
        }

        // Junction: split flow proportionally by demand
        float budget = pipePressures.getOrDefault(node, 0f) / 2f;
        float totalDemand = 0;
        for (NodeId child : kids) {
            totalDemand += subtreeDemand.getOrDefault(child, 0f);
        }

        for (NodeId child : kids) {
            float childDemand = subtreeDemand.getOrDefault(child, 0f);
            float childScale;
            if (totalDemand <= budget || totalDemand <= 0) {
                childScale = scaleFactor;
            } else {
                childScale = scaleFactor * (budget / totalDemand);
            }
            applyFlowBudget(child, childScale, children, pipePressures, subtreeDemand);
        }
    }

    private static int reverseFace(int faceIndex) {
        return (faceIndex % 2 == 0) ? faceIndex + 1 : faceIndex - 1;
    }
}
