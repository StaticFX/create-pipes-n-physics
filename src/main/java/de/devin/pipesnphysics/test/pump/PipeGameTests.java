package de.devin.pipesnphysics.test.pump;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.test.helper.Assertions;
import de.devin.pipesnphysics.test.helper.FluidTestConfig;
import de.devin.pipesnphysics.test.helper.TestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class PipeGameTests {

    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 2400)
    public static void singlePump(GameTestHelper helper) {
        var config = new FluidTestConfig(new BlockPos(0, 1, 1), new BlockPos(4, 1, 1), 600, null);
        TestHelper.fillSourceAndAwaitDest(helper, config);
    }

    @GameTest(template = "piping/single_pump_with_tank", templateNamespace = PipesNPhysics.ID, timeoutTicks = 2400)
    public static void singlePumpWithTankNextToIt(GameTestHelper helper) {
        var config = new FluidTestConfig(new BlockPos(3, 1, 0), new BlockPos(0, 1, 0), 600, null);
        TestHelper.fillSourceAndAwaitDest(helper, config);
    }

    @GameTest(template = "piping/double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 2400)
    public static void doublePump(GameTestHelper helper) {
        var config = new FluidTestConfig(new BlockPos(8, 1, 0), new BlockPos(0, 1, 0), 600, null);
        TestHelper.fillSourceAndAwaitDest(helper, config);
    }

    @GameTest(template = "piping/unpowered_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void unpoweredPump(GameTestHelper helper) {
        var sourceBlock = helper.absolutePos(new BlockPos(2, 1, 0));
        TestHelper.fillTankAt(helper, sourceBlock);

        helper.runAfterDelay(60, () -> {
            if (TestHelper.getFillAmountOfTank(helper, sourceBlock) != 8000) {
                helper.fail("Source tank lost water even though pump is not powered");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "piping/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 660)
    public static void pipeOpenEnd(GameTestHelper helper) {
        var sourceBlock = helper.absolutePos(new BlockPos(2, 1, 0));
        TestHelper.fillTankAt(helper, sourceBlock);

        helper.runAfterDelay(660, () -> {
            int fill = TestHelper.getFillAmountOfTank(helper, sourceBlock);
            if (fill != 7000) {
                helper.fail("Source tank did not lose exactly 1 block of fluid, fill=" + fill);
            }

            var destBlock = helper.absolutePos(new BlockPos(0, 1, 0));
            if (helper.getLevel().getBlockState(destBlock).isAir()) {
                helper.fail("Dest block is not at " + destBlock);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 2400)
    public static void pumpLongPipe(GameTestHelper helper) {
        var config = new FluidTestConfig(new BlockPos(7, 1, 0), new BlockPos(0, 1, 0), 600, null);
        TestHelper.fillSourceAndAwaitDest(helper, config);
    }

    @GameTest(template = "piping/no_flow", templateNamespace = PipesNPhysics.ID, timeoutTicks = 660)
    public static void noFlow(GameTestHelper helper) {
        var sourceBlock = helper.absolutePos(new BlockPos(2, 1, 0));
        var destBlock = helper.absolutePos(new BlockPos(0, 1, 0));

        TestHelper.fillTankAt(helper, sourceBlock);

        helper.runAfterDelay(60, () -> {
            if (TestHelper.getFillAmountOfTank(helper, sourceBlock) != 8000) {
                helper.fail("Source tank did drain");
            }
            if (TestHelper.getFillAmountOfTank(helper, destBlock) != 0) {
                helper.fail("Sink tank did receive fluid");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "piping/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 660)
    public static void chargingMaxRange(GameTestHelper helper) {
        var sinkBlock = helper.absolutePos(new BlockPos(0, 1, 0));

        helper.runAfterDelay(600, () -> {
            int sinkFill = TestHelper.getFillAmountOfTank(helper, sinkBlock);
            if (sinkFill <= 0) {
                helper.fail("Sink did not receive any fluid from pump at max range");
            }
            helper.succeed();
        });
    }
}
