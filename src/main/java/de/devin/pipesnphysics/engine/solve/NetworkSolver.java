package de.devin.pipesnphysics.engine.solve;

import java.util.Arrays;
import java.util.List;

/**
 * Pure hydraulic network solver. Has no Minecraft dependencies so it can be
 * unit-tested directly.
 *
 * The network is modeled as an electrical-circuit analogue:
 * <ul>
 *   <li><b>Nodes</b> carry a hydraulic head (fluid surface height, in blocks).
 *       Nodes with positive capacitance are reservoirs (tanks/basins): capacitance
 *       is the volume in mB needed to raise their head by one block. Nodes with
 *       zero capacitance are junctions or pumps — pure Kirchhoff nodes whose head
 *       is solved from flow conservation.</li>
 *   <li><b>Branches</b> are pipe runs: a conductance (mB/tick of flow per block of
 *       head difference), an optional EMF (a pump's head boost, in blocks, driving
 *       a→b when positive), an optional one-way constraint (check valve), and an
 *       optional crest gate (the highest cell of the run; flow is cut when the
 *       interpolated head at the crest falls more than the suction limit below it —
 *       the siphon/cavitation rule).</li>
 * </ul>
 *
 * One call advances the network by one tick using an <b>implicit Euler</b> step:
 * the linear system {@code (C/dt + L) h' = (C/dt) h + emf terms} is solved for the
 * end-of-tick heads, and branch flows are read off the solved heads. Implicit Euler
 * is unconditionally stable: reservoir heads converge monotonically toward
 * equilibrium and can never overshoot or oscillate, regardless of conductance,
 * capacitance, or tick length. This is the property that makes tank equalization
 * settle instead of sloshing forever.
 *
 * One-way and crest constraints are enforced with an active-set loop: solve, drop
 * every branch whose solved flow violates a constraint, re-solve. Each iteration
 * only removes branches, so the loop terminates in at most |branches| rounds.
 */
public final class NetworkSolver {

    /** Flows smaller than this (mB/tick) are treated as zero for constraint checks. */
    private static final double FLOW_TOLERANCE = 1.0e-7;

    /** Node count above which the iterative solver replaces Gaussian elimination. */
    private static final int DIRECT_SOLVE_LIMIT = 128;

    /** Fraction of the suction limit over which crest flow tapers to zero (no cliff). */
    private static final double CREST_TAPER_FRACTION = 0.25;

    private NetworkSolver() {}

    /**
     * One node of the solver network.
     *
     * @param capacitance mB of volume per block of head; 0 for junctions and pumps
     * @param head        current head in blocks (fluid surface height for reservoirs;
     *                    ignored as input for zero-capacitance nodes)
     */
    public record NodeSpec(double capacitance, double head) {}

    /**
     * One branch of the solver network.
     *
     * @param a, b         endpoint node indices
     * @param conductance  mB/tick of flow per block of head difference; ≤ 0 disables the branch
     * @param emf          pump head in blocks, driving a→b flow when positive
     * @param allowedSign  +1 = only a→b flow allowed, -1 = only b→a, 0 = bidirectional
     * @param crestHeight  highest cell elevation along the run (blocks), or NaN for no gate
     * @param crestPos     fractional position of the crest along the run, 0 (at a) .. 1 (at b)
     */
    public record BranchSpec(int a, int b, double conductance, double emf, int allowedSign,
                             double crestHeight, double crestPos) {

        public static BranchSpec passive(int a, int b, double conductance) {
            return new BranchSpec(a, b, conductance, 0, 0, Double.NaN, 0);
        }
    }

    /**
     * Solver output.
     *
     * @param heads           end-of-tick head per node
     * @param flows           flow per branch in mB/tick, positive = a→b
     * @param netInflow       net volume gained per node this tick in mB (negative = drained)
     * @param active          whether each branch survived the constraint gates
     * @param crestBlocked    branches whose liquid column broke at their crest
     * @param backflowBlocked branches deactivated because the net potential opposed
     *                        their one-way direction; on a pump's EMF branch this
     *                        means exactly "the opposing head exceeds the pump head"
     */
    public record Result(double[] heads, double[] flows, double[] netInflow,
                         boolean[] active, boolean[] crestBlocked, boolean[] backflowBlocked) {}

    /**
     * Advance the network by one tick of length {@code dt}.
     *
     * @param suctionLimit how far (blocks) the head at a crest may fall below the crest
     *                     before the liquid column breaks and the branch stops flowing
     */
    public static Result solve(List<NodeSpec> nodes, List<BranchSpec> branches,
                               double dt, double suctionLimit) {
        int n = nodes.size();
        int m = branches.size();

        boolean[] active = new boolean[m];
        double[] gateScale = new double[m];
        for (int e = 0; e < m; e++) {
            BranchSpec br = branches.get(e);
            active[e] = br.conductance() > 0 && br.a() != br.b()
                    && br.a() >= 0 && br.a() < n && br.b() >= 0 && br.b() < n;
            gateScale[e] = 1;
        }

        double[] flows = new double[m];
        boolean[] backflowBlocked = new boolean[m];
        double[] heads = runActiveSet(nodes, branches, active, gateScale, flows, backflowBlocked, dt);

        // Crest (siphon/cavitation) gating is evaluated exactly ONCE against the
        // ungated solution, then frozen for a final pass. Re-evaluating it against
        // its own output is a positive feedback loop on suction lines (less flow →
        // lower head at the crest → more gating) that spirals working lines to dead.
        boolean[] crestBlocked = new boolean[m];
        boolean gated = false;
        for (int e = 0; e < m; e++) {
            if (!active[e]) continue;
            double factor = crestFactor(branches.get(e), flows[e], heads, suctionLimit);
            if (factor <= 0) {
                active[e] = false;
                crestBlocked[e] = true;
                gated = true;
            } else if (factor < 1) {
                gateScale[e] = factor;
                gated = true;
            }
        }
        if (gated) {
            heads = runActiveSet(nodes, branches, active, gateScale, flows, backflowBlocked, dt);
        }

        double[] netInflow = new double[n];
        for (int e = 0; e < m; e++) {
            if (flows[e] == 0) continue;
            BranchSpec br = branches.get(e);
            netInflow[br.a()] -= flows[e] * dt;
            netInflow[br.b()] += flows[e] * dt;
        }

        return new Result(heads, flows, netInflow, active, crestBlocked, backflowBlocked);
    }

    /**
     * Solve heads and flows, deactivating one-way (check valve) violators and
     * re-solving until consistent. Deactivation is monotone, so this terminates in
     * at most |branches| rounds.
     */
    private static double[] runActiveSet(List<NodeSpec> nodes, List<BranchSpec> branches,
                                         boolean[] active, double[] gateScale,
                                         double[] flows, boolean[] backflowBlocked, double dt) {
        int m = branches.size();
        double[] heads = new double[nodes.size()];

        for (int round = 0; round <= m; round++) {
            pruneCapacitanceFreeComponents(nodes, branches, active);

            heads = solveHeads(nodes, branches, active, gateScale, dt);

            boolean changed = false;
            for (int e = 0; e < m; e++) {
                if (!active[e]) {
                    flows[e] = 0;
                    continue;
                }
                BranchSpec br = branches.get(e);
                double q = gateScale[e] * br.conductance()
                        * (heads[br.a()] - heads[br.b()] + br.emf());
                flows[e] = q;

                if (violatesDirection(br, q)) {
                    active[e] = false;
                    backflowBlocked[e] = true;
                    flows[e] = 0;
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return heads;
    }

    private static boolean violatesDirection(BranchSpec br, double flow) {
        return br.allowedSign() != 0 && flow * br.allowedSign() < -FLOW_TOLERANCE;
    }

    /**
     * How much of this branch's conductance the crest gate permits: 1 with the crest
     * comfortably below the local head, tapering linearly to 0 as the suction deficit
     * approaches the limit. A pump's EMF raises the head profile from the end it
     * drives, so a powered line can cross a rise an unpowered siphon cannot.
     */
    private static double crestFactor(BranchSpec br, double flow, double[] heads,
                                      double suctionLimit) {
        if (Double.isNaN(br.crestHeight()) || Math.abs(flow) <= FLOW_TOLERANCE) return 1;

        double headA = heads[br.a()];
        double headB = heads[br.b()];
        double headAtCrest = br.emf() >= 0
                ? (headA + br.emf()) + (headB - headA - br.emf()) * br.crestPos()
                : headA + (headB - br.emf() - headA) * br.crestPos();

        double deficit = br.crestHeight() - headAtCrest;
        if (deficit <= 0) return 1;
        double taperBand = Math.max(0.5, suctionLimit * CREST_TAPER_FRACTION);
        return Math.clamp((suctionLimit - deficit) / taperBand, 0, 1);
    }

    /**
     * Deactivate every branch in a connected component that holds no capacitance.
     * Such a component (a loop or run of bare junctions with no reservoir) has no
     * defined head and can carry no net fluid; removing it keeps the linear system
     * non-singular and guarantees pipe loops can never circulate fluid out of nothing.
     */
    private static void pruneCapacitanceFreeComponents(List<NodeSpec> nodes,
                                                       List<BranchSpec> branches,
                                                       boolean[] active) {
        int n = nodes.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int e = 0; e < branches.size(); e++) {
            if (!active[e]) continue;
            BranchSpec br = branches.get(e);
            union(parent, br.a(), br.b());
        }

        double[] componentCapacitance = new double[n];
        for (int i = 0; i < n; i++) {
            componentCapacitance[find(parent, i)] += nodes.get(i).capacitance();
        }

        for (int e = 0; e < branches.size(); e++) {
            if (!active[e]) continue;
            if (componentCapacitance[find(parent, branches.get(e).a())] <= 0) {
                active[e] = false;
            }
        }
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /**
     * Assemble and solve the implicit-Euler system {@code A h' = rhs} with
     * {@code A = C/dt + L} (L the weighted graph Laplacian over active branches).
     * A is symmetric positive definite on every component that contains capacitance,
     * which the pruning pass guarantees.
     */
    private static double[] solveHeads(List<NodeSpec> nodes, List<BranchSpec> branches,
                                       boolean[] active, double[] gateScale, double dt) {
        int n = nodes.size();
        double[][] a = new double[n][n];
        double[] rhs = new double[n];

        for (int i = 0; i < n; i++) {
            NodeSpec node = nodes.get(i);
            a[i][i] = node.capacitance() / dt;
            rhs[i] = node.capacitance() / dt * node.head();
        }

        for (int e = 0; e < branches.size(); e++) {
            if (!active[e]) continue;
            BranchSpec br = branches.get(e);
            double c = gateScale[e] * br.conductance();
            a[br.a()][br.a()] += c;
            a[br.b()][br.b()] += c;
            a[br.a()][br.b()] -= c;
            a[br.b()][br.a()] -= c;
            rhs[br.a()] -= c * br.emf();
            rhs[br.b()] += c * br.emf();
        }

        for (int i = 0; i < n; i++) {
            if (a[i][i] == 0) {
                a[i][i] = 1;
                rhs[i] = nodes.get(i).head();
            }
        }

        return n <= DIRECT_SOLVE_LIMIT
                ? gaussianSolve(a, rhs)
                : conjugateGradient(a, rhs);
    }

    private static double[] gaussianSolve(double[][] a, double[] rhs) {
        int n = rhs.length;
        double[] x = Arrays.copyOf(rhs, n);

        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            }
            if (Math.abs(a[pivot][col]) < 1.0e-12) continue;

            double[] tmpRow = a[col]; a[col] = a[pivot]; a[pivot] = tmpRow;
            double tmpVal = x[col]; x[col] = x[pivot]; x[pivot] = tmpVal;

            for (int row = col + 1; row < n; row++) {
                double factor = a[row][col] / a[col][col];
                if (factor == 0) continue;
                for (int k = col; k < n; k++) a[row][k] -= factor * a[col][k];
                x[row] -= factor * x[col];
            }
        }

        for (int row = n - 1; row >= 0; row--) {
            double sum = x[row];
            for (int k = row + 1; k < n; k++) sum -= a[row][k] * x[k];
            x[row] = Math.abs(a[row][row]) < 1.0e-12 ? 0 : sum / a[row][row];
        }
        return x;
    }

    private static double[] conjugateGradient(double[][] a, double[] rhs) {
        int n = rhs.length;
        double[] x = new double[n];
        double[] r = Arrays.copyOf(rhs, n);
        double[] p = Arrays.copyOf(rhs, n);
        double rsOld = dot(r, r);
        double tolerance = Math.max(1.0e-18, 1.0e-16 * rsOld);

        for (int iter = 0; iter < 20 * n && rsOld > tolerance; iter++) {
            double[] ap = multiply(a, p);
            double pap = dot(p, ap);
            if (pap <= 0) break;
            double alpha = rsOld / pap;
            for (int i = 0; i < n; i++) {
                x[i] += alpha * p[i];
                r[i] -= alpha * ap[i];
            }
            double rsNew = dot(r, r);
            double beta = rsNew / rsOld;
            for (int i = 0; i < n; i++) p[i] = r[i] + beta * p[i];
            rsOld = rsNew;
        }
        return x;
    }

    private static double[] multiply(double[][] a, double[] v) {
        int n = v.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            double[] row = a[i];
            for (int j = 0; j < n; j++) sum += row[j] * v[j];
            out[i] = sum;
        }
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }
}
