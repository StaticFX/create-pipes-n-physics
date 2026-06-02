package de.devin.pipesnphysics.test.pump;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.handler.FluidTransportHandler;
import de.devin.pipesnphysics.test.helper.Assertions;
import de.devin.pipesnphysics.test.helper.FluidTestConfig;
import de.devin.pipesnphysics.test.helper.TestHelper;
import de.devin.pipesnphysics.test.lifecycle.LifecycleAssertions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class GravityGameTests {

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 3000)
    public static void oneBlockFall(GameTestHelper helper) {
        var config = new FluidTestConfig(new BlockPos(0, 3, 0), new BlockPos(0, 1, 0), 1000, null);
        TestHelper.fillSourceAndAwaitDest(helper, config);
    }

    @GameTest(template = "gravity/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 3000)
    public static void twoBlockFall(GameTestHelper helper) {
        var config = new FluidTestConfig(new BlockPos(0, 4, 0), new BlockPos(0, 1, 0), 1000, null);
        TestHelper.fillSourceAndAwaitDest(helper, config);
    }

    @GameTest(template = "gravity/5_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 3000)
    public static void fiveBlockFall(GameTestHelper helper) {
        var config = new FluidTestConfig(new BlockPos(0, 7, 0), new BlockPos(0, 1, 0), 1000, null);
        TestHelper.fillSourceAndAwaitDest(helper, config);
    }

    @GameTest(template = "gravity/open_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 1200)
    public static void openEndedPipe(GameTestHelper helper) {
        helper.runAfterDelay(600, () -> {
            var sourceBlock = helper.absolutePos(new BlockPos(0, 3, 0));
            if (TestHelper.getFillAmountOfTank(helper, sourceBlock) != 7000) {
                helper.fail("Source tank did not lose exactly 1 block");
            }

            var waterPosition = helper.absolutePos(new BlockPos(0, 1, 0));
            if (helper.getLevel().getBlockState(waterPosition).isAir()) {
                helper.fail("Water block is not at " + waterPosition);
            }

            helper.succeed();
        });
    }

    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 1200)
    public static void simpleFluidLeveling(GameTestHelper helper) {
        var tankA = helper.absolutePos(new BlockPos(0, 3, 0));
        var tankB = helper.absolutePos(new BlockPos(2, 3, 0));

        TestHelper.fillTankAt(helper, tankA);

        helper.runAfterDelay(60, () -> {
            if (TestHelper.getFillAmountOfTank(helper, tankA) == 8000) {
                helper.fail("Source tank did not lose any fluid");
            }

            helper.runAfterDelay(600, () -> {
                int srcFill = TestHelper.getFillAmountOfTank(helper, tankA);
                int dstFill = TestHelper.getFillAmountOfTank(helper, tankB);
                int total = srcFill + dstFill;
                float diff = total > 0 ? Math.abs(srcFill - dstFill) / (float) total : 0;
                if (diff > 0.05f) {
                    helper.fail("Tanks not within 5% of equal: src=" + srcFill + " dst=" + dstFill);
                }
                helper.succeed();
            });
        });
    }

    /**
     * Fills tank B instead of A. Verifies flow goes B->A with correct visuals.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 1200)
    public static void reverseLevelingDirection(GameTestHelper helper) {
        var tankA = helper.absolutePos(new BlockPos(0, 3, 0));
        var tankB = helper.absolutePos(new BlockPos(2, 3, 0));

        TestHelper.fillTankAt(helper, tankB);

        LifecycleAssertions.awaitCharging(helper, tankB, 100, () -> {
            LifecycleAssertions.awaitAnyPipeHasFlow(helper, tankB, 40, () -> {
                LifecycleAssertions.assertVisualFrontAdvanced(helper, tankB, 0.1f,
                        "Visual front not advancing for B->A flow");

                helper.runAfterDelay(600, () -> {
                    int fillA = TestHelper.getFillAmountOfTank(helper, tankA);
                    int fillB = TestHelper.getFillAmountOfTank(helper, tankB);
                    int total = fillA + fillB;
                    float diff = total > 0 ? Math.abs(fillA - fillB) / (float) total : 0;
                    if (diff > 0.05f) {
                        helper.fail("Tanks not within 5% of equal: A=" + fillA + " B=" + fillB);
                    }
                    helper.succeed();
                });
            });
        });
    }

    /**
     * Fills A, equalizes, drains A, verifies B->A reverse flow with visuals in both directions.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 2400)
    public static void bidirectionalLeveling(GameTestHelper helper) {
        var tankA = helper.absolutePos(new BlockPos(0, 3, 0));
        var tankB = helper.absolutePos(new BlockPos(2, 3, 0));

        TestHelper.fillTankAt(helper, tankA);

        LifecycleAssertions.awaitAnyPipeHasFlow(helper, tankA, 200, () -> {
            LifecycleAssertions.assertVisualFrontAdvanced(helper, tankA, 0.1f,
                    "A->B: visual front not advancing");

            Assertions.pollUntil(helper, "A->B equalized", 800, tankA, () -> {
                int a = TestHelper.getFillAmountOfTank(helper, tankA);
                int b = TestHelper.getFillAmountOfTank(helper, tankB);
                return a > 0 && b > 0 && Math.abs(a - b) < 500;
            }, () -> {
                Assertions.assertConservation(helper, tankA, tankB, 8000);

                var handler = helper.getLevel().getCapability(
                        Capabilities.FluidHandler.BLOCK, tankA, null);
                int inA = TestHelper.getFillAmountOfTank(helper, tankA);
                handler.drain(inA, IFluidHandler.FluidAction.EXECUTE);
                int bAfterDrain = TestHelper.getFillAmountOfTank(helper, tankB);

                FluidTransportHandler.clearCooldown(helper.getLevel(), tankA);

                LifecycleAssertions.awaitAnyPipeHasFlow(helper, tankA, 400, () -> {
                    LifecycleAssertions.assertVisualFrontAdvanced(helper, tankA, 0.1f,
                            "B->A: visual front not advancing");

                    Assertions.pollUntil(helper, "B->A equalized", 800, tankA, () -> {
                        int a = TestHelper.getFillAmountOfTank(helper, tankA);
                        int b = TestHelper.getFillAmountOfTank(helper, tankB);
                        return Math.abs(a - b) < 500;
                    }, () -> {
                        int finalTotal = TestHelper.getFillAmountOfTank(helper, tankA)
                                + TestHelper.getFillAmountOfTank(helper, tankB);
                        if (Math.abs(finalTotal - bAfterDrain) > 100) {
                            helper.fail("Conservation violated: total=" + finalTotal
                                    + " expected~" + bAfterDrain);
                        }
                        helper.succeed();
                    });
                });
            });
        });
    }

    /**
     * Long pipe equalization: charging and draining must each complete within 1 second (20 ticks).
     */
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 2400)
    public static void longEqualization(GameTestHelper helper) {
        var tankA = helper.absolutePos(new BlockPos(0, 1, 0));
        var tankB = helper.absolutePos(new BlockPos(0, 1, 9));

        TestHelper.fillTankAt(helper, tankA);

        LifecycleAssertions.awaitCharging(helper, tankA, 40, () -> {
            Assertions.pollUntil(helper, "charging-primed", 40, tankA, () -> {
                var net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), tankA);
                if (net == null) return false;
                return net.edges().stream()
                        .anyMatch(e -> e.visualFrontPos() >= e.length());
            }, () -> {
                LifecycleAssertions.assertAnyPipeHasFlow(helper, tankA,
                        "No pipe flow after charging primed");

                Assertions.pollUntil(helper, "equalized", 1200, tankA, () -> {
                    int a = TestHelper.getFillAmountOfTank(helper, tankA);
                    int b = TestHelper.getFillAmountOfTank(helper, tankB);
                    return a > 0 && b > 0 && Math.abs(a - b) < 500;
                }, () -> {
                    Assertions.assertConservation(helper, tankA, tankB, 8000);

                    LifecycleAssertions.awaitDraining(helper, tankA, 200, () -> {
                        Assertions.pollUntil(helper, "drain-complete", 40, tankA, () -> {
                            var net = FluidTransportHandler.getCachedNetwork(helper.getLevel(), tankA);
                            if (net == null) return true;
                            return net.edges().stream()
                                    .allMatch(e -> e.visualFrontPos() <= 0);
                        }, () -> {
                            LifecycleAssertions.assertNoPipeHasFlow(helper, tankA,
                                    "Pipes still have flow after drain");
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }
}
