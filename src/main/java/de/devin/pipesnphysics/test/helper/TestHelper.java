package de.devin.pipesnphysics.test.helper;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;

public class TestHelper {

    public static void fillSourceAndAwaitDest(GameTestHelper helper, FluidTestConfig relativeConfig) {
        var config = convertToAbsolutePos(helper, relativeConfig);
        helper.runAfterDelay(5, () -> {
            Assertions.assertTankFill(helper, config.sink(), 0, "Sink is not empty");
            fillTankAt(helper, config.source());

            helper.runAfterDelay(config.ticksToWait(), () -> {
                var fillOne = getFillAmountOfTank(helper, config.source());

                helper.runAfterDelay(config.ticksToWait(), () -> {
                    var fillTwo = getFillAmountOfTank(helper, config.sink());

                    if (config.pipeConfig() != null) {
                        assertPipesHaveFluid(helper, config.pipeConfig());
                    }

                    if (fillTwo <= fillOne) {
                        helper.fail("Sink did not fill");
                        return;
                    }

                    Assertions.assertConservation(helper, config.source(), config.sink(), 8000);
                    Assertions.assertTankFill(helper, config.source(), 0,
                            "Source did not drain fully, it still has " + getFillAmountOfTank(helper, config.source()) + "mb");
                    helper.succeed();
                });
            });
        });
    }

    private static FluidTestConfig convertToAbsolutePos(GameTestHelper helper, FluidTestConfig config) {
        var pipeConfig = config.pipeConfig() == null ? null : new PipeConfig(Arrays.stream(config.pipeConfig().pipes()).map(helper::absolutePos).toArray(BlockPos[]::new));

        return new FluidTestConfig(
                helper.absolutePos(config.source()),
                helper.absolutePos(config.sink()),
                config.ticksToWait(),
                pipeConfig
        );
    }

    public static void fillTankAt(GameTestHelper helper, BlockPos pos) {
        var handler = getFluidHandlerOrFail(helper, pos);
        handler.fill(new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
    }

    private static void assertPipesHaveFluid(GameTestHelper helper, PipeConfig config) {
        var pipes = getPipesAt(helper, config);

        for (var pipe : pipes) {
            var transportBehavior = BlockEntityBehaviour.get(pipe, FluidTransportBehaviour.TYPE);

            if (transportBehavior == null || !hasAnyFlow(transportBehavior)) {
                helper.fail("Pipe at " + pipe.getBlockPos() + " does not have fluid");
            }
        }
    }

    /**
     * Check if a pipe has any active flow (Flow objects on connections).
     * Replaces hasAnyPressure() since we no longer use addPressure.
     */
    public static boolean hasAnyFlow(FluidTransportBehaviour pipe) {
        if (pipe.hasAnyPressure()) return true;
        for (Direction dir : Direction.values()) {
            PipeConnection conn = pipe.getConnection(dir);
            if (conn instanceof PipeConnectionAccessor accessor) {
                if (accessor.pipesnphysics$getFlow().isPresent()) return true;
            }
        }
        return false;
    }

    private static FluidPipeBlockEntity[] getPipesAt(GameTestHelper helper, PipeConfig config) {
        var pipes = new ArrayList<FluidPipeBlockEntity>();

        for (var pos: config.pipes()) {
            var pipe = getPipeHandlerOrFail(helper, pos);
            pipes.add(pipe);
        }

        return pipes.toArray(new FluidPipeBlockEntity[0]);
    }

    public static FluidPipeBlockEntity getPipeHandlerOrFail(GameTestHelper helper, BlockPos pos) {
        var pipe = helper.getLevel().getBlockEntity(pos);

        if (pipe == null) {
            helper.fail("No pipe handler at " + pos);
        }

        if (!(pipe instanceof FluidPipeBlockEntity)) {
            helper.fail("Block at " + pos + " is not a fluid pipe");
        }

        return (FluidPipeBlockEntity) pipe;
    }

    public static FluidTransportBehaviour getPipeBehaviourOrFail(GameTestHelper helper, BlockPos pos) {
        var pipe = helper.getLevel().getBlockEntity(pos);

        if (pipe == null) {
            helper.fail("No pipe handler at " + pos);
        }

        if ((pipe instanceof FluidPipeBlockEntity)) {
            return BlockEntityBehaviour.get(pipe, FluidTransportBehaviour.TYPE);
        }

        if (pipe instanceof StraightPipeBlockEntity) {
            return BlockEntityBehaviour.get(pipe, FluidTransportBehaviour.TYPE);
        }

        helper.fail("Block at " + pos + " is not a fluid pipe");
        return null;
    }

    public static int getFillAmountOfTank(GameTestHelper helper, BlockPos pos) {
        var handler = getFluidHandlerOrFail(helper, pos);
        return handler.getFluidInTank(0).getAmount();
    }

    private static @NotNull IFluidHandler getFluidHandlerOrFail(GameTestHelper helper, BlockPos pos) {
        var be = helper.getLevel().getBlockEntity(pos);

        if (be == null) {
            helper.fail("No block entity at " + pos);
        }

        var source = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, pos, null);

        if (source == null) {
            helper.fail("No fluid handler at " + pos);
        }

        return source;
    }
}
