package de.devin.pipesnphysics;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import de.devin.pipesnphysics.client.PipeStatusText;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import de.devin.pipesnphysics.engine.Edge;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Graph;
import de.devin.pipesnphysics.engine.GraphBuilder;
import de.devin.pipesnphysics.engine.OpenEndPipes;
import de.devin.pipesnphysics.engine.PipeProbe;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.ValveThrottle;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end engine tests on real Create blocks.
 * Run with: ./gradlew runGameTestServer
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class PipesNPhysicsGameTests {
    /**
     * Two identical tanks joined by a U-shaped pipe under them (communicating
     * vessels). One starts full; both must converge to equal fill at gameplay
     * speed, conserving fluid throughout.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void tanksEqualizeAtEqualSurfaces(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        fill(helper, left, 8000);

        helper.succeedWhen(() -> {
            int a = amount(helper, left);
            int b = amount(helper, right);
            if (a + b != 8000) helper.fail("fluid not conserved: " + a + " + " + b);
            if (Math.abs(a - b) > 800) helper.fail("not equalized yet: " + a + " vs " + b);
        });
    }

    /**
     * Tank → pipe → powered pump → pipe → tank on flat ground. The pump must move
     * everything: the source drains to exactly 0 mB even though its connection sits
     * at base level (regression: the lip gate used to strand the last 80 mB).
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void pumpMovesAllFluidOnFlatGround(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        fill(helper, source, 8000);

        helper.succeedWhen(() -> {
            int left = amount(helper, source);
            int moved = amount(helper, sink);
            if (left + moved != 8000) helper.fail("fluid not conserved: " + left + " + " + moved);
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void runningPumpArmsTheFastRecheck(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up
            BlockPos seed = null;
            for (int x = 0; x < 6 && seed == null; x++)
                for (int y = 0; y < 4 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 160)
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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

    private static int basinFluid(GameTestHelper helper, BlockPos relativePos, Fluid fluid) {
        IFluidHandler h = handler(helper, relativePos);
        int sum = 0;
        for (int i = 0; i < h.getTanks(); i++) {
            FluidStack f = h.getFluidInTank(i);
            if (f.getFluid() == fluid) sum += f.getAmount();
        }
        return sum;
    }

    /**
     * The goggle's flow number must be the fluid ACTUALLY moved, not the solver's hydraulic
     * flow (which the lip / max-flow caps throttle below). {@code actualEdgeFlow} reads it off
     * the executed transfers via the edge's cut: a transfer of 37 mB across a bridge edge whose
     * hydraulic flow is 200 must report 37, so a near-empty source no longer reads a brisk flow
     * while only a trickle leaves the tank.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void gogglePipeRateReflectsActualTransfer(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            BlockPos seed = null;
            for (int x = 0; x < 6 && seed == null; x++)
                for (int y = 0; y < 4 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

            Graph g = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            Edge edge = null;
            for (Edge e : g.edges()) {
                if (!e.pipes().isEmpty()) { edge = e; break; }
            }
            if (edge == null) { helper.fail("no edge with pipes"); return; }
            var a = g.node(edge.a());
            var b = g.node(edge.b());

            List<EdgeFlow> flows = new ArrayList<>();
            for (Edge e : g.edges()) {
                flows.add(e.index() == edge.index()
                        ? new EdgeFlow(edge.index(), EdgeFlow.Direction.A_TO_B, 200)
                        : EdgeFlow.none(e.index()));
            }
            List<Solution.Transfer> transfers = List.of(
                    new Solution.Transfer(a.pos(), b.pos(), new FluidStack(Fluids.WATER, 37)));
            Solution sol = new Solution(flows, transfers, Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), true);

            int actual = PipeProbe.actualEdgeFlow(g, sol, edge);
            if (actual != 37) {
                helper.fail("actualEdgeFlow=" + actual + " — expected the transferred 37 mB, "
                        + "not the hydraulic 200");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The fine-grained valve throttle (a 0-90 degree scroll value) must scale a run's solved
     * flow: fully open at 90 degrees passes the full hydraulic flow, halving the angle roughly
     * halves it, and 0 degrees shuts the run (blocked, {@code Reason.VALVE}) exactly as the shaft
     * would. A valve is inserted into the bottom of a communicating-vessels U — no pump, so
     * conductance (not a pump cap) sets the rate — and the solved edge flow is read at each angle.
     * The shaft state is forced open and every solve happens in the SAME tick, before the
     * unpowered valve would chase {@code ENABLED} back to closed.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveThrottleScalesFlow(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();

            // The bottom of the U is a straight pipe cell connected only along X — host the valve there.
            BlockPos valveRel = null;
            BlockPos seedRel = null;
            for (int x = 0; x < 6 && valveRel == null; x++)
                for (int y = 0; y < 6 && valveRel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (!pipeAt(helper, rel)) continue;
                        if (seedRel == null) seedRel = rel;
                        if (pipeAt(helper, rel.west()) && pipeAt(helper, rel.east())
                                && !pipeAt(helper, rel.above()) && !pipeAt(helper, rel.below())
                                && !pipeAt(helper, rel.north()) && !pipeAt(helper, rel.south())) {
                            valveRel = rel;
                            break;
                        }
                    }
            if (valveRel == null) { helper.fail("no straight X pipe cell to host a valve"); return; }

            // Orient the valve so its pipe axis is X (matching the run) and force the shaft open.
            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != Direction.Axis.X) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            fill(helper, new BlockPos(0, 3, 0), 8000); // a head gradient across the valve

            BlockPos valveAbs = helper.absolutePos(valveRel);
            Graph g = GraphBuilder.build(level, helper.absolutePos(seedRel));
            Edge edge = null;
            for (Edge e : g.edges()) {
                if (e.pipes().contains(valveAbs)) { edge = e; break; }
            }
            if (edge == null) { helper.fail("valve cell landed on no edge"); return; }

            int full = valveFlow(level, g, edge, valveAbs, 90);
            int half = valveFlow(level, g, edge, valveAbs, 45);
            int fifth = valveFlow(level, g, edge, valveAbs, 18);
            if (full <= 0) { helper.fail("a fully open valve passed no flow (" + full + ")"); return; }
            if (!(full > half && half > fifth && fifth > 0)) {
                helper.fail("throttle did not scale flow monotonically: 90=" + full
                        + " 45=" + half + " 18=" + fifth);
                return;
            }
            // The two tanks contract to a 2-node system with capacitance >> conductance, so the
            // solved flow is near-linear in the angle — assert proportionality, not just monotonicity,
            // to catch a non-linear (sqrt/square/clamped) angle->opening mapping.
            if (half < 0.38 * full || half > 0.62 * full) {
                helper.fail("45 degrees should pass ~half: 90=" + full + " 45=" + half);
                return;
            }
            if (fifth < 0.10 * full || fifth > 0.32 * full) {
                helper.fail("18 degrees should pass ~a fifth: 90=" + full + " 18=" + fifth);
                return;
            }

            setThrottle(level, valveAbs, 0);
            Solution shut = FlowSolver.solve(level, g);
            if (!shut.blockedEdges().contains(edge.index())
                    || shut.edgeReasons().get(edge.index()) != Solution.Reason.VALVE) {
                helper.fail("a 0 degree valve did not shut its run with Reason.VALVE");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Regression for "the throttle does nothing on a pumped line": the angle must scale the FINAL
     * conductance, AFTER the pump's internal-conductance cap — otherwise the tiny pump cap masks it
     * and flow stays constant until the valve is nearly shut. Inserts a valve on the running pump's
     * push side and asserts the solved flow drops materially from 90° to 45° to 18°. (Before the fix
     * the three solves tied, because {@code min(edgeG·throttle, pumpInternalG)} pinned at the cap.)
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveThrottleScalesPumpedFlow(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            Level level = helper.getLevel();
            BlockPos pumpRel = null;
            for (int x = 0; x < 6 && pumpRel == null; x++)
                for (int y = 0; y < 4 && pumpRel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        if (helper.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof PumpBlock) {
                            pumpRel = new BlockPos(x, y, z);
                            break;
                        }
                    }
            if (pumpRel == null) { helper.fail("no pump in template"); return; }
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos valveRel = pumpRel.relative(push);
            if (!pipeAt(helper, valveRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            fill(helper, new BlockPos(0, 1, 1), 8000); // source full
            drain(helper, new BlockPos(4, 1, 1));      // sink empty -> the pump wants to move fluid

            BlockPos valveAbs = helper.absolutePos(valveRel);
            Graph g = GraphBuilder.build(level, valveAbs);
            Edge edge = null;
            for (Edge e : g.edges()) {
                if (e.pipes().contains(valveAbs)) { edge = e; break; }
            }
            if (edge == null) { helper.fail("valve cell landed on no edge"); return; }

            int full = valveFlow(level, g, edge, valveAbs, 90);
            int half = valveFlow(level, g, edge, valveAbs, 45);
            int fifth = valveFlow(level, g, edge, valveAbs, 18);
            if (full <= 0) { helper.fail("the pump moved no fluid through a fully open valve (" + full + ")"); return; }
            // The throttle must bite on the pumped run, not stay pinned at the pump cap.
            if (!(half < 0.8 * full && fifth < 0.8 * half && fifth > 0)) {
                helper.fail("throttle did not scale the PUMPED flow: 90=" + full
                        + " 45=" + half + " 18=" + fifth + " (it should drop materially each step)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The held-head foundation: a fully-shut valve mid-run becomes a CLOSED_GATE node that the
     * solver treats as a WALL — the run SPLITS there into two edges. A pump feeding the gate
     * HOLDS its pressurized column up to it (the feed edge is flagged held; the head doesn't
     * reset), NO flow crosses, and the far side is free to settle. Generalizes "the head doesn't
     * reset when blocked" to a mid-run valve (the worked example). Build the graph AFTER shutting,
     * since the split is a topology decision made at graph-build time (as it is in-game per tick).
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void closedValveSplitsRunAndHoldsFeed(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            var level = helper.getLevel();
            BlockPos pumpRel = null;
            for (int x = 0; x < 6 && pumpRel == null; x++)
                for (int y = 0; y < 4 && pumpRel == null; y++)
                    for (int z = 0; z < 4; z++)
                        if (helper.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof PumpBlock) {
                            pumpRel = new BlockPos(x, y, z);
                            break;
                        }
            if (pumpRel == null) { helper.fail("no pump in template"); return; }
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos valveRel = pumpRel.relative(push);
            if (!pipeAt(helper, valveRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            fill(helper, new BlockPos(0, 1, 1), 8000); // source full
            drain(helper, new BlockPos(4, 1, 1));      // sink empty -> the pump wants to deliver

            BlockPos valveAbs = helper.absolutePos(valveRel);
            setThrottle(level, valveAbs, 0);                  // SHUT, then build so the gate appears
            Graph g = GraphBuilder.build(level, valveAbs);

            var gate = g.nodeAt(valveAbs);
            if (gate == null || !gate.isClosedGate()) {
                helper.fail("shut valve did not become a CLOSED_GATE node: "
                        + (gate == null ? "null" : gate.kind()));
                return;
            }
            List<Edge> incident = g.edgesOf(gate.index());
            if (incident.size() != 2) {
                helper.fail("closed gate did not split the run into 2 edges: " + incident.size());
                return;
            }

            Solution sol = FlowSolver.solve(level, g);
            Edge feed = null;
            for (Edge e : incident) {
                if (g.node(e.a()).isPump() || g.node(e.b()).isPump()) feed = e;
                if (sol.edgeFlows().get(e.index()).mbPerTick() != 0) {
                    helper.fail("flow crossed a shut gate on edge " + e.index());
                    return;
                }
            }
            if (feed == null) { helper.fail("no pump-fed edge at the gate"); return; }
            if (!sol.heldEdges().contains(feed.index())) {
                helper.fail("the pump-fed run dead-heading a shut valve was not flagged held");
                return;
            }
            if (!sol.transfers().isEmpty()) {
                helper.fail("a transfer crossed a shut valve: " + sol.transfers().size());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A pump dead-heading a shut valve with NO SUPPLY must NOT be flagged held — it develops a
     * head but holds NO water, so rendering a column would be phantom fluid (the symptom of
     * placing a running pump where an open end used to be). Built by draining the pump's suction
     * tank while leaving water on the FAR side of the valve, so the pass still runs but the pump's
     * island has no source.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void unsuppliedPumpDeadheadingValveNotHeld(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pumpRel = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 8; x++)
                for (int y = 0; y < 4; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).getBlock() instanceof PumpBlock) pumpRel = rel;
                        else if (helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
            if (pumpRel == null || tanks.size() != 2) {
                helper.fail("scan found pump=" + pumpRel + " tanks=" + tanks.size());
                return;
            }
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos valveRel = pumpRel.relative(push); // valve on the push side, between pump and the far tank
            if (!pipeAt(helper, valveRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos suction = push == Direction.WEST ? tanks.get(1) : tanks.get(0);
            BlockPos far = push == Direction.WEST ? tanks.get(0) : tanks.get(1);
            drain(helper, suction);                       // the pump has NOTHING to pull
            fillFluid(helper, far, Fluids.WATER, 8000);    // water exists, but on the FAR side of the valve

            BlockPos valveAbs = helper.absolutePos(valveRel);
            setThrottle(level, valveAbs, 0);
            Graph g = GraphBuilder.build(level, valveAbs);
            var gate = g.nodeAt(valveAbs);
            if (gate == null || !gate.isClosedGate()) { helper.fail("valve is not a CLOSED_GATE"); return; }
            Edge feed = null;
            for (Edge e : g.edgesOf(gate.index())) {
                if (g.node(e.a()).isPump() || g.node(e.b()).isPump()) feed = e;
            }
            if (feed == null) { helper.fail("no pump-fed edge at the gate"); return; }
            Solution sol = FlowSolver.solve(level, g);
            if (sol.heldEdges().contains(feed.index())) {
                helper.fail("a pump with no supply dead-heading a shut valve was flagged held "
                        + "(would render phantom water)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The held column is legible and RESUMES: a pump pushes down a run with a valve a couple cells
     * past it; shutting the valve must report the FEED cell as "holding pressure" (DETAIL_HELD,
     * fluid present — not "dry" nor idly "settled"), and reopening must let flow resume across the
     * rejoined run. Exercises the goggle wording and the close→open round trip end to end.
     */
    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void heldValveReportsHeldAndResumes(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            var level = helper.getLevel();
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 10; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var st = helper.getBlockState(rel);
                        if (st.getBlock() instanceof PumpBlock) pump = rel;
                        else if (st.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
            Direction push = helper.getBlockState(pump).getValue(PumpBlock.FACING);
            // Walk the push-side run for two consecutive pipe cells: the first is the FEED cell
            // (between pump and valve), the second hosts the valve. Falls out if the run is shorter.
            BlockPos feedCell = pump.relative(push);
            BlockPos valveRel = pump.relative(push, 2);
            if (!pipeAt(helper, feedCell) || !pipeAt(helper, valveRel)) {
                helper.fail("need two consecutive pipes off the pump push side (feed cell + valve), got feed="
                        + pipeAt(helper, feedCell) + " valve=" + pipeAt(helper, valveRel));
                return;
            }
            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos suction = push == Direction.WEST ? tanks.get(1) : tanks.get(0);
            BlockPos discharge = push == Direction.WEST ? tanks.get(0) : tanks.get(1);
            drain(helper, suction);
            fillFluid(helper, suction, Fluids.WATER, 8000);
            drain(helper, discharge);
            fillFluid(helper, discharge, Fluids.WATER, 4000); // partial: downstream settles full, with room to resume

            BlockPos valveAbs = helper.absolutePos(valveRel);
            BlockPos feedAbs = helper.absolutePos(feedCell);
            BlockPos downstreamCell = pump.relative(push, 3); // a cell on the far side of the valve
            if (!pipeAt(helper, downstreamCell)) { helper.fail("no downstream pipe cell past the valve"); return; }
            BlockPos downstreamAbs = helper.absolutePos(downstreamCell);

            setThrottle(level, valveAbs, 0); // SHUT
            PipeStatusPayload held = PipeProbe.probe(level, feedAbs);
            if (held.statusDetail() != PipeStatusPayload.DETAIL_HELD) {
                helper.fail("feed cell before a shut valve not reported HELD: detail="
                        + held.statusDetail() + " status=" + held.status());
                return;
            }
            if (held.fluid().isEmpty()) {
                helper.fail("a held feed cell reports no fluid (goggle would call it dry)");
                return;
            }
            // The settled section PAST the valve must report its fluid, not read dry (the gate
            // endpoint has no head of its own — PipeProbe must substitute it like the renderer does).
            PipeStatusPayload downstream = PipeProbe.probe(level, downstreamAbs);
            if (downstream.fluid().isEmpty()) {
                helper.fail("a settled cell downstream of a shut valve reads dry — goggle disagrees "
                        + "with the renderer (gate-head substitution missing)");
                return;
            }

            setThrottle(level, valveAbs, 90); // REOPEN
            Graph g = GraphBuilder.build(level, feedAbs);
            // The run must actually REJOIN — the valve is a pipe cell again, not a CLOSED_GATE wall.
            var reopened = g.nodeAt(valveAbs);
            if (reopened != null && reopened.isClosedGate()) {
                helper.fail("valve still a CLOSED_GATE after reopening — the run did not rejoin");
                return;
            }
            Solution sol = FlowSolver.solve(level, g);
            boolean resumed = sol.edgeFlows().stream().anyMatch(f -> f.mbPerTick() > 0);
            if (!resumed) {
                helper.fail("flow did not resume after the valve reopened" + dump(helper, feedCell));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The DOWNSTREAM of a shut valve must stay DRY when it leads to an open end (air), not paint
     * phantom "settled" water: there is no reservoir on that side, so nothing fills it. (The bug:
     * the gate-head substitution took the OPEN END's head — its mouth, a spill threshold, not a
     * water surface — which read as a full waterline. Fixed by substituting a gate head only from a
     * real reservoir.) Builds tank → pump → valve → open-end by turning the discharge tank to air.
     */
    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void shutValveToOpenEndLeavesDownstreamDry(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 10; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var st = helper.getBlockState(rel);
                        if (st.getBlock() instanceof PumpBlock) pump = rel;
                        else if (st.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
            Direction push = helper.getBlockState(pump).getValue(PumpBlock.FACING);
            BlockPos valveRel = pump.relative(push, 3);
            BlockPos downstreamCell = pump.relative(push, 4); // between the valve and the open end
            if (!pipeAt(helper, valveRel) || !pipeAt(helper, downstreamCell)) {
                helper.fail("template lacks a long enough push-side run for valve+downstream");
                return;
            }
            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos suction = push == Direction.WEST ? tanks.get(1) : tanks.get(0);
            BlockPos discharge = push == Direction.WEST ? tanks.get(0) : tanks.get(1);
            helper.setBlock(discharge, Blocks.AIR.defaultBlockState()); // run now opens into AIR

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            fillFluid(helper, suction, Fluids.WATER, 8000);

            setThrottle(level, helper.absolutePos(valveRel), 0); // SHUT
            PipeStatusPayload downstream = PipeProbe.probe(level, helper.absolutePos(downstreamCell));
            if (!downstream.fluid().isEmpty()) {
                helper.fail("downstream of a shut valve facing an open end reports fluid — should be "
                        + "dry (no reservoir on that side): " + downstream.fluid().getAmount());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The same invariant for an EMPTY TANK downstream of a shut valve (not an open end): the tank
     * IS a reservoir but holds no water, so its side has no SOURCE and must render dry. (An empty
     * tank's head sits at its base, half a block above the connecting pipe's bottom, so the cell
     * looked submerged — the bug is fixed by the island-has-a-source gate on restFluids, the single
     * invariant behind all the "shut valve shows water on the far side" reports.)
     */
    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void shutValveToEmptyTankLeavesDownstreamDry(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 10; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var st = helper.getBlockState(rel);
                        if (st.getBlock() instanceof PumpBlock) pump = rel;
                        else if (st.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
            Direction push = helper.getBlockState(pump).getValue(PumpBlock.FACING);
            BlockPos valveRel = pump.relative(push, 3);
            BlockPos downstreamCell = pump.relative(push, 4); // between the valve and the empty tank
            if (!pipeAt(helper, valveRel) || !pipeAt(helper, downstreamCell)) {
                helper.fail("template lacks a long enough push-side run for valve+downstream");
                return;
            }
            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos suction = push == Direction.WEST ? tanks.get(1) : tanks.get(0);
            BlockPos discharge = push == Direction.WEST ? tanks.get(0) : tanks.get(1);

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            drain(helper, discharge);                      // an EMPTY tank downstream — no water there
            fillFluid(helper, suction, Fluids.WATER, 8000); // all the water is on the FEED side

            setThrottle(level, helper.absolutePos(valveRel), 0); // SHUT
            PipeStatusPayload downstream = PipeProbe.probe(level, helper.absolutePos(downstreamCell));
            if (!downstream.fluid().isEmpty()) {
                helper.fail("downstream of a shut valve facing an EMPTY tank reports fluid — should "
                        + "be dry (the tank holds no water): " + downstream.fluid().getAmount());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Regression for the world-migration shut-valve bug: a valve saved BEFORE this feature has
     * no "ScrollValue" tag, and Create's {@code ScrollValueBehaviour.read} reads an absent key as
     * 0 — which would load every existing valve fully shut. The mixin re-asserts the open default
     * on a keyless read; verify a valve reloaded WITHOUT the tag comes up at 90° (fully open), not 0.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveDefaultsOpenWhenLoadedWithoutThrottleNbt(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = null;
            for (int x = 0; x < 6 && rel == null; x++)
                for (int y = 0; y < 6 && rel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        if (pipeAt(helper, new BlockPos(x, y, z))) { rel = new BlockPos(x, y, z); break; }
                    }
            if (rel == null) { helper.fail("no pipe cell to host a valve"); return; }
            helper.setBlock(rel, AllBlocks.FLUID_VALVE.get().defaultBlockState());

            BlockPos abs = helper.absolutePos(rel);
            var registries = level.registryAccess();
            BlockEntity be = level.getBlockEntity(abs);
            if (be == null) { helper.fail("valve has no block entity"); return; }

            // Simulate an old-world save: serialize, drop the throttle key, reload through read().
            CompoundTag saved = be.saveWithoutMetadata(registries);
            saved.remove("ScrollValue");
            be.loadWithComponents(saved, registries);

            ScrollValueBehaviour throttle = BlockEntityBehaviour.get(level, abs, ScrollValueBehaviour.TYPE);
            if (throttle == null) { helper.fail("valve lost its throttle behaviour"); return; }
            if (throttle.getValue() != 90) {
                helper.fail("a valve reloaded without a throttle tag came up at " + throttle.getValue()
                        + "°, expected 90 (fully open) — pre-feature valves would shut");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A cranked-open valve must HOLD open while its shaft idles — the open angle is a stored
     * position, so stopping the shaft (or having none) leaves it where it was set. An early
     * version gated ENABLED on live shaft speed and slammed the valve shut the moment rotation
     * stopped. Open a valve, read it once (as on a chunk reload), idle with no shaft — stays open.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveStaysOpenWhileShaftIdles(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = null;
            for (int x = 0; x < 6 && rel == null; x++)
                for (int y = 0; y < 6 && rel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        if (pipeAt(helper, new BlockPos(x, y, z))) { rel = new BlockPos(x, y, z); break; }
                    }
            if (rel == null) { helper.fail("no pipe cell to host a valve"); return; }
            BlockPos cell = rel;
            helper.setBlock(cell, AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP)
                    .setValue(FluidValveBlock.ENABLED, true));

            var registries = level.registryAccess();
            BlockEntity be = level.getBlockEntity(helper.absolutePos(cell));
            if (be == null) { helper.fail("valve has no block entity"); return; }
            // Read once so the open latch initializes from ENABLED, like a chunk reload does.
            be.loadWithComponents(be.saveWithoutMetadata(registries), registries);

            helper.runAfterDelay(30, () -> { // idle, no shaft attached
                if (!helper.getBlockState(cell).getValue(FluidValveBlock.ENABLED)) {
                    helper.fail("an opened valve snapped shut while its shaft idled — the latch was lost");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * The valve-side of the crank: a Valve Handle adds its set angle to connected valves via
     * {@code adjustThrottle}, which must step the opening by that many degrees and clamp 0–90.
     * (The handle applies its INTENT directly because its actual shaft rotation overshoots a small
     * set angle — 1° turns the shaft ~17°.) Drive a few steps and a clamp at each end.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveHandleStepsAndClampsTheThrottle(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = null;
            for (int x = 0; x < 6 && rel == null; x++)
                for (int y = 0; y < 6 && rel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        if (pipeAt(helper, new BlockPos(x, y, z))) { rel = new BlockPos(x, y, z); break; }
                    }
            if (rel == null) { helper.fail("no pipe cell to host a valve"); return; }
            helper.setBlock(rel, AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP));

            BlockPos abs = helper.absolutePos(rel);
            if (!(level.getBlockEntity(abs) instanceof ValveThrottle valve)) {
                helper.fail("valve BE is not a ValveThrottle"); return;
            }
            ScrollValueBehaviour t = BlockEntityBehaviour.get(level, abs, ScrollValueBehaviour.TYPE);
            t.setValue(40);
            valve.pipesnphysics$adjustThrottle(10);   // 40 -> 50
            if (t.getValue() != 50) { helper.fail("+10 from 40 gave " + t.getValue()); return; }
            valve.pipesnphysics$adjustThrottle(-30);  // 50 -> 20
            if (t.getValue() != 20) { helper.fail("-30 from 50 gave " + t.getValue()); return; }
            valve.pipesnphysics$adjustThrottle(-90);  // clamp to 0
            if (t.getValue() != 0) { helper.fail("-90 from 20 should clamp to 0, got " + t.getValue()); return; }
            valve.pipesnphysics$adjustThrottle(200);  // clamp to 90
            if (t.getValue() != 90) { helper.fail("+200 from 0 should clamp to 90, got " + t.getValue()); return; }
            helper.succeed();
        });
    }

    /** The solved hydraulic flow on the valve's edge after dialing the throttle to {@code angle}. */
    private static int valveFlow(Level level, Graph g, Edge edge, BlockPos valveAbs, int angle) {
        setThrottle(level, valveAbs, angle);
        Solution sol = FlowSolver.solve(level, g);
        for (EdgeFlow f : sol.edgeFlows()) {
            if (f.edgeIndex() == edge.index()) return f.mbPerTick();
        }
        return 0;
    }

    private static void setThrottle(Level level, BlockPos valveAbs, int angle) {
        ScrollValueBehaviour throttle = BlockEntityBehaviour.get(level, valveAbs, ScrollValueBehaviour.TYPE);
        if (throttle != null) throttle.setValue(angle);
    }

    private static boolean pipeAt(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get());
    }

    /** A raised tank must drain completely into the tank below it, no pump needed. */
    @GameTest(template = "gravity/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void gravityDrainsUpperTankCompletely(GameTestHelper helper) {
        BlockPos top = new BlockPos(0, 4, 0);
        BlockPos bottom = new BlockPos(0, 1, 0);
        fill(helper, top, 8000);

        helper.succeedWhen(() -> {
            int left = amount(helper, top);
            int below = amount(helper, bottom);
            if (left + below != 8000) helper.fail("fluid not conserved: " + left + " + " + below);
            if (left != 0) helper.fail("upper tank still holds " + left + " mB");
        });
    }

    /**
     * Tank above an open-ended pipe pointing down: the fluid must spill out into
     * the world (the tank drains and a water block appears below the opening).
     */
    @GameTest(template = "gravity/open_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void openEndSpillsDownward(GameTestHelper helper) {
        BlockPos tank = new BlockPos(0, 3, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        fill(helper, tank, 8000);

        helper.succeedWhen(() -> {
            if (amount(helper, tank) >= 8000) helper.fail("tank has not started draining");
            if (!helper.getLevel().getFluidState(helper.absolutePos(space)).isSource()) {
                helper.fail("no fluid placed below the open end");
            }
        });
    }

    /** A powered pump must push tank contents out of an open pipe end on its face. */
    @GameTest(template = "piping/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void pumpPushesOutOfOpenEnd(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        fill(helper, tank, 8000);

        helper.succeedWhen(() -> {
            if (amount(helper, tank) >= 8000) helper.fail("tank has not started draining");
            if (!helper.getLevel().getFluidState(helper.absolutePos(space)).isSource()) {
                helper.fail("no fluid placed at the open end");
            }
        });
    }

    /**
     * A pump must spill a source whose surface sits BELOW the open-end mouth: it lifts the
     * fluid out, and once a full source's worth (1000 mB) has accumulated in the open end's
     * buffer a water block appears. Drains a low, intermittently-topped tank — the buffer must
     * ACCUMULATE across drains (not leak between them) and eventually place a block.
     */
    @GameTest(template = "piping/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void pumpSpillsLowSourceOncePastBlockThreshold(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        fill(helper, tank, 600);
        helper.runAfterDelay(60, () -> fill(helper, tank, 600)); // > 1000 mB total over two drains
        helper.succeedWhen(() -> {
            if (!helper.getLevel().getFluidState(helper.absolutePos(space)).isSource()) {
                helper.fail("low source never spilled a block despite >1000 mB drained "
                        + "(buffer not accumulating across drains?)");
            }
        });
    }

    /**
     * Conservation: a spill must never MINT a block. With only 500 mB of network fluid — less than
     * one source's 1000 mB — the open end's buffer can hold it but must NOT place a source block, or
     * fluid is created from nothing (the user's "placed a block but only took ~500 mB" duplication).
     */
    @GameTest(template = "piping/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void spillDoesNotMintABlockFromTooLittleFluid(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        fill(helper, tank, 500); // less than one source block (1000 mB)
        helper.runAfterDelay(120, () -> {
            if (helper.getLevel().getFluidState(helper.absolutePos(space)).isSource()) {
                helper.fail("a 1000 mB source block appeared from only 500 mB of network fluid — duplication");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Re-enabled open-pipe INTAKE: a full water cauldron sits at an open pipe mouth that
     * drops to a tank below it, pulling the network head under the mouth (a "vacuum"). The
     * mouth must draw the cauldron's water IN — proving an open pipe sucks fluid from the
     * world again — and the cauldron drains to empty. Draining a cauldron leaves a clean
     * empty cauldron (nothing to re-spill), so nothing flickers. (A self-regenerating
     * lake is the other intake-eligible body; it drains the same way but is left intact.)
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void openEndSucksFromCauldronUnderVacuum(GameTestHelper helper) {
        BlockPos cauldron = new BlockPos(0, 3, 0); // the open mouth slot: a riser pipe opens up into it
        helper.runAfterDelay(3, () -> helper.setBlock(cauldron,
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3)));

        helper.succeedWhen(() -> {
            if (helper.getBlockState(cauldron).is(Blocks.WATER_CAULDRON)) {
                helper.fail("cauldron not drained — open end did not suck the water in");
            }
        });
    }

    /**
     * A water cauldron beside a pipe must join the graph as an OPEN_END (Create's
     * VanillaFluidTargets path), NOT a HANDLER — even though NeoForge registers a
     * fluid-handler capability for cauldrons. Its CauldronWrapper only drains in whole
     * 1000 mB increments, far above MAX_FLOW_PER_ENDPOINT, so the generic handler path
     * reads it as empty and a pump beside it never pulls (the "won't suck from a cauldron"
     * bug). Routing it to the open end (atomic drain + buffered intake) is the fix.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void cauldronJoinsAsOpenEndNotHandler(GameTestHelper helper) {
        BlockPos cauldron = new BlockPos(0, 3, 0);
        BlockPos seed = new BlockPos(1, 1, 0);
        helper.setBlock(cauldron,
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));

        helper.runAfterDelay(3, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            var node = graph.nodes().stream()
                    .filter(n -> n.pos().equals(helper.absolutePos(cauldron)))
                    .findFirst().orElse(null);
            if (node == null) {
                helper.fail("cauldron is not in the graph");
                return;
            }
            if (!node.isOpenEnd()) {
                helper.fail("cauldron joined as " + node.kind() + ", expected OPEN_END");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The cauldron's water must actually be DELIVERED to the sink, not merely vanish from
     * the cauldron. NeoForge's CauldronWrapper refuses every sub-1000 mB drain, so if
     * {@code apply} resolves the cauldron through that capability instead of the open-end
     * pipe, the solver shows flow while nothing moves — and the cauldron can still empty
     * via Create's manageSource side effect, leaving the tank dry ("shows a flow but moves
     * no fluid"). This asserts the creative tank at the run's end actually fills with water.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void cauldronIntakeActuallyFillsTheTank(GameTestHelper helper) {
        BlockPos cauldron = new BlockPos(0, 3, 0);
        BlockPos tank = new BlockPos(2, 1, 0);
        // The template's sink is a CREATIVE tank, which voids what it receives — useless as a
        // delivery probe. Swap in a real tank so "did the water actually arrive?" is observable.
        helper.setBlock(tank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.runAfterDelay(5, () -> helper.setBlock(cauldron,
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3)));

        helper.succeedWhen(() -> {
            IFluidHandler sink = helper.getLevel().getCapability(
                    Capabilities.FluidHandler.BLOCK, helper.absolutePos(tank), null);
            FluidStack held = sink == null ? FluidStack.EMPTY : sink.getFluidInTank(0);
            if (held.isEmpty() || !held.getFluid().isSame(Fluids.WATER)) {
                helper.fail("cauldron water did not reach the tank (held=" + held + ")");
            }
        });
    }

    /**
     * Intake of a body whose per-tick yield is BELOW the transfer cap (a full beehive
     * gives 250 mB &lt; MAX_FLOW 256): the mouth must draw it in (honey_level falls to 0)
     * and the engine must never request more than the world holds — Create's drain
     * over-reports a partial body, which would mint a few mB of honey from nothing. The
     * intake column's contentMb carries the real 250 mB yield and caps the request.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void openEndIntakeRespectsSubCapYield(GameTestHelper helper) {
        BlockPos hive = new BlockPos(0, 3, 0);
        BlockPos seed = new BlockPos(1, 1, 0);
        helper.runAfterDelay(3, () -> helper.setBlock(hive, Blocks.BEEHIVE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_HONEY, 5)));

        // Solve while the hive is still full (before any natural intake drains it): the
        // mouth must plan to draw honey IN, and never request more than the body's 250 mB.
        helper.runAfterDelay(5, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            Solution sol = FlowSolver.solve(helper.getLevel(), graph);
            Solution.Transfer fromHive = sol.transfers().stream()
                    .filter(t -> t.from().equals(helper.absolutePos(hive))).findFirst().orElse(null);
            if (fromHive == null) {
                helper.fail("open end did not draw honey from the beehive under vacuum");
                return;
            }
            if (fromHive.fluid().getAmount() > 250) {
                helper.fail("intake requested " + fromHive.fluid().getAmount()
                        + " mB from a 250 mB body — would duplicate fluid");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Cross-mod compat: a block tagged {@code pipesnphysics:fluid_conduits} (createpropulsion's
     * chainable liquid burner) is threaded into the network so a row of them shares fluid.
     * The engine cancels Create's transport that used to drive the burner's neighbour-
     * passthrough, so without this the directly-fed burner would fill alone. We build the
     * graph from a pipe feeding a row of three burners and assert all three are linked nodes.
     * Skips when createpropulsion is not loaded.
     */
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void fluidConduitChainsIntoOneNetwork(GameTestHelper helper) {
        Block burner = BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.parse("createpropulsion:liquid_burner")).orElse(null);
        if (burner == null) {
            helper.succeed(); // mod not present in this runtime — nothing to verify
            return;
        }

        BlockPos pipe = new BlockPos(0, 3, 0); // in the air above the 1-tall run
        BlockPos b0 = new BlockPos(0, 3, 1), b1 = new BlockPos(0, 3, 2), b2 = new BlockPos(0, 3, 3);
        helper.runAfterDelay(2, () -> {
            for (BlockPos p : new BlockPos[]{pipe, b0, b1, b2}) helper.setBlock(p, Blocks.AIR);
            helper.setBlock(b0, burner.defaultBlockState());
            helper.setBlock(b1, burner.defaultBlockState());
            helper.setBlock(b2, burner.defaultBlockState());
            helper.setBlock(pipe, pipeState(AllBlocks.FLUID_PIPE.get(), Direction.SOUTH)); // toward b0 (+z)
        });

        helper.runAfterDelay(6, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(pipe));
            long burnerNodes = graph.nodes().stream()
                    .filter(n -> helper.getLevel().getBlockState(n.pos()).is(burner))
                    .count();
            if (burnerNodes < 3) {
                helper.fail("conduit burners not all linked into the network: "
                        + burnerNodes + "/3 (nodes=" + graph.nodes().size()
                        + ", edges=" + graph.edges().size() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The headline request: a hand-placed water block in front of an open mouth, with a
     * tank below pulling a vacuum, must be sucked IN. The network never spilled, so the
     * finite-source gate is open and the engine plans to draw from the mouth.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void openEndDrinksHandPlacedSource(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 3, 0); // the open mouth slot
        BlockPos seed = new BlockPos(1, 1, 0);
        helper.runAfterDelay(8, () -> {
            helper.setBlock(source, Blocks.WATER.defaultBlockState()); // a lone, hand-placed source
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            Solution sol = FlowSolver.solve(helper.getLevel(), graph);
            boolean intake = sol.transfers().stream()
                    .anyMatch(t -> t.from().equals(helper.absolutePos(source)));
            if (!intake) helper.fail("open end did not draw in a hand-placed water source");
            else helper.succeed();
        });
    }

    /**
     * The anti-oscillation guard for finite sources: once a network has spilled, it must
     * NOT suck a finite source back in (its own spit, or a sibling mouth's), until a
     * cooldown lapses. Stamp a spill at the mouth, then confirm a source there is refused
     * within the cooldown and accepted again after it — the gate is temporary, not a ban.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void openEndDoesNotReclaimAfterSpill(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 3, 0);
        BlockPos seed = new BlockPos(1, 1, 0);
        int cooldown = PipesNPhysicsConfig.OPEN_END_INTAKE_COOLDOWN_TICKS.get();

        helper.runAfterDelay(3, () ->
                OpenEndPipes.markSpilled(helper.getLevel(), helper.absolutePos(source)));

        // Within the cooldown: a source at the just-spilled mouth must NOT be drawn in.
        helper.runAfterDelay(6, () -> {
            helper.setBlock(source, Blocks.WATER.defaultBlockState());
            Solution sol = FlowSolver.solve(helper.getLevel(),
                    GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed)));
            if (sol.transfers().stream().anyMatch(t -> t.from().equals(helper.absolutePos(source)))) {
                helper.fail("open end reclaimed a source within the spill cooldown");
            }
        });

        // After the cooldown lapses: the same source is drinkable again.
        helper.runAfterDelay(cooldown + 12, () -> {
            helper.setBlock(source, Blocks.WATER.defaultBlockState());
            Solution sol = FlowSolver.solve(helper.getLevel(),
                    GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed)));
            if (sol.transfers().stream().noneMatch(t -> t.from().equals(helper.absolutePos(source)))) {
                helper.fail("intake never resumed after the spill cooldown elapsed");
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * The goggle "Head left" readout must exist on BOTH sides of a working pump —
     * including when the suction run contains a junction with a dead-end stub,
     * which makes the suction cells junction NODES rather than edge interiors.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
    @GameTest(template = "piping/double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void headLeftAccumulatesAcrossIdlePumpsInSeries(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            List<BlockPos> pumps = new ArrayList<>();
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 12; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var state = helper.getBlockState(rel);
                        if (state.getBlock() instanceof PumpBlock) pumps.add(rel);
                        else if (state.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
                }
            }
            if (pumps.size() != 2 || tanks.size() != 2) {
                helper.fail("expected 2 pumps and 2 tanks, found " + pumps.size() + "/" + tanks.size());
                return;
            }
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void stalledPipeReportsSinkFull(GameTestHelper helper) {
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 8000);
        fill(helper, new BlockPos(4, 1, 1), 8000);

        helper.runAfterDelay(3, () -> {
            var push = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pushPipe));
            if (push.status() != PipeStatusPayload.STATUS_STALLED) {
                helper.fail("expected STALLED on the push pipe, got status "
                        + push.status() + dump(helper));
            }
            if (push.statusDetail() != PipeStatusPayload.DETAIL_SINK_FULL) {
                helper.fail("expected SINK_FULL detail, got " + push.statusDetail() + dump(helper));
            }
            helper.succeed();
        });
    }

    /**
     * A dry pipe whose (running) pump has nothing to pull must name the real culprit, not
     * leave a bare "No flow" beside a healthy-looking lift bar. With the source emptied the
     * powered pump just spins at zero flow — every branch idle and unflagged — so the probe
     * reports the pump as starved. (A pump pressing a full sink stalls; one that can't lift
     * is NO_HEAD; a valved one is blocked — only starvation reads as plain idle + dry.)
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void blockedPipeReportsUnpoweredPump(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 4000);

        helper.runAfterDelay(3, () -> {
            List<BlockPos> motors = new ArrayList<>();
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.CREATIVE_MOTOR.get())) {
                            motors.add(rel);
                        }
                    }
                }
            }
            if (motors.isEmpty()) {
                helper.fail("no creative motor found to unpower the pump");
                return;
            }
            motors.forEach(motor -> helper.setBlock(motor, Blocks.AIR));

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
     * Fluid must never cross a hydraulic barrier. Two unpowered pumps (closed valves)
     * split one discovered graph into two islands; each island has an elevated full
     * source over a near tank. Island A's near tank is FULL (its source has nowhere
     * local to put its surplus); island B's near tank is EMPTY (its source can fill
     * it). The greedy transfer planner used to spill island A's stuck surplus into
     * island B's open sink — teleporting fluid through the closed pumps. Sources may
     * now pair only with sinks in the same active-branch component, so nothing crosses.
     */
    @GameTest(template = "piping/double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void fluidDoesNotTeleportAcrossClosedBarrier(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            List<BlockPos> baseTanks = new ArrayList<>();
            List<BlockPos> motors = new ArrayList<>();
            for (int x = 0; x <= 9; x++) for (int y = 0; y <= 1; y++) for (int z = 0; z <= 2; z++) {
                BlockPos rel = new BlockPos(x, y, z);
                var st = helper.getBlockState(rel);
                if (st.is(AllBlocks.FLUID_TANK.get())) baseTanks.add(rel);
                else if (st.is(AllBlocks.CREATIVE_MOTOR.get())) motors.add(rel);
            }
            if (baseTanks.size() != 2) { helper.fail("expected 2 base tanks, found " + baseTanks); return; }
            if (motors.isEmpty()) { helper.fail("no motors found to unpower the pumps"); return; }

            // Unpower both pumps so each is a closed check valve. The whole pipe line
            // is still ONE discovered graph (BFS walks through pump cells), but the
            // solver drops the off-pump branches, splitting it into two islands.
            motors.forEach(m -> helper.setBlock(m, Blocks.AIR));

            baseTanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos nearA = baseTanks.get(0);   // island A near tank, kept FULL (clamped sink)
            BlockPos nearB = baseTanks.get(1);   // island B near tank, kept EMPTY (open sink)
            // Elevated sources join the line through the horizontal pipe next to each
            // tank (a stub above a PIPE, not above the tank — a tank is a graph leaf).
            BlockPos pipeA = nearA.east();
            BlockPos pipeB = nearB.west();
            if (isNotPipe(helper, pipeA) || isNotPipe(helper, pipeB)) {
                helper.fail("expected a pipe beside each base tank (A=" + pipeA + " B=" + pipeB + ")");
                return;
            }
            BlockPos srcA = pipeA.above(2);
            BlockPos srcB = pipeB.above(2);

            var pipe = AllBlocks.FLUID_PIPE.get();
            helper.setBlock(pipeA.above(), pipeState(pipe, Direction.UP, Direction.DOWN));
            helper.setBlock(pipeB.above(), pipeState(pipe, Direction.UP, Direction.DOWN));
            helper.setBlock(srcA, AllBlocks.FLUID_TANK.get().defaultBlockState());
            helper.setBlock(srcB, AllBlocks.FLUID_TANK.get().defaultBlockState());

            helper.runAfterDelay(5, () -> {
                drain(helper, nearA); fill(helper, nearA, 8000);   // island A: source over a FULL tank
                fill(helper, srcA, 8000);
                drain(helper, nearB);                              // island B: source over an EMPTY tank
                fill(helper, srcB, 8000);

                helper.runAfterDelay(5, () -> {
                    var level = helper.getLevel();
                    var graph = GraphBuilder.build(level, helper.absolutePos(pipeA));
                    var sol = FlowSolver.solve(level, graph);

                    Set<BlockPos> islandA = Set.of(
                            helper.absolutePos(nearA), helper.absolutePos(srcA));
                    Set<BlockPos> islandB = Set.of(
                            helper.absolutePos(nearB), helper.absolutePos(srcB));

                    boolean withinB = false;
                    for (var t : sol.transfers()) {
                        boolean cross = (islandA.contains(t.from()) && islandB.contains(t.to()))
                                || (islandB.contains(t.from()) && islandA.contains(t.to()));
                        if (cross) {
                            helper.fail("fluid teleported across the closed pumps: "
                                    + t.from().toShortString() + " -> " + t.to().toShortString()
                                    + dump(helper, pipeA));
                            return;
                        }
                        if (islandB.contains(t.from()) && islandB.contains(t.to())) withinB = true;
                    }
                    if (!withinB) {
                        helper.fail("island B should move its source into its empty tank, but planned "
                                + sol.transfers().size() + " transfers" + dump(helper, pipeA));
                        return;
                    }
                    helper.succeed();
                });
            });
        });
    }

    private static boolean isNotPipe(GameTestHelper helper, BlockPos rel) {
        return FluidPropagator.getPipe(
                helper.getLevel(), helper.absolutePos(rel)) == null;
    }

    /**
     * A pump pushing a viscous fluid (lava) down a long run is friction-limited:
     * its goggle load breakdown must report a friction factor below 1, and the
     * shipped factors must reconstruct the displayed load bar exactly
     * (load = headFactor · frictionFactor = rate / cap). Pump facing settles with
     * its rotation, so the suction tank and run side are chosen at runtime.
     */
    @GameTest(template = "piping/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void pumpLoadBreakdownExplainsFrictionLimit(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 5; y++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var state = helper.getBlockState(rel);
                        if (state.getBlock() instanceof PumpBlock) pump = rel;
                        else if (state.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
                }
            }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
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

            helper.runAfterDelay(5, () -> {
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
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * With Create's own transport tick cancelled, the engine must drive the
     * windowed-pipe fluid rendering itself: after fluid flows, a carrying pipe
     * cell must hold a Create {@code Flow} of that fluid on one of its
     * connections (what the client renders). Verifies the render bridge is wired
     * into the engine tick.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void flowingPipesCarryRenderableFluid(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 1, 1), 8000);

        helper.runAfterDelay(4, () -> {
            FluidStack rendered = pipesnphysics$findPipeFlow(helper);
            if (rendered == null) {
                helper.fail("no pipe cell carries a render Flow while fluid is moving"
                        + dump(helper));
                return;
            }
            if (!FluidStack.isSameFluidSameComponents(rendered, new FluidStack(Fluids.WATER, 1))) {
                helper.fail("pipe render Flow holds the wrong fluid: "
                        + rendered.getHoverName().getString());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Two equal tanks joined by a submerged pipe equalize to zero net flow, yet the
     * connecting pipe is full of water and must keep rendering it — the render bridge
     * must show resting fluid below the surface, not blank the pipe when flow stops.
     * (Threshold matches {@link #tanksEqualizeAtEqualSurfaces}: travel time delays the
     * start of delivery, so check "mostly settled with the pipe full" rather than dead level.)
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void restingFullPipesStillRenderFluid(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        fill(helper, left, 8000);

        helper.succeedWhen(() -> {
            int a = amount(helper, left);
            int b = amount(helper, right);
            if (Math.abs(a - b) > 800) helper.fail("not equalized yet: " + a + " vs " + b);
            // Settled (near-zero flow) but the U-pipe under the tanks is still full.
            if (pipesnphysics$findPipeFlow(helper) == null) {
                helper.fail("equalized pipe lost its fluid render" + dump(helper, left));
            }
        });
    }

    /**
     * A running pump dead-headed by a solid block on its push side leaves only ONE reservoir on the
     * network (its supply tank). The solve used to bail at &lt;2 participants, so the SUBMERGED pull
     * pipe between the full tank and the pump rendered EMPTY even though it sits below the tank's
     * surface — the user's "no fluid in the pipe though the head is there". A single reservoir now
     * still records the settled head + restFluids, so the resting water renders.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void deadEndedPumpRendersSubmergedSupplyPipe(GameTestHelper helper) {
        BlockPos pullPipe = new BlockPos(1, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 8000);            // full source → pull pipe sits below it
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE); // cap the output: one reservoir left

        helper.runAfterDelay(5, () -> {
            Level level = helper.getLevel();
            Graph graph = GraphBuilder.build(level, helper.absolutePos(pullPipe));
            Solution sol = FlowSolver.solve(level, graph);
            CreatePipeRendering.apply(level, graph, sol);

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, helper.absolutePos(pullPipe));
            boolean hasFluid = false;
            if (pipe != null) {
                for (Direction d : Direction.values()) {
                    if (pipe.getConnection(d) instanceof PipeConnectionAccessor acc
                            && acc.pipesnphysics$getFlow().isPresent()
                            && !acc.pipesnphysics$getFlow().get().fluid.isEmpty()) {
                        hasFluid = true;
                        break;
                    }
                }
            }
            if (!hasFluid) {
                helper.fail("submerged supply pipe of a dead-headed pump rendered no fluid" + dump(helper));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The fluid in a RESTING run beside a pump must read the way the pump moves it — not flow into
     * the tank it draws from. With the pump now carrying a display head (the stored-heads feature),
     * both ends of its pull run tie, and the old fallback oriented the fill by graph node order,
     * rendering pump->tank on half the runs (the reported "Edge B flows into the tank"). The fill
     * now follows the pump's push/pull side: the PULL run shows fluid leaving the tank toward the
     * pump (pump-side connection OUTBOUND), the PUSH run shows it leaving the pump (INBOUND).
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void restingPumpRunFollowsPushPullDirection(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            Level level = helper.getLevel();
            Graph graph = GraphBuilder.build(level, helper.absolutePos(new BlockPos(1, 1, 1)));
            if (graph.pumps().isEmpty()) { helper.fail("no pump in graph" + dump(helper)); return; }
            var pump = graph.pumps().get(0);
            if (pump.pumpFacing() == null) { helper.fail("pump facing unresolved" + dump(helper)); return; }

            Edge pull = null, push = null;
            for (Edge e : graph.edgesOf(pump.index())) {
                if (e.pipes().isEmpty()) continue;
                BlockPos adj = e.a() == pump.index()
                        ? e.pipes().get(0) : e.pipes().get(e.pipes().size() - 1);
                Direction d = Direction.fromDelta(adj.getX() - pump.pos().getX(),
                        adj.getY() - pump.pos().getY(), adj.getZ() - pump.pos().getZ());
                if (d == pump.pumpFacing()) push = e;
                else if (d == pump.pumpFacing().getOpposite()) pull = e;
            }
            if (pull == null || push == null) { helper.fail("pump lacks a push or pull run" + dump(helper)); return; }

            Boolean pullInbound = pipesnphysics$restingPumpRimInbound(level, graph, pump.index(), pump.pos(), pull);
            if (pullInbound == null) { helper.fail("pull run rendered no resting fluid" + dump(helper)); return; }
            if (pullInbound) {
                helper.fail("resting PULL run flows INTO the tank: the pump-side connection is inbound, "
                        + "it should be outbound (fluid leaving the tank toward the pump)");
                return;
            }
            Boolean pushInbound = pipesnphysics$restingPumpRimInbound(level, graph, pump.index(), pump.pos(), push);
            if (pushInbound == null) { helper.fail("push run rendered no resting fluid" + dump(helper)); return; }
            if (!pushInbound) {
                helper.fail("resting PUSH run: the pump-side connection should be inbound (fluid leaving the pump)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Apply a RESTING render solution (heads tied high enough to submerge the run) to {@code edge}
     * and return the inbound flag of the pump-adjacent cell's pump-facing connection, or null if it
     * rendered no fluid there. Outbound = fluid leaving the cell toward the pump.
     */
    private static Boolean pipesnphysics$restingPumpRimInbound(Level level, Graph graph,
                                                               int pumpIndex, BlockPos pumpPos, Edge edge) {
        double head = edge.pipes().get(0).getY() + 1.0;
        for (BlockPos c : edge.pipes()) head = Math.max(head, c.getY() + 1.0);
        Solution resting = pipesnphysics$renderSolution(graph, edge.index(),
                EdgeFlow.Direction.NONE, head, head, false);
        CreatePipeRendering.apply(level, graph, resting);

        BlockPos nearPump = edge.a() == pumpIndex
                ? edge.pipes().get(0) : edge.pipes().get(edge.pipes().size() - 1);
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, nearPump);
        Direction towardPump = Direction.fromDelta(pumpPos.getX() - nearPump.getX(),
                pumpPos.getY() - nearPump.getY(), pumpPos.getZ() - nearPump.getZ());
        if (pipe == null || towardPump == null) return null;
        if (pipe.getConnection(towardPump) instanceof PipeConnectionAccessor acc
                && acc.pipesnphysics$getFlow().isPresent()) {
            return acc.pipesnphysics$getFlow().get().inbound;
        }
        return null;
    }

    /**
     * An open pipe mouth ABOVE the connected reservoir's surface, with the run at REST, must
     * render NO fluid up the riser: an open end is a vent pinned at its mouth (the spill/intake
     * threshold), not a fluid surface, so interpolating a resting waterline up to it wrongly
     * filled the top cells — and a full Flow on the mouth cell makes Create's tickFlowProgress
     * pour liquid particles out of the open end. This is the user's "highest pipe shows water +
     * particles though nothing flows" report. Feeds the render bridge the exact buggy inputs
     * (idle edge, reservoir surface well below the mouth) and asserts the mouth cell stays dry.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void restingOpenEndAboveSurfaceRendersDry(GameTestHelper helper) {
        BlockPos seed = new BlockPos(1, 1, 0); // leave the mouth slot (0,3,0) as AIR: an open riser
        helper.runAfterDelay(5, () -> {
            Level level = helper.getLevel();
            Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));

            Edge riser = null;
            for (Edge e : graph.edges()) {
                boolean open = graph.node(e.a()).isOpenEnd() || graph.node(e.b()).isOpenEnd();
                if (open && !e.pipes().isEmpty()) { riser = e; break; }
            }
            if (riser == null) { helper.fail("no open-end pipe run in graph" + dump(helper, seed)); return; }

            boolean aOpen = graph.node(riser.a()).isOpenEnd();
            BlockPos mouthCell = aOpen ? riser.pipes().get(0)
                    : riser.pipes().get(riser.pipes().size() - 1);

            // A RESTING solution: the open end pinned at its MOUTH (high), the reservoir surface
            // two blocks below every riser cell. The buggy waterline interpolates up to the mouth
            // and fills the riser; the fix keeps it flat at the low surface, leaving the riser dry.
            double mouthHead = graph.node(aOpen ? riser.a() : riser.b()).pos().getY() + 0.5;
            double surfaceHead = mouthCell.getY() - 2.0;
            Solution resting = pipesnphysics$renderSolution(graph, riser.index(),
                    EdgeFlow.Direction.A_TO_B, aOpen ? mouthHead : surfaceHead,
                    aOpen ? surfaceHead : mouthHead, false);

            CreatePipeRendering.apply(level, graph, resting);

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, mouthCell);
            if (pipe != null) {
                for (Direction d : Direction.values()) {
                    if (pipe.getConnection(d) instanceof PipeConnectionAccessor acc
                            && acc.pipesnphysics$getFlow().isPresent()
                            && !acc.pipesnphysics$getFlow().get().fluid.isEmpty()) {
                        helper.fail("open-end mouth cell rendered fluid while the run rests below "
                                + "the mouth — Create would pour particles out of the open end");
                        return;
                    }
                }
            }
            helper.succeed();
        });
    }

    /**
     * The render fill threshold is the pipe's CENTRE, not its bottom face: a horizontal run sitting
     * just above a low reservoir surface (the waterline barely entering the block) must read DRY, so
     * a near-empty tank does not paint a full pipe — and a dry pipe shows no false "Reach limit".
     * Probes {@link CreatePipeRendering#restingCellSubmerged} directly: a waterline 0.2 into the
     * block is dry, one past the centre fills.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void lowHeadLeavesPipeDry(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            Graph graph = GraphBuilder.build(level, helper.absolutePos(new BlockPos(1, 1, 1)));
            Edge edge = null;
            for (Edge e : graph.edges()) {
                if (!e.pipes().isEmpty()) { edge = e; break; }
            }
            if (edge == null) { helper.fail("no pipe run in graph" + dump(helper)); return; }
            BlockPos cell = edge.pipes().get(0);
            double bottom = cell.getY();          // SableCompat.getWorldY - 0.5
            double belowCentre = bottom + 0.2;    // above the block bottom, below its centre (bottom+0.5)
            double aboveCentre = bottom + 0.75;   // past the centre

            if (CreatePipeRendering.restingCellSubmerged(level, graph, edge, 0, belowCentre, belowCentre, false)) {
                helper.fail("a waterline only 0.2 into the block still filled the pipe — must reach the centre");
                return;
            }
            if (!CreatePipeRendering.restingCellSubmerged(level, graph, edge, 0, aboveCentre, aboveCentre, false)) {
                helper.fail("a waterline past the pipe centre failed to fill it");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The "Lift left / Reach limit" reach readout must be SUPPRESSED on an idle, settled run — it is
     * only meaningful while fluid moves or a pump is being asked to lift. A balanced pipe otherwise
     * reads an alarming "Reach limit — raise the supply or add a pump" though nothing is trying to
     * deliver (the user's confusion). Asserts a settled tank-to-tank pipe is NOT shown the reach line,
     * while a flowing payload still is.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void reachLineSuppressedOnSettledRun(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 3, 0), 8000);
        fill(helper, new BlockPos(2, 3, 0), 8000);
        helper.runAfterDelay(10, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(new BlockPos(0, 3, 0)));
            BlockPos pipeCell = null;
            for (Edge e : graph.edges()) {
                if (graph.node(e.a()).isHandler() && graph.node(e.b()).isHandler() && !e.pipes().isEmpty()) {
                    BlockPos lowest = e.pipes().get(0);
                    for (BlockPos c : e.pipes()) if (c.getY() < lowest.getY()) lowest = c;
                    pipeCell = lowest; // graph built from an absolute seed → pipe cells are absolute
                    break;
                }
            }
            if (pipeCell == null) { helper.fail("no tank-to-tank pipe in graph" + dump(helper)); return; }

            PipeStatusPayload settled = PipeProbe.probe(helper.getLevel(), pipeCell);
            if (settled.status() != PipeStatusPayload.STATUS_NO_FLOW || settled.fluid().isEmpty()) {
                helper.fail("expected a settled NO_FLOW pipe with resting fluid, got status "
                        + settled.status() + dump(helper));
                return;
            }
            if (PipeStatusText.showsReach(settled)) {
                helper.fail("settled idle pipe still shows the reach line (a balanced run would read "
                        + "a false 'Reach limit')");
                return;
            }
            PipeStatusPayload flowing = new PipeStatusPayload(BlockPos.ZERO,
                    PipeStatusPayload.STATUS_FLOWING, 100, null, new FluidStack(Fluids.WATER, 1),
                    true, 1f, true, 3f, 5f, PipeStatusPayload.DETAIL_NONE, false, 0, false, 0, 0);
            if (!PipeStatusText.showsReach(flowing)) {
                helper.fail("a flowing pipe with headroom must still show the reach line");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Goggle legibility (the complement of {@link #restingOpenEndAboveSurfaceRendersDry}): on a
     * pipe rising past the tank's fluid surface to an open end, the goggle must report the DRY
     * upper cells as dry — not "settled, levels balanced". PipeProbe read the cell's fluid from
     * the edge-global restFluids, so every cell of a half-full run claimed water even where the
     * pipe is visibly empty ("the pipe says it has water inside, the vertical ones"). Per-cell
     * waterline gating fixes it: the highest riser cell (above the surface) probes EMPTY, the
     * lowest (below it) still probes the resting fluid.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void dryRiserCellAboveSurfaceProbesDry(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos seed = new BlockPos(1, 1, 0);
        // The template's tank is CREATIVE (always brim-full); swap a real one so its surface sits
        // low and the riser is dry above it. The mouth slot holds an EMPTY cauldron by default
        // (un-fillable, so the open end wouldn't even join the solve) — clear it to AIR so the run
        // is a true open-to-air vent that neither spills nor intakes.
        helper.setBlock(new BlockPos(0, 3, 0), Blocks.AIR.defaultBlockState());
        helper.setBlock(tank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.runAfterDelay(5, () -> {
            fill(helper, tank, 4000);
            helper.runAfterDelay(5, () -> {
                var level = helper.getLevel();
                Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));

                Edge riser = null;
                for (Edge e : graph.edges()) {
                    boolean open = graph.node(e.a()).isOpenEnd() || graph.node(e.b()).isOpenEnd();
                    if (open && !e.pipes().isEmpty()) { riser = e; break; }
                }
                if (riser == null) { helper.fail("no open-end pipe run" + dump(helper, seed)); return; }

                BlockPos highest = riser.pipes().get(0);
                BlockPos lowest = riser.pipes().get(0);
                for (BlockPos c : riser.pipes()) {
                    if (c.getY() > highest.getY()) highest = c;
                    if (c.getY() < lowest.getY()) lowest = c;
                }
                if (highest.getY() == lowest.getY()) {
                    helper.fail("riser is not vertical, can't test a dry-above/wet-below split");
                    return;
                }

                PipeStatusPayload top = PipeProbe.probe(level, highest);
                PipeStatusPayload bottom = PipeProbe.probe(level, lowest);
                if (!top.fluid().isEmpty()) {
                    helper.fail("dry riser cell above the surface still reports fluid — the goggle "
                            + "would call an empty pipe 'settled, levels balanced'");
                    return;
                }
                if (bottom.fluid().isEmpty()) {
                    helper.fail("submerged riser cell below the surface lost its resting fluid");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * Goggle legibility: an idle pipe that is FULL of resting fluid must report that fluid
     * (so the goggle can say "settled, levels balanced"), not read empty like a starved/dry
     * run. The probe used to send only the flowing fluid (empty when idle), so a healthy
     * balanced pipe and a dry one were indistinguishable — both bare "No flow". Equalizes two
     * tanks and asserts the settled pipe between them probes NO_FLOW with a non-empty fluid.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void settledPipeReportsRestingFluidForGoggle(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        // Equal fills on both ends → no head difference → settled at rest (no asymptotic
        // trickle), and the U-pipe between them sits submerged and full.
        fill(helper, left, 8000);
        fill(helper, right, 8000);
        helper.succeedWhen(() -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(left));
            BlockPos pipeCell = null;
            for (Edge e : graph.edges()) {
                if (graph.node(e.a()).isHandler() && graph.node(e.b()).isHandler() && !e.pipes().isEmpty()) {
                    pipeCell = e.pipes().get(e.pipes().size() / 2);
                    break;
                }
            }
            if (pipeCell == null) { helper.fail("no tank-to-tank pipe edge in graph"); return; }

            PipeStatusPayload payload = PipeProbe.probe(helper.getLevel(), pipeCell);
            if (payload.status() != PipeStatusPayload.STATUS_NO_FLOW) {
                helper.fail("pipe not settled yet (status " + payload.status() + ")");
                return;
            }
            if (payload.fluid().isEmpty()) {
                helper.fail("settled full pipe probed EMPTY — goggle would call a balanced pipe 'dry'");
            }
        });
    }

    /**
     * Render and delivery stay IN STEP: a flowing run fills as a travelling FRONT (progressive,
     * NOT instant), and `deliveryReady` holds the endpoint transfer until that front reaches the
     * sink — so the sink receives only once the fluid visually arrives, and the two match. Drives
     * the render bridge a tick at a time on the longest run: one pass leaves the front mid-pipe
     * (delivery gated), then the front crawls to the sink and delivery is released.
     */
    @GameTest(template = "piping/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void deliveryGatedUntilFrontReachesSink(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            BlockPos seed = null;
            for (int x = 0; x < 16 && seed == null; x++)
                for (int y = 0; y < 5 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

            Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));
            Edge edge = null; // the longest run, where the travelling front is clearest
            for (Edge e : graph.edges()) {
                if (!e.pipes().isEmpty() && (edge == null || e.pipes().size() > edge.pipes().size())) edge = e;
            }
            if (edge == null || edge.pipes().size() < 3) { helper.fail("no multi-cell pipe run"); return; }
            int cells = edge.pipes().size();

            Solution flowing = pipesnphysics$renderSolution(
                    graph, edge.index(), EdgeFlow.Direction.A_TO_B, 0, 0, true);
            Solution.Transfer toSink = new Solution.Transfer(
                    graph.node(edge.a()).pos(), graph.node(edge.b()).pos(), new FluidStack(Fluids.WATER, 200));

            // One pass: the front is still mid-pipe (progressive, not instant) -> delivery gated.
            CreatePipeRendering.apply(level, graph, flowing);
            if (pipesnphysics$countChargedEdgeCells(level, edge) >= cells) {
                helper.fail("whole run charged in ONE pass — fill is instant, not a travelling front");
                return;
            }
            if (CreatePipeRendering.deliveryReady(level, graph, flowing, toSink)) {
                helper.fail("delivery NOT gated while the front is still crawling to the sink");
                return;
            }

            // Let the front crawl to the sink; delivery must then release.
            for (int i = 0; i < 40 * cells + 80
                    && !CreatePipeRendering.deliveryReady(level, graph, flowing, toSink); i++) {
                CreatePipeRendering.apply(level, graph, flowing);
                pipesnphysics$tickEdgePipes(level, edge);
            }
            if (!CreatePipeRendering.deliveryReady(level, graph, flowing, toSink)) {
                helper.fail("delivery never released after the front had time to reach the sink");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Fluid travels down a pipe as a front, NOT a pop-fill: the number of fully-charged cells
     * GROWS over ticks while a long run fills, and the fill speed scales with the flow
     * (`flowPressure`). End-to-end with a real pump pushing water down the long discharge run.
     */
    @GameTest(template = "piping/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void fluidFrontAdvancesOverTime(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 5; y++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var state = helper.getBlockState(rel);
                        if (state.getBlock() instanceof PumpBlock) pump = rel;
                        else if (state.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
                }
            }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
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

    /** Count pipe cells that are fully charged (have a complete fluid Flow). */
    private static int pipesnphysics$countChargedPipes(GameTestHelper helper) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 4; z++) {
                    var pipe = FluidPropagator.getPipe(
                            helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
                    if (pipe == null) continue;
                    for (Direction dir : Direction.values()) {
                        var conn = pipe.getConnection(dir);
                        if (conn instanceof PipeConnectionAccessor accessor) {
                            var flow = accessor.pipesnphysics$getFlow();
                            if (flow.isPresent() && flow.get().complete && !flow.get().fluid.isEmpty()) {
                                count++;
                                break;
                            }
                        }
                    }
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
     * A raised tank draining into a lower one leaves the connecting pipe above the
     * lower waterline. That fluid must recede into the lower tank — a dead (no
     * longer solved) tank-to-tank run must still drain to empty rather than stay
     * stuck full (the failure mode if the drain freezes when the network sleeps).
     * The recede is gradual; this guards the end state, the feel is visual.
     */
    @GameTest(template = "gravity/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 1000)
    public static void drainedPipeRecedesNotStuck(GameTestHelper helper) {
        BlockPos top = new BlockPos(0, 4, 0);
        fill(helper, top, 8000);

        // POLL until the end state holds (tank drained AND the pipe has fully receded). The drain
        // and the gradual recede finish at a time that varies tick-to-tick, so a one-shot check at
        // a fixed tick was flaky; succeedWhen retries each tick until both hold (or the timeout).
        helper.succeedWhen(() -> {
            if (amount(helper, top) != 0) {
                helper.fail("upper tank has not drained yet: " + amount(helper, top));
            } else if (pipesnphysics$findPipeFlow(helper) != null) {
                helper.fail("connecting pipe stayed full after the upper tank drained");
            }
        });
    }

    /**
     * Breaking the pipe between two tanks leaves a dangling open-ended pipe; the
     * filled tank spills out of it. That spill must settle, not place and reclaim a
     * fluid block forever. Reproduces the user's "break a pipe → block spawns/
     * despawns forever" by removing the pump from tank-pipe-pump-pipe-tank, then
     * sampling the spilled block over consecutive ticks: present and stable.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 240)
    public static void openEndSpillDoesNotFlicker(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos gap = new BlockPos(2, 1, 1); // the pump's spot, soon to be a broken gap

        helper.runAfterDelay(3, () -> {
            helper.setBlock(gap, Blocks.AIR);             // break the run between the tanks
            helper.setBlock(new BlockPos(2, 0, 1), Blocks.STONE); // floor so the spill stays a source
            fill(helper, source, 6000);
        });

        boolean[] present = new boolean[16];
        for (int i = 0; i < 16; i++) {
            int idx = i;
            helper.runAfterDelay(180 + i, () ->
                    present[idx] = helper.getLevel().getFluidState(helper.absolutePos(gap)).isSource());
        }
        helper.runAfterDelay(200, () -> {
            for (boolean b : present) {
                if (!b) {
                    helper.fail("open-end spill flickered/absent (oscillation): "
                            + Arrays.toString(present));
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * Two level 1x1 tanks joined by a flat pipe run (tank-pipe-pipe-tank). Partly
     * filled, they equalize with the waterline settling INSIDE the connecting pipe
     * cells — those cells are still full and must keep rendering, not revert to empty
     * the instant flow stops. (Regression: the submersion test used the cell centre,
     * so an equalized level below centre wrongly read as above the waterline.)
     */
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void flatEqualizedPipeKeepsFluid(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 12; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 12; z++) {
                BlockPos rel = new BlockPos(x, y, z);
                if (helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
            }
            if (tanks.size() < 2) { helper.fail("expected 2 tanks, found " + tanks.size()); return; }
            // Equal, partial fill: no flow at all, and the surface settles low inside
            // the connecting pipe cells (below their centre, above their bottom). Drain
            // first — the template ships its tanks full.
            for (BlockPos t : tanks) drain(helper, t);
            for (BlockPos t : tanks) fill(helper, t, 2000);

            helper.runAfterDelay(3, () -> {
                // Force one render against a fresh solve so the result reflects the
                // resting state directly (the live engine may be asleep with stale
                // flows left over from the brief equalization).
                var graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(tanks.get(0)));
                var sol = FlowSolver.solve(helper.getLevel(), graph);
                CreatePipeRendering.apply(helper.getLevel(), graph, sol);

                if (pipesnphysics$findPipeFlow(helper) == null) {
                    helper.fail("flat resting pipe (surface inside the cell) rendered empty");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * Regression: a fully-charged pipe must NOT visually revert (drain and refill)
     * when an equalizing flow that runs B&rarr;A stops or briefly resumes.
     *
     * The render bridge keys each cell's inbound rim off the flow direction, but the
     * resting path used to hardcode node a as the inbound side. An edge whose flow ran
     * toward node a therefore had its inbound flags flipped on every flowing&harr;resting
     * transition, and the next charge reset {@code progress} to 0 &mdash; replaying the
     * whole fill backward. This drives that exact sequence on a real pipe and asserts
     * the charged cells stay charged.
     */
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void equalizedPipeDoesNotRevertWhenFlowRunsTowardA(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            BlockPos tank = null;
            for (int x = 0; x < 12 && tank == null; x++)
                for (int y = 0; y < 5 && tank == null; y++)
                    for (int z = 0; z < 12 && tank == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) tank = rel;
                    }
            if (tank == null) { helper.fail("no tank in template"); return; }

            var level = helper.getLevel();
            Graph graph = GraphBuilder.build(level, helper.absolutePos(tank));
            Edge edge = null;
            for (Edge e : graph.edges()) {
                if (graph.node(e.a()).isHandler() && graph.node(e.b()).isHandler() && !e.pipes().isEmpty()) {
                    edge = e;
                    break;
                }
            }
            if (edge == null) { helper.fail("no tank-to-tank pipe edge in graph"); return; }

            // Heads that submerge every pipe cell, with b HIGHER than a so the fluid runs
            // B->A: the orientation restEdge used to get wrong.
            double baseY = edge.pipes().get(0).getY();
            for (BlockPos cell : edge.pipes()) baseY = Math.max(baseY, cell.getY());
            double headA = baseY + 1.0;
            double headB = baseY + 2.0;
            Solution flowBtoA = pipesnphysics$renderSolution(
                    graph, edge.index(), EdgeFlow.Direction.B_TO_A, headA, headB, true);
            Solution resting = pipesnphysics$renderSolution(
                    graph, edge.index(), EdgeFlow.Direction.NONE, headA, headB, false);

            // Charge the front fully (chargeEdge seeds, the kept tickFlowProgress advances).
            // Each cell takes ~18 ticks to fill both halves, so cap well above run length.
            int cells = edge.pipes().size();
            for (int i = 0; i < 40 * cells + 80
                    && pipesnphysics$countChargedEdgeCells(level, edge) < cells; i++) {
                CreatePipeRendering.apply(level, graph, flowBtoA);
                pipesnphysics$tickEdgePipes(level, edge);
            }
            int charged = pipesnphysics$countChargedEdgeCells(level, edge);
            if (charged < cells) {
                helper.fail("front never charged: " + charged + "/" + cells);
                return;
            }

            CreatePipeRendering.apply(level, graph, resting);   // settle: must stay full
            CreatePipeRendering.apply(level, graph, flowBtoA);  // brief re-flow: must NOT recede
            int after = pipesnphysics$countChargedEdgeCells(level, edge);
            if (after < charged) {
                helper.fail("pipe reverted across flowing->resting->flowing: charged="
                        + charged + " after=" + after);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * No-flow rendering depends on WHY there is no flow. A stall whose source is dry is
     * phantom flow — nothing can feed the pipe — so it must render EMPTY. A sink-full
     * stall has fluid genuinely backed up in the pipe and must keep rendering it. Drives
     * the render bridge with both stalled solutions on the same pipe and checks each.
     */
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void drySourceStallRendersEmptyButSinkFullKeepsFluid(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            BlockPos tank = null;
            for (int x = 0; x < 12 && tank == null; x++)
                for (int y = 0; y < 5 && tank == null; y++)
                    for (int z = 0; z < 12 && tank == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) tank = rel;
                    }
            if (tank == null) { helper.fail("no tank in template"); return; }

            var level = helper.getLevel();
            Graph graph = GraphBuilder.build(level, helper.absolutePos(tank));
            Edge edge = null;
            for (Edge e : graph.edges()) {
                if (graph.node(e.a()).isHandler() && graph.node(e.b()).isHandler() && !e.pipes().isEmpty()) {
                    edge = e;
                    break;
                }
            }
            if (edge == null) { helper.fail("no tank-to-tank pipe edge in graph"); return; }

            double baseY = edge.pipes().get(0).getY();
            for (BlockPos cell : edge.pipes()) baseY = Math.max(baseY, cell.getY());
            double headA = baseY + 1.0;
            double headB = baseY + 2.0;

            // SINK_FULL: pressurized with fluid present -> must keep rendering.
            CreatePipeRendering.apply(level, graph,
                    pipesnphysics$stalledSolution(graph, edge.index(), Solution.Reason.SINK_FULL, headA, headB));
            if (!pipesnphysics$edgeHasAnyFlow(level, edge)) {
                helper.fail("SINK_FULL stall rendered the pipe empty (fluid should stay)");
                return;
            }

            // SOURCE_DRY: pressurized but no source -> must render nothing (sweep clears
            // the cell charged just above).
            CreatePipeRendering.apply(level, graph,
                    pipesnphysics$stalledSolution(graph, edge.index(), Solution.Reason.SOURCE_DRY, headA, headB));
            if (pipesnphysics$edgeHasAnyFlow(level, edge)) {
                helper.fail("SOURCE_DRY stall still rendered fluid (source is dry, show nothing)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A pump pushing into a full basin backs the pipe up with NO flow this tick (it rounds
     * to zero / the pump is dead-headed). The pump-to-sink run is not tank-to-tank, so it
     * misses the gradual-drain path and used to get swept blank — meaning when the basin
     * makes room the front had to re-travel the whole pipe (the flow "delayed all over
     * again"). It must instead stay charged. Charges a pump-to-handler run, then drives it
     * with a flowless SINK_FULL and a flowless NO_HEAD solution and asserts the fluid stays.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void backedUpStallKeepsChargedPipe(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            var level = helper.getLevel();
            BlockPos seed = null;
            for (int x = 0; x < 6 && seed == null; x++)
                for (int y = 0; y < 4 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

            Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));
            Edge edge = null;
            for (Edge e : graph.edges()) {
                var a = graph.node(e.a());
                var b = graph.node(e.b());
                if (((a.isPump() && b.isHandler()) || (a.isHandler() && b.isPump())) && !e.pipes().isEmpty()) {
                    edge = e;
                    break;
                }
            }
            if (edge == null) { helper.fail("no pump-to-handler pipe edge in graph"); return; }

            double baseY = edge.pipes().get(0).getY();
            for (BlockPos cell : edge.pipes()) baseY = Math.max(baseY, cell.getY());
            Solution charging = pipesnphysics$renderSolution(
                    graph, edge.index(), EdgeFlow.Direction.A_TO_B, baseY + 2.0, baseY + 1.0, true);
            int cells = edge.pipes().size();
            for (int i = 0; i < 40 * cells + 80
                    && pipesnphysics$countChargedEdgeCells(level, edge) < cells; i++) {
                CreatePipeRendering.apply(level, graph, charging);
                pipesnphysics$tickEdgePipes(level, edge);
            }
            if (pipesnphysics$countChargedEdgeCells(level, edge) < cells) {
                helper.fail("could not charge the pump-to-handler run to test the backed-up case");
                return;
            }

            for (boolean noHead : new boolean[]{false, true}) {
                CreatePipeRendering.apply(level, graph,
                        pipesnphysics$backedUpSolution(graph, edge.index(), noHead));
                if (!pipesnphysics$edgeHasAnyFlow(level, edge)) {
                    helper.fail("backed-up " + (noHead ? "NO_HEAD" : "SINK_FULL")
                            + " stall blanked the charged pipe — front would re-travel");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * A pump run whose SOURCE briefly runs dry (e.g. a basin fed in recipe-sized chunks, empty
     * between outputs) carries no flow this tick, isn't backed up against a sink, and — being a
     * pump run (not tank-to-tank) with no pump-junction head to settle a waterline — used to miss
     * every preservation guard and get swept blank. The travelling front then had to re-crawl the
     * whole run when the source refilled, so a LONG pipe delivered in bursts ("pumps every N ticks
     * then a big slug"). It must instead keep its charged cells. Charges a pump-to-handler run, then
     * drives it with an idle source-dry solution and asserts the fluid stays (vs being blanked).
     * Uses the long-discharge template so receding the single top cell per heartbeat still leaves
     * a charged run behind — exactly the long-pipe case where the burst was visible.
     */
    @GameTest(template = "piping/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void drySourcePumpRunKeepsChargedPipe(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            BlockPos seed = null;
            for (int x = 0; x < 16 && seed == null; x++)
                for (int y = 0; y < 5 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

            Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));
            Edge edge = null; // the LONGEST pump-to-handler run (the discharge side)
            for (Edge e : graph.edges()) {
                var a = graph.node(e.a());
                var b = graph.node(e.b());
                boolean pumpRun = (a.isPump() && b.isHandler()) || (a.isHandler() && b.isPump());
                if (pumpRun && (edge == null || e.pipes().size() > edge.pipes().size())) edge = e;
            }
            if (edge == null || edge.pipes().size() < 2) {
                helper.fail("no multi-cell pump-to-handler run in graph");
                return;
            }

            double baseY = edge.pipes().get(0).getY();
            for (BlockPos cell : edge.pipes()) baseY = Math.max(baseY, cell.getY());
            Solution charging = pipesnphysics$renderSolution(
                    graph, edge.index(), EdgeFlow.Direction.A_TO_B, baseY + 2.0, baseY + 1.0, true);
            int cells = edge.pipes().size();
            for (int i = 0; i < 40 * cells + 80
                    && pipesnphysics$countChargedEdgeCells(level, edge) < cells; i++) {
                CreatePipeRendering.apply(level, graph, charging);
                pipesnphysics$tickEdgePipes(level, edge);
            }
            if (pipesnphysics$countChargedEdgeCells(level, edge) < cells) {
                helper.fail("could not charge the pump-to-handler run to test the dry-source case");
                return;
            }

            // Source dries: idle, no flow, and no pump-junction head -> falls to drainDeadEdge,
            // which must KEEP the charged cells (receding gradually) rather than sweep them blank.
            CreatePipeRendering.apply(level, graph,
                    pipesnphysics$idleSourceDrySolution(graph, edge.index()));
            if (!pipesnphysics$edgeHasAnyFlow(level, edge)) {
                helper.fail("dry-source idle run was blanked — the long-pipe front would re-crawl");
                return;
            }
            helper.succeed();
        });
    }

    /** Build a one-edge render Solution carrying water, with the two endpoint heads set. */
    private static Solution pipesnphysics$renderSolution(Graph graph, int edgeIndex,
                                                         EdgeFlow.Direction direction,
                                                         double headA, double headB, boolean flowing) {
        Edge target = graph.edge(edgeIndex);
        List<EdgeFlow> flows = new ArrayList<>();
        for (Edge e : graph.edges()) {
            flows.add(e.index() == edgeIndex
                    ? new EdgeFlow(edgeIndex, direction, flowing ? 200 : 0)
                    : EdgeFlow.none(e.index()));
        }
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        Map<Integer, FluidStack> restFluids = new HashMap<>();
        restFluids.put(edgeIndex, water);
        Map<Integer, FluidStack> edgeFluids = new HashMap<>();
        if (flowing) edgeFluids.put(edgeIndex, water);
        Map<Integer, Double> heads = new HashMap<>();
        heads.put(target.a(), headA);
        heads.put(target.b(), headB);
        return new Solution(flows, List.of(), heads, Map.of(), Map.of(), edgeFluids, restFluids,
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), flowing);
    }

    /** A flowless solution where the edge is backed up against a blockage (no fluid carried). */
    private static Solution pipesnphysics$backedUpSolution(Graph graph, int edgeIndex, boolean noHead) {
        List<EdgeFlow> flows = new ArrayList<>();
        for (Edge e : graph.edges()) flows.add(EdgeFlow.none(e.index()));
        Set<Integer> stalled = new HashSet<>();
        Set<Integer> noHeadEdges = new HashSet<>();
        Map<Integer, Solution.Reason> reasons = new HashMap<>();
        if (noHead) {
            noHeadEdges.add(edgeIndex);
        } else {
            stalled.add(edgeIndex);
            reasons.put(edgeIndex, Solution.Reason.SINK_FULL);
        }
        return new Solution(flows, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), stalled, noHeadEdges, Set.of(), reasons, Map.of(), true);
    }

    /**
     * A flowless, head-less solution standing in for a pump run whose source just ran dry: no
     * flow, no stall flags, and NO node heads (the idle pump junction develops none), so the edge
     * falls past restEdge to the gradual-drain guard instead of being swept. restFluids is set to
     * mirror the real solve (the sink-side fluid is still sampled onto the assembled branch).
     */
    private static Solution pipesnphysics$idleSourceDrySolution(Graph graph, int edgeIndex) {
        List<EdgeFlow> flows = new ArrayList<>();
        for (Edge e : graph.edges()) flows.add(EdgeFlow.none(e.index()));
        Map<Integer, FluidStack> restFluids = new HashMap<>();
        restFluids.put(edgeIndex, new FluidStack(Fluids.WATER, 1));
        return new Solution(flows, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), restFluids,
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), false);
    }

    /** Advance the kept fill animation on every connection of an edge's pipe cells. */
    private static void pipesnphysics$tickEdgePipes(Level level, Edge edge) {
        for (BlockPos cell : edge.pipes()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe == null) continue;
            for (Direction dir : Direction.values()) {
                PipeConnection conn = pipe.getConnection(dir);
                if (conn != null) conn.tickFlowProgress(level, cell);
            }
        }
    }

    /** Count an edge's pipe cells that hold a complete (full) water Flow. */
    private static int pipesnphysics$countChargedEdgeCells(Level level, Edge edge) {
        int count = 0;
        for (BlockPos cell : edge.pipes()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe == null) continue;
            for (Direction dir : Direction.values()) {
                if (pipe.getConnection(dir) instanceof PipeConnectionAccessor accessor) {
                    var flow = accessor.pipesnphysics$getFlow();
                    if (flow.isPresent() && flow.get().complete && !flow.get().fluid.isEmpty()) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    /** A one-edge Solution that is pressurized (carries a direction + fluid) yet stalled. */
    private static Solution pipesnphysics$stalledSolution(Graph graph, int edgeIndex,
                                                          Solution.Reason reason,
                                                          double headA, double headB) {
        Edge target = graph.edge(edgeIndex);
        List<EdgeFlow> flows = new ArrayList<>();
        for (Edge e : graph.edges()) {
            flows.add(e.index() == edgeIndex
                    ? new EdgeFlow(edgeIndex, EdgeFlow.Direction.A_TO_B, 50)
                    : EdgeFlow.none(e.index()));
        }
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        Map<Integer, FluidStack> edgeFluids = new HashMap<>();
        edgeFluids.put(edgeIndex, water);
        Map<Integer, FluidStack> restFluids = new HashMap<>();
        restFluids.put(edgeIndex, water);
        Map<Integer, Double> heads = new HashMap<>();
        heads.put(target.a(), headA);
        heads.put(target.b(), headB);
        Map<Integer, Solution.Reason> reasons = new HashMap<>();
        reasons.put(edgeIndex, reason);
        return new Solution(flows, List.of(), heads, Map.of(), Map.of(), edgeFluids, restFluids,
                Set.of(), Set.of(edgeIndex), Set.of(), Set.of(), reasons, Map.of(), true);
    }

    /** Whether any of an edge's pipe cells currently holds a non-empty Create Flow. */
    private static boolean pipesnphysics$edgeHasAnyFlow(Level level, Edge edge) {
        for (BlockPos cell : edge.pipes()) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, cell);
            if (pipe == null) continue;
            for (Direction dir : Direction.values()) {
                if (pipe.getConnection(dir) instanceof PipeConnectionAccessor accessor) {
                    var flow = accessor.pipesnphysics$getFlow();
                    if (flow.isPresent() && !flow.get().fluid.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /** First non-empty Create pipe Flow found anywhere in the test structure, or null. */
    private static FluidStack pipesnphysics$findPipeFlow(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    var pipe = FluidPropagator.getPipe(
                            helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
                    if (pipe == null) continue;
                    for (Direction dir : Direction.values()) {
                        var conn = pipe.getConnection(dir);
                        if (!(conn instanceof PipeConnectionAccessor accessor)) {
                            continue;
                        }
                        var flow = accessor.pipesnphysics$getFlow();
                        if (flow.isPresent() && !flow.get().fluid.isEmpty()) {
                            return flow.get().fluid;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String dump(GameTestHelper helper) {
        return dump(helper, new BlockPos(2, 1, 1));
    }

    private static String dump(GameTestHelper helper, BlockPos probe) {
        var graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(probe));
        var solution = FlowSolver.solve(helper.getLevel(), graph);
        StringBuilder out = new StringBuilder(" | GRAPH:");
        for (var n : graph.nodes()) {
            out.append(String.format(" [%d %s %s head=%s ceil=%s]",
                    n.index(), n.kind(), n.pos().toShortString(),
                    solution.nodeHeads().get(n.index()), solution.nodeCeilings().get(n.index())));
        }
        for (var e : graph.edges()) {
            out.append(String.format(" e%d(%d-%d len%d %s)",
                    e.index(), e.a(), e.b(), e.length(),
                    solution.edgeFlows().get(e.index()).direction()));
        }
        return out.toString();
    }

    private static BlockState pipeState(
            Block pipe, Direction... connections) {
        var state = pipe.defaultBlockState();
        for (var property : PipeBlock.PROPERTY_BY_DIRECTION.values()) {
            state = state.setValue(property, false);
        }
        for (var direction : connections) {
            state = state.setValue(
                    PipeBlock.PROPERTY_BY_DIRECTION.get(direction), true);
        }
        return state;
    }

    private static void fill(GameTestHelper helper, BlockPos relativePos, int mb) {
        fillFluid(helper, relativePos, Fluids.WATER, mb);
    }

    private static void fillFluid(GameTestHelper helper, BlockPos relativePos,
                                  Fluid fluid, int mb) {
        handler(helper, relativePos)
                .fill(new FluidStack(fluid, mb), IFluidHandler.FluidAction.EXECUTE);
    }

    private static void drain(GameTestHelper helper, BlockPos relativePos) {
        handler(helper, relativePos).drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
    }

    private static int amount(GameTestHelper helper, BlockPos relativePos) {
        return handler(helper, relativePos).getFluidInTank(0).getAmount();
    }

    private static IFluidHandler handler(GameTestHelper helper, BlockPos relativePos) {
        IFluidHandler handler = helper.getLevel().getCapability(
                Capabilities.FluidHandler.BLOCK, helper.absolutePos(relativePos), null);
        if (handler == null) helper.fail("no fluid handler at " + relativePos);
        return handler;
    }
}
