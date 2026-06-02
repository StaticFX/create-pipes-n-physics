package de.devin.pipesnphysics.test.lifecycle;

import de.devin.pipesnphysics.handler.FluidTransportHandler;
import de.devin.pipesnphysics.handler.PipeGraphBuilder;
import de.devin.pipesnphysics.physics.*;
import de.devin.pipesnphysics.test.helper.Assertions;
import de.devin.pipesnphysics.test.helper.TestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Phase-aware assertions for testing the edge lifecycle:
 * EMPTY → CHARGING → FLOWING → DRAINING → EMPTY.
 */
public final class LifecycleAssertions {

    private LifecycleAssertions() {}

    public static void awaitCharging(GameTestHelper helper, BlockPos netPos, int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "CHARGING", maxTicks, netPos,
                () -> hasPhase(helper, netPos, EdgePhase.CHARGING), next);
    }

    public static void awaitAllFlowing(GameTestHelper helper, BlockPos netPos, int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "all-FLOWING", maxTicks, netPos,
                () -> allPhase(helper, netPos, EdgePhase.FLOWING), next);
    }

    /**
     * Poll until every edge has a non-zero flow rate (circuit fully primed).
     * Replaces awaitAllFlowing for the cycling-CHARGING model.
     */
    public static void awaitCircuitPrimed(GameTestHelper helper, BlockPos netPos, int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "circuit-primed", maxTicks, netPos, () -> {
            FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
            if (net == null || net.edges().isEmpty()) return false;
            for (SimEdge edge : net.edges()) {
                if (edge.phase() == EdgePhase.EMPTY || edge.phase() == EdgePhase.DRAINING) return false;
            }
            return net.flowRates() != null && java.util.stream.IntStream.range(0, net.edges().size())
                    .allMatch(i -> net.flowRate(i) != 0);
        }, next);
    }

    public static void awaitDraining(GameTestHelper helper, BlockPos netPos, int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "DRAINING", maxTicks, netPos,
                () -> hasPhase(helper, netPos, EdgePhase.DRAINING)
                        || allPhase(helper, netPos, EdgePhase.EMPTY)
                        || networkGone(helper, netPos),
                next);
    }

    public static void awaitAllEmpty(GameTestHelper helper, BlockPos netPos, int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "all-EMPTY", maxTicks, netPos,
                () -> networkGone(helper, netPos) || allPhase(helper, netPos, EdgePhase.EMPTY),
                next);
    }

    public static void awaitSinkReceiving(GameTestHelper helper, BlockPos sinkPos, BlockPos netPos,
                                           int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "transfer-started", maxTicks, netPos,
                () -> TestHelper.getFillAmountOfTank(helper, sinkPos) > 0, next);
    }

    public static void awaitSourceEmpty(GameTestHelper helper, BlockPos sourcePos, BlockPos netPos,
                                         int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "source-empty", maxTicks, netPos,
                () -> TestHelper.getFillAmountOfTank(helper, sourcePos) == 0, next);
    }

    public static boolean hasPhase(GameTestHelper helper, BlockPos netPos, EdgePhase phase) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
        return net != null && net.edges().stream().anyMatch(e -> e.phase() == phase);
    }

    public static boolean allPhase(GameTestHelper helper, BlockPos netPos, EdgePhase phase) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
        return net != null && !net.edges().isEmpty()
                && net.edges().stream().allMatch(e -> e.phase() == phase);
    }

    public static boolean networkGone(GameTestHelper helper, BlockPos netPos) {
        return FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos) == null;
    }

    // ---- Visual assertions ----

    /**
     * Assert that at least one pipe in the network has a visible Flow object.
     * This verifies Create's pipe renderer would show fluid.
     */
    public static void assertAnyPipeHasFlow(GameTestHelper helper, BlockPos netPos, String msg) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
        if (net == null) { helper.fail(msg + " — no network"); return; }

        for (SimEdge edge : net.edges()) {
            for (PipeEntry entry : edge.pipes()) {
                var pipe = TestHelper.getPipeBehaviourOrFail(helper, entry.pos());
                if (pipe != null && TestHelper.hasAnyFlow(pipe)) return; // found one
            }
        }
        helper.fail(msg + " — no pipe has a Flow object");
    }

    /**
     * Assert that NO pipe in the network has a visible Flow object.
     * Used after draining completes to verify pipes are visually clear.
     */
    public static void assertNoPipeHasFlow(GameTestHelper helper, BlockPos netPos, String msg) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
        if (net == null) return; // no network = no flows, OK

        for (SimEdge edge : net.edges()) {
            for (PipeEntry entry : edge.pipes()) {
                var pipe = TestHelper.getPipeBehaviourOrFail(helper, entry.pos());
                if (pipe != null && TestHelper.hasAnyFlow(pipe)) {
                    helper.fail(msg + " — pipe at " + entry.pos() + " still has flow");
                    return;
                }
            }
        }
    }

    /**
     * Assert that the visual front has advanced past a minimum position.
     * Verifies that the charging animation is progressing, not stuck at 0.
     */
    public static void assertVisualFrontAdvanced(GameTestHelper helper, BlockPos netPos,
                                                  float minPos, String msg) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
        if (net == null) { helper.fail(msg + " — no network"); return; }

        for (SimEdge edge : net.edges()) {
            if (edge.visualFrontPos() >= minPos) return; // found one
        }
        helper.fail(msg + " — visualFrontPos < " + minPos + " on all edges. Edges: "
                + Assertions.describeEdges(helper, netPos));
    }

    /**
     * Assert that the upstream node of at least one CHARGING edge matches the
     * expected source position. Verifies flow direction for the visual.
     */
    public static void assertUpstreamIs(GameTestHelper helper, BlockPos netPos,
                                         BlockPos expectedSourcePos, String msg) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
        if (net == null) { helper.fail(msg + " — no network"); return; }

        NodeId expected = PipeGraphBuilder.nodeOf(expectedSourcePos);
        for (SimEdge edge : net.edges()) {
            if (edge.phase() == EdgePhase.CHARGING && expected.equals(edge.upstreamNode())) {
                return; // correct direction
            }
        }
        // Build diagnostic
        String actual = "none";
        for (SimEdge edge : net.edges()) {
            if (edge.phase() == EdgePhase.CHARGING && edge.upstreamNode() != null) {
                actual = PipeGraphBuilder.posOf(edge.upstreamNode()).toString();
                break;
            }
        }
        helper.fail(msg + " — expected upstream=" + expectedSourcePos
                + " but got " + actual);
    }

    /**
     * Poll until at least one pipe in the network has a visible Flow object.
     */
    public static void awaitAnyPipeHasFlow(GameTestHelper helper, BlockPos netPos,
                                            int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "pipe-has-flow", maxTicks, netPos, () -> {
            FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
            if (net == null) return false;
            for (SimEdge edge : net.edges()) {
                for (PipeEntry entry : edge.pipes()) {
                    var pipe = TestHelper.getPipeBehaviourOrFail(helper, entry.pos());
                    if (pipe != null && TestHelper.hasAnyFlow(pipe)) return true;
                }
            }
            return false;
        }, next);
    }
}
