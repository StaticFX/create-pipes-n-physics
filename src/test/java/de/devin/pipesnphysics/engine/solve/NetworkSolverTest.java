package de.devin.pipesnphysics.engine.solve;

import de.devin.pipesnphysics.engine.solve.NetworkSolver.BranchSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.NodeSpec;
import de.devin.pipesnphysics.engine.solve.NetworkSolver.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSolverTest {
    private static final double SUCTION_LIMIT = 8;
    private static final double TANK_CAPACITANCE = 8000;

    private static Result step(List<NodeSpec> nodes, List<BranchSpec> branches) {
        return NetworkSolver.solve(nodes, branches, 1, SUCTION_LIMIT);
    }

    /** Apply one tick's net inflow back onto reservoir heads, like the engine does. */
    private static List<NodeSpec> advance(List<NodeSpec> nodes, Result result) {
        List<NodeSpec> next = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            NodeSpec node = nodes.get(i);
            double newHead = node.capacitance() > 0
                    ? node.head() + result.netInflow()[i] / node.capacitance()
                    : node.head();
            next.add(new NodeSpec(node.capacitance(), newHead));
        }
        return next;
    }

    @Test
    void twoTanksEqualizeMonotonicallyWithoutOscillation() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 10),
                new NodeSpec(TANK_CAPACITANCE, 6));
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 50));

        double previousGap = 4;
        for (int tick = 0; tick < 2000; tick++) {
            Result result = step(nodes, branches);
            assertTrue(result.flows()[0] >= -1e-9, "flow must never reverse during equalization");
            nodes = advance(nodes, result);

            double gap = nodes.get(0).head() - nodes.get(1).head();
            assertTrue(gap >= -1e-9, "higher tank must never drop below lower tank (overshoot)");
            assertTrue(gap <= previousGap + 1e-9, "gap must shrink monotonically");
            previousGap = gap;
        }
        assertEquals(0, previousGap, 1e-3, "tanks should settle at equal surfaces");
    }

    @Test
    void unequalFootprintsSettleAtEqualSurfacesNotEqualVolumes() {
        double smallCapacitance = 8000;
        double bigCapacitance = 32000;
        List<NodeSpec> nodes = List.of(
                new NodeSpec(smallCapacitance, 12),
                new NodeSpec(bigCapacitance, 4));
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 100));

        double initialVolume = smallCapacitance * 12 + bigCapacitance * 4;
        for (int tick = 0; tick < 5000; tick++) {
            nodes = advance(nodes, step(nodes, branches));
        }

        assertEquals(nodes.get(0).head(), nodes.get(1).head(), 1e-3, "surfaces equalize");
        double finalVolume = smallCapacitance * nodes.get(0).head() + bigCapacitance * nodes.get(1).head();
        assertEquals(initialVolume, finalVolume, 1e-6, "volume conserved");
        double movedFromSmall = smallCapacitance * (12 - nodes.get(0).head());
        double movedToBig = bigCapacitance * (nodes.get(1).head() - 4);
        assertEquals(movedFromSmall, movedToBig, 1e-6);
        assertTrue(Math.abs(nodes.get(0).head() - 12) > 1, "small tank actually drained");
    }

    @Test
    void gravityTowerFlowsDownhillOnly() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 10));
        List<BranchSpec> branches = List.of(BranchSpec.passive(0, 1, 10));

        Result result = step(nodes, branches);
        assertTrue(result.flows()[0] > 0, "fluid flows from the tower down");

        List<NodeSpec> reversed = List.of(nodes.get(1), nodes.get(0));
        Result reversedResult = step(reversed, branches);
        assertTrue(reversedResult.flows()[0] < 0, "direction follows the head, not the node order");
    }

    @Test
    void pumpPushesUntilHeadDifferenceMatchesPumpHead() {
        double pumpHead = 16;
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 5),
                new NodeSpec(0, 0),
                new NodeSpec(TANK_CAPACITANCE, 5));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 40, 0, +1, Double.NaN, 0),
                new BranchSpec(1, 2, 40, pumpHead, +1, Double.NaN, 0));

        double previousFlow = Double.MAX_VALUE;
        for (int tick = 0; tick < 8000; tick++) {
            Result result = step(nodes, branches);
            assertTrue(result.flows()[1] >= -1e-9, "pump branch never flows backwards");
            assertTrue(result.flows()[1] <= previousFlow + 1e-9, "flow decays monotonically toward equilibrium");
            previousFlow = result.flows()[1];
            nodes = advance(nodes, result);
        }

        double finalGap = nodes.get(2).head() - nodes.get(0).head();
        assertEquals(pumpHead, finalGap, 0.01, "pump holds exactly its head worth of surface difference");
    }

    @Test
    void weakPumpAgainstTallColumnIsBlockedByCheckValveNotReversed() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 5),
                new NodeSpec(0, 0),
                new NodeSpec(TANK_CAPACITANCE, 40));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 40, 0, +1, Double.NaN, 0),
                new BranchSpec(1, 2, 40, 10, +1, Double.NaN, 0));

        Result result = step(nodes, branches);
        assertEquals(0, result.flows()[0], 1e-9, "check valve blocks back-flow");
        assertEquals(0, result.flows()[1], 1e-9, "check valve blocks back-flow");
        assertFalse(result.active()[1], "overpowered pump branch is deactivated");
    }

    @Test
    void threeTankStarEqualizesThroughJunction() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 10),
                new NodeSpec(TANK_CAPACITANCE, 8),
                new NodeSpec(TANK_CAPACITANCE, 3),
                new NodeSpec(0, 0));
        List<BranchSpec> branches = List.of(
                BranchSpec.passive(0, 3, 60),
                BranchSpec.passive(1, 3, 60),
                BranchSpec.passive(2, 3, 60));

        for (int tick = 0; tick < 4000; tick++) {
            Result result = step(nodes, branches);
            double junctionBalance = result.netInflow()[3];
            assertEquals(0, junctionBalance, 1e-9, "junction stores no fluid");
            nodes = advance(nodes, result);
        }

        assertEquals(7, nodes.get(0).head(), 1e-3);
        assertEquals(7, nodes.get(1).head(), 1e-3);
        assertEquals(7, nodes.get(2).head(), 1e-3);
    }

    @Test
    void handlerFreeLoopCarriesNoFlow() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(0, 0), new NodeSpec(0, 0), new NodeSpec(0, 0));
        List<BranchSpec> branches = List.of(
                BranchSpec.passive(0, 1, 40),
                BranchSpec.passive(1, 2, 40),
                BranchSpec.passive(2, 0, 40));

        Result result = step(nodes, branches);
        for (int e = 0; e < 3; e++) {
            assertEquals(0, result.flows()[e], 0.0, "no reservoir, no flow");
            assertFalse(result.active()[e]);
        }
        for (double head : result.heads()) {
            assertFalse(Double.isNaN(head), "heads stay finite");
        }
    }

    @Test
    void crestAboveSuctionLimitStopsTheSiphonLowerCrestRuns() {
        List<NodeSpec> tanks = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 50));

        BranchSpec tooHigh = new BranchSpec(0, 1, 40, 0, 0, 75, 0.5);
        Result blocked = step(tanks, List.of(tooHigh));
        assertEquals(0, blocked.flows()[0], 1e-9, "crest far above the heads cavitates");

        BranchSpec siphonable = new BranchSpec(0, 1, 40, 0, 0, 56, 0.5);
        Result flowing = step(tanks, List.of(siphonable));
        assertTrue(flowing.flows()[0] > 0, "a modest crest is siphoned over");
    }

    @Test
    void crestNearTheSuctionLimitTapersInsteadOfCliff() {
        List<NodeSpec> tanks = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 50));

        double fullFlow = step(tanks, List.of(BranchSpec.passive(0, 1, 40))).flows()[0];
        // Crest 7 blocks above the 60 supply: inside the 8-block suction limit but
        // within the taper band, so a reduced trickle siphons over. Measured against
        // the friction-free supply elevation (not the flow-dragged solved head), which
        // is why a genuine 7-block rise is needed to taper rather than a low crest the
        // friction gradient used to drag below the limit.
        BranchSpec marginal = new BranchSpec(0, 1, 40, 0, 0, 67, 0.5);
        double taperedFlow = step(tanks, List.of(marginal)).flows()[0];

        assertTrue(taperedFlow > 0, "inside the taper band a trickle still flows");
        assertTrue(taperedFlow < fullFlow * 0.9, "near the limit, flow is visibly reduced");
    }

    /**
     * Regression for "spin the pump up and the siphon dies": a strong pump pulling
     * over a modest crest must not gate itself off. At high RPM the suction-side
     * friction drawdown drags the SOLVED junction head far below the supply, but the
     * liquid column's existence depends on the supply ELEVATION and pump lift, not on
     * that flow-rate artifact — so more RPM may never turn a working line off.
     */
    @Test
    void strongPumpDoesNotCavitationGateItsOwnSuctionSide() {
        double suctionConductance = 120.0 / 11;   // a ~10-cell suction run
        double pumpInternalConductance = 4;        // flowPerRpm / headPerRpm
        for (double rpm : new double[]{8, 32, 96, 128, 256}) {
            double pumpHead = rpm * 0.25;          // |RPM| * headPerRpm
            List<NodeSpec> nodes = List.of(
                    new NodeSpec(TANK_CAPACITANCE, 64),  // source tank
                    new NodeSpec(0, 0),                  // pump
                    new NodeSpec(TANK_CAPACITANCE, 50)); // destination tank
            List<BranchSpec> branches = List.of(
                    new BranchSpec(0, 1, suctionConductance, 0, +1, 68, 0.5),  // crest only 4 blocks up
                    new BranchSpec(1, 2, pumpInternalConductance, pumpHead, +1, Double.NaN, 0));

            Result result = step(nodes, branches);
            assertTrue(result.flows()[0] > 0,
                    "suction line must keep flowing at rpm " + rpm
                            + " (crest only 4 blocks above a 64 supply, well inside the suction limit) "
                            + "but flow was " + result.flows()[0]);
        }
    }

    /**
     * The friction-free crest gate must not LEAK a reservoir's head across a crest it
     * cannot itself clear. node0 (60) is walled off by a crest at 75 (> 60 + suction);
     * node1 (30) and node2 (10) sit either side of a crest at 40 that neither can
     * clear. Nothing may flow — in particular node0's head must not propagate past the
     * broken 75 crest to falsely prime the 40 crest and drain node1 into node2.
     */
    @Test
    void brokenCrestDoesNotLeakHeadToPrimeADownstreamCrest() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 60),
                new NodeSpec(TANK_CAPACITANCE, 30),
                new NodeSpec(TANK_CAPACITANCE, 10));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 40, 0, 0, 75, 0.5),   // breaks: 75 > 60 + suctionLimit
                new BranchSpec(1, 2, 40, 0, 0, 40, 0.5));  // 40 is > suctionLimit above both 30 and 10

        Result result = step(nodes, branches);
        assertEquals(0, result.flows()[0], 1e-9, "the 75-block crest walls off node0");
        assertEquals(0, result.flows()[1], 1e-9,
                "node0's head must not leak across the broken crest to prime the 40-block crest");
        assertTrue(result.crestBlocked()[1], "the downstream crest must report as broken");
    }

    @Test
    void pumpHeadLiftsTheCrestGate() {
        List<NodeSpec> tanks = List.of(
                new NodeSpec(TANK_CAPACITANCE, 20),
                new NodeSpec(TANK_CAPACITANCE, 10));

        BranchSpec unpowered = new BranchSpec(0, 1, 40, 0, 0, 30, 0.5);
        assertEquals(0, step(tanks, List.of(unpowered)).flows()[0], 1e-9,
                "an unpowered line cannot hold a column 15 blocks above its head");

        BranchSpec pumped = new BranchSpec(0, 1, 40, 20, +1, 30, 0.5);
        assertTrue(step(tanks, List.of(pumped)).flows()[0] > 0,
                "a pump's head raises the pressure profile over the same rise");
    }

    @Test
    void volumeIsConservedOnRandomNetworks() {
        Random random = new Random(42);
        for (int trial = 0; trial < 50; trial++) {
            int reservoirCount = 2 + random.nextInt(6);
            int junctionCount = random.nextInt(6);
            int n = reservoirCount + junctionCount;

            List<NodeSpec> nodes = new ArrayList<>();
            for (int i = 0; i < reservoirCount; i++) {
                nodes.add(new NodeSpec(1000 + random.nextInt(50000), random.nextDouble() * 100));
            }
            for (int i = 0; i < junctionCount; i++) {
                nodes.add(new NodeSpec(0, 0));
            }

            List<BranchSpec> branches = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                branches.add(new BranchSpec(random.nextInt(i), i,
                        1 + random.nextDouble() * 100,
                        random.nextDouble() < 0.3 ? random.nextDouble() * 30 - 10 : 0,
                        random.nextInt(3) - 1,
                        Double.NaN, 0));
            }

            Result result = step(nodes, branches);
            double total = 0;
            for (int i = 0; i < n; i++) {
                if (nodes.get(i).capacitance() > 0) {
                    total += result.netInflow()[i];
                } else {
                    assertEquals(0, result.netInflow()[i], 1e-6,
                            "junctions never store fluid (trial " + trial + ")");
                }
            }
            assertEquals(0, total, 1e-6, "total volume conserved (trial " + trial + ")");
        }
    }

    @Test
    void longRunWithPumpAndJunctionsNeverFlipsFlowDirection() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 12),
                new NodeSpec(0, 0),
                new NodeSpec(0, 0),
                new NodeSpec(24000, 10),
                new NodeSpec(TANK_CAPACITANCE, 2));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 30, 0, +1, Double.NaN, 0),
                new BranchSpec(1, 2, 30, 12, +1, Double.NaN, 0),
                BranchSpec.passive(2, 3, 25),
                BranchSpec.passive(2, 4, 25));

        int totalTicks = 5000;
        int[] signFlips = new int[branches.size()];
        int lastFlipTick = -1;
        double[] lastSign = new double[branches.size()];
        for (int tick = 0; tick < totalTicks; tick++) {
            Result result = step(nodes, branches);
            for (int e = 0; e < branches.size(); e++) {
                double sign = Math.abs(result.flows()[e]) < 1e-6 ? 0 : Math.signum(result.flows()[e]);
                if (sign != 0 && lastSign[e] != 0 && sign != lastSign[e]) {
                    signFlips[e]++;
                    lastFlipTick = tick;
                }
                if (sign != 0) lastSign[e] = sign;
            }
            nodes = advance(nodes, result);
        }

        for (int e = 0; e < branches.size(); e++) {
            assertTrue(signFlips[e] <= 1,
                    "branch " + e + " flipped " + signFlips[e] + " times; one regime change is physics, more is oscillation");
        }
        assertTrue(lastFlipTick < totalTicks - 1000,
                "steady state must be quiet, but a flow direction changed at tick " + lastFlipTick);
    }

    /**
     * Regression for the in-game pump deadlock: a strong pump fed through a junction,
     * all pipes at tank level. The crest gate must not let the pump's suction
     * drawdown talk itself into shutting the whole line off (h_pump pinned at
     * tankHead - pumpHead with zero flow everywhere).
     */
    @Test
    void pumpFedThroughJunctionAtPipeLevelKeepsFlowing() {
        double pumpHead = 36.25;
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 56.39),  // source tank
                new NodeSpec(0, 0),                     // junction
                new NodeSpec(0, 0),                     // pump
                new NodeSpec(TANK_CAPACITANCE, 56.00)); // destination tank
        double crest = 56.5;
        double pumpCurveConductance = 4;
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 1, 60, 0, 0, crest, 0.5),
                new BranchSpec(1, 2, 60, 0, +1, crest, 0.5),
                new BranchSpec(2, 3, pumpCurveConductance, pumpHead, +1, crest, 0.25));

        Result result = step(nodes, branches);
        assertTrue(result.flows()[2] > 5,
                "pump must move fluid, not deadlock at h = tank - pumpHead (got "
                        + result.flows()[2] + ")");
        assertEquals(result.flows()[1], result.flows()[2], 1e-6,
                "suction line feeds the pump exactly");
        for (double head : result.heads()) {
            assertTrue(head > 40, "no node may be pulled toward vacuum, got " + head);
        }
    }

    @Test
    void disconnectedAndDegenerateInputsAreHandled() {
        List<NodeSpec> nodes = List.of(
                new NodeSpec(TANK_CAPACITANCE, 10),
                new NodeSpec(0, 0),
                new NodeSpec(TANK_CAPACITANCE, 4));
        List<BranchSpec> branches = List.of(
                new BranchSpec(0, 0, 40, 0, 0, Double.NaN, 0),
                new BranchSpec(0, 2, 0, 0, 0, Double.NaN, 0),
                new BranchSpec(0, 9, 40, 0, 0, Double.NaN, 0));

        Result result = step(nodes, branches);
        for (double flow : result.flows()) assertEquals(0, flow, 0.0);
        for (double head : result.heads()) assertFalse(Double.isNaN(head));
        assertEquals(10, result.heads()[0], 1e-9, "isolated tank keeps its head");
    }
}
