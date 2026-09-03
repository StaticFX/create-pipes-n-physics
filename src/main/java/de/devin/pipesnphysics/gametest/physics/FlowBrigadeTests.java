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
 * Conserved plug flow: depth gating, priming, junction slots, conservation, front advance.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class FlowBrigadeTests {

    /**
     * Gas hydrostatics at a junction: a lighter-than-air gas POOLS UPWARD. The tank's gas rises
     * through the riser and packs the junction slot (the mirrored settle draws it up; the slot
     * bubbles up), and nothing ever bleeds SIDEWAYS into the capped stubs — the guard the old
     * full freeze provided (the TFMG coke-oven churn: the liquid-target math bled a slot's gas
     * into an idle edge every tick while the brigade pushed it back) now comes from buoyant
     * exchange being monotone. Skips when no lighter-than-air fluid is registered.
     */
    @GameTest(template = "physics/gas_junction", templateNamespace = PipesNPhysics.ID, timeoutTicks = 160)
    public static void gasPacksUpwardFromTheTankBelow(GameTestHelper helper) {
        Fluid gas = lighterThanAirFluid();
        if (gas == null) {
            helper.succeed(); // no gas fluid in this runtime — nothing to verify
            return;
        }

        // gas_junction: tank(1,1,1) — riser(1,2,1) — junction(1,3,1) with two stubs (EAST, SOUTH)
        // capped by iron blocks, so no flow can solve: the settle alone moves the gas.
        BlockPos tank = new BlockPos(1, 1, 1);
        BlockPos riser = new BlockPos(1, 2, 1);
        BlockPos center = new BlockPos(1, 3, 1);
        BlockPos stubEast = new BlockPos(2, 3, 1);
        BlockPos stubSouth = new BlockPos(1, 3, 2);

        helper.runAfterDelay(10, () -> {
            handler(helper, tank).fill(new FluidStack(gas, 2000), IFluidHandler.FluidAction.EXECUTE);
            PipeStore.Store slot = PipeStore.at(helper.getLevel(), helper.absolutePos(center));
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(riser));
            if (slot == null || cell == null) {
                helper.fail("no pipe store at the junction/riser cell");
                return;
            }
            slot.insert(new FluidStack(gas, 200), 200);
            slot.flush();
            cell.insert(new FluidStack(gas, 100), 100);
            cell.flush();
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(riser));
        });
        helper.runAfterDelay(140, () -> {
            int slotHeld = pipeAmount(helper, center);
            int cellHeld = pipeAmount(helper, riser);
            int tankHeld = amount(helper, tank);
            int stubs = pipeAmount(helper, stubEast) + pipeAmount(helper, stubSouth);
            if (stubs > 0) {
                helper.fail("gas bled SIDEWAYS into a capped stub (" + stubs + " mB) — the churn"
                        + " the buoyant exchange must not reintroduce");
                return;
            }
            if (slotHeld < 240 || cellHeld < 240) {
                helper.fail("gas did not pack the column above the tank (slot " + slotHeld
                        + "/250, riser " + cellHeld + "/250, tank " + tankHeld + ")");
                return;
            }
            if (slotHeld + cellHeld + tankHeld + stubs != 2300) {
                helper.fail("gas not conserved: " + slotHeld + " + " + cellHeld + " + " + tankHeld
                        + " != 2300");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A FLOWING run below both waterlines must fill up WHILE it flows, source-side-first. The
     * brigade's plug rules alone only top the TAIL cell (delivery gates on a full tail; every
     * upstream cell passes what it receives, netting zero), so a submerged run froze at whatever
     * partial fill it started with, fullest at the sink ("the 3 pipes get increasingly more
     * fluid" report). The flowing top-up (SettlingRun.topUp) draws from the reservoirs toward
     * the hydrostatic profile alongside the flow: all three cells must reach ~full.
     */
    @GameTest(template = "common/wide_tank_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void submergedFlowingRunTopsUpFromTheSourceSide(GameTestHelper helper) {
        // wide_tank_run: two WIDE 2x2x3 tanks (x∈{0,1} and {5,6}) with a straight run at their BOTTOM
        // row (both waterlines above it). The head difference stays SMALL, so the freeze only shows at
        // slow flow next to the cell capacity, and the wide surfaces keep the run FLOWING at assert time
        // (equalized tanks would let the ordinary idle settle fill the run and hide the bug).
        List<BlockPos> run = List.of(
                new BlockPos(2, 1, 1), new BlockPos(3, 1, 1), new BlockPos(4, 1, 1));
        helper.runAfterDelay(5, () -> {
            fill(helper, new BlockPos(1, 1, 1), 88000); // surface 2.75 blocks up
            fill(helper, new BlockPos(5, 1, 1), 75200); // surface 2.35 — slow flow, long window
            for (BlockPos rel : run) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, 50), 50); // wet, so the plug moves freely
                cell.flush();
            }
            // The handler fills fire no block event; wake the sleeping network NOW so the flow
            // window starts immediately and the assert below lands well before equalization.
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(0)));
        });
        helper.runAfterDelay(35, () -> {
            int full = PipeStore.capacityMb();
            // Guard the premise: the run must still be flowing (tanks not yet equalized), else
            // this asserts the idle settle rather than the flowing top-up.
            if (amount(helper, new BlockPos(1, 1, 1)) - amount(helper, new BlockPos(5, 1, 1)) < 1500) {
                helper.fail("rig equalized before the assert — enlarge the head difference");
                return;
            }
            for (BlockPos rel : run) {
                int held = pipeAmount(helper, rel);
                if (held < full - 25) {
                    helper.fail("submerged flowing cell " + rel.toShortString() + " holds " + held
                            + "/" + full + " — the run froze at the plug's partial fill instead of"
                            + " topping up to the waterline");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * Swapping a pipe block IN PLACE (the shift-swap, Create's wrench window toggle, encasing)
     * replaces the block entity — and stored fluid rides the block entity, so the glassed pipe
     * used to come up EMPTY and the content was voided ("switch a pipe to glassed view loses its
     * content"). {@code PipeContentCarryMixin} stashes a destroyed cell's content and the
     * replacement cell adopts it as it initializes: swap a full pipe for its windowed variant and
     * the fluid must survive.
     */
    @GameTest(template = "physics/capped_cell", templateNamespace = PipesNPhysics.ID, timeoutTicks = 60)
    public static void swappedPipeKeepsItsContent(GameTestHelper helper) {
        // capped_cell: a single pipe(2,1,1) capped by iron on both sides — no open mouths and no
        // neighbours to settle into, so its content can only survive the swap or vanish with the BE.
        BlockPos middle = new BlockPos(2, 1, 1);
        helper.runAfterDelay(5, () -> {
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(middle));
            if (cell == null) {
                helper.fail("no pipe store at the middle cell");
                return;
            }
            cell.insert(new FluidStack(Fluids.WATER, 250), 250);
            cell.flush();
            helper.setBlock(middle, AllBlocks.GLASS_FLUID_PIPE.get().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,
                            Direction.Axis.X));
        });
        helper.runAfterDelay(30, () -> {
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(middle));
            int held = cell == null ? 0 : cell.amount();
            if (held < 250) {
                helper.fail("the swapped (glassed) pipe lost its content: holds " + held + "/250");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * CONSERVATION under transfer: from the first tick to the settled end state, the water in the
     * two tanks plus the water stored in the pipes must always sum to what was poured in — the
     * brigade may neither mint nor void a single mB while it primes, flows, and settles.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void fluidConservedThroughPriming(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        fill(helper, source, 8000);

        helper.succeedWhen(() -> {
            int total = amount(helper, source) + amount(helper, sink)
                    + pipesnphysics$areaPipeContent(helper, 6, 4, 4);
            if (total != 8000) helper.fail("fluid not conserved: tanks+pipes=" + total);
            if (amount(helper, source) > 0) helper.fail("source not fully drained yet");
        });
    }

    /**
     * TRAVEL TIME: the sink must not receive its first drop before the pipe feeding it carries
     * the flowing column — fluid physically resides in the run while it primes, so delivery
     * begins exactly when the column reaches the sink (what the player sees is what happens).
     * The column gate is the FLOW DEPTH, not a full cell (a trickle streams shallow); the rate
     * here is the template pump's, so this asserts the depth FLOOR — the weakest gate any rate
     * produces. The exact at-depth semantics live in {@link #trickleFlowsAtItsPartialDepth}.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void sinkFillsOnlyAfterPipePrimes(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        fill(helper, source, 8000);

        helper.succeedWhen(() -> {
            int delivered = amount(helper, sink);
            int pushCell = cellMb(helper.getLevel(), helper.absolutePos(pushPipe));
            int depthFloor = FlowNetwork.flowDepthMb(1, PipeStore.capacityMb());
            if (delivered > 0 && pushCell < depthFloor) {
                helper.fail("sink received " + delivered + " mB while the pipe feeding it holds only "
                        + pushCell + "/" + depthFloor + " (flow-depth floor) — delivery outran the fluid");
            }
            if (delivered <= 0) helper.fail("nothing delivered yet");
        });
    }

    /**
     * A pump wedged DIRECTLY against a junction — zero pipe cells between them — must still
     * deliver. The brigade tops a junction slot up from the feeding run's TAIL CELL; a zero-cell
     * run has none, and a consumer past the junction only pulls from a slot at its flow depth, so
     * the slot never filled and the whole line read solved flow with zero actual (the coke-oven smokestack
     * report: pumps flush under the junction row). Converts the template's push-side cell into a
     * junction by hanging a tank off it, then asserts fluid really arrives past it.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void pumpAgainstJunctionSlotStillDelivers(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            BlockPos pumpRel = new BlockPos(2, 1, 1); // piping/single_pump: pump pinned from NBT; FACING is re-read below (Create re-orients it at runtime)
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos junctionRel = pumpRel.relative(push);
            if (!pipeAt(helper, junctionRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            // A tank flush against the push-side cell gives it a third connection: the cell
            // becomes a junction NODE and the pump→junction edge carries ZERO pipe cells.
            BlockPos tankRel = null;
            for (Direction side : Direction.values()) {
                if (side.getAxis() == push.getAxis()) continue;
                if (helper.getBlockState(junctionRel.relative(side)).isAir()) {
                    tankRel = junctionRel.relative(side);
                    break;
                }
            }
            if (tankRel == null) { helper.fail("no free face beside the junction cell"); return; }
            helper.setBlock(tankRel, AllBlocks.FLUID_TANK.get().defaultBlockState());

            fill(helper, new BlockPos(0, 1, 1), 8000); // source full
            drain(helper, new BlockPos(4, 1, 1));      // sink empty -> the pump wants to move fluid

            BlockPos sideTank = tankRel;
            helper.succeedWhen(() -> {
                if (amount(helper, sideTank) <= 0 && amount(helper, new BlockPos(4, 1, 1)) <= 0) {
                    helper.fail("no fluid delivered past the zero-cell pump→junction edge");
                }
            });
        });
    }

    /**
     * A manifold's junction slots must serve their feeders FAIRLY. Several runs feeding one slot
     * used to refill its freed room in fixed tick order, so on a chained manifold (junction row,
     * one shared outlet) the first feeder monopolized the room every tick and a competing line
     * starved indefinitely — the coke-oven row: one oven fed the smokestack, its neighbours never
     * did. The brigade splits a slot's room among its feeders by proportional share (the planner's
     * manifold rule, applied at the slot). Builds a gravity rig — three source tanks flush against
     * a junction row draining through ONE outlet — and asserts every source contributes.
     */
    // Own batch: this test PINS the global PIPE_CONDUCTANCE at 5000 for a 60-tick window, and
    // batches are the only isolation gametests have — in the default batch every concurrently
    // running flow test solved with 42x conductance during that window (it masked the
    // submerged-run top-up freeze as a false green, and can skew any rate-shaped assertion).
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300, batch = "pinnedConfig")
    public static void manifoldSlotServesAllFeeders(GameTestHelper helper) {
        var level = helper.getLevel();
        // Raze the template rig and build the manifold in its bounds (floor at y=0 stays).
        for (int x = 0; x < 6; x++)
            for (int y = 1; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());

        // Junction row at y=2 with a full source tank ON TOP of each cell (full head, no side
        // lip), draining through one outlet corner into a single sink below.
        BlockPos sink = new BlockPos(0, 1, 0);
        BlockPos outlet = new BlockPos(0, 2, 0);
        List<BlockPos> junctionRow = List.of(
                new BlockPos(0, 2, 1), new BlockPos(1, 2, 1), new BlockPos(2, 2, 1));
        List<BlockPos> sources = List.of(
                new BlockPos(0, 3, 1), new BlockPos(1, 3, 1), new BlockPos(2, 3, 1));

        helper.setBlock(sink, AllBlocks.FLUID_TANK.get().defaultBlockState());
        for (BlockPos tank : sources) helper.setBlock(tank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        for (BlockPos pipe : junctionRow) {
            level.setBlockAndUpdate(helper.absolutePos(pipe), AllBlocks.FLUID_PIPE.getDefaultState());
        }
        level.setBlockAndUpdate(helper.absolutePos(outlet), AllBlocks.FLUID_PIPE.getDefaultState());
        // setBlock only re-shapes the NEIGHBOURS; recompute the placed cells' own connections too.
        for (BlockPos pipe : junctionRow) {
            BlockPos abs = helper.absolutePos(pipe);
            level.setBlock(abs, Block.updateFromNeighbourShapes(level.getBlockState(abs), level, abs), 3);
        }
        BlockPos outletAbs = helper.absolutePos(outlet);
        level.setBlock(outletAbs, Block.updateFromNeighbourShapes(level.getBlockState(outletAbs), level, outletAbs), 3);

        for (BlockPos tank : sources) fill(helper, tank, 8000);

        // Starvation needs SCARCITY: freed slot room per tick far below the feeders' solved
        // budgets. In the wild it comes from a rate-limited sink (a venting smokestack); here the
        // engine's own per-boundary ceiling manufactures it — solved rates cranked far above the
        // one-cell-volume-per-tick cap, so every slot's room is a crumb the feeders compete for.
        double priorConductance = PipesNPhysicsConfig.PIPE_CONDUCTANCE.get();
        PipesNPhysicsConfig.PIPE_CONDUCTANCE.set(5000.0);

        // Well before the sink fills: every source must have contributed a fair share, not the
        // crumbs a monopolized slot leaks. The one whose feeder ticks first at its slot must not
        // be the only real contributor.
        helper.runAfterDelay(60, () -> {
            PipesNPhysicsConfig.PIPE_CONDUCTANCE.set(priorConductance);
            int[] given = new int[sources.size()];
            int total = 0;
            for (int i = 0; i < sources.size(); i++) {
                given[i] = 8000 - amount(helper, sources.get(i));
                total += given[i];
            }
            if (total < 1000) {
                helper.fail("manifold barely moved: " + total + " mB total");
                return;
            }
            for (int i = 0; i < given.length; i++) {
                if (given[i] * 6 < total) {
                    helper.fail("source " + i + " gave " + given[i] + " of " + total
                            + " mB — starved by its junction slot's other feeders (gave "
                            + given[0] + "/" + given[1] + "/" + given[2] + ")");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * Fluid crossing a junction traverses the junction CELL — it may not skip it: the cell fills
     * (and renders) before anything continues into the downstream run, so a chain reads as one
     * continuous travel. Splits the template's longest run with a pipe stub (the mid cell gains a
     * third connection and becomes a junction), primes the feeder half, and drives the executor:
     * the junction slot must go wet no later than the dependent run's first cell.
     */
    @GameTest(template = "common/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200, batch = "levelRender")
    public static void junctionCellFillsBeforeDownstreamRun(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            BlockPos seed = new BlockPos(1, 1, 0); // piping/charging_max_range: any pipe cell seeds the whole-network graph

            Graph scan = GraphBuilder.build(level, helper.absolutePos(seed));
            Edge longest = null;
            for (Edge e : scan.edges())
                if (!e.pipes().isEmpty() && (longest == null || e.pipes().size() > longest.pipes().size())) longest = e;
            if (longest == null || longest.pipes().size() < 5) { helper.fail("no long pipe run"); return; }

            // Split the run: swap the mid cell for a REGULAR (auto-connecting) fluid pipe — the
            // template's run is straight glass, which is axis-locked and ignores side stubs — and
            // give it a stub neighbour for a third connection, so the rebuilt graph contracts the
            // run into two edges joined at a junction node there.
            List<BlockPos> run = longest.pipes();
            BlockPos mid = run.get(run.size() / 2);
            level.setBlockAndUpdate(mid, AllBlocks.FLUID_PIPE.getDefaultState());
            BlockPos stub = null;
            for (Direction dir : Direction.values()) {
                BlockPos candidate = mid.relative(dir);
                if (!level.getBlockState(candidate).isAir()) continue;
                boolean touchesOtherPipe = false;
                for (Direction d2 : Direction.values()) {
                    BlockPos n = candidate.relative(d2);
                    if (!n.equals(mid) && level.getBlockState(n).is(AllBlocks.FLUID_PIPE.get())) {
                        touchesOtherPipe = true;
                        break;
                    }
                }
                if (!touchesOtherPipe) { stub = candidate; break; }
            }
            if (stub == null) { helper.fail("no free face beside the mid cell for the stub"); return; }
            level.setBlockAndUpdate(stub, AllBlocks.FLUID_PIPE.getDefaultState());
            // setBlock only re-shapes the NEIGHBOURS; the placed cells' own connection blockstates
            // must be recomputed too (GraphBuilder requires reciprocal openings on both sides).
            level.setBlock(stub, Block.updateFromNeighbourShapes(level.getBlockState(stub), level, stub), 3);
            level.setBlock(mid, Block.updateFromNeighbourShapes(level.getBlockState(mid), level, mid), 3);

            Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));
            Node junction = graph.nodeAt(mid);
            if (junction == null) {
                helper.fail("mid cell did not become a junction node (mid=" + level.getBlockState(mid)
                        + ", stub=" + level.getBlockState(stub) + ")");
                return;
            }
            Edge feeder = null;
            Edge dependent = null;
            for (Edge e : graph.edgesOf(junction.index())) {
                if (e.pipes().isEmpty()) continue;
                if (feeder == null) feeder = e;
                else if (dependent == null || e.pipes().size() > dependent.pipes().size()) dependent = e;
            }
            if (feeder == null || dependent == null) { helper.fail("junction did not split the run into two edges"); return; }

            // Flow: feeder INTO the junction, dependent OUT of it, water on both — as one FlowPass
            // the executor runs. The feeder starts FULL (a primed line), everything past it dry.
            List<EdgeFlow> flows = new ArrayList<>();
            double[] passFlow = new double[graph.edges().size()];
            for (Edge e : graph.edges()) {
                if (e.index() == feeder.index()) {
                    boolean aToB = e.b() == junction.index();
                    flows.add(new EdgeFlow(e.index(), aToB
                            ? EdgeFlow.Direction.A_TO_B : EdgeFlow.Direction.B_TO_A, 200));
                    passFlow[e.index()] = aToB ? 200 : -200;
                } else if (e.index() == dependent.index()) {
                    boolean aToB = e.a() == junction.index();
                    flows.add(new EdgeFlow(e.index(), aToB
                            ? EdgeFlow.Direction.A_TO_B : EdgeFlow.Direction.B_TO_A, 200));
                    passFlow[e.index()] = aToB ? 200 : -200;
                } else {
                    flows.add(EdgeFlow.none(e.index()));
                }
            }
            FluidStack water = new FluidStack(Fluids.WATER, 1);
            Solution flowing = new Solution(flows, List.of(),
                    List.of(new Solution.FlowPass(water, passFlow)), new int[graph.edges().size()],
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), true);

            int capacity = PipeStore.capacityMb();
            for (BlockPos cell : feeder.pipes()) {
                PipeStore.Store store = PipeStore.at(level, cell);
                if (store != null) {
                    store.extract(capacity);
                    store.insert(new FluidStack(Fluids.WATER, capacity), capacity);
                    store.flush();
                }
            }
            for (BlockPos cell : dependent.pipes()) {
                PipeStore.Store store = PipeStore.at(level, cell);
                if (store != null) { store.extract(capacity); store.flush(); }
            }
            PipeStore.Store slot = PipeStore.at(level, mid);
            if (slot != null) { slot.extract(capacity); slot.flush(); }

            // Drive the executor tick by tick: the junction CELL must fill before the dependent
            // run's first cell sees anything — fluid traverses the junction, it never skips it.
            int slotTick = -1;
            int depTick = -1;
            BlockPos depFirst = dependent.a() == junction.index()
                    ? dependent.pipes().get(0) : dependent.pipes().get(dependent.pipes().size() - 1);
            for (int i = 0; i < 40 && depTick < 0; i++) {
                PipeFlowExecutor.run((ServerLevel) level, graph, flowing);
                if (slotTick < 0 && cellMb(level, mid) > 0) {
                    slotTick = i;
                    // The goggle must READ that slot: a junction's probe reports its stored
                    // content ("Holds: N mB") exactly like an edge cell's — it used to send 0.
                    if (PipeProbe.probe((ServerLevel) level, mid).holdsMb() <= 0) {
                        helper.fail("junction slot is wet but the goggle probe reads holds=0");
                        return;
                    }
                }
                if (depTick < 0 && cellMb(level, depFirst) > 0) depTick = i;
            }
            if (depTick < 0) { helper.fail("flow never crossed the junction into the dependent run"); return; }
            if (slotTick < 0 || slotTick > depTick) {
                helper.fail("fluid skipped the junction cell (slot wet at tick " + slotTick
                        + ", dependent at " + depTick + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * DEPTH-GATED plug flow: a slow run carries its fluid at the FLOW DEPTH
     * ({@code FlowNetwork.flowDepthMb} — a few ticks of buffered rate), NOT a full cell — an
     * 11 mB/t trickle is a shallow stream, and only pressure or back-up packs full-bore. Every
     * plug gate keeps its shape at that depth: the sink receives nothing until the tail cell
     * carries the depth, the junction slot conducts only once it pools the depth, and in steady
     * state every cell (and the slot) rides AT the depth — never climbing toward full (the "a
     * flowing pipe always renders 100% full" report) and never smearing below it.
     *
     * Builds tank — cell — junction(+stub) — cell — pump — cell — tank on the empty canvas and
     * drives the executor synchronously with a synthetic trickle pass (the whole burst runs
     * inside one server tick, so the real engine never interleaves), tracing delivery and fills
     * per iteration. The layout threads every plug gate: park-until-depth (tail into the dry
     * slot), the slot conduction gate, the pull-through-a-pump tail gate, and the sink delivery
     * gate.
     */
    @GameTest(template = "physics/trickle_rig", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void trickleFlowsAtItsPartialDepth(GameTestHelper helper) {
        // trickle_rig: tank(0,1,1) — cell — junction(2,1,1)+stub(2,1,2) — cell — UNPOWERED pump(4,1,1,
        // FACING EAST, a slot-less pass-through) — cell — tank(6,1,1); threads every plug gate.
        BlockPos sourceRel = new BlockPos(0, 1, 1);
        BlockPos feederCell = new BlockPos(1, 1, 1);
        BlockPos junctionRel = new BlockPos(2, 1, 1);
        BlockPos stubRel = new BlockPos(2, 1, 2);
        BlockPos midCell = new BlockPos(3, 1, 1);
        BlockPos pumpRel = new BlockPos(4, 1, 1);
        BlockPos deliveryCell = new BlockPos(5, 1, 1);
        BlockPos sinkRel = new BlockPos(6, 1, 1);

        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            int cap = PipeStore.capacityMb();
            int rate = Math.max(1, Math.min(cap / 12,
                    PipesNPhysicsConfig.MAX_FLOW_PER_ENDPOINT.get() / 4));
            int depth = FlowNetwork.flowDepthMb(rate, cap);
            if (cap <= 0 || depth <= rate || depth >= cap) {
                helper.succeed(); // wire mode / degenerate capacity: no partial depth to observe
                return;
            }
            // Plenty above the source's draw lip: this test measures the DEPTH gates, and the
            // 100-iteration drain must never dip into the lip cap's taper zone near the aperture.
            fill(helper, sourceRel, 6000);

            Graph graph = GraphBuilder.build(level, helper.absolutePos(feederCell));
            Node source = graph.nodeAt(helper.absolutePos(sourceRel));
            Node junction = graph.nodeAt(helper.absolutePos(junctionRel));
            Node pump = graph.nodeAt(helper.absolutePos(pumpRel));
            Node sink = graph.nodeAt(helper.absolutePos(sinkRel));
            if (source == null || junction == null || pump == null || sink == null) {
                helper.fail("rig did not resolve to tank—junction—pump—tank nodes");
                return;
            }
            List<EdgeFlow> flows = new ArrayList<>();
            double[] passFlow = new double[graph.edges().size()];
            for (Edge e : graph.edges()) {
                int upstream = edgeJoins(e, source.index(), junction.index()) ? source.index()
                        : edgeJoins(e, junction.index(), pump.index()) ? junction.index()
                        : edgeJoins(e, pump.index(), sink.index()) ? pump.index() : -1;
                if (upstream < 0) {
                    flows.add(EdgeFlow.none(e.index())); // the stub edge stays idle
                    continue;
                }
                boolean aToB = e.a() == upstream;
                flows.add(new EdgeFlow(e.index(),
                        aToB ? EdgeFlow.Direction.A_TO_B : EdgeFlow.Direction.B_TO_A, rate));
                passFlow[e.index()] = aToB ? rate : -rate;
            }
            Solution trickle = new Solution(flows, List.of(),
                    List.of(new Solution.FlowPass(new FluidStack(Fluids.WATER, 1), passFlow)),
                    new int[graph.edges().size()],
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), true);

            // Drive the executor tick by tick, all inside THIS server tick. The tail cell is
            // read BEFORE each iteration: it is exactly what deliverFromTail gated on.
            BlockPos tailAbs = helper.absolutePos(deliveryCell);
            int firstDeliveryTail = -1;
            int sinkAtSeventy = -1;
            for (int i = 0; i < 100; i++) {
                if (i == 70) sinkAtSeventy = amount(helper, sinkRel);
                int tailBefore = cellMb(level, tailAbs);
                int sinkBefore = amount(helper, sinkRel);
                PipeFlowExecutor.run(level, graph, trickle);
                if (firstDeliveryTail < 0 && amount(helper, sinkRel) > sinkBefore) {
                    firstDeliveryTail = tailBefore;
                }
            }

            if (firstDeliveryTail < 0) {
                helper.fail("the trickle never delivered to the sink");
                return;
            }
            if (firstDeliveryTail < depth) {
                helper.fail("sink received fluid while the tail cell held " + firstDeliveryTail
                        + "/" + depth + " mB — delivery outran the flow-depth column");
                return;
            }
            int windowGain = amount(helper, sinkRel) - sinkAtSeventy;
            if (windowGain < 28 * rate || windowGain > 30 * rate) {
                helper.fail("steady delivery moved " + windowGain + " mB over 30 ticks at rate "
                        + rate + " — the depth gates throttled or burst the throughput");
                return;
            }
            // The FEEDER cell sits below the source's waterline, so the flowing top-up
            // legitimately fills it toward the tank's rendered line (the depth is a FLOOR gate,
            // never a target) and fill-only keeps the high-water mark from the starting fill.
            // Every cell past the junction has no reservoir line and rides at the depth itself.
            double sourceLine = tankRenderedSurface(1.0, 1, 6000, 8000);
            int feederTarget = (int) Math.round(
                    Math.clamp((sourceLine - (1.0 + 0.5 - 3.0 / 16)) / (2 * (3.0 / 16)), 0, 1) * cap);
            int held = 0;
            for (BlockPos rel : List.of(feederCell, junctionRel, midCell, deliveryCell)) {
                int mb = pipeAmount(helper, rel); // the junction slot is a cell like any other here
                held += mb;
                int bound = rel.equals(feederCell) ? Math.max(depth, feederTarget) : depth;
                if (mb < depth - rate || mb > bound + rate) {
                    helper.fail("flowing cell " + rel.toShortString() + " holds " + mb
                            + " mB in steady state, expected the flow depth " + depth
                            + " (bound " + bound
                            + ") — the run packed toward full or smeared below the plug");
                    return;
                }
            }
            int total = amount(helper, sourceRel) + amount(helper, sinkRel) + held
                    + pipeAmount(helper, stubRel);
            if (total != 6000) {
                helper.fail("fluid not conserved through depth-gated flow: " + total + "/6000");
                return;
            }
            helper.succeed();
        });
    }

    /** Whether the edge joins exactly the two given nodes (either orientation). */
    private static boolean edgeJoins(Edge e, int nodeA, int nodeB) {
        return (e.a() == nodeA && e.b() == nodeB) || (e.a() == nodeB && e.b() == nodeA);
    }

    /**
     * The pipe's stored fluid is REAL volume, so it must survive the world save: the disk path
     * writes the content key, and reading the saved tag back yields the same stack — a reload
     * resumes with the exact in-transit fluid. The cosmetic flow stamp (direction/rate) is
     * re-derived every tick and must NOT be saved.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "levelRender")
    public static void contentPersistsToSaveButFlowStampDoesNot(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 1, 1), 8000);
        helper.succeedWhen(() -> {
            Level level = helper.getLevel();
            BlockPos wet = null;
            for (BlockPos rel : new BlockPos[] {new BlockPos(1, 1, 1), new BlockPos(3, 1, 1)}) {
                if (cellMb(level, helper.absolutePos(rel)) > 0) { wet = helper.absolutePos(rel); break; }
            }
            if (wet == null) { helper.fail("no pipe cell holds fluid yet"); return; }

            var be = level.getBlockEntity(wet);
            if (be == null) { helper.fail("no BE at wet cell"); return; }
            CompoundTag saved = be.saveWithoutMetadata(level.registryAccess());
            if (!pipesnphysics$containsKey(saved, "PnpContent")) {
                helper.fail("stored pipe fluid was NOT written to the world save — it would vanish on reload");
            }
            if (pipesnphysics$containsKey(saved, "PnpFlow")) {
                helper.fail("the cosmetic flow stamp was written to the world save");
            }
        });
    }

    /** Whether a serialized-BE NBT tree contains {@code key} anywhere. */
    private static boolean pipesnphysics$containsKey(net.minecraft.nbt.Tag tag, String key) {
        if (tag instanceof CompoundTag c) {
            if (c.contains(key)) return true;
            for (String k : c.getAllKeys()) if (pipesnphysics$containsKey(c.get(k), key)) return true;
        } else if (tag instanceof net.minecraft.nbt.CollectionTag<?> list) {
            for (net.minecraft.nbt.Tag t : list) if (pipesnphysics$containsKey(t, key)) return true;
        }
        return false;
    }

    /**
     * Fluid travels down a pipe as a front, NOT a pop-fill: the number of cells holding fluid
     * GROWS over ticks while a long run primes — the front is the real stored volume advancing.
     * End-to-end with a real pump pushing water down the long discharge run.
     */
    @GameTest(template = "common/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void fluidFrontAdvancesOverTime(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            BlockPos pump = new BlockPos(12, 1, 0); // piping/charging_max_range: pump pinned from NBT (FACING re-read below)
            List<BlockPos> tanks = new ArrayList<>(List.of(new BlockPos(0, 1, 0), new BlockPos(14, 1, 0)));
            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos pumpPos = pump;
            Direction facing = helper.getBlockState(pumpPos).getValue(PumpBlock.FACING);
            BlockPos suction = facing == Direction.WEST ? tanks.get(1) : tanks.get(0);
            drain(helper, tanks.get(0));
            drain(helper, tanks.get(1));
            fillFluid(helper, suction, Fluids.WATER, 8000);

            int[] early = {-1};
            helper.runAfterDelay(8, () -> early[0] = pipesnphysics$countChargedPipes(helper));
            helper.runAfterDelay(160, () -> {
                int late = pipesnphysics$countChargedPipes(helper);
                if (late < 1) {
                    helper.fail("no pipe ever charged — front never formed" + dump(helper, pumpPos));
                    return;
                }
                if (late <= early[0]) {
                    helper.fail("front did not advance over time (instant fill?): early="
                            + early[0] + " late=" + late + dump(helper, pumpPos));
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** Count pipe cells that hold stored fluid. */
    private static int pipesnphysics$countChargedPipes(GameTestHelper helper) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 4; z++) {
                    if (cellMb(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z))) > 0) count++;
                }
            }
        }
        return count;
    }

    /**
     * DIAGNOSTIC: after two tanks equalize AND the network has settled (slept),
     * the connecting pipe must still render fluid — not revert to empty. Probes the
     * solve state to report WHY if it reverted.
     */
    /**
     * A raised tank draining into a lower one: the upper tank must empty COMPLETELY, and every
     * drop is accounted for — what is not yet in the lower tank still resides in the pipes
     * (settling down over time), never voided. The recede is gradual; this guards the end state
     * and conservation, the feel is visual.
     */
    @GameTest(template = "common/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 1000)
    public static void drainedPipeRecedesNotStuck(GameTestHelper helper) {
        BlockPos top = new BlockPos(0, 4, 0);
        fill(helper, top, 8000);

        helper.succeedWhen(() -> {
            if (amount(helper, top) != 0) {
                helper.fail("upper tank has not drained yet: " + amount(helper, top));
                return;
            }
            int pipes = pipesnphysics$areaPipeContent(helper, 4, 6, 4);
            BlockPos lower = null;
            for (int x = 0; x < 4 && lower == null; x++)
                for (int y = 0; y < 6 && lower == null; y++)
                    for (int z = 0; z < 4 && lower == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (!rel.equals(top) && helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) {
                            lower = rel;
                        }
                    }
            int lowerMb = lower == null ? 0 : amount(helper, lower);
            if (lowerMb + pipes != 8000) {
                helper.fail("fluid lost while draining down: lower=" + lowerMb + " pipes=" + pipes);
            }
        });
    }

    /**
     * BREAK-SPILL: a broken pipe cell's stored fluid is pushed back into the network (adjacent
     * cells and tanks with room), not voided — tearing down a wet line gives the fluid back.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void brokenPipeSpillsContentBackIntoNetwork(GameTestHelper helper) {
        BlockPos tank = new BlockPos(0, 1, 1);
        BlockPos cell = new BlockPos(1, 1, 1);
        fill(helper, tank, 4000);
        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            int capacity = PipeStore.capacityMb();
            PipeStore.Store store = PipeStore.at(level, helper.absolutePos(cell));
            if (store == null) { helper.fail("no pipe store at the pull cell"); return; }
            store.extract(capacity);
            store.insert(new FluidStack(Fluids.WATER, capacity), capacity);
            store.flush();
            int before = amount(helper, tank) + capacity;

            FluidStack content = store.fluid().copy();
            helper.setBlock(cell, Blocks.AIR.defaultBlockState());
            NetworkEditHandler.spillBrokenPipe((ServerLevel) level, helper.absolutePos(cell), content);

            int after = amount(helper, tank);
            if (after != before) {
                helper.fail("broken pipe voided its content: tank holds " + after
                        + " mB, expected " + before);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The live-report rig gas hydrostatics shipped for: gas STRANDED mid-riser between an empty
     * bottom tank and a gas-holding top tank must RISE and finish in the TOP tank — the solve is
     * correctly idle (the top tank's gas is already where buoyancy wants it; the empty bottom
     * tank cannot give), so only the mirrored settle can move it, and before the mirror it froze
     * in place forever (cells 250/0/250, "why does the gas here not flow up?"). Nothing may leak
     * into the bottom tank (the empty-reservoir pour gate: gas never pours DOWN into an empty
     * vessel). Skips when no lighter-than-air fluid is registered.
     */
    @GameTest(template = "gas/stranded_riser", templateNamespace = PipesNPhysics.ID, timeoutTicks = 160)
    public static void strandedGasRisesIntoTheTankAbove(GameTestHelper helper) {
        Fluid gas = lighterThanAirFluid();
        if (gas == null) {
            helper.succeed();
            return;
        }
        // stranded_riser: bottom tank, two vertical pipe cells, top tank — one column at x0
        BlockPos bottomTank = new BlockPos(0, 1, 0);
        BlockPos lowCell = new BlockPos(0, 2, 0);
        BlockPos highCell = new BlockPos(0, 3, 0);
        BlockPos topTank = new BlockPos(0, 4, 0);

        helper.runAfterDelay(10, () -> {
            handler(helper, topTank).fill(new FluidStack(gas, 1000), IFluidHandler.FluidAction.EXECUTE);
            for (BlockPos rel : List.of(lowCell, highCell)) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(gas, 200), 200);
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(lowCell));
        });
        helper.runAfterDelay(140, () -> {
            int top = amount(helper, topTank);
            int bottom = amount(helper, bottomTank);
            int inPipes = pipeAmount(helper, lowCell) + pipeAmount(helper, highCell);
            if (bottom > 0) {
                helper.fail("gas leaked DOWN into the empty bottom tank (" + bottom + " mB)");
                return;
            }
            if (top != 1400 || inPipes != 0) {
                helper.fail("stranded gas did not rise into the top tank (top " + top
                        + "/1400, pipes " + inPipes + "/0)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Gas EQUALIZES between vessels topping out LEVEL, whatever their heights: a 3-tall and a
     * 4-tall tank with aligned ceilings, joined at the top row, must converge to equal fills
     * (equal gas interfaces). The solve's gas head anchors at the column TOP (minus the
     * interface — the true mirror of the liquid surface); the old BASE anchor gave the taller
     * tank a full block of phantom priority, so the solve pushed gas toward the SHORTER tank
     * while the settle's interface math poured it back — a limit cycle the /pipegraph recent
     * strip showed as {@code ←20 ·49 ←20 …} ("the gas does not really equalize" report).
     * Skips when no lighter-than-air fluid is registered.
     */
    @GameTest(template = "gas/equal_top_tanks", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void gasEqualizesBetweenEqualTopTanks(GameTestHelper helper) {
        Fluid gas = lighterThanAirFluid();
        if (gas == null) {
            helper.succeed();
            return;
        }
        // equal_top_tanks: 3-tall tank at x0 (y 2..4) and 4-tall at x3 (y 1..4), same
        // ceiling, joined by the top-row 2-cell run
        BlockPos shortTank = new BlockPos(0, 4, 0);
        BlockPos tallTank = new BlockPos(3, 4, 0);
        List<BlockPos> run = List.of(new BlockPos(1, 4, 0), new BlockPos(2, 4, 0));

        helper.runAfterDelay(10, () -> {
            handler(helper, shortTank).fill(new FluidStack(gas, 3900), IFluidHandler.FluidAction.EXECUTE);
            handler(helper, tallTank).fill(new FluidStack(gas, 8), IFluidHandler.FluidAction.EXECUTE);
            if (amount(helper, shortTank) < 3900) {
                helper.fail("short tank did not assemble 3-tall (holds " + amount(helper, shortTank) + ")");
                return;
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(0)));
        });
        helper.runAfterDelay(380, () -> {
            int shortHeld = amount(helper, shortTank);
            int tallHeld = amount(helper, tallTank);
            int inPipes = 0;
            for (BlockPos rel : run) inPipes += pipeAmount(helper, rel);
            if (tallHeld < 1200 || shortHeld > 2700) {
                helper.fail("gas did not equalize toward equal interfaces (short " + shortHeld
                        + ", tall " + tallHeld + ", pipes " + inPipes
                        + ") — the base-anchored head churned it toward the short tank");
                return;
            }
            if (shortHeld + tallHeld + inPipes != 3908) {
                helper.fail("gas not conserved: " + shortHeld + " + " + tallHeld + " + " + inPipes
                        + " != 3908");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A FLOWING gas run packs toward the interface profile WHILE it flows — the mirror of the
     * liquid flowing top-up ({@code submergedFlowingRunTopsUpFromTheSourceSide}): the brigade's
     * plug rules alone leave flowing cells riding at flow depth, so mid-equalization the pipe's
     * hanging gas visibly missed the tank's interface until the flow stopped ("the gas heights
     * inside the pipe and the tank don't match"). Two 4-tall tanks equalize a large gas
     * imbalance; sampled MID-FLOW, the run's cells must already hang near the interface profile
     * (~200 mB), far above the plug depth (~64) the bail left them at. Skips without a gas.
     */
    @GameTest(template = "gas/tall_tank_pair", templateNamespace = PipesNPhysics.ID, timeoutTicks = 160)
    public static void flowingGasRunPacksTowardTheInterface(GameTestHelper helper) {
        Fluid gas = lighterThanAirFluid();
        if (gas == null) {
            helper.succeed();
            return;
        }
        // tall_tank_pair: two 4-tall tanks at x0/x3 joined by the top-row 2-cell run
        BlockPos tankA = new BlockPos(0, 1, 0);
        BlockPos tankB = new BlockPos(3, 1, 0);
        List<BlockPos> run = List.of(new BlockPos(1, 4, 0), new BlockPos(2, 4, 0));

        helper.runAfterDelay(10, () -> {
            handler(helper, tankA).fill(new FluidStack(gas, 8000), IFluidHandler.FluidAction.EXECUTE);
            handler(helper, tankB).fill(new FluidStack(gas, 2000), IFluidHandler.FluidAction.EXECUTE);
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(0)));
        });
        // t=70 is mid-equalization (the imbalance decays with τ≈100 ticks): flow is still
        // running, and the interface profile expects ~200 mB per cell vs the ~64 plug depth.
        helper.runAfterDelay(70, () -> {
            int a = amount(helper, tankA);
            int b = amount(helper, tankB);
            if (Math.abs(a - b) < 800) {
                helper.fail("rig equalized before the mid-flow sample (a " + a + ", b " + b
                        + ") — widen the imbalance");
                return;
            }
            for (BlockPos rel : run) {
                int held = pipeAmount(helper, rel);
                if (held < 150) {
                    helper.fail("flowing gas cell " + rel.toShortString() + " holds " + held
                            + " mB — riding at plug depth instead of packing toward the interface");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * A MANIFOLD must serve every branch, not just whichever ticks first. One junction slot holds a
     * single cell, and a run's flow depth clamps to a FULL cell at any rate past a quarter cell per
     * tick — so two branches off one junction each demand the whole slot. Reading the slot's LIVE
     * level, the first consumer dropped it below depth and every sibling's gate failed; consumer
     * order is stable, so the same branch won every tick and the other stayed bone dry forever
     * while the trunk ran at a fraction of its solved rate (the reported "flow to edge E is 0",
     * whose dump showed the starved branch's junction-end cell at 0 and its own tail stranded
     * below depth). {@link BrigadePass#snapshotSlots} gates on ARRIVAL instead.
     *
     * Mutation check: gate on {@code slot.amount()} again and one sink here finishes at 0.
     *
     * The DIVISION past the open gate is first-come, and that is fine: a run's demand is bounded by
     * its head cell's room ({@code FlowingRun.intake}), so a branch that just took the slot must
     * deliver downstream before it can ask again and its sibling gets the turn. The manifold
     * round-robins on its own — hence the tight balance asserted below, which a proportional share
     * was built for and could not improve on.
     */
    @GameTest(template = "physics/manifold_split", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void manifoldSlotServesEveryBranch(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos junction = new BlockPos(3, 1, 1);
        BlockPos northSink = new BlockPos(5, 1, 0);
        BlockPos southSink = new BlockPos(5, 1, 2);
        fill(helper, source, 8000);

        helper.runAfterDelay(120, () -> {
            int north = amount(helper, northSink);
            int south = amount(helper, southSink);
            String graph = dump(helper, junction);

            if (north <= 0 || south <= 0) {
                helper.fail("the junction starved a branch: north " + north + ", south " + south
                        + " — one slot must serve every consumer that pulls on it" + graph);
                return;
            }
            // Equal-length, equal-elevation branches solve at the same rate, so their SHARES of the
            // slot are equal and the sinks must track each other — not merely both be non-zero.
            if (Math.abs(north - south) * 10 > Math.max(north, south)) {
                helper.fail("branches served unevenly: north " + north + ", south " + south
                        + " — equal solved rates must take equal shares of the slot" + graph);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Branches of DIFFERENT length off one junction split by their fittings as well as their
     * length. A run's resistance is {@code length + PIPE_FITTING_LENGTH} — the tee it branches
     * off, its elbows, and its two ends counted as the straight pipe they cost — so a 2-cell
     * branch beside a 6-cell one takes 11/7 of it, not the 7/3 that raw length alone would say.
     *
     * That is the physical answer, not a fairness knob: real pipe flow is turbulent, its loss goes
     * as the SQUARE of the rate, and parallel branches sharing one junction pressure then divide
     * as 1/sqrt(length) — 6-against-2 splits 63/37 in reality, where a linear 1/length model says
     * 70/30 and drifts further apart the more the lengths differ. The engine's loss law has to
     * stay linear (that is what keeps one implicit Euler solve per tick), so charging the fittings
     * is how the turbulent ratio is reproduced; it tracks it within a couple of points over the
     * whole practical range of runs.
     *
     * Rig: a CREATIVE supply tank (an infinite reservoir at a fixed surface) pumped into a
     * junction that feeds a 2-cell and a 6-cell branch. Both sinks are emptied every tick, so
     * neither builds a head of its own — otherwise the branch that wins fills its tank first,
     * backs its own flow off, and the measured split evens out on its way to being wrong. What
     * is measured is what each sink actually RECEIVES over a window after both branches prime.
     *
     * Mutation check: divide by {@code edge.length() + 1} again and the split reads ~2.33.
     */
    // Own batch: pins PIPE_FITTING_LENGTH across the whole run, and batches are the only
    // isolation gametests have (see manifoldSlotServesAllFeeders).
    @GameTest(template = "physics/manifold_uneven", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300, batch = "pinnedConfig")
    public static void unevenBranchesSplitByFittingsNotLengthAlone(GameTestHelper helper) {
        BlockPos shortSink = new BlockPos(5, 1, 0);
        BlockPos longSink = new BlockPos(9, 1, 2);
        int shortCells = 2;
        int longCells = 6;
        // The long branch holds 6 cells of pipe to prime at roughly 25 mB/t; measure well past that.
        int windowStart = 100;
        int windowEnd = 180;

        double priorFittings = PipesNPhysicsConfig.PIPE_FITTING_LENGTH.get();
        PipesNPhysicsConfig.PIPE_FITTING_LENGTH.set(5.0);
        double fittings = PipesNPhysicsConfig.PIPE_FITTING_LENGTH.get();

        int[] received = new int[2];
        for (int tick = 1; tick <= windowEnd; tick++) {
            boolean counts = tick > windowStart;
            helper.runAtTickTime(tick, () -> {
                if (counts) {
                    received[0] += amount(helper, shortSink);
                    received[1] += amount(helper, longSink);
                }
                drain(helper, shortSink);
                drain(helper, longSink);
            });
        }

        helper.runAtTickTime(windowEnd + 1, () -> {
            PipesNPhysicsConfig.PIPE_FITTING_LENGTH.set(priorFittings);
            if (received[0] < 500 || received[1] < 500) {
                helper.fail("a branch barely ran: short " + received[0] + " mB, long "
                        + received[1] + " mB over " + (windowEnd - windowStart) + " ticks"
                        + dump(helper, new BlockPos(3, 1, 1)));
                return;
            }
            double predicted = (longCells + fittings) / (shortCells + fittings);
            double measured = received[0] / (double) received[1];
            if (Math.abs(measured - predicted) > 0.2 * predicted) {
                helper.fail(String.format(
                        "uneven branches split %.2f:1 (short %d mB, long %d mB) — their"
                        + " conductances say %.2f:1 at %.0f blocks of fittings; raw length alone"
                        + " would say %.2f:1", measured, received[0], received[1], predicted,
                        fittings, (longCells + 1) / (double) (shortCells + 1))
                        + dump(helper, new BlockPos(3, 1, 1)));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A junction the solve never reached must not SEAL fluid into the runs beside it. A network
     * with nothing participating (every endpoint stopped, or none at all) gets no node heads, and
     * {@code SettlePass.settleSlot} used to bail on the missing head — while a settling run only
     * ever moves within its own cells and its END reservoirs and never pushes into a slot. So a
     * stub resting against a headless junction had no path out in either direction, and its
     * content stayed there for good.
     *
     * Reported on a TFMG engine rig: the exhaust manifold's CO2 sat in the two stubs either side
     * of an empty junction ("can't move to the exhaust because the flagged pipe is empty") while
     * the starved engines produced none, so no CO2 pass ran to give that junction a head. Tested
     * with WATER on a bare capped T — no reservoir anywhere, so every node is headless — because
     * the wall is the missing head, not the fluid: the level arms must equalize through the
     * junction like communicating vessels.
     */
    @GameTest(template = "physics/collision_u_below", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 200, batch = "headlessSlot")
    public static void headlessJunctionDoesNotSealTheRunsBesideIt(GameTestHelper helper) {
        Block pipe = AllBlocks.FLUID_PIPE.get();
        BlockPos centre = new BlockPos(3, 1, 1);
        BlockPos loaded = new BlockPos(2, 1, 1);   // the stub cell holding the water
        BlockPos across = new BlockPos(4, 1, 1);   // the far arm, past the junction
        // A capped T: every end closed, so no mouth can anchor a head and nothing participates.
        // The caps must be SOLID BLOCKS — Create straightens a pipe left with one connection, so
        // a lone end pipe opens its far face and spills as a mouth (which would also anchor a head
        // and defeat the point of the rig).
        helper.setBlock(new BlockPos(0, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(6, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, 1), pipeState(pipe, Direction.EAST));
        helper.setBlock(loaded, pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(centre, pipeState(pipe, Direction.WEST, Direction.EAST, Direction.SOUTH));
        helper.setBlock(across, pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(5, 1, 1), pipeState(pipe, Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 2), pipeState(pipe, Direction.NORTH, Direction.SOUTH));
        helper.setBlock(new BlockPos(3, 1, 3), pipeState(pipe, Direction.NORTH));

        int filled = PipeStore.capacityMb();
        helper.runAfterDelay(5, () -> {
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(loaded));
            if (cell == null) {
                helper.fail("no pipe store at " + loaded.toShortString());
                return;
            }
            cell.insert(new FluidStack(Fluids.WATER, filled), filled);
            cell.flush();
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(centre));
        });
        helper.runAfterDelay(180, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(centre));
            Node node = graph.nodeAt(helper.absolutePos(centre));
            if (node == null || !node.isJunction()) {
                helper.fail("the centre of the T is not a junction node — the rig never built");
                return;
            }
            int held = storedAt(helper, loaded);
            int slot = storedAt(helper, centre);
            int far = storedAt(helper, across);
            if (slot + far <= 0) {
                helper.fail("the headless junction sealed the water in: stub still holds " + held
                        + " mB, junction " + slot + " mB, far arm " + far + " mB");
                return;
            }
            int total = held + slot + far + storedAt(helper, new BlockPos(1, 1, 1))
                    + storedAt(helper, new BlockPos(5, 1, 1))
                    + storedAt(helper, new BlockPos(3, 1, 2))
                    + storedAt(helper, new BlockPos(3, 1, 3));
            if (total != filled) {
                helper.fail("settling across the headless junction lost fluid: " + total
                        + " mB of " + filled + dump(helper, centre));
                return;
            }
            helper.succeed();
        });
    }

    /** The mB actually stored in one pipe cell (a junction node's slot reads the same way). */
    private static int storedAt(GameTestHelper helper, BlockPos rel) {
        PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
        return cell == null ? 0 : cell.amount();
    }
}
