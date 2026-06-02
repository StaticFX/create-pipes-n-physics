package de.devin.pipesnphysics.physics;

import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Two-phase tick-based fluid simulation.
 *
 * Phase 1 (CHARGING): fluid front advances through pipe at speed driven by head and viscosity.
 * Phase 2 (FLOWING): steady through-flow once a complete source→sink circuit exists.
 *
 * Direction from Φ = staticPressure + phaseSign * density * G * elevation.
 * Reach bounded by head budget consumed over friction + gravity.
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

        // Step 1: Head propagation
        propagateHead(net, fluids);

        // Step 2: Per-edge phase dispatch
        for (int i = 0; i < net.edges().size(); i++) {
            SimEdge edge = net.edges().get(i);
            String fluidId = edge.primaryFluid();
            SimFluid fluid = fluidId != null ? fluids.get(fluidId) : null;

            switch (edge.phase()) {
                case EMPTY -> tickEmpty(net, edge, i, fluids);
                case CHARGING, STALLED, DRAINING -> tickFront(net, edge, i, fluid);
                case FLOWING -> tickFlowing(net, edge, i, fluid);
            }
        }

        // Step 3: Resolve nodes
        collisions.addAll(detectEdgeCollisions(net));
        collisions.addAll(resolveNodes(net, fluids));

        // Compute potentials for display
        Map<NodeId, Float> potentials = computePotentials(net, fluids);

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

    // -- Phase handlers --

    private void tickEmpty(FluidNetwork net, SimEdge edge, int idx, Map<String, SimFluid> fluids) {
        // Only start charging if there's an actual potential difference to drive flow.
        // This prevents settled circuits from looping EMPTY→CHARGING→FLOWING→DRAINING.
        NodeId feedNode = null;
        NodeId otherNode = null;

        // 1. Pump push-side: only if pump is powered AND has fluid to move.
        //    A pump with nothing on its pull side shouldn't pressurize empty pipes.
        for (NodeId nodeId : List.of(edge.a(), edge.b())) {
            SimNode node = net.node(nodeId);
            if (node != null && node.kind() == SimNodeKind.PUMP
                    && node.staticPressure() > 0) {
                NodeId other = nodeId.equals(edge.a()) ? edge.b() : edge.a();
                if (net.isPushSide(nodeId, other) && pumpHasFluidSource(net, nodeId)) {
                    feedNode = nodeId;
                    otherNode = other;
                    break;
                }
            }
        }

        // 2. Non-pump SOURCE nodes (tanks, etc.) — only if they have head.
        //    If the other end is a pump, only charge if we're on the pump's PULL side
        //    (feeding the intake). Don't charge on the push side (that's reverse flow).
        //    The Φ gate is checked per candidate so both directions are tried.
        if (feedNode == null) {
            for (NodeId nodeId : List.of(edge.a(), edge.b())) {
                SimNode node = net.node(nodeId);
                if (node == null || node.kind() == SimNodeKind.PUMP) continue;
                NodeId other = nodeId.equals(edge.a()) ? edge.b() : edge.a();
                SimNode otherSim = net.node(other);
                // If other end is a pump: only allow if we're on its pull side
                if (otherSim != null && otherSim.kind() == SimNodeKind.PUMP) {
                    if (net.isPushSide(other, nodeId)) continue;
                }
                if ((node.kind() == SimNodeKind.SOURCE || node.kind() == SimNodeKind.JUNCTION)
                        && net.headAt(nodeId) > 0) {
                    // Gate: SOURCE must have fluid
                    if (node.kind() == SimNodeKind.SOURCE && node.staticPressure() <= 0) continue;
                    // Gate: feed Φ must exceed other Φ (prevents wrong-direction charging)
                    boolean pumpInvolved = (otherSim != null && otherSim.kind() == SimNodeKind.PUMP);
                    if (!pumpInvolved && otherSim != null) {
                        float feedPhi = node.staticPressure()
                                + config.G() * (float) node.elevation();
                        float otherPhi = otherSim.staticPressure()
                                + config.G() * (float) otherSim.elevation();
                        if (feedPhi - otherPhi <= config.EPS()) continue;
                    }
                    feedNode = nodeId;
                    otherNode = other;
                    break;
                }
            }
        }

        if (feedNode == null) return;

        String fluidId = findFluidAtNode(net, feedNode, fluids);
        if (fluidId != null) {
            edge.setPhase(EdgePhase.CHARGING);
            edge.setUpstreamNode(feedNode);
            edge.setFrontPos(0);
            edge.setPrimaryFluid(fluidId);
        }
    }

    /**
     * Unified CHARGING / DRAINING tick. Both phases use the same ΔΦ-driven
     * speed — CHARGING advances the front, DRAINING recedes it.
     */
    private void tickFront(FluidNetwork net, SimEdge edge, int idx, SimFluid fluid) {
        if (fluid == null) { edge.setPhase(EdgePhase.EMPTY); return; }

        // Legacy STALLED edges are now handled here — convert to CHARGING
        if (edge.phase() == EdgePhase.STALLED) {
            edge.setPhase(EdgePhase.CHARGING);
        }

        boolean draining = edge.phase() == EdgePhase.DRAINING;

        SimNode nodeA = net.node(edge.a());
        SimNode nodeB = net.node(edge.b());
        float phiA = computePhiForEdge(net, nodeA, edge.b(), fluid);
        float phiB = computePhiForEdge(net, nodeB, edge.a(), fluid);
        float deltaPhi = phiA - phiB;

        // --- DRAINING ---
        // Speed from the higher endpoint's absolute pressure — the pipe empties
        // at a rate proportional to how pressurised the system is, not the
        // near-zero ΔΦ that triggered the drain. EPS floor ensures it always drains.
        if (draining) {
            float drive = Math.max(nodeA.staticPressure(), nodeB.staticPressure());
            float speed = config.frontK() * Math.max(drive, config.EPS()) / fluid.viscosity();
            edge.setFrontPos(Math.max(0, edge.frontPos() - speed));
            edge.advanceVisualFront(-speed);
            net.setFlowRate(idx, 0);

            if (edge.visualFrontPos() <= 0) {
                edge.setPhase(EdgePhase.EMPTY);
                edge.setFrontPos(0);
                edge.resetVisualFront();
                edge.setUpstreamNode(null);
                edge.setPrimaryFluid(null);
            }
            return;
        }

        // --- CHARGING ---
        NodeId upstream = edge.upstreamNode();
        if (upstream == null) { edge.setPhase(EdgePhase.EMPTY); return; }

        boolean primed = net.flowRate(idx) != 0;

        if (primed && Math.abs(deltaPhi) <= config.EPS()) {
            edge.setPhase(EdgePhase.DRAINING);
            net.setFlowRate(idx, 0);
            return;
        }

        boolean pumpDriven = edgeIsPumpDriven(net, edge);

        if (primed) {
            // Primed (effectively flowing): use ΔΦ for speed and flow rate
            float speed = config.frontK() * Math.abs(deltaPhi) / fluid.viscosity();
            edge.setFrontPos(edge.frontPos() + speed);
            edge.advanceVisualFront(speed);

            float Q = computeQ(net, fluid, deltaPhi);
            float newRate = Math.signum(deltaPhi) * Q;

            if (Math.signum(newRate) != Math.signum(net.flowRate(idx))) {
                edge.setUpstreamNode(newRate > 0 ? edge.a() : edge.b());
                edge.resetVisualFront();
                edge.setFrontPos(0);
            }

            net.setFlowRate(idx, newRate);
        } else if (pumpDriven) {
            // Pump-driven, not yet primed: head budget bounds reach.
            // Head decays with friction and gravity as the front advances.
            float headAtFront = computeHeadAtFront(net, edge, fluid);

            if (headAtFront <= 0) {
                // Head exhausted — don't advance. Recede if head is negative
                // (pump slowed down, front overshot the new equilibrium).
                if (headAtFront < -config.EPS()) {
                    float recedeSpeed = config.frontK() * Math.abs(headAtFront) / fluid.viscosity();
                    float newPos = edge.frontPos() - recedeSpeed;
                    if (newPos <= 0) {
                        edge.setPhase(EdgePhase.DRAINING);
                    } else {
                        edge.setFrontPos(newPos);
                        edge.advanceVisualFront(-recedeSpeed);
                    }
                }
                net.setFlowRate(idx, 0);
                return;
            }

            float speed = config.frontK() * headAtFront / fluid.viscosity();
            edge.setFrontPos(edge.frontPos() + speed);
            edge.advanceVisualFront(speed);
            net.setFlowRate(idx, 0);
        } else {
            // Gravity-driven, not yet primed: ΔΦ drives the front.
            // No head budget stall — gravity flow reaches wherever ΔΦ > EPS.
            float speed = config.frontK() * Math.abs(deltaPhi) / fluid.viscosity();
            edge.setFrontPos(edge.frontPos() + speed);
            edge.advanceVisualFront(speed);
            net.setFlowRate(idx, 0);
        }

        if (edge.frontPos() >= edge.length()) {
            float excess = edge.frontPos() - edge.length();
            String fluidId = edge.primaryFluid();

            NodeId downstream = edge.downstreamNode();
            if (downstream == null) return;
            SimNode downNode = net.node(downstream);
            if (downNode == null) return;

            if (downNode.kind() == SimNodeKind.DEAD_END || isClosedPump(downNode)) {
                edge.setFrontPos(edge.length());
                net.setFlowRate(idx, 0);
                return;
            }

            if (Math.abs(deltaPhi) > config.EPS()) {
                net.setFlowRate(idx, Math.signum(deltaPhi) * computeQ(net, fluid, deltaPhi));
                edge.setFrontPos(excess);
            } else {
                // Front reached the end but no driving force — sit at the end.
                // Stays CHARGING so it re-evaluates each tick (effective STALLED).
                net.setFlowRate(idx, 0);
                edge.setFrontPos(edge.length());
            }

            if (downNode.kind() == SimNodeKind.PUMP || downNode.kind() == SimNodeKind.JUNCTION) {
                for (int downEdgeIdx : net.edgesAt(downstream)) {
                    SimEdge downEdge = net.edges().get(downEdgeIdx);
                    if (downEdge == edge) continue;
                    if (downEdge.phase() == EdgePhase.EMPTY) {
                        downEdge.setPhase(EdgePhase.CHARGING);
                        downEdge.setUpstreamNode(downstream);
                        downEdge.setFrontPos(0);
                        if (fluidId != null) downEdge.setPrimaryFluid(fluidId);
                    }
                }
            }
        }
    }

    /**
     * Compute flow rate Q for an edge. Pump-driven networks use the max pump
     * throughput (RPM * flowPerRPM); gravity networks use conductance * |ΔΦ|.
     */
    private float computeQ(FluidNetwork net, SimFluid fluid, float deltaPhi) {
        float gravityQ = Math.clamp(
                (config.conductance() / fluid.viscosity()) * Math.abs(deltaPhi),
                0, config.maxFlow());

        float pumpQ = computeNetworkPumpFlow(net);
        return pumpQ > 0 ? Math.max(gravityQ, pumpQ) : gravityQ;
    }

    /**
     * Max pump throughput across all active pumps in the network.
     * Returns 0 if no pump is active.
     */
    private float computeNetworkPumpFlow(FluidNetwork net) {
        float maxPumpFlow = 0;
        for (SimNode n : net.nodes().values()) {
            if (n.kind() == SimNodeKind.PUMP && n.staticPressure() > 0) {
                float rpm = n.staticPressure() / config.speedToHead();
                float pumpFlow = Math.min(rpm * config.flowPerRPM(), config.maxFlow());
                maxPumpFlow = Math.max(maxPumpFlow, pumpFlow);
            }
        }
        return maxPumpFlow;
    }

    private void tickFlowing(FluidNetwork net, SimEdge edge, int idx, SimFluid fluid) {
        if (fluid == null) {
            net.setFlowRate(idx, 0);
            return;
        }

        SimNode nodeA = net.node(edge.a());
        SimNode nodeB = net.node(edge.b());

        // Dead pump: no flow through an unpowered pump.
        if (isClosedPump(nodeA) || isClosedPump(nodeB)) {
            net.setFlowRate(idx, 0);
            return;
        }

        // Asymmetric Φ for pump nodes: push side uses accumulated head + own boost,
        // pull side uses suction. Series pumps stack because headAt carries upstream head.
        float phiA = computePhiForEdge(net, nodeA, edge.b(), fluid);
        float phiB = computePhiForEdge(net, nodeB, edge.a(), fluid);
        float deltaPhi = phiA - phiB;

        if (Math.abs(deltaPhi) <= config.EPS()) {
            // No driving force — source empty or pressures equalized.
            edge.setPhase(EdgePhase.DRAINING);
            net.setFlowRate(idx, 0);
            return;
        }

        float Q = computeQ(net, fluid, deltaPhi);
        net.setFlowRate(idx, Math.signum(deltaPhi) * Q);
    }

    // -- Helpers --

    private float computePhi(SimNode node, SimFluid fluid) {
        return node.staticPressure()
                + fluid.phase().sign() * fluid.density() * config.G() * (float) node.elevation();
    }

    /**
     * Compute Φ at a node for a specific edge.
     * PUMP push side: headAt already includes the pump's own head (seeded in propagateHead),
     *   so Φ = headAt + gravity. No double-count of staticPressure.
     * PUMP pull side: suction = -staticPressure + gravity.
     * SOURCE: staticPressure (tank fill pressure) + gravity.
     */
    private float computePhiForEdge(FluidNetwork net, SimNode node, NodeId otherEnd, SimFluid fluid) {
        float gravity = fluid.phase().sign() * fluid.density() * config.G() * (float) node.elevation();
        if (node.kind() == SimNodeKind.PUMP) {
            boolean pushSide = net.isPushSide(node.id(), otherEnd);
            if (pushSide) {
                // Push side: headAt already includes pump head + upstream contributions
                float head = (net != null) ? net.headAt(node.id()) : node.staticPressure();
                return head + gravity;
            } else {
                return -node.staticPressure() + gravity; // pull: suction
            }
        }
        return node.staticPressure() + gravity;
    }

    /**
     * Check if a pump has any fluid source on its pull side (reachable via non-push edges).
     * A pump with nothing to pull shouldn't start charging its push side.
     */
    private boolean pumpHasFluidSource(FluidNetwork net, NodeId pumpId) {
        SimNode pump = net.node(pumpId);
        if (pump == null) return false;

        // Check all edges connected to the pump
        for (int edgeIdx : net.edgesAt(pumpId)) {
            SimEdge adj = net.edges().get(edgeIdx);
            NodeId otherEnd = adj.a().equals(pumpId) ? adj.b() : adj.a();

            // Skip push-side edges — we want the pull side
            if (net.isPushSide(pumpId, otherEnd)) continue;

            // The pull-side leads to a SOURCE with fluid?
            SimNode other = net.node(otherEnd);
            if (other != null && other.kind() == SimNodeKind.SOURCE && other.staticPressure() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if this edge is pump-driven: the upstream node is a pump, or a pump
     * propagated head to it (headAt exceeds the node's own contribution).
     * Gravity-only edges should not use head budget stalling.
     */
    private boolean edgeIsPumpDriven(FluidNetwork net, SimEdge edge) {
        NodeId upstream = edge.upstreamNode();
        if (upstream == null) return false;
        SimNode upNode = net.node(upstream);
        if (upNode == null) return false;
        if (upNode.kind() == SimNodeKind.PUMP) return true;
        // Head at upstream exceeds node's own head → a pump propagated here
        return net.headAt(upstream) > upNode.head() + config.EPS();
    }

    /**
     * Head budget remaining at the fluid front's current position within an edge.
     * Decays from headAt[upstream] by friction and gravity as the front advances.
     * Returns 0 or negative when the pump can't push any further.
     */
    private float computeHeadAtFront(FluidNetwork net, SimEdge edge, SimFluid fluid) {
        NodeId upstream = edge.upstreamNode();
        if (upstream == null) return 0;

        float headAtUpstream = net.headAt(upstream);
        float frictionCost = config.frictionPerBlock() * edge.frontPos();

        // Interpolate elevation at front position between the two endpoints
        SimNode upNode = net.node(upstream);
        NodeId downId = edge.downstreamNode();
        SimNode downNode = downId != null ? net.node(downId) : null;
        float gravityCost = 0;
        if (upNode != null && downNode != null && edge.length() > 0) {
            float t = edge.frontPos() / edge.length();
            float elevAtFront = (float) (upNode.elevation() + t * (downNode.elevation() - upNode.elevation()));
            float deltaY = elevAtFront - (float) upNode.elevation();
            gravityCost = fluid.phase().sign() * fluid.density() * config.G() * deltaY;
        }

        return headAtUpstream - frictionCost - gravityCost;
    }

    private boolean isClosedPump(SimNode n) {
        return n != null && n.kind() == SimNodeKind.PUMP && n.staticPressure() <= 0;
    }

    private void propagateHead(FluidNetwork net, Map<String, SimFluid> fluids) {
        // Seed: sources supply head from fill/elevation; pumps supply head on BOTH sides
        // (push = drives flow away, pull = creates suction toward itself).
        for (var entry : net.nodes().entrySet()) {
            SimNode node = entry.getValue();
            // Pumps provide their own head as the seed (reaches both push and pull sides)
            net.setHeadAt(entry.getKey(), node.head());
        }

        boolean changed = true;
        int maxPasses = 10;
        while (changed && maxPasses-- > 0) {
            changed = false;
            for (int i = 0; i < net.edges().size(); i++) {
                SimEdge edge = net.edges().get(i);
                String fluidId = edge.primaryFluid();

                for (boolean aToB : new boolean[]{true, false}) {
                    NodeId from = aToB ? edge.a() : edge.b();
                    NodeId to = aToB ? edge.b() : edge.a();
                    float headFrom = net.headAt(from);
                    if (headFrom <= 0) continue;

                    float cost = edgeCost(edge, net.node(from), net.node(to), fluidId, fluids);
                    float arriving = headFrom - cost;

                    if (arriving > net.headAt(to)) {
                        net.setHeadAt(to, arriving);
                        changed = true;
                    }
                }
            }
        }
    }

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

    private String findFluidAtNode(FluidNetwork net, NodeId nodeId, Map<String, SimFluid> fluids) {
        // Check adjacent edges for fluid
        for (int edgeIdx : net.edgesAt(nodeId)) {
            SimEdge edge = net.edges().get(edgeIdx);
            String f = edge.primaryFluid();
            if (f != null) return f;
        }
        // Return first known fluid as fallback
        if (!fluids.isEmpty()) return fluids.keySet().iterator().next();
        return null;
    }

    private Map<NodeId, Float> computePotentials(FluidNetwork net, Map<String, SimFluid> fluids) {
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
        return potentials;
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

            // Propagate fluid type to outgoing edges (composition tracking only,
            // not real inventory — pipes don't hold conserved mB).
            String dominantFluid = arriving.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (dominantFluid == null) continue;

            for (int edgeIdx : net.edgesAt(nodeId)) {
                SimEdge edge = net.edges().get(edgeIdx);
                float rate = net.flowRate(edgeIdx);
                boolean flowsAway = (rate > 0 && edge.a().equals(nodeId))
                        || (rate < 0 && edge.b().equals(nodeId));
                if (flowsAway && edge.primaryFluid() == null) {
                    edge.setPrimaryFluid(dominantFluid);
                }
            }
        }

        return collisions;
    }
}
