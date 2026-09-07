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
 * Gravity lip / weir crest / siphon priming & barometric retention.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class GravitySiphonTests {

    /**
     * The 11 pipe cells of the {@code gravity/siphon_arch} template — two 1x1 tanks at (1,1,1)/(5,1,1)
     * joined SIDE-ON (a lid connection above the waterline that the lip walls) by a run arching over a
     * y+2 crest: the minimal pump-less siphon rig. Ordered source-side up, over, down to the sink.
     */
    private static List<BlockPos> siphonArchRun() {
        return List.of(
                new BlockPos(0, 1, 1), new BlockPos(0, 2, 1), new BlockPos(0, 3, 1),
                new BlockPos(1, 3, 1), new BlockPos(2, 3, 1), new BlockPos(3, 3, 1),
                new BlockPos(4, 3, 1), new BlockPos(5, 3, 1), new BlockPos(6, 3, 1),
                new BlockPos(6, 2, 1), new BlockPos(6, 1, 1));
    }

    /**
     * An UNPRIMED siphon must not start by itself: the crest sits above the source surface, and
     * suction can only HOLD a column there, never create one — nothing pushes water up a dry,
     * air-filled leg (the "why does this flow? that siphon is going up in y" report: the sink
     * barely gained while the solved trickle just climbed the ascending leg). The waterline may
     * still rise INTO the bottom leg cells (communicating vessels), but the cells above it must
     * stay dry.
     */
    @GameTest(template = "gravity/siphon_arch", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void dryCrestDoesNotSelfPrimeASiphon(GameTestHelper helper) {
        BlockPos tankA = new BlockPos(1, 1, 1);
        BlockPos tankB = new BlockPos(5, 1, 1);
        helper.runAfterDelay(5, () -> {
            fill(helper, tankA, 4000);
            fill(helper, tankB, 1000);
        });
        helper.runAfterDelay(100, () -> {
            int climbA = pipeAmount(helper, new BlockPos(0, 2, 1));
            int climbB = pipeAmount(helper, new BlockPos(6, 2, 1));
            if (climbA > 0 || climbB > 0) {
                helper.fail("the dry siphon climbed its leg by itself (" + climbA + " / " + climbB
                        + " mB above the waterline, tanks A=" + amount(helper, tankA)
                        + " B=" + amount(helper, tankB) + ") — a dry crest must not self-prime");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The counterpart: the SAME arch with its run pre-filled (a primed column, as a pump would
     * leave behind) must siphon source→sink — the wet crest keeps the normal suction allowance —
     * and the sealed full column must not lose its prime to the idle waterline recede (which
     * would show as the SOURCE gaining fluid back from its own leg).
     */
    @GameTest(template = "gravity/siphon_arch", templateNamespace = PipesNPhysics.ID, timeoutTicks = 140)
    public static void primedSiphonFlowsAndKeepsItsPrime(GameTestHelper helper) {
        BlockPos tankA = new BlockPos(1, 1, 1);
        BlockPos tankB = new BlockPos(5, 1, 1);
        List<BlockPos> run = siphonArchRun();
        helper.runAfterDelay(5, () -> {
            fill(helper, tankA, 4000);
            fill(helper, tankB, 1000);
            for (BlockPos rel : run) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
        });
        helper.runAfterDelay(120, () -> {
            int a = amount(helper, tankA);
            int b = amount(helper, tankB);
            if (b < 1200 || a > 3950) {
                helper.fail("the primed siphon did not flow (source " + a + "/4000, sink " + b
                        + "/1000) — a wet crest within the suction limit must siphon");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The sealed-column rule must key on the RENDERED waterline, not the block bottom: a full run
     * whose openings hang ABOVE the tanks' visible fluid is NOT sealed — air enters and the fluid
     * must recede into the tanks. The tanks here are 2-tall and near-empty (their rendered surface
     * sits low in the bottom block), while the run bridges them a block HIGHER — so its openings are
     * in the tanks' head space. Reading a full run above the waterline as a sealed siphon prime
     * froze its cells forever ("the pipes hold 250 mB instead of equalizing to the tanks" report).
     */
    @GameTest(template = "physics/run_above_low_tanks", templateNamespace = PipesNPhysics.ID, timeoutTicks = 140)
    public static void fullRunAboveTheWaterlinesIsNotSealedAndDrainsBack(GameTestHelper helper) {
        // run_above_low_tanks: two 2-tall tanks (columns at x=1 and x=5) bridged by a run at their TOP
        // row (y=2), above the near-empty fluid — so the run's openings sit in the tanks' head space.
        BlockPos tankA = new BlockPos(1, 2, 1);
        BlockPos tankB = new BlockPos(5, 2, 1);
        List<BlockPos> run = List.of(
                new BlockPos(2, 2, 1), new BlockPos(3, 2, 1), new BlockPos(4, 2, 1));
        helper.runAfterDelay(5, () -> {
            // 1600/16000 (10%) renders each surface at ~1.46 — down in the bottom block, well below
            // the run's bore floor at y=2 (2.3125). Equal fills, so nothing flows and only the
            // settle acts on the pre-charged full run.
            fill(helper, tankA, 1600);
            fill(helper, tankB, 1600);
            for (BlockPos rel : run) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(0)));
        });
        helper.runAfterDelay(120, () -> {
            int held = 0;
            for (BlockPos rel : run) {
                int mb = pipeAmount(helper, rel);
                held += mb;
                if (mb > 100) {
                    helper.fail("pipe cell " + rel.toShortString() + " still holds " + mb + "/"
                            + PipeStore.capacityMb() + " mB above both waterlines — a phantom"
                            + " sealed column froze instead of receding into the tanks");
                    return;
                }
            }
            int total = amount(helper, tankA) + amount(helper, tankB) + held;
            if (total != 3200 + 3 * PipeStore.capacityMb()) {
                helper.fail("fluid not conserved while receding: " + total);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The 3 pipe cells of the {@code gravity/own_level_drain} template: a source tank at (1,2,1)
     * draining by GRAVITY along its own base level, an elbow down, to a sink tank at (4,1,1) a block
     * lower. (gravityFlowStopsAtThePipeLip adds a second tank at (1,1,1) to make the source 2-tall.)
     */
    private static List<BlockPos> ownLevelDrainRun() {
        return List.of(new BlockPos(2, 2, 1), new BlockPos(3, 2, 1), new BlockPos(3, 1, 1));
    }

    /**
     * Gravity flow leaves a tank only through an opening its RENDERED surface reaches — the lip
     * (the pipe's 4x4 px connection aperture bottom, block + 6/16) is judged against the fluid
     * the player SEES, which Create draws inset. A 2-tall source with its opening on the TOP row
     * must stop giving when its visible waterline rests at that lip: rendered = base + 0.3125 +
     * f·1.4375 = 2.375 → ~11.8k of 16000 mB stays. (A BASE-row opening keeps just the puddle
     * below the aperture — pipeGravityRigDrainsTheRaisedTank.) A PUMP actively pulling still
     * reaches the block floor (pumpMovesAllFluidOnFlatGround).
     */
    @GameTest(template = "gravity/own_level_drain", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void gravityFlowStopsAtThePipeLip(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 2, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        List<BlockPos> run = ownLevelDrainRun();
        helper.setBlock(new BlockPos(1, 1, 1), AllBlocks.FLUID_TANK.get()); // source becomes 2-tall
        helper.runAfterDelay(5, () -> fill(helper, source, 16000));

        helper.succeedWhen(() -> {
            int left = amount(helper, source);
            int moved = amount(helper, sink);
            int pipes = 0;
            for (BlockPos rel : run) pipes += pipeAmount(helper, rel);
            if (left + moved + pipes != 16000 && left + moved + pipes != 0) {
                helper.fail("fluid not conserved: " + left + " + " + moved + " + pipes " + pipes);
            }
            if (left < 11650 || left > 12050) {
                helper.fail("source at " + left + " mB — its VISIBLE waterline must rest at the"
                        + " top-row pipe aperture (~11826)");
            }
            if (moved < 3500) helper.fail("sink only got " + moved + " mB");
        });
    }

    /**
     * The GAS twin of the lip above, and the one give-gate the buoyant frame was missing: a vessel
     * may only give through an opening its gas layer reaches DOWN to.
     *
     * A buoyant pocket rides the ceiling, so a tank holding a LITTLE gas cannot discharge it
     * through a pipe below that pocket — exactly as a nearly-empty water tank cannot give through
     * an opening above its puddle. The whole lip block used to be liquid-only, so the solve drained
     * such a tank anyway while the settle, which DOES read the mirrored geometry, poured it back
     * the next tick: the tank sat at a constant few mB while every single tick moved fluid, and the
     * pipes flickered without end. Reported 2026-09-06 on a CO2 rig whose /pipegraph tell was an
     * alternating {@code recent ·3 →3 ·3 →3} strip against an edge reading
     * {@code idle solved=0 actual=3}.
     *
     * The rig is {@code own_level_drain} read upside down: for a gas the LOW tank is the giver (a
     * lower vessel always presses its gas upward, however little it holds — which is why any trace
     * of gas in a low tank triggered this), and its opening lies along its own base level. 100 mB
     * of 8000 hangs the interface at ~1.74 against an aperture top of 1.625, so the tank must stand
     * perfectly still: it may neither give the gas nor be handed it back. Skips without a gas.
     */
    @GameTest(template = "gravity/own_level_drain", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void aThinGasPocketNeverGrindsAgainstItsOwnPipe(GameTestHelper helper) {
        Fluid gas = lighterThanAirFluid();
        if (gas == null) {
            helper.succeed();
            return;
        }
        BlockPos lowTank = new BlockPos(4, 1, 1);   // the giver in the gas frame
        BlockPos highTank = new BlockPos(1, 2, 1);
        List<BlockPos> run = ownLevelDrainRun();
        int seeded = 100;

        helper.runAfterDelay(10, () -> {
            drain(helper, lowTank);
            drain(helper, highTank);
            handler(helper, lowTank).fill(new FluidStack(gas, seeded), IFluidHandler.FluidAction.EXECUTE);
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.getFirst()));
        });
        // Sample EVERY tick: the grind hands the gas back on the tick after it leaves, so a sparse
        // sample can land twice on the same phase and read a constant amount either way.
        for (int tick = 30; tick <= 280; tick++) {
            helper.runAfterDelay(tick, () -> {
                int held = amount(helper, lowTank);
                if (held != seeded) {
                    helper.fail("the tank's thin gas pocket moved (" + held + "/" + seeded
                            + " mB): it cannot reach its own opening, so it must neither give the"
                            + " gas nor be handed it back" + dump(helper, run.getFirst()));
                }
            });
        }
        helper.runAfterDelay(290, () -> {
            int inPipes = 0;
            for (BlockPos rel : run) inPipes += pipeAmount(helper, rel);
            if (inPipes != 0 || amount(helper, highTank) != 0) {
                helper.fail("gas escaped a tank that cannot reach its opening (pipes " + inPipes
                        + ", far tank " + amount(helper, highTank) + ")" + dump(helper, run.getFirst()));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The owner's three-tank repro (assets/ponder/physics/pipe_gravity, copied into the test
     * structures): a raised 3-tall tank feeds two lower tanks through runs leaving its BASE
     * block. Every gravity gate keys on the RENDERED surface, and the lip is the pipe's 4x4 px
     * connection aperture, whose bottom sits ONE pixel above Create's tank fluid floor (6/16 vs
     * the 5/16 render inset) — so the raised tank drains until its visible puddle rests at the
     * aperture bottom (~615 mB of 24000 at 3-tall), reaches BOTH sinks, and never empties out.
     * Regressions both ways: gates on the LIQUID surface walled the tank while its rendered
     * fluid stood a quarter block over the pipe ("why does this pipegraph not flow?"), and a
     * 5/16 lip coincided with the render floor and drained it to nothing ("the tank drains
     * fully empty still, which shouldnt happen").
     */
    @GameTest(template = "gravity/pipe_gravity", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void pipeGravityRigDrainsTheRaisedTank(GameTestHelper helper) {
        var level = helper.getLevel();
        helper.runAfterDelay(10, () -> {
            // Locate the tanks (by controller, so multiblocks count once) and every pipe cell.
            Set<BlockPos> controllers = new LinkedHashSet<>();
            List<BlockPos> pipes = new ArrayList<>();
            for (BlockPos rel : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(7, 7, 7))) {
                BlockPos abs = helper.absolutePos(rel);
                if (level.getBlockEntity(abs) instanceof FluidTankBlockEntity tank) {
                    controllers.add(tank.getController().immutable());
                } else if (PipeStore.at(level, abs) != null) {
                    pipes.add(rel.immutable());
                }
            }
            BlockPos raised = controllers.stream().max(Comparator.comparingInt(BlockPos::getY)).orElse(null);
            if (raised == null || controllers.size() != 3) {
                helper.fail("rig should hold 3 tanks, found " + controllers.size());
                return;
            }
            List<BlockPos> sinks = controllers.stream().filter(c -> !c.equals(raised)).toList();

            // Normalize whatever fluid rode in with the saved structure, then pour a known amount.
            for (BlockPos ctrl : controllers) {
                pipesnphysics$tankHandler(level, ctrl).drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            }
            for (BlockPos rel : pipes) {
                PipeStore.Store cell = PipeStore.at(level, helper.absolutePos(rel));
                if (cell != null && cell.amount() > 0) {
                    cell.extract(cell.amount());
                    cell.flush();
                }
            }
            pipesnphysics$tankHandler(level, raised)
                    .fill(new FluidStack(Fluids.WATER, 6000), IFluidHandler.FluidAction.EXECUTE);
            EngineTickHandler.markChanged(level, helper.absolutePos(pipes.get(0)));

            helper.succeedWhen(() -> {
                int raisedMb = pipesnphysics$tankHandler(level, raised).getFluidInTank(0).getAmount();
                int pipeMb = 0;
                for (BlockPos rel : pipes) pipeMb += pipeAmount(helper, rel);
                int sinkSum = 0;
                int sinkMin = Integer.MAX_VALUE;
                for (BlockPos ctrl : sinks) {
                    int mb = pipesnphysics$tankHandler(level, ctrl).getFluidInTank(0).getAmount();
                    sinkSum += mb;
                    sinkMin = Math.min(sinkMin, mb);
                }
                if (raisedMb + sinkSum + pipeMb != 6000) {
                    helper.fail("fluid not conserved: " + raisedMb + " + " + sinkSum + " + pipes " + pipeMb);
                }
                // Rendered puddle rests at the aperture bottom: 24000·(0.0625/2.4375) ≈ 615 mB.
                if (raisedMb < 450 || raisedMb > 800) {
                    helper.fail("raised tank holds " + raisedMb + " mB — its visible puddle must"
                            + " rest at the pipe aperture (~615), neither stuck high nor drained out");
                }
                if (sinkMin < 1000) helper.fail("a sink only got " + sinkMin + " mB");
                // And the network must actually COME TO REST: a source stranded a hair over its
                // lip used to keep a solved-but-undrainable flow alive forever (SOURCE_DRY every
                // tick, flow stamps scrolling on the pipes while nothing moved).
                for (BlockPos rel : pipes) {
                    PipeStore.Store cell = PipeStore.at(level, helper.absolutePos(rel));
                    if (cell != null && cell.flowData() != 0) {
                        helper.fail("pipe " + rel.toShortString() + " still carries a flow stamp"
                                + " after the drain settled — phantom flow at the lip equilibrium");
                    }
                }
            });
        });
    }

    /**
     * The same rig with the source filled INTO the weir band — its RENDERED surface between the
     * side cell's lip (2.375) and centre (2.5) — and every pipe DRY. The supply reaches into
     * the dry crest cell, so plain gravity wets it and pours over (weir flow); the old
     * centre-height dry-crest gate declared an air break and locked the run out, which is how a
     * momentarily idle run whose crest cell the settle had drained froze mid-drain forever.
     */
    @GameTest(template = "gravity/own_level_drain", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void weirBandSupplyDrainsOverItsOwnLevelCrest(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 2, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        List<BlockPos> run = ownLevelDrainRun();
        helper.runAfterDelay(5, () -> fill(helper, source, 3400)); // rendered 2.498, lip 2.375

        helper.succeedWhen(() -> {
            int left = amount(helper, source);
            int moved = amount(helper, sink);
            int pipes = 0;
            for (BlockPos rel : run) pipes += pipeAmount(helper, rel);
            if (left + moved + pipes != 3400 && left + moved + pipes != 0) {
                helper.fail("fluid not conserved: " + left + " + " + moved + " + pipes " + pipes);
            }
            if (moved < 800) helper.fail("sink only got " + moved + " mB — the weir band is gated");
            if (left > 2150) helper.fail("source stuck at " + left + " mB above the pipe lip");
        });
    }

    /**
     * Barometric retention needs a SEALED tube: the vacuum in a broken crest's gap exists only
     * while no air can enter at either end, and a wet-but-unsealed source — its surface below its
     * end cell's bore — is an air path exactly like an empty one. The old per-leg "endpoint holds
     * fluid" rule kept a broken siphon's sink leg hanging full in mid-air beside a drained source
     * ("how can the flagged pipe hold fluid" report). Rig: siphon arch, source at dregs (500 mB,
     * below its bore), sink at 1000 mB, only the SINK-side leg below the crest pre-filled — the
     * dry crest gates, and the leg must recede into the sink instead of holding at surface+8.
     */
    @GameTest(template = "gravity/siphon_arch", templateNamespace = PipesNPhysics.ID, timeoutTicks = 160)
    public static void brokenSiphonLegAboveAnUnsealedSourceRecedes(GameTestHelper helper) {
        BlockPos tankA = new BlockPos(1, 1, 1);
        BlockPos tankB = new BlockPos(5, 1, 1);
        List<BlockPos> run = siphonArchRun();
        BlockPos hangingLeg = new BlockPos(6, 2, 1);
        BlockPos submergedLeg = new BlockPos(6, 1, 1);
        helper.runAfterDelay(5, () -> {
            fill(helper, tankA, 500);
            fill(helper, tankB, 1000);
            for (BlockPos rel : List.of(hangingLeg, submergedLeg)) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(hangingLeg));
        });
        helper.runAfterDelay(140, () -> {
            int hanging = pipeAmount(helper, hangingLeg);
            if (hanging > 100) {
                helper.fail("the sink leg still hangs " + hanging + "/" + PipeStore.capacityMb()
                        + " mB in mid-air above the waterline — an unsealed source lets air in,"
                        + " the leg must recede into the tank");
                return;
            }
            int total = amount(helper, tankA) + amount(helper, tankB);
            for (BlockPos rel : run) total += pipeAmount(helper, rel);
            if (total != 1500 + 2 * PipeStore.capacityMb()) {
                helper.fail("fluid not conserved while receding: " + total);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A gravity siphon whose crest rises more than the suction limit above the supply surface
     * BREAKS: the arch is a CREST-blocked edge carrying no flow. The goggle must then report the
     * air break as its reason and NOT a positive "air break in N blocks" margin — that margin is
     * recomputed from the display heads and can read a small POSITIVE value right at the threshold,
     * flatly contradicting the "air break over the crest" line the same run shows (the reported
     * "air break in 0.82 blocks on a dead pipe"). Builds its own tall arch so the crest height is
     * under our control, independent of any template's geometry.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void airBrokenCrestReportsNoSuctionMargin(GameTestHelper helper) {
        int crestY = (int) Math.ceil(PipesNPhysicsConfig.SUCTION_LIMIT.get()) + 3; // safely above the limit
        BlockPos supply = new BlockPos(0, 1, 0);
        BlockPos sink = new BlockPos(2, 1, 0);
        BlockPos riserProbe = new BlockPos(0, crestY - 2, 1);

        helper.runAfterDelay(10, () -> pipesnphysics$placeBrokenSiphon(helper, crestY, supply, sink));

        // Fill AFTER the freshly placed tank BEs have ticked once, so the handler is ready — then a
        // driving surface difference (full supply, empty sink) gives the pre-gate solve the flow the
        // crest gate cuts (a balanced pair would idle for an unrelated reason).
        helper.runAfterDelay(20, () -> {
            fill(helper, supply, 8000);
            drain(helper, sink);
        });

        helper.succeedWhen(() -> {
            BlockPos seedAbs = helper.absolutePos(riserProbe);
            Graph graph = GraphBuilder.build(helper.getLevel(), seedAbs);
            Solution solution = FlowSolver.solve(helper.getLevel(), graph);
            Edge arch = graph.edges().stream()
                    .filter(e -> e.pipes().contains(seedAbs))
                    .findFirst().orElse(null);
            if (arch == null) {
                helper.fail("no arch edge through the riser" + dump(helper, riserProbe));
                return;
            }
            if (!solution.blockedEdges().contains(arch.index())
                    || solution.edgeReasons().get(arch.index()) != Solution.Reason.CREST) {
                helper.fail("the arch should be CREST-blocked, got reason "
                        + solution.edgeReasons().get(arch.index())
                        + " supply=" + amount(helper, supply) + " sink=" + amount(helper, sink)
                        + dump(helper, riserProbe));
                return;
            }
            PipeStatusPayload probe = PipeProbe.probe(helper.getLevel(), seedAbs);
            if (probe.statusDetail() != PipeStatusPayload.DETAIL_CREST) {
                helper.fail("probe should read the air-break reason, got detail "
                        + probe.statusDetail() + dump(helper, riserProbe));
                return;
            }
            if (probe.hasSuctionMargin()) {
                helper.fail("a broken air-break run must not report a positive suction margin, got "
                        + probe.suctionMarginBlocks() + dump(helper, riserProbe));
            }
        });
    }

    /**
     * A broken siphon is a BAROMETER: the level renderer must fill the source leg with the static
     * column suction holds — up to {@code source_surface + SUCTION_LIMIT} — then leave an air gap over
     * the crest, so the water visibly climbs to the break. Reproduces "the source pipes render empty":
     * the resting waterline was flattened to the LOWER (sink) surface, hiding the source column. Checks
     * the LEVEL path ({@code apply(...,true)} → {@link PipeLevelData}): a source-riser cell well ABOVE
     * the source surface is stamped (impossible under the min flatten), and the crest cell stays dry.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200, batch = "levelRender")
    public static void brokenSiphonRendersBarometricSourceColumn(GameTestHelper helper) {
        int crestY = (int) Math.ceil(PipesNPhysicsConfig.SUCTION_LIMIT.get()) + 3;
        BlockPos supply = new BlockPos(0, 1, 0);
        BlockPos sink = new BlockPos(2, 1, 0);

        helper.runAfterDelay(10, () -> pipesnphysics$placeBrokenSiphon(helper, crestY, supply, sink));
        // BOTH tanks hold water (supply fuller, to drive the pre-gate flow the crest cuts) — like the
        // real report — so each leg is a barometer and the source column fills right up to the break,
        // not tapering toward an empty far end.
        helper.runAfterDelay(20, () -> {
            fill(helper, supply, 8000);
            fill(helper, sink, 1000);
        });
        // Prime the legs as a previously-running siphon would have left them: full to the crest.
        // Physically the break then collapses only the part above the barometric reach — the legs
        // within surface + SUCTION_LIMIT hold (a vacuum gap forms at the crest), which is exactly
        // what the settle pass must reproduce with the real stored volume.
        helper.runAfterDelay(25, () -> {
            Level level = helper.getLevel();
            for (int y = 1; y <= crestY; y++) {
                for (int x : new int[] {0, 2}) {
                    PipeStore.Store cell = PipeStore.at(level, helper.absolutePos(new BlockPos(x, y, 1)));
                    if (cell != null) {
                        cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                        cell.flush();
                    }
                }
            }
            PipeStore.Store arch = PipeStore.at(level, helper.absolutePos(new BlockPos(1, crestY, 1)));
            if (arch != null) {
                arch.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                arch.flush();
            }
        });
        helper.succeedWhen(() -> {
            Level level = helper.getLevel();
            BlockPos seedAbs = helper.absolutePos(new BlockPos(0, crestY - 2, 1));
            Graph graph = GraphBuilder.build(level, seedAbs);
            Solution solution = FlowSolver.solve(level, graph);
            Edge arch = graph.edges().stream()
                    .filter(e -> e.pipes().contains(seedAbs)).findFirst().orElse(null);
            if (arch == null || !solution.blockedEdges().contains(arch.index())
                    || solution.edgeReasons().get(arch.index()) != Solution.Reason.CREST) {
                helper.fail("arch not crest-blocked" + dump(helper, new BlockPos(0, crestY - 2, 1)));
                return;
            }
            Double supplyHead = graph.nodes().stream()
                    .filter(n -> n.pos().equals(helper.absolutePos(supply)))
                    .findFirst().map(n -> solution.nodeHeads().get(n.index())).orElse(null);
            if (supplyHead == null) { helper.fail("no supply head" + dump(helper, supply)); return; }
            double reach = PipesNPhysicsConfig.SUCTION_LIMIT.get();

            // The crest cell sits above surface + reach — the air-break gap must drain dry
            // (level-spreading leaves at most a dregs film on a flat crest arch).
            if (cellMb(level, helper.absolutePos(new BlockPos(0, crestY, 1))) > 8) {
                helper.fail("the crest cell should drain to the dry air-break gap, but holds "
                        + cellMb(level, helper.absolutePos(new BlockPos(0, crestY, 1))) + " mB");
            }
            // A source-riser cell WELL above the source surface yet within the suction reach must
            // KEEP its barometric column — the settle pass may only collapse the part above reach.
            boolean sawColumnAboveSurface = false;
            for (int y = 2; y < crestY; y++) {
                BlockPos abs = helper.absolutePos(new BlockPos(0, y, 1));
                double cellBottom = abs.getY() - 0.5;
                if (cellBottom <= supplyHead + 0.5) continue;          // still at/below the surface
                if (cellBottom > supplyHead + reach - 1) continue;     // near/above the barometric reach
                if (cellMb(level, abs) > 0) {
                    sawColumnAboveSurface = true;
                    break;
                }
            }
            if (!sawColumnAboveSurface) {
                helper.fail("no source-riser cell above the surface kept the barometric column — "
                        + "the settle drained the leg (surface=" + supplyHead + " reach=" + reach + ")"
                        + dump(helper, new BlockPos(0, crestY - 2, 1)));
            }
        });
    }

    /** Builds the tall gravity siphon used by the air-break tests: two side-drawn tanks an x-gap apart,
     *  a run that climbs at z=1, bridges the crest across x, and drops into the sink. */
    private static void pipesnphysics$placeBrokenSiphon(GameTestHelper helper, int crestY,
                                                        BlockPos supply, BlockPos sink) {
        var pipe = AllBlocks.FLUID_PIPE.get();
        var tank = AllBlocks.FLUID_TANK.get();
        // Clear the template's own machine out of the working volume first.
        for (int x = 0; x <= 4; x++)
            for (int y = 1; y <= crestY + 1; y++)
                for (int z = 0; z <= 1; z++)
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());

        helper.setBlock(supply, tank.defaultBlockState());
        helper.setBlock(sink, tank.defaultBlockState());

        // Draw from the SIDE of each tank at its own level (a pipe leaving the TOP of a full 1-tall
        // tank sits exactly at the surface lip and reads "can't draw").
        helper.setBlock(new BlockPos(0, 1, 1), pipeState(pipe, Direction.NORTH, Direction.UP));
        helper.setBlock(new BlockPos(2, 1, 1), pipeState(pipe, Direction.NORTH, Direction.UP));
        helper.setBlock(new BlockPos(0, crestY, 1), pipeState(pipe, Direction.DOWN, Direction.EAST));
        helper.setBlock(new BlockPos(1, crestY, 1), pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(2, crestY, 1), pipeState(pipe, Direction.WEST, Direction.DOWN));
        for (int y = 2; y < crestY; y++) {
            helper.setBlock(new BlockPos(0, y, 1), pipeState(pipe, Direction.UP, Direction.DOWN));
            helper.setBlock(new BlockPos(2, y, 1), pipeState(pipe, Direction.UP, Direction.DOWN));
        }
    }

    /**
     * A source resting just ABOVE its lip equilibrium must hand its margin to the sink and go
     * QUIET — never orbit it. Two 2-tall tanks joined by a top-row run, the source 14 mB over the
     * fill where its rendered surface meets the aperture bottom, the sink far below the bore. The
     * live-report limit cycle ("major oscillation issues, flows shortly, stops"): the flow pass
     * dribbles the margin into the head cell (lip-cap dregs), where the depth gate parks it —
     * {@code probeSupply} read the raw handler, so a lip-WALLED source looked bottomless and
     * {@code columnFullyArrived} never fired — and on the stall ticks the settle's min-flattened
     * target 0 read the parked film as excess and poured it back UP into the source, re-opening
     * the gate. Fluid ping-ponged source↔head-cell at 4 mB forever; the sink never gained a drop.
     * Asserts the two fixes independently: the sink actually RECEIVES the margin (lip-aware
     * supply probe → wire-remnant delivery), and the settled source never regains fluid
     * (per-end pour gates — pouring into a tank is a gravity act, gated on ITS OWN line).
     */
    @GameTest(template = "common/top_row_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 240)
    public static void lipEquilibriumMarginReachesTheSinkAndQuiets(GameTestHelper helper) {
        // top_row_run: two 2-tall tanks at x0/x4 joined by the top-row 3-cell run
        BlockPos tankA = new BlockPos(0, 1, 0);
        BlockPos tankB = new BlockPos(4, 1, 0);
        List<BlockPos> run = List.of(
                new BlockPos(1, 2, 0), new BlockPos(2, 2, 0), new BlockPos(3, 2, 0));

        // 2-tall tank: lip equilibrium = fill where rendered surface (base+0.3125+f·1.4375) meets
        // the top-row aperture bottom (base+1.375) → f=73.91% = 11826 mB. Start 14 mB above it.
        int sourceStart = 11840;
        int sinkStart = 4000;
        helper.runAfterDelay(5, () -> {
            fill(helper, tankA, sourceStart);
            fill(helper, tankB, sinkStart);
            if (amount(helper, tankA) < sourceStart) { // a lone block clamps at 8000
                helper.fail("source tank did not assemble 2-tall (holds " + amount(helper, tankA) + ")");
                return;
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(1)));
        });

        int[] lastSeen = new int[1];
        helper.runAfterDelay(140, () -> lastSeen[0] = amount(helper, tankA));
        for (int t = 141; t <= 190; t++) {
            helper.runAfterDelay(t, () -> {
                int now = amount(helper, tankA);
                if (now > lastSeen[0]) {
                    helper.fail("source tank REGAINED fluid (" + lastSeen[0] + " → " + now
                            + ") — the settle poured the parked film back uphill (orbit)");
                    return;
                }
                lastSeen[0] = now;
            });
        }
        helper.runAfterDelay(191, () -> {
            int a = amount(helper, tankA);
            int b = amount(helper, tankB);
            int inPipes = 0;
            for (BlockPos rel : run) inPipes += cellMb(helper.getLevel(), helper.absolutePos(rel));
            if (b < sinkStart + 4) {
                helper.fail("sink never received the source's above-lip margin (sink " + b
                        + ", source " + a + ", pipes " + inPipes
                        + ") — the depth gate parked the dribble forever");
                return;
            }
            if (a + b + inPipes != sourceStart + sinkStart) {
                helper.fail("fluid not conserved: " + a + " + " + b + " + " + inPipes
                        + " != " + (sourceStart + sinkStart));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The pure settle half of the lip-equilibrium orbit, isolated from the flow pass: a small
     * film resting in the run BESIDE the higher tank — standing BELOW that tank's own waterline
     * — must never pour back UP into it. The min-flattened retain target (the far sink's line,
     * below the bore, so 0) read the film as excess and lifted it into the higher tank; the pour
     * gate now uses each end's OWN line (pouring in is a gravity act). The film instead spreads
     * along the run and pours into the LOW sink, minus the anti-slosh dregs.
     */
    @GameTest(template = "common/top_row_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void idleFilmBesideAHigherTankNeverPoursBackUphill(GameTestHelper helper) {
        // top_row_run: two 2-tall tanks at x0/x4 joined by the top-row 3-cell run
        BlockPos tankA = new BlockPos(0, 1, 0);
        BlockPos tankB = new BlockPos(4, 1, 0);
        List<BlockPos> run = List.of(
                new BlockPos(1, 2, 0), new BlockPos(2, 2, 0), new BlockPos(3, 2, 0));

        // A rests exactly AT its lip equilibrium (11826 → surface a hair under the aperture), so
        // no flow pass ever assembles; B far below the bore. The 30 mB film beside A stands under
        // A's own line (~42 mB of the bore), so nothing about it is excess toward A.
        int sourceStart = 11826;
        int sinkStart = 4000;
        int filmMb = 30;
        helper.runAfterDelay(5, () -> {
            fill(helper, tankA, sourceStart);
            fill(helper, tankB, sinkStart);
            if (amount(helper, tankA) < sourceStart) {
                helper.fail("source tank did not assemble 2-tall (holds " + amount(helper, tankA) + ")");
                return;
            }
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(run.get(0)));
            if (cell == null) {
                helper.fail("no pipe store at " + run.get(0).toShortString());
                return;
            }
            cell.insert(new FluidStack(Fluids.WATER, filmMb), filmMb);
            cell.flush();
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(1)));
        });

        for (int t = 10; t <= 150; t += 2) {
            helper.runAfterDelay(t, () -> {
                int a = amount(helper, tankA);
                if (a > sourceStart) {
                    helper.fail("the higher tank GAINED the idle film (" + a + " > " + sourceStart
                            + ") — the settle poured it back uphill");
                }
            });
        }
        helper.runAfterDelay(155, () -> {
            int a = amount(helper, tankA);
            int b = amount(helper, tankB);
            int inPipes = 0;
            for (BlockPos rel : run) inPipes += cellMb(helper.getLevel(), helper.absolutePos(rel));
            if (b < sinkStart + filmMb / 2) {
                helper.fail("the film never reached the low sink (sink " + b + ", pipes " + inPipes + ")");
                return;
            }
            if (a + b + inPipes != sourceStart + sinkStart + filmMb) {
                helper.fail("fluid not conserved: " + a + " + " + b + " + " + inPipes
                        + " != " + (sourceStart + sinkStart + filmMb));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A running pump SUCKS, at a tenth of what it pushes ({@code pumpPullHeadFraction}, §3): it may
     * establish through its own dry suction line by that much head, so a supply standing a little
     * BELOW the pipe's aperture is still drawn instead of stalling there.
     *
     * The rig is the wall's own edge case: 500 of 8000 mB renders 0.34 up the block, under the 6/16
     * lip, which used to gate the run outright — a dead-flat line reading "the supply sits below
     * this pipe's opening" ({@link
     * de.devin.pipesnphysics.gametest.display.GoggleProbeTests#levelRunBelowApertureReadsSupplyLowNotCrest},
     * which pins the fraction to 0 to keep that wall). At the default a 0.4-block allowance clears
     * that pixel easily and the tank empties into the sink, which is the point of the feature.
     *
     * Mutation check: set the fraction to 0 (or hand the branch a 0 allowance in {@code
     * FluidPass.assembleBranch}) and the source keeps its 500 mB — the crest gate never opens.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void pumpPullAllowanceClearsASupplyUnderTheOpening(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        int supplied = 500;
        fill(helper, source, supplied);
        drain(helper, sink);

        helper.succeedWhen(() -> {
            int left = amount(helper, source);
            int delivered = amount(helper, sink);
            if (left > supplied / 10) {
                helper.fail("the pump left " + left + " mB standing under the pipe's opening —"
                        + " its pull allowance should draw a supply this close to the lip"
                        + dump(helper, source));
            }
            if (delivered <= 0) {
                helper.fail("nothing reached the sink, so nothing was actually drawn"
                        + dump(helper, source));
            }
        });
    }
}
