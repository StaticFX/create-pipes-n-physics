package de.devin.pipesnphysics.physics;

import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Tick-based fluid simulation over a contracted pipe network.
 * Direction comes from potential difference, not stored state.
 *
 * Per-tick loop:
 * 1. Refresh static pressures if topology changed
 * 2. Compute flow rate per edge from potential difference
 * 3. Advance fluid fronts along edges
 * 4. Resolve nodes (conserve volume, detect collisions)
 * 5. Handle boundary nodes (source injection, sink consumption)
 */
public final class FluidSimulator {

    private final SimConfig config;

    public FluidSimulator(SimConfig config) {
        this.config = config;
    }

    public SimConfig config() {
        return config;
    }

    /**
     * Run one simulation tick on the network.
     *
     * @param net the fluid network (mutated in place)
     * @param fluids registry of known fluid types
     * @return result with flow rates, bursts, and collisions
     */
    public SimResult tick(FluidNetwork net, Map<String, SimFluid> fluids) {
        if (net.isDirty()) {
            net.clearDirty();
        }

        List<BurstEvent> bursts = new ArrayList<>();
        List<CollisionEvent> collisions = new ArrayList<>();

        computeFlowRates(net, fluids);
        advanceFluid(net);
        List<CollisionEvent> edgeCollisions = detectEdgeCollisions(net);
        collisions.addAll(edgeCollisions);
        List<CollisionEvent> nodeCollisions = resolveNodes(net, fluids);
        collisions.addAll(nodeCollisions);

        // Compute potentials for display
        Map<NodeId, Float> potentials = new HashMap<>();
        for (var entry : net.nodes().entrySet()) {
            SimNode node = entry.getValue();
            // Use the highest-magnitude potential from any incident edge
            float maxPhi = node.staticPressure();
            for (int edgeIdx : net.edgesAt(entry.getKey())) {
                SimEdge edge = net.edges().get(edgeIdx);
                String fluidId = edge.primaryFluid();
                if (fluidId == null) continue;
                SimFluid f = fluids.get(fluidId);
                if (f == null) continue;
                float phi = node.staticPressure()
                        + f.phase().sign() * f.density() * config.G() * (float) node.elevation();
                if (Math.abs(phi) > Math.abs(maxPhi)) maxPhi = phi;
            }
            potentials.put(entry.getKey(), maxPhi);
            net.setPotential(entry.getKey(), maxPhi);
        }

        // Burst detection: check flow magnitude against threshold
        for (int i = 0; i < net.edges().size(); i++) {
            float absFlow = Math.abs(net.flowRate(i));
            if (absFlow > config.burstThreshold()) {
                SimEdge edge = net.edges().get(i);
                BlockPos pos = edge.pipePositions().isEmpty() ? BlockPos.ZERO : edge.pipePositions().get(0);
                bursts.add(new BurstEvent(edge.a(), absFlow, config.burstThreshold()));
            }
        }

        return new SimResult(
                Arrays.copyOf(net.flowRates(), net.flowRates().length),
                bursts, collisions, potentials);
    }

    private void computeFlowRates(FluidNetwork net, Map<String, SimFluid> fluids) {
        for (int i = 0; i < net.edges().size(); i++) {
            SimEdge edge = net.edges().get(i);
            String fluidId = edge.primaryFluid();

            if (fluidId == null) {
                net.setFlowRate(i, 0);
                continue;
            }

            SimFluid fluid = fluids.get(fluidId);
            if (fluid == null) {
                net.setFlowRate(i, 0);
                continue;
            }

            SimNode nodeA = net.node(edge.a());
            SimNode nodeB = net.node(edge.b());

            float phiA = nodeA.staticPressure()
                    + fluid.phase().sign() * fluid.density() * config.G() * (float) nodeA.elevation();
            float phiB = nodeB.staticPressure()
                    + fluid.phase().sign() * fluid.density() * config.G() * (float) nodeB.elevation();

            float deltaPhi = phiA - phiB;

            if (Math.abs(deltaPhi) <= config.EPS()) {
                net.setFlowRate(i, 0);
                continue;
            }

            // Friction reduces the effective potential difference, not the flow
            float effectiveDelta = Math.abs(deltaPhi) - edge.resistance();
            if (effectiveDelta <= 0) {
                net.setFlowRate(i, 0);
                continue;
            }

            float Q = Math.clamp(config.conductance() * effectiveDelta, 0, config.maxFlow());
            Q = Math.min(Q, edge.totalFill());
            net.setFlowRate(i, Math.signum(deltaPhi) * Q);
        }
    }

    private void advanceFluid(FluidNetwork net) {
        for (int i = 0; i < net.edges().size(); i++) {
            float rate = net.flowRate(i);
            if (rate == 0) continue;

            SimEdge edge = net.edges().get(i);
            int amount = (int) Math.abs(rate);
            if (amount <= 0) continue;

            if (rate > 0) {
                // Flow a → b: drain from a-side, space opens at a-side
                edge.drainFromA(amount);
            } else {
                // Flow b → a: drain from b-side, space opens at b-side
                edge.drainFromB(amount);
            }
        }
    }

    private List<CollisionEvent> detectEdgeCollisions(FluidNetwork net) {
        List<CollisionEvent> collisions = new ArrayList<>();
        for (SimEdge edge : net.edges()) {
            List<FluidFront> col = edge.column();
            for (int i = 0; i < col.size() - 1; i++) {
                FluidFront a = col.get(i);
                FluidFront b = col.get(i + 1);
                if (!a.fluidId().equals(b.fluidId())) {
                    // Collision at the boundary between fronts
                    int filledBefore = 0;
                    for (int j = 0; j <= i; j++) filledBefore += col.get(j).amount();
                    float fraction = edge.capacity() > 0
                            ? (float) filledBefore / edge.capacity()
                            : 0.5f;
                    collisions.add(new CollisionEvent(
                            edge.positionAt(fraction), a.fluidId(), b.fluidId()));
                }
            }
        }
        return collisions;
    }

    private List<CollisionEvent> resolveNodes(FluidNetwork net, Map<String, SimFluid> fluids) {
        List<CollisionEvent> collisions = new ArrayList<>();

        for (var entry : net.nodes().entrySet()) {
            NodeId nodeId = entry.getKey();
            SimNode node = entry.getValue();

            // Collect what's arriving at this node from each edge
            Map<String, Integer> arriving = new HashMap<>();

            for (int edgeIdx : net.edgesAt(nodeId)) {
                SimEdge edge = net.edges().get(edgeIdx);
                float rate = net.flowRate(edgeIdx);
                if (rate == 0) continue;

                boolean flowsTowardNode =
                        (rate > 0 && edge.b().equals(nodeId)) ||
                        (rate < 0 && edge.a().equals(nodeId));

                if (flowsTowardNode) {
                    String fluidId = rate > 0 ? edge.fluidAtB() : edge.fluidAtA();
                    if (fluidId != null) {
                        arriving.merge(fluidId, (int) Math.abs(rate), Integer::sum);
                    }
                }
            }

            // Junction collision: multiple different fluids arriving
            if (arriving.size() > 1) {
                List<String> fluidIds = new ArrayList<>(arriving.keySet());
                for (int i = 0; i < fluidIds.size() - 1; i++) {
                    for (int j = i + 1; j < fluidIds.size(); j++) {
                        // Find a position for the collision
                        BlockPos pos = BlockPos.ZERO;
                        for (int edgeIdx : net.edgesAt(nodeId)) {
                            SimEdge edge = net.edges().get(edgeIdx);
                            if (!edge.pipePositions().isEmpty()) {
                                pos = edge.a().equals(nodeId)
                                        ? edge.pipePositions().get(0)
                                        : edge.pipePositions().get(edge.pipePositions().size() - 1);
                                break;
                            }
                        }
                        collisions.add(new CollisionEvent(pos, fluidIds.get(i), fluidIds.get(j)));
                    }
                }
            }

            // Distribute arriving fluid to outgoing edges
            if (arriving.isEmpty()) continue;

            // Find edges that want to pull fluid away from this node
            List<Integer> outgoingEdges = new ArrayList<>();
            for (int edgeIdx : net.edgesAt(nodeId)) {
                float rate = net.flowRate(edgeIdx);
                SimEdge edge = net.edges().get(edgeIdx);
                boolean flowsAwayFromNode =
                        (rate > 0 && edge.a().equals(nodeId)) ||
                        (rate < 0 && edge.b().equals(nodeId));
                if (flowsAwayFromNode && edge.totalFill() < edge.capacity()) {
                    outgoingEdges.add(edgeIdx);
                }
            }

            if (outgoingEdges.isEmpty()) continue;

            // Push the dominant fluid into outgoing edges
            String dominantFluid = arriving.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (dominantFluid == null) continue;

            int totalAvailable = arriving.getOrDefault(dominantFluid, 0);
            int perEdge = Math.max(1, totalAvailable / outgoingEdges.size());

            for (int edgeIdx : outgoingEdges) {
                SimEdge edge = net.edges().get(edgeIdx);
                float rate = net.flowRate(edgeIdx);
                int space = edge.capacity() - edge.totalFill();
                int push = Math.min(perEdge, space);
                if (push <= 0) continue;

                if (rate > 0 && edge.a().equals(nodeId)) {
                    edge.pushFromA(dominantFluid, push);
                } else if (rate < 0 && edge.b().equals(nodeId)) {
                    edge.pushFromB(dominantFluid, push);
                }
            }
        }

        return collisions;
    }
}
