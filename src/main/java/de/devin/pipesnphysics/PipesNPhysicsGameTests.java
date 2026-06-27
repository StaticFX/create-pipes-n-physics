package de.devin.pipesnphysics;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import de.devin.pipesnphysics.engine.Edge;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Graph;
import de.devin.pipesnphysics.engine.GraphBuilder;
import de.devin.pipesnphysics.engine.OpenEndPipes;
import de.devin.pipesnphysics.engine.PipeProbe;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PipeBlock;
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
     * Fluid must travel down a pipe as a front, not pop full instantly: the number
     * of fully-charged pipe cells must GROW over time while a long run fills. Uses a
     * pump pushing water down the long run; the suction tank is chosen by facing.
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
    @GameTest(template = "gravity/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void drainedPipeRecedesNotStuck(GameTestHelper helper) {
        BlockPos top = new BlockPos(0, 4, 0);
        fill(helper, top, 8000);

        helper.runAfterDelay(750, () -> {
            if (amount(helper, top) != 0) {
                helper.fail("upper tank has not drained yet: " + amount(helper, top));
                return;
            }
            if (pipesnphysics$findPipeFlow(helper) != null) {
                helper.fail("connecting pipe stayed full after the upper tank drained");
                return;
            }
            helper.succeed();
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
     * Travel time: a freshly started flow must fill the pipe before the sink receives
     * anything. The endpoint transfer is held until the visual fluid front reaches the
     * sink, so on a long flat run the sink stays empty for the first ticks (front still
     * travelling) and only fills once the front arrives. (The old engine delivered the
     * instant the circuit solved, so the sink filled immediately.)
     */
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void sinkFillsOnlyAfterFrontArrives(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            var level = helper.getLevel();
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            // Pick the LONGEST tank-to-tank pipe run so the travel delay is clearly visible
            // (the template ships several tanks in separate runs).
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 12; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 12; z++) {
                BlockPos rel = new BlockPos(x, y, z);
                if (helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
            }
            BlockPos source = null;  // node a's tank (relative)
            BlockPos sink = null;    // node b's tank (relative)
            int bestLength = -1;
            for (BlockPos t : tanks) {
                Graph g = GraphBuilder.build(level, helper.absolutePos(t));
                for (Edge e : g.edges()) {
                    if (g.node(e.a()).isHandler() && g.node(e.b()).isHandler()
                            && !e.pipes().isEmpty() && e.length() > bestLength) {
                        bestLength = e.length();
                        source = g.node(e.a()).pos().subtract(origin);
                        sink = g.node(e.b()).pos().subtract(origin);
                    }
                }
            }
            if (source == null) { helper.fail("no tank-to-tank pipe run found"); return; }
            final BlockPos src = source;
            final BlockPos snk = sink;

            for (BlockPos t : tanks) drain(helper, t);
            fill(helper, src, 8000);

            // Early: the front is still crawling the long run, so the transfer has not
            // started — the source is untouched and the sink is empty. (The old engine
            // delivered the instant the circuit solved, draining the source immediately.)
            helper.runAfterDelay(20, () -> {
                if (amount(helper, src) != 8000) {
                    helper.fail("source drained before the front reached the sink: " + amount(helper, src));
                }
                if (amount(helper, snk) != 0) {
                    helper.fail("sink received fluid before the front arrived: " + amount(helper, snk));
                }
            });
            // Late: the front has long since reached the sink, so delivery is underway.
            helper.runAfterDelay(360, () -> {
                if (amount(helper, snk) <= 0) {
                    helper.fail("sink never filled after the front arrived: source="
                            + amount(helper, src) + " sink=" + amount(helper, snk));
                    return;
                }
                helper.succeed();
            });
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
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), flowing);
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
                Set.of(), stalled, noHeadEdges, reasons, Map.of(), true);
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
                Set.of(), Set.of(edgeIndex), Set.of(), reasons, Map.of(), true);
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
