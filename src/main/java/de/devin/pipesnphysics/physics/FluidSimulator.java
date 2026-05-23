package de.devin.pipesnphysics.physics;

import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Tick-based fluid simulation using the potential + head-budget model.
 *
 * Direction: Φ = staticPressure + phaseSign * density * G * elevation
 * Flow: Q = COND * |ΔΦ| * fullnessFactor * reachFactor, clamped to available volume
 * Reach: bounded by head budget consumed over friction + gravity
 */
public final class FluidSimulator {

    private final SimConfig config;

    public FluidSimulator(SimConfig config) {
        this.config = config;
    }

    public SimConfig config() {
        return config;
    }

    public SimResult tick(FluidNetwork net, Map<String, SimFluid> fluids) {
        if (net.isDirty()) {
            net.clearDirty();
        }

        List<BurstEvent> bursts = new ArrayList<>();
        List<CollisionEvent> collisions = new ArrayList<>();

        // Step 1.5: Propagate head budgets from pumps/sources
        propagateHead(net, fluids);

        // Step 2: Compute flow rates
        computeFlowRates(net, fluids);

        // Step 3: Advance fluid fronts
        advanceFluid(net);

        // Step 4: Resolve nodes + detect collisions
        collisions.addAll(detectEdgeCollisions(net));
        collisions.addAll(resolveNodes(net, fluids));

        // Compute potentials for display
        Map<NodeId, Float> potentials = new HashMap<>();
        for (var entry : net.nodes().entrySet()) {
            SimNode node = entry.getValue();
            float maxPhi = node.staticPressure();
            for (int edgeIdx : net.edgesAt(entry.getKey())) {
                SimEdge edge = net.edges().get(edgeIdx);
                String fluidId = edge.primaryFluid();
                if (fluidId == null) continue;
                SimFluid f = fluids.get(fluidId);
                if (f == null) continue;
                float phi = computePhi(node, f);
                if (Math.abs(phi) > Math.abs(maxPhi)) maxPhi = phi;
            }
            potentials.put(entry.getKey(), maxPhi);
            net.setPotential(entry.getKey(), maxPhi);
        }

        // Burst detection
        for (int i = 0; i < net.edges().size(); i++) {
            float absFlow = Math.abs(net.flowRate(i));
            if (absFlow > config.burstThreshold()) {
                SimEdge edge = net.edges().get(i);
                bursts.add(new BurstEvent(edge.a(), absFlow, config.burstThreshold()));
            }
        }

        return new SimResult(
                Arrays.copyOf(net.flowRates(), net.flowRates().length),
                bursts, collisions, potentials);
    }

    private float computePhi(SimNode node, SimFluid fluid) {
        return node.staticPressure()
                + fluid.phase().sign() * fluid.density() * config.G() * (float) node.elevation();
    }

    /**
     * Propagate head budget from pumps/sources outward through the network.
     * Head is consumed by friction (R_PER_TILE * length) and gravity lift,
     * refunded by gravity drops.
     */
    private void propagateHead(FluidNetwork net, Map<String, SimFluid> fluids) {
        // Initialize: sources/pumps start with their head budget
        for (var entry : net.nodes().entrySet()) {
            SimNode node = entry.getValue();
            net.setHeadAt(entry.getKey(), node.head());
        }

        // Relaxation: BFS from nodes with head, propagate to neighbors
        // Multiple passes to handle multi-path networks
        boolean changed = true;
        int maxPasses = 10;
        while (changed && maxPasses-- > 0) {
            changed = false;
            for (int i = 0; i < net.edges().size(); i++) {
                SimEdge edge = net.edges().get(i);
                String fluidId = edge.primaryFluid();

                // Propagate in both directions, take the better path
                for (boolean aToB : new boolean[]{true, false}) {
                    NodeId from = aToB ? edge.a() : edge.b();
                    NodeId to = aToB ? edge.b() : edge.a();
                    float headFrom = net.headAt(from);
                    if (headFrom <= 0) continue;

                    float cost = edgeCost(edge, net.node(from), net.node(to), fluidId, fluids);
                    float headArriving = headFrom - cost;

                    if (headArriving > net.headAt(to)) {
                        net.setHeadAt(to, headArriving);
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * Head cost to traverse an edge in a given direction.
     * Friction always costs. Gravity costs for lift, refunds for drops.
     */
    private float edgeCost(SimEdge edge, SimNode from, SimNode to,
                           String fluidId, Map<String, SimFluid> fluids) {
        float friction = config.frictionPerBlock() * edge.length();

        float gravityCost = 0;
        if (fluidId != null) {
            SimFluid fluid = fluids.get(fluidId);
            if (fluid != null) {
                float deltaY = (float) (to.elevation() - from.elevation());
                gravityCost = fluid.phase().sign() * fluid.density() * config.G() * deltaY;
            }
        }

        return friction + gravityCost;
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

            float phiA = computePhi(nodeA, fluid);
            float phiB = computePhi(nodeB, fluid);
            float deltaPhi = phiA - phiB;

            if (Math.abs(deltaPhi) <= config.EPS()) {
                net.setFlowRate(i, 0);
                continue;
            }

            // Base flow from potential difference
            float Q = Math.clamp(config.conductance() * Math.abs(deltaPhi), 0, config.maxFlow());

            // fullnessFactor: rate proportional to how full the edge is (stability)
            float fullness = edge.capacity() > 0
                    ? (float) edge.totalFill() / edge.capacity()
                    : 0;
            Q *= fullness;

            // reachFactor: taper near head limit
            NodeId upstream = deltaPhi > 0 ? edge.a() : edge.b();
            float headIn = net.headAt(upstream);
            float reachFactor = Math.clamp(headIn / config.taperMargin(), 0, 1);
            Q *= reachFactor;

            // Clamp to available fluid (can't move more than what's in the edge)
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
                edge.drainFromA(amount);
            } else {
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

            // Junction collision
            if (arriving.size() > 1) {
                List<String> fluidIds = new ArrayList<>(arriving.keySet());
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
                for (int i = 0; i < fluidIds.size() - 1; i++) {
                    for (int j = i + 1; j < fluidIds.size(); j++) {
                        collisions.add(new CollisionEvent(pos, fluidIds.get(i), fluidIds.get(j)));
                    }
                }
            }

            if (arriving.isEmpty()) continue;

            // Distribute to outgoing edges
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
