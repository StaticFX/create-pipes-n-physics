package de.devin.pipesnphysics.physics;

import de.devin.pipesnphysics.handler.PipeGraphBuilder;
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
                case CHARGING -> tickCharging(net, edge, i, fluid);
                case STALLED -> tickStalled(net, edge, i, fluid);
                case FLOWING -> tickFlowing(net, edge, i, fluid);
                case DRAINING -> tickDraining(net, edge, i);
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
        // Determine which node should feed (charge) this edge.
        // Pump push-side edges charge FROM the pump (it pushes toward the other end).
        // All other edges charge from non-pump nodes (TANK, SOURCE, JUNCTION).
        NodeId feedNode = null;

        // 1. Check if a pump wants to push through this edge (pump push-side first)
        for (NodeId nodeId : List.of(edge.a(), edge.b())) {
            SimNode node = net.node(nodeId);
            if (node != null && node.kind() == SimNodeKind.PUMP && node.pushSidePos() != null) {
                NodeId otherEnd = nodeId.equals(edge.a()) ? edge.b() : edge.a();
                if (isPushSide(node, otherEnd)) {
                    feedNode = nodeId; break;
                }
            }
        }

        // 2. Otherwise, non-pump nodes feed (pull-side of pump, gravity, tank pressure)
        if (feedNode == null) {
            for (NodeId nodeId : List.of(edge.a(), edge.b())) {
                SimNode node = net.node(nodeId);
                if (node == null || node.kind() == SimNodeKind.PUMP) continue;
                boolean canFeed = node.kind() == SimNodeKind.SOURCE
                        || node.kind() == SimNodeKind.SOURCE
                        || (node.kind() == SimNodeKind.JUNCTION && net.headAt(nodeId) > 0);
                if (canFeed) { feedNode = nodeId; break; }
            }
        }
        if (feedNode != null) {
            String fluidId = findFluidAtNode(net, feedNode, fluids);
            if (fluidId != null) {
                edge.setPhase(EdgePhase.CHARGING);
                edge.setUpstreamNode(feedNode);
                edge.setFrontPos(0);
                edge.setPrimaryFluid(fluidId);
            }
        }
    }

    private void tickCharging(FluidNetwork net, SimEdge edge, int idx, SimFluid fluid) {
        if (fluid == null) { edge.setPhase(EdgePhase.EMPTY); return; }

        NodeId upstream = edge.upstreamNode();
        if (upstream == null) { edge.setPhase(EdgePhase.EMPTY); return; }

        SimNode upNode = net.node(upstream);
        float headAtUpstream = net.headAt(upstream);

        // Head at the advancing front: subtract friction and gravity cost to frontPos
        float frictionToFront = config.frictionPerBlock() * edge.frontPos();
        float gravityToFront = 0;
        if (edge.length() > 0) {
            SimNode downNode = net.node(edge.downstreamNode());
            if (downNode != null) {
                float fracAdvanced = edge.frontPos() / edge.length();
                float elevAtFront = (float) (upNode.elevation()
                        + (downNode.elevation() - upNode.elevation()) * fracAdvanced);
                gravityToFront = fluid.phase().sign() * fluid.density() * config.G()
                        * (elevAtFront - (float) upNode.elevation());
            }
        }

        float headAtFront = headAtUpstream - frictionToFront - gravityToFront;

        // Whether this is a pump-driven edge (affects velocity scaling)
        boolean chargeReachApplies = upNode != null && upNode.kind() == SimNodeKind.PUMP;
        // Stall when head is exhausted — bounded reach applies to ALL flows.
        // Pumps get reach from head budget, tanks from fill level, gravity from elevation.
        if (headAtFront <= 0) {
            edge.setPhase(EdgePhase.STALLED);
            net.setFlowRate(idx, 0);
            return;
        }

        // Front advance speed: tiles/tick = FRONT_K * headAtFront / viscosity.
        // For pump-driven flow, divide heavily so fluid visibly crawls through pipes.
        float v;
        float velocityHead = headAtFront;
        if (chargeReachApplies && config.pumpHeadMultiplier() > 0) {
            velocityHead = headAtFront / (config.pumpHeadMultiplier() * 20f);
        }
        v = Math.max(0, config.frontK() * velocityHead / fluid.viscosity());
        edge.setFrontPos(edge.frontPos() + v);

        // Pipes don't hold real inventory — frontPos is purely visual/timing.
        // Conservation happens at boundary handlers only.
        String fluidId = edge.primaryFluid();

        // CHARGING has zero through-flow (spec §5.1) — the circuit isn't complete yet.
        // Transfer only starts once all edges transition to FLOWING.
        net.setFlowRate(idx, 0);

        // Check if front reached the downstream end
        if (edge.frontPos() >= edge.length()) {
            edge.setFrontPos(edge.length());

            NodeId downstream = edge.downstreamNode();
            if (downstream == null) return;

            SimNode downNode = net.node(downstream);
            if (downNode == null) return;

            if (downNode.kind() == SimNodeKind.DEAD_END) {
                edge.setPhase(EdgePhase.STALLED);
            } else if (isClosedPump(downNode)) {
                edge.setPhase(EdgePhase.STALLED);
            } else {
                // Reached a valid node (SOURCE, PUMP, JUNCTION).
                // This edge is now primed — transition to FLOWING.
                edge.setPhase(EdgePhase.FLOWING);

                // If the downstream is a pass-through (PUMP/JUNCTION), also
                // start charging the next edges in the path.
                if (downNode.kind() == SimNodeKind.PUMP
                        || downNode.kind() == SimNodeKind.JUNCTION) {
                    for (int downEdgeIdx : net.edgesAt(downstream)) {
                        SimEdge downEdge = net.edges().get(downEdgeIdx);
                        if (downEdge == edge) continue;
                        if (downEdge.phase() == EdgePhase.EMPTY) {
                            downEdge.setPhase(EdgePhase.CHARGING);
                            downEdge.setUpstreamNode(downstream);
                            downEdge.setFrontPos(0);
                            if (fluidId != null) {
                                downEdge.setPrimaryFluid(fluidId);
                            }
                        }
                    }
                }
            }
        }
    }

    private void tickStalled(FluidNetwork net, SimEdge edge, int idx, SimFluid fluid) {
        if (fluid == null) return;
        NodeId upstream = edge.upstreamNode();
        if (upstream == null) return;

        // Recheck if head is now available (e.g. booster placed)
        SimNode upNode = net.node(upstream);
        float headAtUpstream = net.headAt(upstream);
        float frictionToFront = config.frictionPerBlock() * edge.frontPos();

        if (headAtUpstream - frictionToFront > 0) {
            edge.setPhase(EdgePhase.CHARGING);
        }

        net.setFlowRate(idx, 0);
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
            net.setFlowRate(idx, 0);
            return;
        }

        // Steady flow: COND / viscosity * |ΔΦ|
        float Q = Math.clamp((config.conductance() / fluid.viscosity()) * Math.abs(deltaPhi),
                0, config.maxFlow());

        // Pump throughput cap: limits flow proportional to pump RPM.
        // Prevents low-RPM pumps from hitting maxFlow due to gravity assist or high ΔΦ.
        float pumpCap = config.maxFlow();
        for (SimNode n : net.nodes().values()) {
            if (n.kind() == SimNodeKind.PUMP && n.staticPressure() > 0) {
                float cap = n.staticPressure() * config.pumpThroughputScale();
                pumpCap = Math.min(pumpCap, cap);
            }
        }
        Q = Math.min(Q, pumpCap);

        // Pipes don't hold real inventory — conservation is enforced at boundary
        // handlers (src.drain / dst.fill). ΔΦ naturally drops to 0 as destination
        // fills past the pump's head, providing stability feedback.

        net.setFlowRate(idx, Math.signum(deltaPhi) * Q);

        // FLOWING edges stay full — actual fluid transfer happens at boundary nodes
        // (GravityFlowHandler.transferFluid). No drain here.

        // FLOWING edges persist — they don't revert to DRAINING.
        // The flow rate naturally drops to 0 when ΔΦ hits EPS.
    }

    private void tickDraining(FluidNetwork net, SimEdge edge, int idx) {
        // Pipes don't hold real inventory — just transition back to EMPTY.
        // The visual "draining" is handled by frontPos receding in the renderer.
        edge.setFrontPos(Math.max(0, edge.frontPos() - 1));
        if (edge.frontPos() <= 0) {
            edge.setPhase(EdgePhase.EMPTY);
            edge.setFrontPos(0);
            edge.setUpstreamNode(null);
            edge.setPrimaryFluid(null);
        }
        net.setFlowRate(idx, 0);
    }

    // -- Helpers --

    private float computePhi(SimNode node, SimFluid fluid) {
        return node.staticPressure()
                + fluid.phase().sign() * fluid.density() * config.G() * (float) node.elevation();
    }

    /**
     * Compute Φ at a node for a specific edge. For PUMP nodes, staticPressure only
     * applies on the push side. On the pull side, Φ = gravity only.
     *
     * @param node     the node to compute Φ for
     * @param otherEnd the NodeId of the OTHER endpoint of this edge
     * @param fluid    the fluid
     */
    /**
     * Compute Φ at a node for a specific edge. For PUMP nodes, the push side uses
     * accumulated head (from upstream tanks + prior pumps) plus this pump's own boost.
     * The pull side uses suction (-speed) to draw fluid in.
     */
    private float computePhiForEdge(SimNode node, NodeId otherEnd, SimFluid fluid) {
        return computePhiForEdge(null, node, otherEnd, fluid);
    }

    private float computePhiForEdge(FluidNetwork net, SimNode node, NodeId otherEnd, SimFluid fluid) {
        float gravity = fluid.phase().sign() * fluid.density() * config.G() * (float) node.elevation();
        if (node.kind() == SimNodeKind.PUMP && node.pushSidePos() != null) {
            boolean isPushSide = isPushSide(node, otherEnd);
            if (isPushSide) {
                // Push side: Φ = accumulated head + pump speed + gravity.
                // headAt carries upstream pump contributions for series stacking.
                // For single pumps it also includes source tank pressure (minor coupling).
                float accumulated = (net != null) ? net.headAt(node.id()) : 0;
                return accumulated + node.staticPressure() + gravity;
            } else {
                return -node.staticPressure() + gravity; // pull: suction
            }
        }
        return node.staticPressure() + gravity;
    }

    private boolean isClosedPump(SimNode n) {
        return n != null && n.kind() == SimNodeKind.PUMP && n.staticPressure() <= 0;
    }

    private boolean isPushSide(SimNode pumpNode, NodeId otherEnd) {
        BlockPos pumpPos = PipeGraphBuilder.posOf(pumpNode.id());
        BlockPos otherPos = PipeGraphBuilder.posOf(otherEnd);
        int dx = pumpNode.pushSidePos().getX() - pumpPos.getX();
        int dy = pumpNode.pushSidePos().getY() - pumpPos.getY();
        int dz = pumpNode.pushSidePos().getZ() - pumpPos.getZ();
        int ox = otherPos.getX() - pumpPos.getX();
        int oy = otherPos.getY() - pumpPos.getY();
        int oz = otherPos.getZ() - pumpPos.getZ();
        return (dx * ox + dy * oy + dz * oz) > 0;
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
