package de.devin.pipesnphysics.test.helper;

import de.devin.pipesnphysics.handler.FluidTransportHandler;
import de.devin.pipesnphysics.physics.FluidNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.function.BooleanSupplier;

/**
 * Generic assertions and polling helpers for fluid simulation game tests.
 */
public final class Assertions {

    private Assertions() {}

    public static void assertTankFill(GameTestHelper helper, BlockPos pos, int expected, String msg) {
        int actual = TestHelper.getFillAmountOfTank(helper, pos);
        if (actual != expected)
            helper.fail(msg + " (expected=" + expected + " actual=" + actual + ")");
    }

    public static void assertTankAbove(GameTestHelper helper, BlockPos pos, int min, String msg) {
        int actual = TestHelper.getFillAmountOfTank(helper, pos);
        if (actual < min)
            helper.fail(msg + " (min=" + min + " actual=" + actual + ")");
    }

    public static void assertConservation(GameTestHelper helper, BlockPos src, BlockPos dst, int total) {
        int srcFill = TestHelper.getFillAmountOfTank(helper, src);
        int dstFill = TestHelper.getFillAmountOfTank(helper, dst);
        if (srcFill + dstFill != total)
            helper.fail("Conservation violated: src=" + srcFill + " dst=" + dstFill
                    + " total=" + (srcFill + dstFill) + " expected=" + total);
    }

    public static void pollUntil(GameTestHelper helper, String phase, int maxTicks,
                                  BlockPos netPos, BooleanSupplier condition, Runnable then) {
        pollInner(helper, phase, maxTicks, netPos, condition, then, 0);
    }

    private static void pollInner(GameTestHelper helper, String phase, int maxTicks,
                                   BlockPos netPos, BooleanSupplier condition,
                                   Runnable then, int tick) {
        if (tick > maxTicks) {
            helper.fail("[" + phase + "] timed out after " + maxTicks + "t. Edges: " + describeEdges(helper, netPos));
            return;
        }
        helper.runAfterDelay(1, () -> {
            if (condition.getAsBoolean()) {
                then.run();
            } else {
                pollInner(helper, phase, maxTicks, netPos, condition, then, tick + 1);
            }
        });
    }

    public static String describeEdges(GameTestHelper helper, BlockPos netPos) {
        FluidNetwork net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), netPos);
        if (net == null) return "no network";
        return net.edges().stream()
                .map(e -> e.phase() + "(" + String.format("%.1f", e.frontPos()) + "/" + e.length() + ")")
                .toList().toString();
    }
}
