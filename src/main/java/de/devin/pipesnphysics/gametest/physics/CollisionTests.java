package de.devin.pipesnphysics.gametest.physics;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.FluidHandlerApi;
import de.devin.pipesnphysics.api.FluidHandlerRole;
import de.devin.pipesnphysics.client.PipeStatusText;
import de.devin.pipesnphysics.display.PipeDisplayMetric;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.PipeFlowExecutor;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.FluidCaps;
import de.devin.pipesnphysics.engine.boundary.HandlerRoles;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.flow.FlowNetwork;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.valve.ValveCharacteristic;
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import de.devin.pipesnphysics.handler.NetworkEditHandler;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.server.level.ServerLevel;
import net.createmod.catnip.lang.LangNumberFormat;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.TestSideHandlers;
import static de.devin.pipesnphysics.gametest.GameTestSupport.*;

/**
 * Crossing the streams: two fluids the WORLD presses together break the pipe (Create parity) —
 * and the mixing rigs the engine must never do that to on its own.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class CollisionTests {

    /**
     * The reported build, at the moment it used to bite: a basin mixing TWO ingredients, one
     * supply line per fluid, and the WATER supply has just run out. Its emptied tank is an
     * unclaimed empty that accepts anything, the lava pass runs first (the larger total volume),
     * and it drove lava down the water line — straight into the column the spent supply left
     * standing there. Create's collision calls {@code destroyBlock} for ANY two fluids, reactivity
     * only picking the block left behind, so a milk-and-honey mixer lost its plumbing exactly as
     * fast as this water-and-lava one: the pipes beside the basin turn to cobblestone.
     *
     * A pipe carries one fluid at a time ({@code FluidPass.runCarriesAnotherFluid}), so the lava
     * pass must wait for that line instead of crashing into it.
     */
    @GameTest(template = "physics/basin_two_supplies", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 400)
    public static void spentSupplyLineIsNotFloodedByTheOtherIngredient(GameTestHelper helper) {
        BlockPos basinPos = new BlockPos(3, 1, 0); // tank—pipe×2—basin—pipe×2—tank
        BlockPos lavaTank = new BlockPos(6, 1, 0);
        List<BlockPos> waterLine = List.of(new BlockPos(1, 1, 0), new BlockPos(2, 1, 0));
        List<BlockPos> lavaLine = List.of(new BlockPos(4, 1, 0), new BlockPos(5, 1, 0));

        helper.runAfterDelay(5, () -> {
            // The water tank ships EMPTY — its supply is spent — while lava still has the bigger
            // total, so the lava pass is the one that runs first and reaches for that empty tank.
            fillFluid(helper, lavaTank, Fluids.LAVA, 4000);
            seedBasin(helper, basinPos, Fluids.WATER, 1000);
            seedBasin(helper, basinPos, Fluids.LAVA, 1000);
            primeRun(helper, waterLine, Fluids.WATER); // what the spent supply left standing
        });
        helper.runAfterDelay(300, () -> {
            for (List<BlockPos> line : List.of(waterLine, lavaLine)) {
                for (BlockPos pos : line) {
                    if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(pos)) == null) {
                        helper.fail("the basin's plumbing broke at " + pos.toShortString()
                                + " — its two ingredients met in the pipe ("
                                + helper.getBlockState(pos) + ")");
                        return;
                    }
                }
                if (!lineHoldsOne(helper, line)) return;
            }
            helper.succeed();
        });
    }

    /**
     * The run claim must not fire for a pass that moves NOTHING. The solve is fluid-blind about
     * supply — a column's head and capacitance come from its TOTAL fill — so a basin holding only
     * milk still drives a branch in the WATER pass, out of its 400 mB "column" into the empty tank
     * below it. That pass has no source anywhere (the basin has no water to give, and the water
     * tank sits below its own draw lip), so it moves nothing; claiming the run for it walled the
     * MILK off its own line for good. The live report: the run reads {@code stalled solved=3
     * actual=0 [SOURCE_DRY]} with both cells bone dry, tick after tick, while the basin plainly
     * shows {@code give=394}.
     */
    @GameTest(template = "physics/basin_two_supplies", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 400)
    public static void aSourcelessPassDoesNotClaimTheRun(GameTestHelper helper) {
        BlockPos sinkTank = new BlockPos(0, 1, 0); // ships EMPTY — where the milk must end up
        BlockPos basinPos = new BlockPos(3, 1, 0);
        BlockPos waterTank = new BlockPos(6, 1, 0);
        Fluid milk = NeoForgeMod.MILK.value();

        helper.runAfterDelay(5, () -> {
            seedBasin(helper, basinPos, milk, 400);
            // Below its own draw lip (rendered ~0.34 against the 6/16 aperture), so the water pass
            // has no source at all: not this tank, and not the basin, which holds none of it.
            fillFluid(helper, waterTank, Fluids.WATER, 500);
        });
        helper.runAfterDelay(300, () -> {
            int delivered = amount(helper, sinkTank);
            // Communicating vessels: 400 mB over a 4000 mB basin and an 8000 mB tank at one level
            // settles at ~267 in the tank. Anything at all proves the milk was not walled.
            if (delivered < 200) {
                helper.fail("the sink tank holds " + delivered + " mB of milk — the water pass"
                        + " claimed the run without having anything to put in it"
                        + dump(helper, new BlockPos(1, 1, 0)));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The live report's SECOND half, at the numbers it was dumped at: a basin at 188 mB draining
     * into a tank already holding 195. It must drain ONE WAY — the basin may never regain what it
     * gave, which is the signature of the circulation reported ({@code solved=1 actual=1} tick
     * after tick with a {@code ·7} settle move going the other way, and nothing arriving).
     *
     * The settle equalizes the surfaces it is handed, so a basin's surface must be the same
     * function of fill as a tank's ({@link BoundaryColumn#renderedSurface}). Read off the basin's
     * own geometry it sat a third of a block lower at every fill, so the settle poured the tank
     * back into the basin while the solve pumped the basin into the tank, forever. The rig stops
     * short of the ideal 128/255 split — a sub-1 mB/t solved flow carries no whole millibucket and
     * the settle's hysteresis band holds the rest, the same tolerance
     * {@code tanksEqualizeAtEqualSurfaces} allows — so what is asserted is the DIRECTION, not the
     * endpoint.
     */
    @GameTest(template = "physics/basin_two_supplies", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 600)
    public static void aDrainingBasinNeverCirculates(GameTestHelper helper) {
        BlockPos sinkTank = new BlockPos(0, 1, 0);
        BlockPos basinPos = new BlockPos(3, 1, 0);
        BlockPos waterTank = new BlockPos(6, 1, 0);
        Fluid milk = NeoForgeMod.MILK.value();
        int[] lowest = {Integer.MAX_VALUE};

        helper.runAfterDelay(5, () -> {
            seedBasin(helper, basinPos, milk, 188);
            fillFluid(helper, sinkTank, milk, 195);
            fillFluid(helper, waterTank, Fluids.WATER, 1000);
        });
        for (int tick = 40; tick <= 400; tick += 4) {
            helper.runAfterDelay(tick, () -> {
                int held = basinFluid(helper, basinPos, milk);
                // DREGS_MB of slack: the last few mB may cross a boundary in one go either way.
                if (lowest[0] != Integer.MAX_VALUE && held > lowest[0] + 4) {
                    helper.fail("the basin went back up to " + held + " mB after reaching "
                            + lowest[0] + " — its own supply is circulating"
                            + dump(helper, new BlockPos(1, 1, 0)));
                    return;
                }
                lowest[0] = Math.min(lowest[0], held);
            });
        }
        helper.runAfterDelay(420, () -> {
            if (amount(helper, sinkTank) <= 195) {
                helper.fail("the sink tank never gained: " + amount(helper, sinkTank) + " mB");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The run claim on its own: two fluids may never be driven down ONE pipe run. Both ends here
     * are BASINS, which is what makes the rig bite — a tank locks to one fluid (it can neither
     * give nor take another, so the second pass is walled at the endpoint) and an EMPTY endpoint
     * is already claimed by the first pass that fills it, but a part-full basin has a free segment
     * for the OTHER fluid too, so both passes reach across the same run. Whichever moves first
     * owns the pipe; the other must wait rather than crash into the column standing there.
     */
    @GameTest(template = "physics/basin_two_supplies", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 400)
    public static void twoFluidsNeverShareOneRun(GameTestHelper helper) {
        BlockPos sourceBasin = new BlockPos(3, 1, 0);
        BlockPos sinkBasin = new BlockPos(0, 1, 0); // the template's west tank, swapped for a basin
        List<BlockPos> run = List.of(new BlockPos(1, 1, 0), new BlockPos(2, 1, 0));
        helper.setBlock(sinkBasin, AllBlocks.BASIN.get());
        helper.setBlock(new BlockPos(6, 1, 0), Blocks.STONE); // cap the east side: ONE shared run
        // (a solid block, not air — air would leave an open MOUTH there and spill into the world)

        helper.runAfterDelay(5, () -> {
            seedBasin(helper, sourceBasin, Fluids.WATER, 1000);
            seedBasin(helper, sourceBasin, Fluids.LAVA, 1000);
            seedBasin(helper, sinkBasin, Fluids.WATER, 200); // part-full: a free segment for lava
        });
        helper.runAfterDelay(300, () -> {
            for (BlockPos pos : run) {
                if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(pos)) == null) {
                    helper.fail("the run broke at " + pos.toShortString() + " — two fluids were"
                            + " driven down one pipe (" + helper.getBlockState(pos) + ")");
                    return;
                }
            }
            if (!lineHoldsOne(helper, run)) return;
            helper.succeed();
        });
    }

    /** Fill a basin's INPUT tanks directly, past the fill rules a pipe would go through. */
    private static void seedBasin(GameTestHelper helper, BlockPos basinPos, Fluid fluid, int mb) {
        BasinBlockEntity be = (BasinBlockEntity) helper.getBlockEntity(basinPos);
        var internal = (SmartFluidTankBehaviour.InternalFluidHandler) be.inputTank.getCapability();
        internal.forceFill(new FluidStack(fluid, mb), IFluidHandler.FluidAction.EXECUTE);
    }

    /** Fill every cell of a run to the brim, as a flow that has since stopped would leave it. */
    private static void primeRun(GameTestHelper helper, List<BlockPos> run, Fluid fluid) {
        for (BlockPos pos : run) {
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(pos));
            if (cell == null) {
                helper.fail("no pipe store at " + pos.toShortString());
                return;
            }
            cell.insert(new FluidStack(fluid, PipeStore.capacityMb()), PipeStore.capacityMb());
            cell.flush();
        }
    }

    /** A run may hold ONE fluid at a time; two would be a collision waiting to happen. */
    private static boolean lineHoldsOne(GameTestHelper helper, List<BlockPos> line) {
        FluidStack carried = FluidStack.EMPTY;
        for (BlockPos pos : line) {
            FluidStack held = pipeFluid(helper, pos);
            if (held.isEmpty()) continue;
            if (carried.isEmpty()) carried = held;
            else if (!FluidStack.isSameFluidSameComponents(carried, held)) {
                helper.fail("the run carries both " + carried.getHoverName().getString() + " and "
                        + held.getHoverName().getString() + " at " + pos.toShortString());
                return false;
            }
        }
        return true;
    }

    /**
     * A foreign PLUG in a column a tank is pressing must react exactly like Create's crossing the
     * streams: the pipe BREAKS and a reactive pair leaves its block (water + lava → cobblestone).
     * Our transport-cancel mixin removed Create's own {@code FluidReactions.handlePipeFlowCollision};
     * the executor restores it. Rig: a full water tank communicating with an empty sink through a
     * flat run filled water|LAVA|water — the tank's own column runs into the plug, and that cell
     * must turn to stone rather than pistoning the lava into the (water) sink.
     *
     * The plug is what the tank meets, NOT what a solved flow is driven into: since a run carries
     * one fluid at a time ({@code FluidPass.runCarriesAnotherFluid}) the engine no longer drives
     * its own passes together, so every surviving collision is one the WORLD presses — a reservoir
     * against the column in its mouth (here, and {@link #restingTanksOfDifferentFluidsCollide},
     * {@link #restingSplitFluidRunCollidesMidRun}) or a pump packing a foreign outlet.
     */
    @GameTest(template = "physics/collision_flat_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void crossingTheStreamsBreaksThePipe(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 1, 1); // tank—pipe×3—tank flat run (collision_flat_run)
        BlockPos lavaCell = new BlockPos(3, 1, 1);

        helper.runAfterDelay(5, () -> {
            fillFluid(helper, source, Fluids.WATER, 8000); // full → its column presses into the run
            for (int x = 2; x <= 4; x++) {
                BlockPos rel = new BlockPos(x, 1, 1);
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                Fluid fluid = rel.equals(lavaCell) ? Fluids.LAVA : Fluids.WATER;
                cell.insert(new FluidStack(fluid, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(lavaCell));
        });
        helper.runAfterDelay(80, () -> {
            if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(lavaCell)) != null) {
                helper.fail("the pipe survived the fluid collision — it must break (crossing the streams)");
                return;
            }
            var state = helper.getBlockState(lavaCell);
            if (!state.is(Blocks.COBBLESTONE)) {
                helper.fail("water + lava collided but left " + state + " instead of cobblestone");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Crossing the streams with NO flow: two tanks of different fluids joined by a pipe must still
     * react. The brigade never catches this (the water pass and lava pass each bail with a single
     * participant — the opposite tank walls the other fluid — so the run is idle, solved=0), yet a
     * lava tank joined to a water-filled pipe cell is incompatible with it exactly as Create pulls
     * both fluids into the pipe. The idle settle must break the mouth cell to cobblestone. Rig: a
     * FULL water tank and a FULL lava tank at the ends of a flat water-filled run — nothing flows,
     * and the cell touching the lava tank must turn to stone.
     */
    @GameTest(template = "physics/collision_flat_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void restingTanksOfDifferentFluidsCollide(GameTestHelper helper) {
        BlockPos waterTank = new BlockPos(1, 1, 1); // tank—pipe×3—tank flat run (collision_flat_run)
        BlockPos lavaTank = new BlockPos(5, 1, 1);
        BlockPos mouthCell = new BlockPos(4, 1, 1); // the pipe cell touching the lava tank

        helper.runAfterDelay(5, () -> {
            fillFluid(helper, waterTank, Fluids.WATER, 8000);
            fillFluid(helper, lavaTank, Fluids.LAVA, 8000); // full → its surface clears the pipe lip
            for (int x = 2; x <= 4; x++) {
                BlockPos rel = new BlockPos(x, 1, 1);
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(mouthCell));
        });
        helper.runAfterDelay(100, () -> {
            if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(mouthCell)) != null) {
                helper.fail("the pipe touching the lava tank survived — resting cross-streams must break it");
                return;
            }
            if (!helper.getBlockState(mouthCell).is(Blocks.COBBLESTONE)) {
                helper.fail("resting water + lava collided but left "
                        + helper.getBlockState(mouthCell) + " instead of cobblestone");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The exact live report: a nearly-empty water tank and a LOW lava tank sit ABOVE the pipe, a
     * U-run drops between them, and the run rests FULL of water — an idle edge (solved=0). The old
     * driven-only detection did nothing; the resting-boundary check must still break the vertical
     * riser touching the lava tank. Faithful to the /pipegraph dump: tanks above (lip at the riser
     * block bottom), lava at 500/8000, water pre-filling the run — no fill-level gate, since the
     * lava tank is simply incompatible with the water in its mouth cell.
     */
    @GameTest(template = "physics/collision_u_below", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120,
            batch = "collisionResting")
    public static void restingWaterPipeBelowALavaTankCollides(GameTestHelper helper) {
        // Built at runtime on a blank canvas (collision_u_below is an empty template): this
        // resting-collision fires on the exact settle tick a runtime setBlock produces, whereas a
        // PRE-PLACED structure lets the run's water redistribute into the tanks before it can react.
        Block pipe = AllBlocks.FLUID_PIPE.get();
        BlockState riserY = AllBlocks.GLASS_FLUID_PIPE.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,
                        Direction.Axis.Y);
        BlockPos waterTank = new BlockPos(0, 3, 0);
        BlockPos lavaTank = new BlockPos(3, 3, 0);
        BlockPos lavaMouth = new BlockPos(3, 2, 0); // vertical glass riser under the lava tank
        helper.setBlock(waterTank, AllBlocks.FLUID_TANK.get());
        helper.setBlock(new BlockPos(0, 2, 0), riserY);
        helper.setBlock(new BlockPos(0, 1, 0), pipeState(pipe, Direction.UP, Direction.EAST));
        helper.setBlock(new BlockPos(1, 1, 0), pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(2, 1, 0), pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(3, 1, 0), pipeState(pipe, Direction.WEST, Direction.UP));
        helper.setBlock(lavaMouth, riserY);
        helper.setBlock(lavaTank, AllBlocks.FLUID_TANK.get());

        List<BlockPos> run = List.of(new BlockPos(0, 2, 0), new BlockPos(0, 1, 0),
                new BlockPos(1, 1, 0), new BlockPos(2, 1, 0), new BlockPos(3, 1, 0), lavaMouth);
        helper.runAfterDelay(5, () -> {
            fillFluid(helper, waterTank, Fluids.WATER, 250);  // nearly empty, like the report
            fillFluid(helper, lavaTank, Fluids.LAVA, 500);    // 500/8000 — a LOW tank, no reach gate
            for (BlockPos rel : run) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(lavaMouth));
        });
        helper.runAfterDelay(100, () -> {
            if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(lavaMouth)) != null) {
                helper.fail("the water riser under the low lava tank survived — it must break (cross-streams)");
                return;
            }
            if (!helper.getBlockState(lavaMouth).is(Blocks.COBBLESTONE)) {
                helper.fail("water riser + low lava tank left "
                        + helper.getBlockState(lavaMouth) + " instead of cobblestone");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The live report: a water tank and a lava tank joined by ONE idle run whose cells have settled
     * SPLIT — water drawn in from the water end, lava from the lava end, meeting deep in the run
     * (holds "250:Water 250:Water 250:Water 250:Lava 250:Lava"). Each MOUTH cell matches its own
     * tank, so the old end-cell-only boundary check saw no collision and the two fluids just sat
     * there touching mid-run. The press-the-column check must follow each tank's column inward to
     * the interface and break it. Rig: full water + full lava tanks at the ends of a 5-cell flat
     * run pre-filled water|water|water|lava|lava — the water cell touching the lava column must
     * turn to stone.
     */
    @GameTest(template = "physics/collision_split_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void restingSplitFluidRunCollidesMidRun(GameTestHelper helper) {
        BlockPos waterTank = new BlockPos(1, 1, 1); // tank—pipe×5—tank flat run (collision_split_run)
        BlockPos lavaTank = new BlockPos(7, 1, 1);
        BlockPos interfaceCell = new BlockPos(4, 1, 1); // last WATER cell, touching the lava column

        helper.runAfterDelay(5, () -> {
            fillFluid(helper, waterTank, Fluids.WATER, 8000);
            fillFluid(helper, lavaTank, Fluids.LAVA, 8000);
            // The settled split: water in cells 2-4 (the water end), lava in cells 5-6 (the lava end).
            for (int x = 2; x <= 6; x++) {
                BlockPos rel = new BlockPos(x, 1, 1);
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                Fluid fluid = x <= 4 ? Fluids.WATER : Fluids.LAVA;
                cell.insert(new FluidStack(fluid, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(interfaceCell));
        });
        helper.runAfterDelay(100, () -> {
            if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(interfaceCell)) != null) {
                helper.fail("the water/lava interface mid-run survived — a split run must break there");
                return;
            }
            if (!helper.getBlockState(interfaceCell).is(Blocks.COBBLESTONE)) {
                helper.fail("split water|lava run collided but left "
                        + helper.getBlockState(interfaceCell) + " instead of cobblestone");
                return;
            }
            helper.succeed();
        });
    }
}
