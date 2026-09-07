package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.BranchSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.NodeSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The player-facing head fields of one solved pass: display heads (the overlay gradient, the
 * goggle pressure line, /pipegraph), ceilings (the friction-free planning potential), and anchors
 * (the supply surface each ceiling was seeded from). One instance computes all three from a
 * solved result and writes them into the pass's accumulators; the three fields stay distinct
 * quantities and are never merged.
 *
 * Display heads are anchored at real reservoirs and spread outward over active branches, but only
 * in directions fluid could actually move — never backward through a check valve or out of a
 * connection that cannot supply. A branch that carries flow keeps its solved heads; across a
 * ZERO-flow branch the head continues unchanged, which drops the EMF jump of a dead-headed pump.
 * Pipes no reservoir can reach hold no fluid and show no pressure at all — an idle pump must not
 * paint phantom vacuum (or phantom tank pressure) over dry lines.
 */
final class DisplayFields {
    private final Graph graph;
    private final int[] solverIndex;
    private final List<NodeSpec> nodeSpecs;
    private final List<Boolean> canSupply;
    private final List<BranchSpec> branches;
    private final NetworkSolver.Result result;
    private final boolean gas;

    private final int nodeCount;
    private final int[] sign;
    private final double[] display;
    private final boolean[] known;
    private final double[] ceiling;
    private final double[] anchor;
    private final boolean[] ceilingKnown;
    private final double[] boostAhead;

    DisplayFields(Graph graph, int[] solverIndex, List<NodeSpec> nodeSpecs, List<Boolean> canSupply,
                  List<BranchSpec> branches, NetworkSolver.Result result, boolean gas) {
        this.graph = graph;
        this.solverIndex = solverIndex;
        this.nodeSpecs = nodeSpecs;
        this.canSupply = canSupply;
        this.branches = branches;
        this.result = result;
        this.gas = gas;
        this.nodeCount = nodeSpecs.size();
        this.sign = foldSaturationSigns();
        this.display = new double[nodeCount];
        this.known = new boolean[nodeCount];
        this.ceiling = new double[nodeCount];
        this.anchor = new double[nodeCount];
        this.ceilingKnown = new boolean[nodeCount];
        this.boostAhead = new double[nodeCount];
    }

    /** Compute all three fields and write them into the pass accumulators. */
    void writeInto(FlowSolver.GroupResults results) {
        spreadDisplayHeads();
        spreadCeilings();
        relaxBoostAhead();
        anchorUnfedSuctionRuns();
        writeOut(results);
    }

    /**
     * The display/planning traversals spread heads only along PERMITTED directions, which include
     * the capacity-box saturation the solver applied (a full column gives-only, an empty one
     * receives-only) — those no longer live in branch.allowedSign(), so fold the solved saturation
     * back in per branch. On a dead-conduit contradiction, keep the pre-full static sign (as the
     * old fullDeadlock path did), so the render stays byte-for-byte what it was.
     */
    private int[] foldSaturationSigns() {
        int[] folded = new int[branches.size()];
        for (int b = 0; b < branches.size(); b++) {
            int s = FluidPass.deadConduitSign(branches.get(b).allowedSign(),
                    result.saturation()[branches.get(b).a()], result.saturation()[branches.get(b).b()]);
            folded[b] = s == Integer.MIN_VALUE ? branches.get(b).allowedSign() : s;
        }
        return folded;
    }

    /** Whether a branch may carry head from {@code current} to its far end (its sign permits it). */
    private boolean spreadsFrom(int branchIndex, boolean fromA) {
        return sign[branchIndex] == 0 || sign[branchIndex] == (fromA ? +1 : -1);
    }

    /** Anchor display heads at reservoir surfaces and spread them over active branches. */
    private void spreadDisplayHeads() {
        List<List<Integer>> incident = incidentBranches(true);

        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        for (int i = 0; i < nodeCount; i++) {
            if (nodeSpecs.get(i).capacitance() > 0) {
                display[i] = result.heads()[i];
                known[i] = true;
                frontier.add(i);
            }
        }
        while (!frontier.isEmpty()) {
            int current = frontier.poll();
            for (int b : incident.get(current)) {
                BranchSpec branch = branches.get(b);
                boolean fromA = branch.a() == current;
                if (!spreadsFrom(b, fromA)) continue;
                int other = fromA ? branch.b() : branch.a();
                if (known[other]) continue;
                display[other] = Math.abs(result.flows()[b]) > FlowSolver.FLOW_TOLERANCE
                        ? result.heads()[other]
                        : display[current];
                known[other] = true;
                frontier.add(other);
            }
        }
    }

    /**
     * The ceiling is the friction-free potential: how high fluid could at most be pushed from
     * each node. Seeded ONLY by reservoirs that can actually supply — an empty tank drives
     * nothing and must not anchor the field (it receives its ceiling from the supply side like
     * any pipe) — and grows by each pump boost crossed along permitted directions. Unlike
     * display heads it traverses ALL assembled branches, including ones the check valves shut
     * this tick: a pump line stopped because the lift exceeds its head is precisely where the
     * player needs the ceiling readout. The anchor rides along: the supply surface a node's
     * budget is measured from. Ceiling − anchor is the total head budget; elevation climbed
     * above the anchor is the part already spent.
     */
    private void spreadCeilings() {
        List<List<Integer>> incident = incidentBranches(false);

        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        for (int i = 0; i < nodeCount; i++) {
            if (nodeSpecs.get(i).capacitance() > 0 && canSupply.get(i)) {
                ceiling[i] = result.heads()[i];
                anchor[i] = result.heads()[i];
                ceilingKnown[i] = true;
                frontier.add(i);
            }
        }
        while (!frontier.isEmpty()) {
            int current = frontier.poll();
            for (int b : incident.get(current)) {
                BranchSpec branch = branches.get(b);
                boolean fromA = branch.a() == current;
                if (!spreadsFrom(b, fromA)) continue;
                int other = fromA ? branch.b() : branch.a();
                if (ceilingKnown[other]) continue;
                double boost = fromA ? Math.max(0, branch.emf()) : Math.max(0, -branch.emf());
                ceiling[other] = ceiling[current] + boost;
                anchor[other] = anchor[current];
                ceilingKnown[other] = true;
                frontier.add(other);
            }
        }
    }

    /** Each node's incident branch indices, over active branches only or over all assembled ones. */
    private List<List<Integer>> incidentBranches(boolean activeOnly) {
        List<List<Integer>> incident = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) incident.add(new ArrayList<>());
        for (int b = 0; b < branches.size(); b++) {
            if (activeOnly && !result.active()[b]) continue;
            incident.get(branches.get(b).a()).add(b);
            incident.get(branches.get(b).b()).add(b);
        }
        return incident;
    }

    /**
     * Fluid on a pump's suction side WILL receive the pump's boost once it passes through, so
     * every node feeding a pump carries the boosts waiting downstream: reverse-relax the best
     * boost-sum along allowed directions and add it on top. Without this, suction-side junctions
     * and pipes read ambient or slightly negative head while the line works perfectly.
     */
    private void relaxBoostAhead() {
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            for (int b = 0; b < branches.size(); b++) {
                BranchSpec branch = branches.get(b);
                double forward = Math.max(0, branch.emf());
                double backward = Math.max(0, -branch.emf());
                if (sign[b] >= 0) {
                    double viaB = forward + boostAhead[branch.b()];
                    if (viaB > boostAhead[branch.a()] + 1e-9) {
                        boostAhead[branch.a()] = viaB;
                        changed = true;
                    }
                }
                if (sign[b] <= 0) {
                    double viaA = backward + boostAhead[branch.a()];
                    if (viaA > boostAhead[branch.b()] + 1e-9) {
                        boostAhead[branch.b()] = viaA;
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        for (int i = 0; i < nodeCount; i++) {
            if (ceilingKnown[i]) ceiling[i] += boostAhead[i];
        }
    }

    /**
     * A suction run no reservoir can feed — empty source tank, draw gated at the lip — must
     * still answer "what could the pumps ahead do from here". Anchor each such node at the head
     * a supply arriving right there would have, plus the boosts waiting downstream; without this
     * the pulling side of an idle pump shows nothing while the pushing side reads fine.
     */
    private void anchorUnfedSuctionRuns() {
        for (Node node : graph.nodes()) {
            int index = solverIndex[node.index()];
            if (index < 0 || ceilingKnown[index] || boostAhead[index] <= 0) continue;
            anchor[index] = anchorHead(nodeSpecs.get(index), node);
            ceiling[index] = anchor[index] + boostAhead[index];
            ceilingKnown[index] = true;
        }
    }

    /** The head a fresh supply would have if its surface sat exactly at this node. */
    private double anchorHead(NodeSpec spec, Node node) {
        if (spec.capacitance() > 0) return spec.head();
        return NetworkSolver.surfaceHead(node.worldY(), node.worldY(), 0, gas);
    }

    /** Publish per graph node, keeping the strongest ceiling (and its anchor) across fluid passes. */
    private void writeOut(FlowSolver.GroupResults results) {
        for (Node node : graph.nodes()) {
            int index = solverIndex[node.index()];
            if (index < 0) continue;
            if (known[index]) {
                results.nodeHeads.put(node.index(), display[index]);
                // The head carries its FRAME: a gas head is fill − topY, not an elevation, and the
                // last pass to write wins — so a node shared by a liquid and a gas pass must say
                // which one it is publishing, or a consumer maps buoyancy onto block Y (§4).
                if (gas) results.gasHeadNodes.add(node.index());
                else results.gasHeadNodes.remove(node.index());
            }
            if (!ceilingKnown[index]) continue;
            Double previous = results.nodeCeilings.get(node.index());
            if (previous == null || ceiling[index] > previous) {
                results.nodeCeilings.put(node.index(), ceiling[index]);
                results.nodeAnchors.put(node.index(), anchor[index]);
            }
        }
    }
}
