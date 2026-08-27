package de.devin.pipesnphysics.gametest.blocks;

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
import de.devin.pipesnphysics.api.PumpApi;
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
import de.devin.pipesnphysics.engine.pump.Pumps;
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
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
 * Pump behavior: move-all, fast recheck, load breakdown, head-left, starved/dead-head reports.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class PumpTests {

    /**
     * Tank → pipe → powered pump → pipe → tank on flat ground. The pump must move
     * everything: the source drains to exactly 0 mB even though its connection sits
     * at base level (regression: the lip gate used to strand the last 80 mB).
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void pumpMovesAllFluidOnFlatGround(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        fill(helper, source, 8000);

        helper.succeedWhen(() -> {
            int left = amount(helper, source);
            int moved = amount(helper, sink);
            int pipes = pipesnphysics$areaPipeContent(helper, 6, 4, 4);
            if (left + moved + pipes != 8000) {
                helper.fail("fluid not conserved: " + left + " + " + moved + " + pipes " + pipes);
            }
            if (left != 0) helper.fail("source still holds " + left + " mB");
        });
    }

    /**
     * A pump holding a sink full must top it back up after a PARTIAL consume, not wait for it to
     * empty. Both tanks start full (pump pressurizes the full sink = SINK_FULL), then a chunk is
     * drained straight from the sink handler (no block event, like a recipe consuming) and the
     * pump must refill it within a few ticks. NOTE: this is the general SINK case and it works —
     * a Create BASIN is different: it gates fill() on recipe state (an empty bare basin returns
     * accepts=0), so a basin only takes fluid when its recipe wants it. That "waits until drained"
     * behavior is Create's, not ours (it persists with the engine off), and we fill via the same
     * fill() the basin gates — so we can't force fluid in, only refill promptly once it accepts.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void fullSinkRefillsAfterPartialDrain(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        fill(helper, source, 8000);
        fill(helper, sink, 8000);

        helper.runAfterDelay(10, () -> {
            handler(helper, sink).drain(2000, IFluidHandler.FluidAction.EXECUTE);
            int afterDrain = amount(helper, sink);
            helper.runAfterDelay(40, () -> {
                int refilled = amount(helper, sink);
                if (refilled <= afterDrain + 200) {
                    helper.fail("sink NOT refilled after partial drain: drained to " + afterDrain
                            + ", 40 ticks later still " + refilled + " (source " + amount(helper, source) + ")");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * A network holding a RUNNING PUMP is "armed": even when it solves to no flow this tick
     * (its source momentarily below the draw lip / empty, or its sink momentarily full), it must
     * re-check on the FAST heartbeat so it resumes the instant conditions allow — a level change
     * inside a tank or basin fires no block event to wake it. Regression: a STRONG pump pinned to
     * zero flow by an unsuppliable source carries no NO_HEAD flag, so it used to drop through to
     * the slow {@code IDLE_RECHECK_TICKS} heartbeat ("takes a long time to retick", "only refills
     * once the basin is near-empty"). Verifies the pump is detected as running and that an idle
     * solution on such a network routes to the fast cadence.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void runningPumpArmsTheFastRecheck(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up
            BlockPos seed = new BlockPos(1, 1, 1); // piping/single_pump: any pipe cell seeds the whole-network graph

            Graph g = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            if (g.pumps().isEmpty()) { helper.fail("graph has no pump"); return; }
            if (!EngineTickHandler.hasRunningPump(helper.getLevel(), g)) {
                helper.fail("a spun-up pump was not detected as running");
                return;
            }

            Solution idle = Solution.idle(g);
            int armed = EngineTickHandler.recheckTicks(idle, true);
            int settled = EngineTickHandler.recheckTicks(idle, false);
            if (armed >= settled) {
                helper.fail("armed re-check (" + armed + ") must be faster than a settled one (" + settled + ")");
                return;
            }
            // The wiring under test: a real running-pump network routes to the fast cadence.
            if (EngineTickHandler.recheckTicks(idle, EngineTickHandler.hasRunningPump(helper.getLevel(), g)) != armed) {
                helper.fail("a running-pump network was not put on the fast re-check");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Integration check for the user-reported "slow retick": a running pump whose source
     * momentarily can't supply (here, empty) solves to no flow with NO NO_HEAD flag, so the
     * network sleeps. Refilling the source through its handler fires NO block event — exactly
     * like a recipe output or external feed — so the armed network must wake itself on the
     * re-check heartbeat and deliver. (The 20→4 SPEED-UP of that heartbeat for a running pump
     * is asserted deterministically by {@link #runningPumpArmsTheFastRecheck}; this test guards
     * the end-to-end path that the network wakes and delivers AT ALL without a block event —
     * the wake cadence here is gated by Create's idle-pipe ticking, not the re-check interval.)
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 160)
    public static void armedPumpRefillsSinkWithoutBlockEvent(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        drain(helper, source);    // source empty: the running pump has nothing to move -> idle
        drain(helper, sink);
        fill(helper, sink, 3000); // sink holds fluid so the network stays live (pipes keep ticking)
        int[] baseline = {0};
        helper.runAfterDelay(30, () -> baseline[0] = amount(helper, sink));
        helper.runAfterDelay(34, () -> fill(helper, source, 4000)); // refill the source: NO block event
        helper.runAfterDelay(150, () -> {
            int now = amount(helper, sink);
            if (now <= baseline[0]) {
                helper.fail("armed pump never delivered after the source rose with no block event "
                        + "(sink stayed " + baseline[0] + " mB, source " + amount(helper, source)
                        + ") — the network never woke itself");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A basin holding TWO fluids (like water + milk for builder's tea) must get a partially
     * drained ingredient topped back up, not wait for it to hit zero. The basin keeps each
     * fluid in its own segment but reports a single representative {@code contents()} — the
     * engine used to treat the basin as a WALL for the OTHER fluid's pass, so a half-full water
     * segment never refilled while milk sat beside it (the "basin only refills once empty" bug).
     * Force a basin to hold lava + a half-full water segment, then a pump pushing water must
     * top the water back to full while the lava is untouched.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void basinRefillsDrainedFluidBesideAnother(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos endPipe = new BlockPos(4, 1, 1);
        BlockPos basinPos = new BlockPos(4, 0, 1);
        helper.setBlock(endPipe, AllBlocks.FLUID_PIPE.get());
        helper.setBlock(basinPos, AllBlocks.BASIN.get());
        helper.runAfterDelay(5, () -> {
            BasinBlockEntity be = (BasinBlockEntity) helper.getBlockEntity(basinPos);
            var internal = (SmartFluidTankBehaviour.InternalFluidHandler) be.inputTank.getCapability();
            internal.forceFill(new FluidStack(Fluids.LAVA, 500), IFluidHandler.FluidAction.EXECUTE);
            internal.forceFill(new FluidStack(Fluids.WATER, 500), IFluidHandler.FluidAction.EXECUTE);
            if (basinFluid(helper, basinPos, Fluids.WATER) != 500) {
                helper.fail("setup: basin should hold 500 mB water beside the lava");
                return;
            }
            // Fill the source only NOW: delivery is instant, so filling it earlier would let the
            // running pump top the basin up before this setup ran, skewing the 500 mB baseline.
            fill(helper, source, 8000);
            helper.runAfterDelay(60, () -> {
                int waterNow = basinFluid(helper, basinPos, Fluids.WATER);
                if (waterNow <= 500) {
                    helper.fail("basin's half-full water segment NOT refilled (still " + waterNow
                            + ") — the lava walls the water pass");
                    return;
                }
                if (basinFluid(helper, basinPos, Fluids.LAVA) != 500) {
                    helper.fail("the other fluid (lava) was disturbed: "
                            + basinFluid(helper, basinPos, Fluids.LAVA));
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * The drain-side dual of {@link #basinRefillsDrainedFluidBesideAnother}: a pump pulling from a
     * basin that holds TWO fluids must be able to drain the NON-representative one, not only the
     * basin's representative {@code contents()}. Two bugs used to conspire to strand it: the solver
     * enumerated one fluid per column (its representative), so the other fluid never got a pass; and
     * the column surface was derived from the representative fluid's volume alone, so a basin holding
     * two half-segments read as barely filled and its fluid "couldn't reach" the side pipe (a phantom
     * crest / draw-lip wall). Here water is the representative (segment 0) and lava is larger (segment
     * 1), so lava's pass runs first and claims the lone sink while the smaller water pass stalls with
     * nowhere to go — the two fluids never share a pipe, so they never collide. The pump must drain
     * the lava out.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void basinDrainsANonRepresentativeFluid(GameTestHelper helper) {
        BlockPos basinPos = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        // Replace the west source tank with a basin on the pump's suction side, at the pump's own
        // level so its side face plumbs straight into the suction pipe (a basin exposes its handler
        // on every face). The pump then pulls fluid horizontally out of it.
        helper.setBlock(basinPos, AllBlocks.BASIN.get());
        drain(helper, sink); // the east sink must start empty so it accepts whatever the pump pulls
        helper.runAfterDelay(5, () -> {
            BasinBlockEntity be = (BasinBlockEntity) helper.getBlockEntity(basinPos);
            var internal = (SmartFluidTankBehaviour.InternalFluidHandler) be.inputTank.getCapability();
            internal.forceFill(new FluidStack(Fluids.WATER, 800), IFluidHandler.FluidAction.EXECUTE);  // representative (segment 0)
            internal.forceFill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE);  // non-representative, larger
            if (basinFluid(helper, basinPos, Fluids.LAVA) != 1000) {
                helper.fail("setup: basin should hold 1000 mB lava beside 800 mB water");
                return;
            }
            helper.runAfterDelay(120, () -> {
                int lavaLeft = basinFluid(helper, basinPos, Fluids.LAVA);
                if (lavaLeft >= 1000) {
                    helper.fail("the basin's non-representative fluid (lava) never drained — still "
                            + lavaLeft + " mB" + dump(helper, new BlockPos(1, 1, 1)));
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * The full separation rig (owner's live build, template {@code multi_fluid_basin}): a basin
     * holding 1000 water + 1000 milk, a milk-filtered line north and a water-filtered line east,
     * each through its own pump into its own tank. The job must FINISH: after enough ticks the
     * basin AND both suction pipes are empty and each tank holds its full 1000 mB. Regression:
     * the last sub-flow-depth residual in each pipe could not deliver (the depth gate), the
     * settle poured it back into the empty basin — an open bowl, immediately re-drainable — and
     * the pump lifted it out again: a permanent basin↔pipe oscillation that left both tanks
     * short and the network awake forever.
     */
    @GameTest(template = "common/multi_fluid_basin", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void multiFluidBasinSeparatesCompletely(GameTestHelper helper) {
        BlockPos basinPos = new BlockPos(0, 1, 3);
        BlockPos milkTank = new BlockPos(0, 1, 0);
        BlockPos waterTank = new BlockPos(3, 1, 3);
        Fluid milk = NeoForgeMod.MILK.value();
        helper.runAfterDelay(5, () -> {
            BasinBlockEntity be = (BasinBlockEntity) helper.getBlockEntity(basinPos);
            var internal = (SmartFluidTankBehaviour.InternalFluidHandler) be.inputTank.getCapability();
            internal.forceFill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
            internal.forceFill(new FluidStack(milk, 1000), IFluidHandler.FluidAction.EXECUTE);
            if (basinFluid(helper, basinPos, Fluids.WATER) != 1000
                    || basinFluid(helper, basinPos, milk) != 1000) {
                helper.fail("setup: basin should hold 1000 mB water + 1000 mB milk");
            }
        });
        helper.succeedWhen(() -> {
            FluidStack inWaterTank = handler(helper, waterTank).getFluidInTank(0);
            FluidStack inMilkTank = handler(helper, milkTank).getFluidInTank(0);
            if (!inWaterTank.isEmpty() && inWaterTank.getFluid() != Fluids.WATER) {
                helper.fail("water tank received " + inWaterTank.getHoverName().getString());
            }
            if (!inMilkTank.isEmpty() && inMilkTank.getFluid() != milk) {
                helper.fail("milk tank received " + inMilkTank.getHoverName().getString());
            }
            if (inWaterTank.getAmount() != 1000) {
                helper.fail("water tank holds " + inWaterTank.getAmount() + "/1000 mB");
            }
            if (inMilkTank.getAmount() != 1000) {
                helper.fail("milk tank holds " + inMilkTank.getAmount() + "/1000 mB");
            }
            if (basinFluid(helper, basinPos, Fluids.WATER) != 0
                    || basinFluid(helper, basinPos, milk) != 0) {
                helper.fail("basin still holds fluid — the residual is orbiting basin↔pipe");
            }
        });
    }

    /**
     * The razor-edge of {@link #multiFluidBasinSeparatesCompletely}: a residual totalling EXACTLY
     * the flow depth (60 mB at this rig's solved 15 mB/t) used to orbit basin↔pipe forever — the
     * tail can only meet the depth gate on the tick the basin gives its last mB, and by the next
     * solve the pass is dead, so no consumer ever saw it; and "fully arrived" excluded the exact
     * boundary because the supply probe was capped at the shortfall. The live report: everything
     * delivered EXCEPT a stuck 60 mB. If a config change shifts this rig's solved rate off
     * 15 mB/t the fill is merely near-boundary — the assertion (complete delivery) still holds.
     */
    @GameTest(template = "common/multi_fluid_basin", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void exactDepthResidualStillDelivers(GameTestHelper helper) {
        BlockPos basinPos = new BlockPos(0, 1, 3);
        BlockPos waterTank = new BlockPos(3, 1, 3);
        helper.runAfterDelay(5, () -> {
            BasinBlockEntity be = (BasinBlockEntity) helper.getBlockEntity(basinPos);
            var internal = (SmartFluidTankBehaviour.InternalFluidHandler) be.inputTank.getCapability();
            internal.forceFill(new FluidStack(Fluids.WATER, 60), IFluidHandler.FluidAction.EXECUTE);
        });
        helper.succeedWhen(() -> {
            if (amount(helper, waterTank) != 60) {
                helper.fail("water tank holds " + amount(helper, waterTank)
                        + "/60 mB — the exact-flow-depth residual is trapped in the basin↔pipe orbit");
            }
            if (basinFluid(helper, basinPos, Fluids.WATER) != 0) {
                helper.fail("basin still holds " + basinFluid(helper, basinPos, Fluids.WATER) + " mB");
            }
        });
    }

    /**
     * A basin is an OPEN BOWL: its surface reads at the column TOP, so a side pipe reaches its
     * fluid at ANY fill level and a pump drains it like stock Create would. 800 of 4000 mB sits
     * well below the pipe's 6/16 aperture — a closed tank at that level stalls on the
     * below-opening wall ({@code levelRunBelowApertureReadsSupplyLowNotCrest}); the basin must
     * flow anyway.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void basinGivesFromAnyFillLevel(GameTestHelper helper) {
        BlockPos basinPos = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        helper.setBlock(basinPos, AllBlocks.BASIN.get());
        drain(helper, sink);
        helper.runAfterDelay(5, () -> {
            BasinBlockEntity be = (BasinBlockEntity) helper.getBlockEntity(basinPos);
            var internal = (SmartFluidTankBehaviour.InternalFluidHandler) be.inputTank.getCapability();
            internal.forceFill(new FluidStack(Fluids.WATER, 800), IFluidHandler.FluidAction.EXECUTE);
            helper.runAfterDelay(120, () -> {
                int delivered = amount(helper, sink);
                if (delivered <= 0) {
                    helper.fail("the pump never drew from a 20%-full basin — the open bowl's fluid"
                            + " should always reach a side pipe" + dump(helper, new BlockPos(1, 1, 1)));
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * The goggle "Head left" readout must exist on BOTH sides of a working pump —
     * including when the suction run contains a junction with a dead-end stub,
     * which makes the suction cells junction NODES rather than edge interiors.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void headLeftShowsOnBothPumpSides(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        BlockPos stubPipe = new BlockPos(1, 2, 1);
        BlockPos pushPipe = new BlockPos(3, 1, 1);

        var pipe = AllBlocks.FLUID_PIPE.get();
        helper.setBlock(stubPipe, pipeState(pipe, Direction.DOWN));
        helper.setBlock(suctionPipe, pipeState(pipe,
                Direction.EAST, Direction.WEST,
                Direction.UP));
        fill(helper, new BlockPos(0, 1, 1), 4000);

        // Poll until the pump's kinetics have spun up and the readout is stable, rather
        // than racing the creative motor at a fixed tick (see the idle-suction test).
        helper.succeedWhen(() -> {
            var suction = PipeProbe.probe(
                    helper.getLevel(), helper.absolutePos(suctionPipe));
            var stub = PipeProbe.probe(
                    helper.getLevel(), helper.absolutePos(stubPipe));
            var push = PipeProbe.probe(
                    helper.getLevel(), helper.absolutePos(pushPipe));
            if (!push.hasHeadroom()) helper.fail("push side has no head-left value" + dump(helper));
            if (!suction.hasHeadroom()) helper.fail("suction junction has no head-left value" + dump(helper));
            if (!stub.hasHeadroom()) helper.fail("suction stub has no head-left value" + dump(helper));
            if (suction.headroomBlocks() < 1) {
                helper.fail("suction side head-left should include the pump boost, got "
                        + suction.headroomBlocks() + dump(helper));
            }
            if (stub.headTotalBlocks() < stub.headroomBlocks() + 0.2f) {
                helper.fail("stub sits above the supply surface, so its budget must exceed "
                        + "what is left: total=" + stub.headTotalBlocks()
                        + " left=" + stub.headroomBlocks() + dump(helper));
            }
            double suctionLimit = PipesNPhysicsConfig.SUCTION_LIMIT.get();
            if (!stub.hasSuctionMargin()
                    || stub.suctionMarginBlocks() <= 0
                    || stub.suctionMarginBlocks() >= suctionLimit) {
                helper.fail("stub hangs above the supply surface and must report a suction "
                        + "margin below the limit, got "
                        + (stub.hasSuctionMargin() ? stub.suctionMarginBlocks() : null)
                        + dump(helper));
            }
        });
    }

    /**
     * A powered pump with nothing to pull (empty source tank) moves no fluid, yet
     * "Head left" must still read on BOTH sides: the push side anchored by the
     * downstream tank, and the suction side seeded with the pump's waiting boost
     * so the player can read the budget before any fluid arrives.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void headLeftShowsOnIdleSuctionSide(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        BlockPos suctionTank = new BlockPos(0, 1, 1);
        BlockPos pushTank = new BlockPos(4, 1, 1);

        // Set up the empty source / full downstream AFTER the pump's kinetics and FACING
        // have settled. Filling at tick 0 races the spin-up, whose transient facing flips
        // slosh (and can drain) the downstream tank before it stabilizes; then POLL for the
        // readout so a slightly-late spin-up still passes.
        helper.runAfterDelay(60, () -> {
            drain(helper, suctionTank);
            drain(helper, pushTank);
            fill(helper, pushTank, 4000);

            helper.succeedWhen(() -> {
                var suction = PipeProbe.probe(helper.getLevel(), helper.absolutePos(suctionPipe));
                var push = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pushPipe));
                if (!push.hasHeadroom()) helper.fail("push side has no head-left value" + dump(helper));
                if (!suction.hasHeadroom()) {
                    helper.fail("idle suction side has no head-left value" + dump(helper));
                }
                if (suction.headroomBlocks() < 1) {
                    helper.fail("idle suction head-left should carry the pump boost, got "
                            + suction.headroomBlocks() + dump(helper));
                }
                if (suction.headTotalBlocks() < suction.headroomBlocks() - 0.01f) {
                    helper.fail("budget can never be smaller than what is left: total="
                            + suction.headTotalBlocks() + " left=" + suction.headroomBlocks()
                            + dump(helper));
                }
            });
        });
    }

    /**
     * Two powered pumps in series with nothing to pull: the dry suction side must
     * read the SUM of both pump boosts while the delivery stretch past both pumps
     * reads only what remains — head-left accumulates across boosters before any
     * fluid arrives. Pump facing is read at runtime because Create re-orients
     * pumps to match their rotation once kinetics settle.
     */
    @GameTest(template = "common/double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void headLeftAccumulatesAcrossIdlePumpsInSeries(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            // double_pump: pinned from NBT — pumps at x=3 & x=6, tanks at x=0 & x=8 (row y=1, z=0)
            List<BlockPos> pumps = new ArrayList<>(List.of(new BlockPos(3, 1, 0), new BlockPos(6, 1, 0)));
            List<BlockPos> tanks = new ArrayList<>(List.of(new BlockPos(0, 1, 0), new BlockPos(8, 1, 0)));
            pumps.sort(Comparator.comparingInt(BlockPos::getX));
            tanks.sort(Comparator.comparingInt(BlockPos::getX));

            boolean west = helper.getBlockState(pumps.get(0))
                    .getValue(PumpBlock.FACING) == Direction.WEST;
            BlockPos pushTank = west ? tanks.get(0) : tanks.get(1);
            BlockPos suctionTank = west ? tanks.get(1) : tanks.get(0);
            BlockPos suctionPipe = suctionTank.relative(west ? Direction.WEST : Direction.EAST);
            BlockPos deliveryPipe = pushTank.relative(west ? Direction.EAST : Direction.WEST);
            BlockPos betweenPumps = new BlockPos(
                    (pumps.get(0).getX() + pumps.get(1).getX()) / 2,
                    pumps.get(0).getY(), pumps.get(0).getZ());
            fill(helper, pushTank, 4000);

            helper.runAfterDelay(3, () -> {
                if (amount(helper, pushTank) != 4000) {
                    helper.fail("network was expected to stay idle" + dump(helper, betweenPumps));
                }
                var suction = PipeProbe.probe(helper.getLevel(), helper.absolutePos(suctionPipe));
                var between = PipeProbe.probe(helper.getLevel(), helper.absolutePos(betweenPumps));
                var delivery = PipeProbe.probe(helper.getLevel(), helper.absolutePos(deliveryPipe));
                if (!suction.hasHeadroom() || !between.hasHeadroom() || !delivery.hasHeadroom()) {
                    helper.fail("head-left missing on an idle series segment" + dump(helper, betweenPumps));
                }
                if (suction.headroomBlocks() < delivery.headroomBlocks() * 1.5f) {
                    helper.fail("suction head-left should stack both pump boosts: suction="
                            + suction.headroomBlocks() + " delivery=" + delivery.headroomBlocks()
                            + dump(helper, betweenPumps));
                }
                helper.succeed();
            });
        });
    }

    /**
     * A pump pushing into a tank that has no room left must report the stall's
     * culprit: the goggle detail line reads "destination is full".
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void stalledPipeReportsSinkFull(GameTestHelper helper) {
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 8000);
        fill(helper, new BlockPos(4, 1, 1), 8000);

        // Poll: the run first PRIMES (real fluid filling the pipe reads as flow), then stalls
        // against the full sink once the column is packed.
        helper.succeedWhen(() -> {
            var push = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pushPipe));
            if (push.status() != PipeStatusPayload.STATUS_STALLED) {
                helper.fail("expected STALLED on the push pipe, got status "
                        + push.status() + dump(helper));
            }
            if (push.statusDetail() != PipeStatusPayload.DETAIL_SINK_FULL) {
                helper.fail("expected SINK_FULL detail, got " + push.statusDetail() + dump(helper));
            }
        });
    }

    /**
     * A dry pipe whose (running) pump has nothing to pull must name the real culprit, not
     * leave a bare "No flow" beside a healthy-looking lift bar. With the source emptied the
     * powered pump just spins at zero flow — every branch idle and unflagged — so the probe
     * reports the pump as starved. (A pump pressing a full sink stalls; one that can't lift
     * is NO_HEAD; a valved one is blocked — only starvation reads as plain idle + dry.)
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void dryPipeReportsStarvedPump(GameTestHelper helper) {
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        drain(helper, new BlockPos(0, 1, 1));
        drain(helper, new BlockPos(4, 1, 1));

        helper.runAfterDelay(5, () -> {
            var push = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pushPipe));
            if (push.status() != PipeStatusPayload.STATUS_NO_FLOW) {
                helper.fail("expected NO_FLOW on the dry push pipe, got status "
                        + push.status() + dump(helper));
                return;
            }
            if (push.statusDetail() != PipeStatusPayload.DETAIL_PUMP_STARVED) {
                helper.fail("expected PUMP_STARVED detail (running pump, empty source), got "
                        + push.statusDetail() + dump(helper));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A running pump whose OUTPUT faces a solid block has nowhere to deliver - it is NOT short of
     * supply. The dry run must name the blocked output, not send the player to the source: capping
     * the push side and reading the intake pipe must report PUMP_NO_OUTPUT, the discriminator being
     * the missing push-side connection (contrast {@link #dryPipeReportsStarvedPump}, same dry pump
     * but an OPEN output, which stays PUMP_STARVED). This was the "can't pull its supply" misreport.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void deadEndedPumpReportsNoOutput(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        drain(helper, new BlockPos(0, 1, 1));
        drain(helper, new BlockPos(4, 1, 1));
        helper.setBlock(pushPipe, Blocks.STONE);

        helper.runAfterDelay(5, () -> {
            var suction = PipeProbe.probe(helper.getLevel(), helper.absolutePos(suctionPipe));
            if (suction.status() != PipeStatusPayload.STATUS_NO_FLOW) {
                helper.fail("expected NO_FLOW on the intake pipe, got status "
                        + suction.status() + dump(helper));
                return;
            }
            if (suction.statusDetail() != PipeStatusPayload.DETAIL_PUMP_NO_OUTPUT) {
                helper.fail("expected PUMP_NO_OUTPUT detail (pump output capped by a solid block), got "
                        + suction.statusDetail() + dump(helper));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * An unpowered pump acts as a closed valve; pipes feeding it must report
     * BLOCKED with the pump named as the culprit. The pump is unpowered by
     * removing the template's creative motor once kinetics have settled.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void blockedPipeReportsUnpoweredPump(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 4000);

        helper.runAfterDelay(3, () -> {
            // single_pump: the creative motor driving the pump sits at (1,1,0) — remove it to unpower
            helper.setBlock(new BlockPos(1, 1, 0), Blocks.AIR);

            helper.runAfterDelay(5, () -> {
                var suction = PipeProbe.probe(helper.getLevel(), helper.absolutePos(suctionPipe));
                if (suction.status() != PipeStatusPayload.STATUS_BLOCKED) {
                    helper.fail("expected BLOCKED on the suction pipe, got status "
                            + suction.status() + dump(helper));
                }
                if (suction.statusDetail() != PipeStatusPayload.DETAIL_PUMP_OFF) {
                    helper.fail("expected PUMP_OFF detail, got "
                            + suction.statusDetail() + dump(helper));
                }
                helper.succeed();
            });
        });
    }

    /**
     * A pump pushing a viscous fluid (lava) down a long run is friction-limited:
     * its goggle load breakdown must report a friction factor below 1, and the
     * shipped factors must reconstruct the displayed load bar exactly
     * (load = headFactor · frictionFactor = rate / cap). Pump facing settles with
     * its rotation, so the suction tank and run side are chosen at runtime.
     */
    @GameTest(template = "common/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void pumpLoadBreakdownExplainsFrictionLimit(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            BlockPos pump = new BlockPos(12, 1, 0); // piping/charging_max_range: pump pinned from NBT (FACING re-read below)
            List<BlockPos> tanks = new ArrayList<>(List.of(new BlockPos(0, 1, 0), new BlockPos(14, 1, 0)));
            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos pumpPos = pump;

            Direction facing = helper.getBlockState(pumpPos).getValue(PumpBlock.FACING);
            // Feed the suction side; the long run (most pipe cells) sits toward the
            // low-x tank, so the discharge is long only when the pump faces that way.
            BlockPos source = facing == Direction.WEST ? tanks.get(1) : tanks.get(0);
            boolean longDischarge = facing == Direction.WEST
                    ? pumpPos.getX() - tanks.get(0).getX() > tanks.get(1).getX() - pumpPos.getX()
                    : tanks.get(1).getX() - pumpPos.getX() > pumpPos.getX() - tanks.get(0).getX();
            // The template ships water-filled tanks; clear both so only the lava we
            // add is in play (a viscous fluid is what makes the long run friction-bound).
            drain(helper, tanks.get(0));
            drain(helper, tanks.get(1));
            fillFluid(helper, source, Fluids.LAVA, 8000);
            // Pre-prime every pipe with lava: at viscous trickle rates a cold 11-cell line takes
            // thousands of ticks to pack, and the load breakdown is a STEADY-STATE identity. Clear
            // each cell FIRST — the tanks shipped water and the pump pushed some into the pipes
            // before this setup runs; insert() never overwrites, so leftover water would meet the
            // lava and cross the streams (breaking the run), the very mechanic this test is beside.
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 4; z++) {
                        PipeStore.Store store = PipeStore.at(
                                helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
                        if (store != null) {
                            if (store.amount() > 0) store.extract(store.amount());
                            store.insert(new FluidStack(Fluids.LAVA, PipeStore.capacityMb()),
                                    PipeStore.capacityMb());
                            store.flush();
                        }
                    }

            // Poll: the reconstruction holds once the line reaches its steady operating point.
            helper.succeedWhen(() -> {
                var probe = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pumpPos));
                if (!probe.hasPumpLoad()) {
                    helper.fail("a running pump reported no load breakdown" + dump(helper, pumpPos));
                    return;
                }
                float speed = helper.getLevel().getBlockEntity(helper.absolutePos(pumpPos))
                        instanceof KineticBlockEntity k ? Math.abs(k.getSpeed()) : 0;
                double cap = speed * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
                double headSupplied = speed * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();
                if (cap <= 0 || headSupplied <= 0) {
                    helper.fail("pump is not spinning, speed=" + speed + dump(helper, pumpPos));
                    return;
                }
                double headFactor = (headSupplied - probe.pumpHeadAgainst()) / headSupplied;
                double loadCalc = headFactor * probe.pumpFrictionFactor();
                double loadBar = probe.mbPerTick() / cap;
                if (Math.abs(loadCalc - loadBar) > 1.0 / cap + 0.03) {
                    helper.fail("breakdown must reconstruct the load bar: head·friction="
                            + loadCalc + " bar=" + loadBar + dump(helper, pumpPos));
                    return;
                }
                if (longDischarge && probe.pumpFrictionFactor() >= 0.95f) {
                    helper.fail("lava down a long run should be friction-limited, factor="
                            + probe.pumpFrictionFactor() + dump(helper, pumpPos));
                }
            });
        });
    }

    /**
     * A running pump must empty a PRIMED suction line into a sink with room even when the solve
     * assembles no branch at all — the general "my source went away" case: an item drain that ran
     * dry, a tank broken off, a contraption undocked. The rig pins it by construction: the suction
     * run dead-ends in a capped pipe, so there is no source endpoint to participate and every edge
     * solves to zero and settles.
     *
     * Nothing but {@code SettlingRun.deliverThroughPump} can finish this. The settle packs the
     * OUTLET pipe to the sink's waterline and stops there — pouring on into the tank is a gravity
     * act a pipe already AT that waterline never satisfies — so the primed column the retention
     * rules deliberately keep would sit behind a spinning pump forever ("a pump that can't pump
     * from a pipe"). Mutation check: drop the {@code deliverThroughPump} call and the suction cell
     * still reads its full 250 mB here.
     */
    @GameTest(template = "physics/pump_dead_suction", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void pumpDeliversAPrimedSuctionLineWithNoSourceLeft(GameTestHelper helper) {
        BlockPos deadEnd = new BlockPos(0, 1, 1);
        BlockPos suction = new BlockPos(1, 1, 1);
        BlockPos outlet = new BlockPos(3, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        int primed = PipeStore.capacityMb();

        helper.runAfterDelay(5, () -> {
            // The sink starts wet so it contributes a real resting line (an empty reservoir defers,
            // and the run would gravity-pool instead of ever reaching the pump priming step).
            fill(helper, sink, 1000);
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(suction));
            if (cell == null) {
                helper.fail("no pipe store at " + suction.toShortString());
                return;
            }
            cell.insert(new FluidStack(Fluids.WATER, primed), primed);
            cell.flush();
        });

        helper.runAfterDelay(120, () -> {
            int held = pipeAmount(helper, suction);
            int tank = amount(helper, sink);
            int total = tank + held + pipeAmount(helper, outlet) + pipeAmount(helper, deadEnd);
            if (total != 1000 + primed) {
                helper.fail("fluid not conserved: tank " + tank + " + suction " + held
                        + " + pipes = " + total + " of " + (1000 + primed) + dump(helper, suction));
                return;
            }
            if (held > 0) {
                helper.fail("the pump left " + held + " mB standing in its primed suction line — a"
                        + " running pump with a sink that has room must draw its own line down"
                        + dump(helper, suction));
                return;
            }
            if (tank <= 1000) {
                helper.fail("the sink never received the primed column, still " + tank + " mB"
                        + dump(helper, sink));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The same delivery, with the pump wedged FLUSH against its sink: its outlet edge holds no
     * cells at all. A zero-cell edge is a wire, and the settle used to bail on one before anything
     * could run — so {@code pumpPrime} never reached {@code deliverThroughPump} (it needs an outlet
     * cell to pack) and the pump sat spinning with a full suction line behind it and a tank with
     * room in front, moving nothing, forever. The reported rig, shipped as its template: a 5-tall
     * tank, the pump against its side, two suction cells, and an EMPTY source tank over the far end
     * so the solve assembles no branch.
     *
     * Mutation check: restore the {@code cells.isEmpty()} bail at the top of {@code settle()} and
     * both suction cells still read their stored 237 mB.
     */
    @GameTest(template = "physics/pumps_pipe_empty", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void pumpFlushAgainstItsSinkStillDrainsItsSuctionLine(GameTestHelper helper) {
        BlockPos sink = new BlockPos(0, 1, 0);
        BlockPos pump = new BlockPos(1, 4, 0);
        BlockPos near = new BlockPos(2, 4, 0);
        BlockPos far = new BlockPos(3, 4, 0);
        BlockPos source = new BlockPos(3, 5, 0);
        int[] start = new int[2];

        // Read the rig once the multiblock tank has formed and the motor has spun the pump up.
        helper.runAfterDelay(5, () -> {
            start[0] = amount(helper, sink);
            start[1] = pipeAmount(helper, near) + pipeAmount(helper, far);
            Direction facing = helper.getBlockState(pump).getValue(PumpBlock.FACING);
            if (facing != Direction.WEST) {
                helper.fail("the pump must push toward the sink it sits against, facing " + facing);
            } else if (start[1] <= 0) {
                helper.fail("the template's suction line came up empty" + dump(helper, near));
            }
        });

        // Mid-delivery: the pump must carry the cosmetic flow stamp of what it is pushing across
        // the wire. It owns no cell, so nothing else on this network can carry one — and the
        // client FX layer reads stamps, so without it a pump against a tank pours no particles.
        helper.runAfterDelay(20, () -> {
            PipeStore.Store store = PipeStore.at(helper.getLevel(), helper.absolutePos(pump));
            Direction stamped = store == null ? null : PipeStore.flowDirection(store.flowData());
            if (stamped != Direction.WEST) {
                helper.fail("a pump delivering across a wire must stamp its own flow, got "
                        + stamped + dump(helper, near));
            }
        });

        helper.runAfterDelay(200, () -> {
            int tank = amount(helper, sink);
            int left = pipeAmount(helper, near) + pipeAmount(helper, far);
            int held = start[0];
            if (tank + left + amount(helper, source) != held + start[1]) {
                helper.fail("fluid not conserved: tank " + tank + " + pipes " + left + " of "
                        + (held + start[1]) + dump(helper, near));
                return;
            }
            if (tank <= held) {
                helper.fail("the pump delivered nothing into the tank it sits against, still "
                        + tank + " mB — a zero-cell outlet is a wire, not a wall"
                        + dump(helper, near));
                return;
            }
            if (left > 0) {
                helper.fail("the pump left " + left + " mB standing in its suction line"
                        + dump(helper, near));
            }
            helper.succeed();
        });
    }

    /**
     * The dual wall: a pump must NOT lift out of a tank its fluid does not reach. The solve gates
     * this ({@code FluidPass.canDrawFrom} against the pump's draw lip — the opening cell's block
     * floor), but the settle's pump paths drained the supply handler STRAIGHT, and no lip cap is
     * installed on a tick that solved no flow — so the pump packed its outlet run and delivered on
     * out of a tank whose visible surface stood more than a block BELOW its own opening ("why does
     * this pump pipe? the surface of the fluid does not reach the pump yet"). The rig is the
     * reported one reversed: a 5-tall tank only a fifth full (surface ~1.2 blocks up) with the pump
     * three blocks above it, pushing away into a sink that has room — so the ONLY thing that may
     * stop it is the draw lip.
     *
     * Mutation check: drop the {@code pumpMayDraw} guards and the source tank bleeds away here.
     */
    @GameTest(template = "physics/pump_above_waterline", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void pumpDoesNotLiftFromBelowItsDrawLip(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 0);
        BlockPos pump = new BlockPos(1, 4, 0);
        BlockPos sink = new BlockPos(3, 5, 0);
        int[] start = new int[1];

        helper.runAfterDelay(5, () -> {
            start[0] = amount(helper, source);
            Direction facing = helper.getBlockState(pump).getValue(PumpBlock.FACING);
            if (facing != Direction.EAST) {
                helper.fail("the pump must pull from the low tank, facing " + facing);
            } else if (start[0] <= 0 || amount(helper, sink) >= 8000) {
                helper.fail("the rig must start with a supply below the lip and a sink with room"
                        + dump(helper, pump));
            }
        });

        helper.runAfterDelay(200, () -> {
            int held = amount(helper, source);
            if (held < start[0]) {
                helper.fail("the pump lifted " + (start[0] - held) + " mB out of a tank whose"
                        + " surface sits below its opening — the settle must honor the draw lip"
                        + dump(helper, pump));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A pump from ANOTHER mod — a block the engine has only been TOLD is a pump — must develop head
     * like a Mechanical Pump. Every pump on Create's pipe network states its strength the same way,
     * whatever powers it: it writes that strength as PRESSURE onto its own connections, inbound on
     * the flank it sucks from and outward on the flank it pushes into. Create's own pump publishes
     * exactly its RPM there, so the engine can read an electric or centrifugal pump on that one
     * scale without knowing the mod.
     *
     * The stand-in is a Create SMART PIPE declared through {@link PumpApi} and pressurized by hand —
     * nothing else about it is a pump, so what is under test is precisely the declaration and the
     * published pressure. The rig is the flat two-tank run, where gravity can only ever reach the
     * halfway line: driving PAST it is the proof that head was developed.
     *
     * Its OWN batch: {@link PumpApi} is global and the declaration is held across ticks, so a smart
     * pipe in a test running CONCURRENTLY would turn into a pump under it (the filtered separation
     * lines did exactly that).
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 600, batch = "foreignPump")
    public static void declaredForeignPumpDevelopsHeadFromItsPublishedPressure(GameTestHelper helper) {
        Block smartPipe = AllBlocks.SMART_FLUID_PIPE.get();
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos pump = new BlockPos(2, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        PumpApi.declarePump(smartPipe);
        placeStandInPipe(helper, pump, AttachFace.FLOOR, Direction.EAST);
        fill(helper, source, 8000);
        drain(helper, sink);

        helper.runAfterDelay(5, () -> {
            Level level = helper.getLevel();
            BlockPos abs = helper.absolutePos(pump);
            publishPumpPressure(helper, pump, Direction.EAST, 64f);
            double strength = Pumps.strength(level, abs);
            if (Math.abs(strength - 64) > 0.01) {
                PumpApi.clearPump(smartPipe);
                helper.fail("the engine read " + strength + " RPM off a pump publishing 64");
                return;
            }
            Node node = GraphBuilder.build(level, abs).nodeAt(abs);
            if (node == null || !node.isPump() || node.pumpFacing() != Direction.EAST) {
                PumpApi.clearPump(smartPipe);
                helper.fail("a declared pump did not join the graph as a PUMP node pushing east: "
                        + (node == null ? "no node" : node.kind() + " facing " + node.pumpFacing()));
            }
        });

        helper.succeedWhen(() -> {
            publishPumpPressure(helper, pump, Direction.EAST, 64f);
            int moved = amount(helper, sink);
            if (moved <= 5000) {
                helper.fail("a declared foreign pump moved " + moved + " of 8000 mB — no more than"
                        + " the levels equalize to on their own, so it developed no head"
                        + dump(helper, pump));
            }
            PumpApi.clearPump(smartPipe);
        });
    }


    /**
     * The addon pumps this dev runtime actually ships — TFMG's and Power Grid's electric pumps —
     * against the real blocks: each must join the graph as a pump node facing the way it was placed,
     * and the engine must read the strength it publishes. Neither can be POWERED inside a GameTest
     * (one wants a voltage, the other a circuit), so the pressure they would publish is stated by
     * hand and read back in the same tick, before their own tick restates it: the number is theirs
     * to write, the reading is ours to get right. Whichever mod is absent is skipped.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void installedAddonPumpsAreDrivenByTheEngine(GameTestHelper helper) {
        BlockPos pump = new BlockPos(2, 1, 1);
        List<ResourceLocation> addonPumps = List.of(
                ResourceLocation.fromNamespaceAndPath("tfmg", "electric_pump"),
                ResourceLocation.fromNamespaceAndPath("powergrid", "electric_pump"));

        int tick = 10;
        for (ResourceLocation id : addonPumps) {
            final int assertAt = tick;
            helper.runAtTickTime(assertAt - 8, () -> placeAddonPump(helper, pump, id));
            helper.runAtTickTime(assertAt, () -> checkAddonPump(helper, pump, id));
            tick += 20;
        }
        helper.runAtTickTime(tick, helper::succeed);
    }

    /** Put the addon's pump into the rig facing east, if that mod is installed. */
    private static void placeAddonPump(GameTestHelper helper, BlockPos rel, ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR) return; // mod not installed
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, Direction.EAST);
        }
        placeRigBlock(helper, rel, state);
    }

    /** What the engine makes of it: a pump node pushing east, developing the strength it states. */
    private static void checkAddonPump(GameTestHelper helper, BlockPos rel, ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR) return; // mod not installed
        Level level = helper.getLevel();
        BlockPos abs = helper.absolutePos(rel);
        BlockState state = level.getBlockState(abs);
        if (!Pumps.isPump(level, abs, state)) {
            helper.fail(id + " is not recognized as a pump (tag entry or PumpBlock kinship missing)");
            return;
        }
        Node node = GraphBuilder.build(level, abs).nodeAt(abs);
        if (node == null || !node.isPump() || node.pumpFacing() != Direction.EAST) {
            helper.fail(id + " did not join the graph as a pump node pushing east: "
                    + (node == null ? "no node" : node.kind() + " facing " + node.pumpFacing()));
            return;
        }
        double scale = PipesNPhysicsConfig.FOREIGN_PUMP_STRENGTH_SCALE.get();
        PipesNPhysicsConfig.FOREIGN_PUMP_STRENGTH_SCALE.set(1.0);
        try {
            publishPumpPressure(helper, rel, Direction.EAST, 64f);
            double strength = Pumps.strength(level, abs);
            if (Math.abs(strength - 64) > 0.01) {
                helper.fail("the engine read " + strength + " RPM off " + id + " publishing 64");
                return;
            }
            if (Pumps.pushSide(level, abs, state) != Direction.EAST) {
                helper.fail(id + " resolved its push side as " + Pumps.pushSide(level, abs, state));
            }
        } finally {
            PipesNPhysicsConfig.FOREIGN_PUMP_STRENGTH_SCALE.set(scale);
        }
    }

    /**
     * Under the engine a PIPE carries no Create pressure; only a pump's own flanks do, because that
     * is how a pump states its strength and how the engine reads another mod's (§2). The reason is
     * the other half of the CROWNS story: a foreign pump keeps distributing pressure down its run
     * from its own tick, and that pressure is exactly what a peer which reimplements the pipe tick
     * would move fluid on, invisibly beside us. Both halves are asserted here — the pipe is wiped,
     * the pump keeps what it published — since wiping the pump too would read as a stopped pump.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void pipesCarryNoCreatePressureButPumpsKeepTheirs(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(1, 1, 1);
        BlockPos pump = new BlockPos(2, 1, 1);

        helper.runAtTickTime(4, () -> {
            publishPumpPressure(helper, pipe, Direction.EAST, 64f); // as a neighbouring pump distributes
            publishPumpPressure(helper, pump, Direction.EAST, 64f); // as the pump itself publishes
        });

        helper.runAtTickTime(10, () -> {
            float onPipe = pressureAt(helper, pipe);
            if (onPipe != 0) {
                helper.fail("a pipe still carries " + onPipe + " of Create pressure — a peer that"
                        + " reimplements the pipe tick would transport fluid on it");
                return;
            }
            if (pressureAt(helper, pump) <= 0) {
                helper.fail("the pump's own published pressure was cleared — that IS how a foreign"
                        + " pump states its strength, so the engine would read it as stopped");
                return;
            }
            helper.succeed();
        });
    }

    /** The strongest pressure standing on a cell's connections, in Create's own units. */
    private static float pressureAt(GameTestHelper helper, BlockPos rel) {
        FluidTransportBehaviour cell = FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(rel));
        if (cell == null || cell.interfaces == null) return 0;
        float strongest = 0;
        for (PipeConnection connection : cell.interfaces.values()) {
            strongest = Math.max(strongest, Math.max(connection.getPressure().getFirst(),
                    connection.getPressure().getSecond()));
        }
        return strongest;
    }
}
