package de.devin.pipesnphysics.test.lifecycle;

import de.devin.pipesnphysics.handler.FluidTransportHandler;
import de.devin.pipesnphysics.physics.EdgePhase;
import de.devin.pipesnphysics.physics.FluidNetwork;
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
                () -> hasPhase(netPos, EdgePhase.CHARGING), next);
    }

    public static void awaitAllFlowing(GameTestHelper helper, BlockPos netPos, int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "all-FLOWING", maxTicks, netPos,
                () -> allPhase(netPos, EdgePhase.FLOWING), next);
    }

    public static void awaitDraining(GameTestHelper helper, BlockPos netPos, int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "DRAINING", maxTicks, netPos,
                () -> hasPhase(netPos, EdgePhase.DRAINING)
                        || allPhase(netPos, EdgePhase.EMPTY)
                        || networkGone(netPos),
                next);
    }

    public static void awaitAllEmpty(GameTestHelper helper, BlockPos netPos, int maxTicks, Runnable next) {
        Assertions.pollUntil(helper, "all-EMPTY", maxTicks, netPos,
                () -> networkGone(netPos) || allPhase(netPos, EdgePhase.EMPTY),
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

    public static boolean hasPhase(BlockPos netPos, EdgePhase phase) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(netPos);
        return net != null && net.edges().stream().anyMatch(e -> e.phase() == phase);
    }

    public static boolean allPhase(BlockPos netPos, EdgePhase phase) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(netPos);
        return net != null && !net.edges().isEmpty()
                && net.edges().stream().allMatch(e -> e.phase() == phase);
    }

    public static boolean networkGone(BlockPos netPos) {
        return FluidTransportHandler.getCachedNetwork(netPos) == null;
    }
}
