package de.devin.pipesnphysics.test.lifecycle;

import com.simibubi.create.content.fluids.PipeConnection;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.handler.FluidTransportHandler;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import de.devin.pipesnphysics.physics.EdgePhase;
import de.devin.pipesnphysics.physics.FluidNetwork;
import de.devin.pipesnphysics.physics.PipeEntry;
import de.devin.pipesnphysics.physics.SimEdge;
import de.devin.pipesnphysics.test.helper.Assertions;
import de.devin.pipesnphysics.test.helper.TestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Tick-by-tick lifecycle test for the fluid simulation.
 * Uses the long_pipe template (source tank → pump → 5 pipes → sink tank).
 * Verifies: EMPTY → CHARGING → FLOWING → transfer → DRAINING → EMPTY.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class LifecycleTest {

    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 6000)
    public static void pumpLifecycle(GameTestHelper helper) {
        var src = helper.absolutePos(new BlockPos(7, 1, 0));
        var dst = helper.absolutePos(new BlockPos(0, 1, 0));

        helper.runAfterDelay(10, () -> {
            Assertions.assertTankFill(helper, dst, 0, "Sink should start empty");
            TestHelper.fillTankAt(helper, src);
            Assertions.assertTankFill(helper, src, 8000, "Source should be full");

            LifecycleAssertions.awaitCharging(helper, src, 100, () -> {
                Assertions.assertTankFill(helper, src, 8000, "No transfer during CHARGING");

                LifecycleAssertions.awaitAllFlowing(helper, src, 2000, () -> {
                    Assertions.assertTankAbove(helper, src, 7000, "Source mostly full after charging");

                    LifecycleAssertions.awaitSinkReceiving(helper, dst, src, 200, () -> {
                        Assertions.assertConservation(helper, src, dst, 8000);

                        LifecycleAssertions.awaitSourceEmpty(helper, src, src, 3000, () -> {
                            Assertions.assertTankFill(helper, dst, 8000, "All fluid in sink");

                            LifecycleAssertions.awaitDraining(helper, src, 200, () -> {

                                LifecycleAssertions.awaitAllEmpty(helper, src, 500, () -> {
                                    // All edges must be fully EMPTY with no residual fluid type
                                    FluidNetwork net = FluidTransportHandler.getCachedNetwork(src);
                                    if (net != null) {
                                        for (SimEdge edge : net.edges()) {
                                            if (edge.phase() != EdgePhase.EMPTY) {
                                                helper.fail("After lifecycle, edge " + edge.id()
                                                        + " is " + edge.phase() + " (expected EMPTY)");
                                            }
                                            if (edge.primaryFluid() != null) {
                                                helper.fail("After lifecycle, edge " + edge.id()
                                                        + " still has fluid: " + edge.primaryFluid());
                                            }

                                            for (PipeEntry pipeEntry : edge.pipes()) {
                                                var behaviour = TestHelper.getPipeBehaviourOrFail(helper, pipeEntry.pos());
                                                if (behaviour == null) {
                                                    helper.fail("Pipe at " + pipeEntry.pos() + " does not have a fluid transport behavior");
                                                }

                                                var flow = behaviour.getFlow(pipeEntry.to());

                                                if (flow != null && !flow.fluid.isEmpty()) {
                                                    helper.fail("Pipe at " + pipeEntry.pos() + " has non-empty flow on " + pipeEntry.to());
                                                }
                                            }

                                        }
                                    }

                                    Assertions.assertTankFill(helper, dst, 8000, "Sink has all fluid after lifecycle");
                                    helper.succeed();
                                });
                            });
                        });
                    });
                });
            });
        });
    }
}
