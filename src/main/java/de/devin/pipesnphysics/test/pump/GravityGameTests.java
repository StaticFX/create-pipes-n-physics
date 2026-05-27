package de.devin.pipesnphysics.test.pump;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.test.helper.FluidTestConfig;
import de.devin.pipesnphysics.test.helper.PipeConfig;
import de.devin.pipesnphysics.test.helper.TestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class GravityGameTests {

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 3000)
    public static void oneBlockFall(GameTestHelper helper) {
        var testConfig = new FluidTestConfig(
                new BlockPos(0, 3,  0),
                new BlockPos(0, 1,  0),
                1000,
                null
        );

        TestHelper.fillSourceAndAwaitDest(helper, testConfig);
    }

    @GameTest(template = "gravity/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 3000)
    public static void twoBlockFall(GameTestHelper helper) {
        var testConfig = new FluidTestConfig(
                new BlockPos(0, 4,  0),
                new BlockPos(0, 1,  0),
                1000,
                null
        );

        TestHelper.fillSourceAndAwaitDest(helper, testConfig);
    }


    @GameTest(template = "gravity/5_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 3000)
    public static void fiveBlockFall(GameTestHelper helper) {
        var testConfig = new FluidTestConfig(
                new BlockPos(0, 7,  0),
                new BlockPos(0, 1,  0),
                1000,
                null
        );

        TestHelper.fillSourceAndAwaitDest(helper, testConfig);
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
        var sourceBlock = helper.absolutePos(new BlockPos(0, 3, 0));

        TestHelper.fillTankAt(helper, sourceBlock);

        helper.runAfterDelay(60, () -> {
            if (TestHelper.getFillAmountOfTank(helper, sourceBlock) == 8000) {
                helper.fail("Source tank did not lose any fluid");
            }

            helper.runAfterDelay(600, () -> {
                var destTank = helper.absolutePos(new BlockPos(2, 3, 0));

                int srcFill = TestHelper.getFillAmountOfTank(helper, sourceBlock);
                int dstFill = TestHelper.getFillAmountOfTank(helper, destTank);
                int total = srcFill + dstFill;
                float diff = total > 0 ? Math.abs(srcFill - dstFill) / (float) total : 0;
                if (diff > 0.05f) {
                    helper.fail("Tanks not within 5% of equal: src=" + srcFill + " dst=" + dstFill);
                }

                helper.succeed();
            });
        });
    }
}
