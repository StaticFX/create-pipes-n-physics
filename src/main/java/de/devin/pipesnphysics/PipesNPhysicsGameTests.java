package de.devin.pipesnphysics;

import de.devin.pipesnphysics.test.helper.FluidTestConfig;
import de.devin.pipesnphysics.test.helper.PipeConfig;
import de.devin.pipesnphysics.test.helper.TestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the rewritten physics engine.
 * Run with: ./gradlew runGameTestServer
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class PipesNPhysicsGameTests {


    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void singlePump(GameTestHelper helper) {

        var testConfig = new FluidTestConfig(
                new BlockPos(0, 1,  1),
                new BlockPos(4, 1,  1),
                40,
                new PipeConfig(new BlockPos[]{
                        new BlockPos(1, 1, 1),
                        new BlockPos(3, 1, 1),
                })
        );

        TestHelper.fillSourceAndAwaitDest(helper, testConfig);
    }





}
