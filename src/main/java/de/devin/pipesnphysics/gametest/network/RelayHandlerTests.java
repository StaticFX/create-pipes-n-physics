package de.devin.pipesnphysics.gametest.network;

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
import de.devin.pipesnphysics.api.EndpointApi;
import de.devin.pipesnphysics.api.EndpointFilter;
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
 * Relay detection & handler-role classification, side-specific / per-face endpoints.
 *
 * ---- automatic relay detection (CLAUDE.md §2, RelayDetector / HandlerRoles) ----
 * These drive RelayDetector.observe directly on a placed block: a real relay (a docking connector,
 * a VS hose) needs a second mod, but the learning is block-type + fluid-amount math the detector
 * exposes. Each body runs synchronously (no runAfterDelay), so clearing the detector at the start
 * fully isolates it from its batch siblings. Distinct block types keep the learned sets disjoint.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class RelayHandlerTests {

    /**
     * A handler whose stored fluid keeps GROWING on its own — with no fill from the engine — is the
     * relay signature: learned as a relay and demoted to receive-only, so the solver stops draining and
     * equalizing it as a tank.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayDetectorLearnsSpontaneousGain(GameTestHelper helper) {
        RelayDetector.clear();
        Level level = helper.getLevel();
        // Blocks.STONE stands in for an unknown mod's relay: non-exempt and untagged.
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.STONE);
        for (int amount = 100; amount <= 700; amount += 100) {
            RelayDetector.observe(level, pos, Fluids.WATER, amount); // +100 each step, no fill from us
        }
        if (!RelayDetector.isRelay(pos)) {
            helper.fail("a block that gained fluid on its own every tick was not learned as a relay");
            return;
        }
        if (!HandlerRoles.isRelayEndpoint(level, pos)) {
            helper.fail("a learned relay is not treated as a drain-priority relay endpoint");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * Relay learning is PER POSITION, never per block type: one type can carry role-diverse
     * instances — every level of a diesel-generators distillation tower is the same block, the
     * bottom a fillable crude INPUT (spontaneously losing to the recipe), the levels above
     * spontaneously GAINING products. Type-keyed strikes let the product levels demote the whole
     * type, and the input then resolved as a brimming one-way SOURCE refusing every fill until a
     * restart ("why can't we pump into here anymore?" report). Two instances of one block: the
     * gaining one is demoted, the losing one must stay a fillable capacitor — and breaking the
     * demoted one forgets its learned role.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayLearningStaysPerInstance(GameTestHelper helper) {
        RelayDetector.clear();
        Level level = helper.getLevel();
        BlockPos productRel = new BlockPos(1, 2, 1);
        BlockPos inputRel = new BlockPos(3, 2, 1);
        BlockPos product = helper.absolutePos(productRel);
        BlockPos input = helper.absolutePos(inputRel);
        helper.setBlock(productRel, Blocks.STONE); // one block TYPE, two roles (a distillation tower)
        helper.setBlock(inputRel, Blocks.STONE);
        for (int step = 0; step < 8; step++) {
            RelayDetector.observe(level, product, Fluids.WATER, 100 + step * 100); // gains: a product level
            RelayDetector.observe(level, input, Fluids.WATER, 1000 - step * 100);  // loses: the crude input
        }
        if (!RelayDetector.isRelay(product)) {
            helper.fail("the spontaneously gaining instance was not learned as a relay");
            return;
        }
        if (RelayDetector.isRelay(input) || HandlerRoles.isRelayEndpoint(level, input)) {
            helper.fail("the consuming INPUT instance was demoted with its block type — it would"
                    + " refuse every fill as a one-way source (the distillation-tower report)");
            return;
        }
        RelayDetector.forget(product);
        if (RelayDetector.isRelay(product)) {
            helper.fail("a broken relay position kept its learned role");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * A handler that spontaneously LOSES fluid is a consumer (a basin, a boiler), not a relay — it must
     * keep receiving fluid and must never be demoted.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayDetectorSparesConsumers(GameTestHelper helper) {
        RelayDetector.clear();
        Level level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.DIRT);
        for (int amount = 1000; amount >= 200; amount -= 100) {
            RelayDetector.observe(level, pos, Fluids.WATER, amount); // spontaneously LOSING = a consumer
        }
        if (RelayDetector.isRelay(pos)) {
            helper.fail("a block that only lost fluid (a consumer) was wrongly demoted to a relay");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * A relay_endpoint-tagged handler (the create-aeronautics docking connector, loaded from run/mods)
     * resolves to a drain-priority BOTTOMLESS column, NOT a finite reservoir — so the solver never holds
     * it "balanced" and refuses to drain it (the equalization stall that stopped fluid crossing a docked
     * connector). Skips if the mod is absent.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayEndpointResolvesBottomless(GameTestHelper helper) {
        Level level = helper.getLevel();
        Block connector = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("simulated", "docking_connector"));
        if (connector == Blocks.AIR) { helper.succeed(); return; } // aeronautics not installed
        BlockPos rel = new BlockPos(1, 2, 1);
        helper.setBlock(rel, connector);
        BlockPos pos = helper.absolutePos(rel);
        if (!HandlerRoles.isRelayEndpoint(level, pos)) {
            helper.fail("docking connector is not classified as a relay endpoint (tag not applied)");
            return;
        }
        BoundaryColumn column = BoundaryColumn.resolve(level,
                new Node(0, pos, Node.Kind.HANDLER, pos.getY() + 0.5, null, null, null));
        if (column == null) { helper.succeed(); return; } // no live cap on a lone connector — nothing to assert
        if (column.isFiniteReservoir()) {
            helper.fail("relay endpoint resolved as a finite reservoir — it would surface-equalize and stall");
            return;
        }
        helper.succeed();
    }

    /**
     * A foreign capability provider that THROWS on a legal query must degrade to "no handler on
     * that side", never crash the server tick: TFMG's blast stove NPEs on the side-agnostic
     * (null-side) lookup — its lambda dereferences the side — and the graph BFS runs inside the
     * tick. The {@link TestSideHandlers} OBSIDIAN fixture reproduces that shape; {@code FluidCaps}
     * swallows the throw, so the null side reads as absent and the block joins the network as an
     * ordinary SIDE-SPECIFIC handler through the face the pipe meets.
     */
    @GameTest(template = "network/obsidian_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void throwingCapabilityProviderDegradesToSideSpecific(GameTestHelper helper) {
        TestSideHandlers.clear();
        var level = helper.getLevel();
        // tank(2,1,1)—pipe(3)—pipe(4)—OBSIDIAN(5): the obsidian provider throws on the null-side query
        BlockPos stoveRel = new BlockPos(5, 1, 1);

        BlockPos stove = helper.absolutePos(stoveRel);
        if (FluidCaps.at(level, stove, null) != null) {
            helper.fail("the throwing provider's null-side query did not degrade to no-handler");
            return;
        }
        Graph graph = GraphBuilder.build(level, helper.absolutePos(new BlockPos(3, 1, 1)));
        Node node = graph.nodeAt(stove);
        if (node == null || !node.isHandler()) {
            helper.fail("the throwing-provider block did not join the network as a handler node");
            return;
        }
        if (node.accessFace() != Direction.WEST) {
            helper.fail("expected the side-specific access face WEST, got " + node.accessFace());
            return;
        }
        TestSideHandlers.clear();
        helper.succeed();
    }

    /**
     * A machine that EXTENDS Create's FluidTankBlockEntity but serves per-face ports (TFMG's blast
     * stove: vertical faces = one combined air/blast wrapper, base-row horizontal faces = the
     * furnace-gas wrapper, null side throws) must NOT ride the Create-tank resolve path — that
     * models the internal gauge inventory, drops the access face, and reads tanks the plumbed port
     * cannot reach. It classifies side-specific (accessFace set) and resolves through the FACE
     * wrapper (the generic path carries the face; resolveTank never does). Uses the real TFMG
     * block; skips when the mod is absent.
     */
    @GameTest(template = "network/stove_port", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void tankDerivedPerPortMachineResolvesThroughItsFace(GameTestHelper helper) {
        Block stoveBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("tfmg", "blast_stove"));
        if (stoveBlock == Blocks.AIR) { helper.succeed(); return; } // TFMG not installed
        var level = helper.getLevel();
        // stove_port bakes the pipe(2,1,1) (UP→stove, SOUTH→tank) and tank(2,1,2); the mod block
        // itself stays a runtime, conditional placement (the NBT must not reference a maybe-absent mod).
        BlockPos stoveRel = new BlockPos(2, 2, 1);
        BlockPos pipeRel = new BlockPos(2, 1, 1);
        helper.setBlock(stoveRel, stoveBlock);

        helper.runAfterDelay(10, () -> { // let the stove's multiblock/connectivity settle
            Graph graph = GraphBuilder.build(level, helper.absolutePos(pipeRel));
            Node stove = graph.nodeAt(helper.absolutePos(stoveRel));
            if (stove == null || !stove.isHandler()) {
                helper.fail("blast stove did not join the network as a handler node");
                return;
            }
            if (stove.accessFace() != Direction.DOWN) {
                helper.fail("blast stove was not classified side-specific through its bottom port"
                        + " (accessFace=" + stove.accessFace() + ")");
                return;
            }
            BoundaryColumn column = BoundaryColumn.resolve(level, stove);
            if (column == null) {
                helper.fail("blast stove resolved no column through its bottom port");
                return;
            }
            if (column.accessFace() != Direction.DOWN) {
                helper.fail("blast stove column dropped the access face (accessFace="
                        + column.accessFace() + ") — it rode the Create-tank resolve path");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A SIDE-SPECIFIC handler (the dev-only {@link TestSideHandlers} on a sponge: a different tank per
     * face, no null-side handler) resolves each face to ITS OWN fluid — the core of the per-face
     * endpoint feature, and the thing no real pack block can exercise. NORTH holds water, SOUTH holds
     * lava, and resolving through each face returns the matching fluid.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void sideSpecificHandlerResolvesPerFace(GameTestHelper helper) {
        TestSideHandlers.clear();
        Level level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.SPONGE);
        TestSideHandlers.tankAt(pos, Direction.NORTH).fill(
                new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
        TestSideHandlers.tankAt(pos, Direction.SOUTH).fill(
                new FluidStack(Fluids.LAVA, 8000), IFluidHandler.FluidAction.EXECUTE);
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null) {
            helper.fail("test fixture is not side-specific (it exposes a null-side handler)");
            return;
        }
        double y = pos.getY() + 0.5;
        BoundaryColumn north = BoundaryColumn.resolve(level,
                new Node(0, pos, Node.Kind.HANDLER, y, null, null, Direction.NORTH));
        BoundaryColumn south = BoundaryColumn.resolve(level,
                new Node(0, pos, Node.Kind.HANDLER, y, null, null, Direction.SOUTH));
        if (north == null || north.contents().getFluid() != Fluids.WATER) {
            helper.fail("NORTH face did not resolve to water: "
                    + (north == null ? "null column" : north.contents().getFluid()));
            return;
        }
        if (south == null || south.contents().getFluid() != Fluids.LAVA) {
            helper.fail("SOUTH face did not resolve to lava — the access face is ignored in resolution");
            return;
        }
        TestSideHandlers.clear();
        helper.succeed();
    }

    /**
     * A side-specific handler is NOT coupled across faces: the pipe on each face lands in its own
     * network, reaching the block through its own face ({@link Node#accessFace}). Confirms the
     * coupling-skip (the south pipe never leaks into the north network) and the recorded face.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void sideSpecificHandlerSplitsNetworksPerFace(GameTestHelper helper) {
        TestSideHandlers.clear();
        Level level = helper.getLevel();
        BlockPos spongeRel = new BlockPos(1, 2, 1);
        BlockPos spongePos = helper.absolutePos(spongeRel);
        helper.setBlock(spongeRel, Blocks.SPONGE);
        TestSideHandlers.tankAt(spongePos, Direction.NORTH).fill(
                new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
        TestSideHandlers.tankAt(spongePos, Direction.SOUTH).fill(
                new FluidStack(Fluids.LAVA, 8000), IFluidHandler.FluidAction.EXECUTE);
        helper.setBlock(spongeRel.north(), AllBlocks.FLUID_PIPE.get());
        helper.setBlock(spongeRel.south(), AllBlocks.FLUID_PIPE.get());
        BlockPos southPipe = helper.absolutePos(spongeRel.south());
        Graph northGraph = GraphBuilder.build(level, helper.absolutePos(spongeRel.north()));
        Node sponge = northGraph.nodes().stream()
                .filter(n -> n.isHandler() && n.pos().equals(spongePos)).findFirst().orElse(null);
        if (sponge == null) {
            helper.fail("side-specific sponge was not discovered as a handler node from the north pipe");
            return;
        }
        if (sponge.accessFace() != Direction.NORTH) {
            helper.fail("sponge reached from the north pipe recorded accessFace " + sponge.accessFace()
                    + " (expected NORTH)");
            return;
        }
        if (northGraph.coverage().contains(southPipe)) {
            helper.fail("side-specific handler coupled its faces — the south pipe leaked into the north network");
            return;
        }
        TestSideHandlers.clear();
        helper.succeed();
    }

    /**
     * A block that DOES expose a null-side handler but hands back a DIFFERENT handler on one face is still
     * side-specific — the shape of TFMG's coke oven (creosote on the null side + non-top faces, CO2 on the
     * top). The old {@code sideAgnostic = (null cap exists)} test coupled it and read the null side, so a
     * pump on top of a coke oven saw the empty creosote tank and never pulled the CO2. With the identity
     * discriminator the top pipe records {@code accessFace = UP} and the node resolves the SECONDARY (CO2)
     * tank, not the null-side PRIMARY (creosote) one. The dev-only {@link TestSideHandlers} wet-sponge
     * reproduces the exact capability shape.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void perFaceHandlerResolvesTopDespiteNullCap(GameTestHelper helper) {
        TestSideHandlers.clear();
        Level level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.WET_SPONGE);
        TestSideHandlers.primaryAt(pos).fill(     // creosote — the null side + non-top faces
                new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
        TestSideHandlers.secondaryAt(pos).fill(   // CO2 — the top face only
                new FluidStack(Fluids.LAVA, 8000), IFluidHandler.FluidAction.EXECUTE);
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) == null) {
            helper.fail("test fixture must expose a null-side handler (the coke-oven shape)");
            return;
        }
        helper.setBlock(rel.above(), AllBlocks.FLUID_PIPE.get());
        Graph graph = GraphBuilder.build(level, helper.absolutePos(rel.above()));
        Node node = graph.nodes().stream()
                .filter(n -> n.isHandler() && n.pos().equals(pos)).findFirst().orElse(null);
        if (node == null) {
            helper.fail("coke-oven-shaped block was not discovered as a handler node from the top pipe");
            return;
        }
        if (node.accessFace() != Direction.UP) {
            helper.fail("top pipe recorded accessFace " + node.accessFace()
                    + " (expected UP — the block was coupled via its null side instead of read per-face)");
            return;
        }
        BoundaryColumn column = BoundaryColumn.resolve(level, node);
        if (column == null || column.contents().getFluid() != Fluids.LAVA) {
            helper.fail("top face resolved to " + (column == null ? "null column" : column.contents().getFluid())
                    + " — expected the SECONDARY (top) tank, not the null-side PRIMARY");
            return;
        }
        TestSideHandlers.clear();
        helper.succeed();
    }

    /**
     * A side-specific block that is ALSO a relay (a machine that PRODUCES a fluid on one face and is
     * demoted to a bottomless one-way source — TFMG's coke oven, learned by the {@link RelayDetector}
     * because it spontaneously gains CO2) must still drain through its ACCESS FACE. {@code relayEndpoint}
     * used to build its column without the face, so the contents resolved through the correct handler but
     * {@code handler(level)} later hit the empty null side — solved flow, no transfer, a SOURCE_DRY stall
     * ("can pull the fluid but can't push it anywhere"). Here the WET_SPONGE fixture (LAVA on top,
     * WATER on null+sides) is forced to the relay role: the column must resolve the top LAVA AND keep
     * {@code accessFace=UP} so a real drain through {@code handler(level)} yields LAVA, not the null WATER.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relaySideSpecificDrainsThroughAccessFace(GameTestHelper helper) {
        TestSideHandlers.clear();
        FluidHandlerApi.setRole(Blocks.WET_SPONGE, FluidHandlerRole.RELAY);
        try {
            Level level = helper.getLevel();
            BlockPos rel = new BlockPos(1, 2, 1);
            BlockPos pos = helper.absolutePos(rel);
            helper.setBlock(rel, Blocks.WET_SPONGE);
            TestSideHandlers.primaryAt(pos).fill(     // null side + non-top faces
                    new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
            TestSideHandlers.secondaryAt(pos).fill(   // top face — the produced fluid
                    new FluidStack(Fluids.LAVA, 8000), IFluidHandler.FluidAction.EXECUTE);
            Node node = new Node(0, pos, Node.Kind.HANDLER, pos.getY() + 0.5, null, null, Direction.UP);
            BoundaryColumn column = BoundaryColumn.resolve(level, node);
            if (column == null || column.contents().getFluid() != Fluids.LAVA) {
                helper.fail("relay column did not resolve the top (secondary) fluid: "
                        + (column == null ? "null" : column.contents().getFluid()));
                return;
            }
            if (column.accessFace() != Direction.UP) {
                helper.fail("relay column dropped its access face (was " + column.accessFace() + ")");
                return;
            }
            FluidStack drained = BoundaryColumn.drainMatching(column.handler(level),
                    new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.SIMULATE);
            if (drained.isEmpty() || drained.getFluid() != Fluids.LAVA) {
                helper.fail("relay handler(level) drained the null side, not the top — the SOURCE_DRY bug ("
                        + (drained.isEmpty() ? "empty" : drained.getFluid()) + ")");
                return;
            }
        } finally {
            FluidHandlerApi.clearRole(Blocks.WET_SPONGE);
            TestSideHandlers.clear();
        }
        helper.succeed();
    }

    /**
     * Create's own tanks are exempt: one legitimately fed by a second network from another side reads
     * as an external gain, so the detector must never demote a real reservoir type.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayDetectorExemptsCreateTanks(GameTestHelper helper) {
        RelayDetector.clear();
        Level level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, AllBlocks.FLUID_TANK.get());
        for (int amount = 100; amount <= 900; amount += 100) {
            RelayDetector.observe(level, pos, Fluids.WATER, amount); // gains, but a Create tank is exempt
        }
        if (RelayDetector.isRelay(pos)) {
            helper.fail("a Create fluid tank was demoted to a relay despite the exemption");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * A broken pipe's spill deposit is fluid WE moved: spillIntoNeighbors must report it to the
     * detector like every other engine fill, or the receiving handler reads a spontaneous gain on
     * its next observation and strikes toward relay demotion — enough break-spills beside one
     * machine would demote it to a bottomless relay for the session.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void breakSpillDoesNotStrikeTheReceivingHandler(GameTestHelper helper) {
        RelayDetector.clear();
        ServerLevel level = helper.getLevel();
        BlockPos spongeRel = new BlockPos(2, 3, 1); // above the template, so only the sponge can accept
        BlockPos sponge = helper.absolutePos(spongeRel);
        helper.setBlock(spongeRel, Blocks.SPONGE);
        RelayDetector.observe(level, sponge, Fluids.WATER, 0); // tracked, like any network handler

        int remaining = NetworkEditHandler.spillIntoNeighbors(
                level, helper.absolutePos(new BlockPos(1, 3, 1)), new FluidStack(Fluids.WATER, 200));
        if (remaining != 0 || TestSideHandlers.tankAt(sponge, Direction.WEST).getFluidAmount() != 200) {
            helper.fail("the spill did not deposit into the side handler the rig expects");
            return;
        }
        RelayDetector.observe(level, sponge, Fluids.WATER, 200);
        if (RelayDetector.strikeCount(sponge) != 0) {
            helper.fail("a break-spill deposit read as a spontaneous gain and struck the receiver");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * Samples and strikes are dropped for positions nothing observes anymore — a handler that
     * leaves the network without its block breaking (a severed pipe, a disassembled contraption)
     * fires no forget() and would otherwise sit in the detector maps forever. Learned relays are
     * deliberately sticky and survive the sweep.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void sweepDropsUnobservedStrikesButKeepsLearnedRelays(GameTestHelper helper) {
        RelayDetector.clear();
        Level level = helper.getLevel();
        BlockPos struck = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos learned = helper.absolutePos(new BlockPos(3, 2, 1));
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 2, 1), Blocks.STONE);
        RelayDetector.observe(level, struck, Fluids.WATER, 100);
        RelayDetector.observe(level, struck, Fluids.WATER, 300); // one unexplained gain = one strike
        for (int amount = 100; amount <= 700; amount += 100) {
            RelayDetector.observe(level, learned, Fluids.WATER, amount); // demoted to a relay
        }
        if (RelayDetector.strikeCount(struck) != 1 || !RelayDetector.isRelay(learned)) {
            helper.fail("rig failed to set up one struck and one learned position");
            return;
        }
        RelayDetector.sweep(level.getGameTime() + 100_000);
        if (RelayDetector.strikeCount(struck) != 0) {
            helper.fail("an unobserved position kept its strikes past the stale window");
            return;
        }
        if (!RelayDetector.isRelay(learned)) {
            helper.fail("the sweep dropped a learned relay — the learned set must stay sticky");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * An {@link EndpointApi} filter takes an endpoint out of the network for one fluid: the vetoed
     * tank must neither RECEIVE nor GIVE, so its contents stand exactly where they were while its
     * twin — which would otherwise equalize with it, as {@code tanksEqualizeAtEqualSurfaces} proves
     * on this same rig — drains into the run below on its own.
     *
     * Holding fluid in the vetoed tank is what makes the assert cover BOTH enforcement points at
     * once: without the solve's participation veto it GAINS (the pass equalizes the surfaces), and
     * without the boundary veto in {@code Reservoir} it LOSES (the settle draws every wet end into
     * the run below it, with no solved flow at all to gate).
     *
     * This is the supported hook that replaces mixing into the engine's own participation test —
     * the coupling that crashed a server on update (issue #80: an addon's mixin still referenced
     * {@code engine.BoundaryColumn}, which moved package in 3.0.0, so the class failed to attach at
     * classload and took the tick down with it). The filter is scoped to ONE position, so even a
     * leaked registration cannot reach another test's rig.
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 300, batch = "endpointFilter")
    public static void endpointFilterKeepsATankOutOfTheNetwork(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        BlockPos vetoed = helper.absolutePos(right);
        EndpointFilter filter = (level, endpoint, fluid) -> !endpoint.equals(vetoed);
        EndpointApi.registerFilter(filter);
        fill(helper, left, 8000);
        fill(helper, right, 4000);

        helper.runAtTickTime(200, () -> {
            try {
                int held = amount(helper, right);
                if (held != 4000) {
                    helper.fail("a vetoed endpoint " + (held > 4000 ? "received" : "gave up")
                            + " fluid: " + held + "/4000 mB left"
                            + dump(helper, new BlockPos(1, 1, 0)));
                    return;
                }
                // Nor may the SOLVE plan a flow the boundary then refuses: that is a permanent
                // stall — scrolling pipes with nothing moving — which the amount alone cannot see.
                Graph graph = GraphBuilder.build(
                        helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 0)));
                if (FlowSolver.solve(helper.getLevel(), graph).active()) {
                    helper.fail("the solve planned flow through a vetoed endpoint"
                            + dump(helper, new BlockPos(1, 1, 0)));
                    return;
                }
                helper.succeed();
            } finally {
                EndpointApi.removeFilter(filter);
            }
        });
    }
}
