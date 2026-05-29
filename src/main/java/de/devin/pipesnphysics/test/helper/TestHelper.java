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

public class TestHelper {

    public static void fillSourceAndAwaitDest(GameTestHelper helper, FluidTestConfig relativeConfig) {
        var config = new FluidTestConfig(
                helper.absolutePos(relativeConfig.source()),
                helper.absolutePos(relativeConfig.sink()),
                relativeConfig.ticksToWait(),
                null
        );

        helper.runAfterDelay(5, () -> {
            Assertions.assertTankFill(helper, config.sink(), 0, "Sink is not empty");
            fillTankAt(helper, config.source());

            helper.runAfterDelay(config.ticksToWait(), () -> {
                var fillOne = getFillAmountOfTank(helper, config.source());

                helper.runAfterDelay(config.ticksToWait(), () -> {
                    var fillTwo = getFillAmountOfTank(helper, config.sink());

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

    public static void fillTankAt(GameTestHelper helper, BlockPos pos) {
        var handler = getFluidHandlerOrFail(helper, pos);
        handler.fill(new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
    }

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

    public static FluidTransportBehaviour getPipeBehaviourOrFail(GameTestHelper helper, BlockPos pos) {
        var pipe = helper.getLevel().getBlockEntity(pos);

        if (pipe == null) {
            helper.fail("No pipe handler at " + pos);
        }

        if (pipe instanceof FluidPipeBlockEntity) {
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
