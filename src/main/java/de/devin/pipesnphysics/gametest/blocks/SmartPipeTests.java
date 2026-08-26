package de.devin.pipesnphysics.gametest.blocks;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static de.devin.pipesnphysics.gametest.GameTestSupport.*;

/**
 * Create's SMART FLUID PIPE — a straight pipe with a fluid filter — against a BASIN, on lines with
 * NO pump. The engine consults the filter through Create's own {@code canPullFluidFrom}
 * ({@code FluidPass.runAcceptsFluid}), and a rejected fluid blocks the whole run for that fluid's
 * pass; a basin is where that gets interesting, because it is the one endpoint that holds SEVERAL
 * fluids at once, so a filtered line off it must pass one and wall the other in the same tick.
 *
 * The pumped separation rig is covered by {@code multiFluidBasinSeparatesCompletely} (template
 * {@code common/multi_fluid_basin}, two filtered lines flush against a basin). These are its
 * PUMP-LESS twins in both directions — a pump changes the picture completely (its check valves,
 * its EMF, and the pump-driven settle paths), so gravity needs its own guard.
 *
 * Both rigs are built over {@code common/single_pump}: its pump becomes the smart pipe, so the run
 * is basin/tank — pipe — smart pipe — pipe — tank with nothing driving it but the levels.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class SmartPipeTests {
    // common/single_pump pinned positions (template pos + (0,1,0)).
    private static final BlockPos WEST_END = new BlockPos(0, 1, 1);
    private static final BlockPos PUMP = new BlockPos(2, 1, 1);
    private static final BlockPos EAST_END = new BlockPos(4, 1, 1);

    /**
     * A basin holding two fluids, gravity-feeding a tank through a water-filtered smart pipe: the
     * water must arrive and the milk must stay put. Water alone reaching the tank is not enough —
     * an unfiltered pipe would pass both, so the milk assertion is what proves the filter is read.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void smartPipeFiltersAGravityLineOffABasin(GameTestHelper helper) {
        Fluid milk = NeoForgeMod.MILK.value();
        helper.setBlock(WEST_END, AllBlocks.BASIN.get());
        placeSmartPipe(helper, PUMP, Items.WATER_BUCKET);
        drain(helper, EAST_END);

        helper.runAfterDelay(5, () -> {
            BasinBlockEntity basin = (BasinBlockEntity) helper.getBlockEntity(WEST_END);
            var internal = (SmartFluidTankBehaviour.InternalFluidHandler) basin.inputTank.getCapability();
            internal.forceFill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
            internal.forceFill(new FluidStack(milk, 1000), IFluidHandler.FluidAction.EXECUTE);
        });

        helper.runAtTickTime(300, () -> {
            FluidStack arrived = handler(helper, EAST_END).getFluidInTank(0);
            if (!arrived.isEmpty() && arrived.getFluid() == milk) {
                helper.fail("milk crossed a water-filtered smart pipe — the filter is not being read"
                        + dump(helper, PUMP));
                return;
            }
            if (arrived.getAmount() < 500) {
                helper.fail("the water-filtered gravity line off the basin moved "
                        + arrived.getAmount() + " mB of its 1000" + dump(helper, PUMP));
                return;
            }
            if (basinFluid(helper, WEST_END, milk) != 1000) {
                helper.fail("the basin's milk went somewhere: "
                        + basinFluid(helper, WEST_END, milk) + "/1000 mB left" + dump(helper, PUMP));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The dual: a tank gravity-feeding a BASIN through a smart pipe filtered to the fluid it
     * carries. A basin is a receive-only sink here (it holds nothing), and its own head is what
     * the water falls toward — the filter must not wall a fluid it accepts.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void smartPipeFillsABasinItAccepts(GameTestHelper helper) {
        helper.setBlock(EAST_END, AllBlocks.BASIN.get());
        placeSmartPipe(helper, PUMP, Items.WATER_BUCKET);
        fill(helper, WEST_END, 8000);

        helper.runAtTickTime(300, () -> {
            int arrived = basinFluid(helper, EAST_END, Fluids.WATER);
            if (arrived <= 0) {
                helper.fail("nothing reached the basin through a water-filtered smart pipe"
                        + dump(helper, PUMP));
                return;
            }
            helper.succeed();
        });
    }

    // blocks/smart_pipe_mouth pinned positions (template pos + (0,1,0)): a full tank feeding a
    // flat run that ends in an OPEN MOUTH, with a smart pipe in the middle. No pump, no sink tank
    // — the mouth is what makes the run drain, and the filter is all that stands in the way.
    private static final BlockPos MOUTH_TANK = new BlockPos(0, 1, 0);
    private static final BlockPos MOUTH_NEAR = new BlockPos(1, 1, 0);
    private static final BlockPos MOUTH_SMART = new BlockPos(2, 1, 0);
    private static final BlockPos MOUTH_FAR = new BlockPos(3, 1, 0);
    private static final BlockPos MOUTH_SPACE = new BlockPos(4, 1, 0);

    /**
     * A smart pipe filtered to a fluid the line does NOT carry must wall it like a shut valve —
     * at REST as much as under flow. The solve already refuses the run ({@code runAcceptsFluid}),
     * so nothing SOLVES; what leaked was the settle, which knows only elevations: the open mouth
     * contributes no resting line, so the whole run targeted the tank's own waterline, the
     * hydrostatic draw walked fluid straight through the filter cell, and the mouth poured it into
     * the world ("I placed the diesel in the basin and it went through the smart pipe" — the
     * reported rig read basin → 4 cells → open end, everything empty afterwards).
     *
     * Fluid may still stand in the cell BETWEEN the tank and the filter — that is a pipe pressed
     * against a shut valve — so the tank keeps everything but that one cell.
     */
    @GameTest(template = "blocks/smart_pipe_mouth", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void smartPipeWallsTheFluidItRejectsAtRest(GameTestHelper helper) {
        setFilter(helper, MOUTH_SMART, Items.LAVA_BUCKET);

        helper.runAtTickTime(200, () -> {
            int past = pipeAmount(helper, MOUTH_SMART) + pipeAmount(helper, MOUTH_FAR);
            if (past > 0) {
                helper.fail(past + " mB of water crossed a lava-filtered smart pipe"
                        + dump(helper, MOUTH_NEAR));
                return;
            }
            if (helper.getLevel().getFluidState(helper.absolutePos(MOUTH_SPACE)).isSource()) {
                helper.fail("the run spilled out of the mouth past a filter that rejects its fluid"
                        + dump(helper, MOUTH_NEAR));
                return;
            }
            int held = amount(helper, MOUTH_TANK);
            if (held < 7700) {
                helper.fail("the tank drained to " + held + " mB through a lava-filtered smart pipe"
                        + dump(helper, MOUTH_NEAR));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The same rig with the filter set to the fluid it carries: the run must drain and spill. This
     * is what keeps its twin above honest — a rig that never flows would pass that one vacuously.
     */
    @GameTest(template = "blocks/smart_pipe_mouth", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void smartPipeDrainsOutTheMouthItAccepts(GameTestHelper helper) {
        setFilter(helper, MOUTH_SMART, Items.WATER_BUCKET);

        helper.succeedWhen(() -> {
            if (amount(helper, MOUTH_TANK) >= 8000) helper.fail("the tank has not started draining");
            if (!helper.getLevel().getFluidState(helper.absolutePos(MOUTH_SPACE)).isSource()) {
                helper.fail("nothing reached the mouth through a water-filtered smart pipe");
            }
        });
    }

    /** Swap in a smart fluid pipe along the rig's X axis and set its fluid filter. */
    private static void placeSmartPipe(GameTestHelper helper, BlockPos rel, net.minecraft.world.item.Item filter) {
        BlockState smart = AllBlocks.SMART_FLUID_PIPE.getDefaultState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, Direction.EAST);
        helper.setBlock(rel, smart);
        setFilter(helper, rel, filter);
        // The neighbours were shaped against whatever stood here before, so recompute their own
        // connection state — setBlock only re-shapes outward.
        for (Direction side : Direction.values()) {
            BlockPos abs = helper.absolutePos(rel).relative(side);
            helper.getLevel().setBlock(abs, Block.updateFromNeighbourShapes(
                    helper.getLevel().getBlockState(abs), helper.getLevel(), abs), 3);
        }
    }

    /** Set the fluid filter of the smart pipe already standing at this position. */
    private static void setFilter(GameTestHelper helper, BlockPos rel, net.minecraft.world.item.Item filter) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(
                helper.getLevel(), helper.absolutePos(rel), FilteringBehaviour.TYPE);
        if (behaviour == null) {
            helper.fail("no filtering behaviour on the smart pipe at " + rel);
            return;
        }
        behaviour.setFilter(new ItemStack(filter));
    }
}
