package de.devin.pipesnphysics.gametest.display;

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
 * In-pipe fluid rendering & settle: waterline-matches-tank, backed-up/blocked render.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class RenderingTests {

    /**
     * A moving run really holds its fluid: while the pump transfers, some pipe cell must store
     * water ({@link PipeFluidCell} content — the only thing the client renders), of the right
     * fluid. Verifies the transfer brigade fills the pipes it moves fluid through.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void flowingPipesCarryRenderableFluid(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 1, 1), 8000);

        helper.succeedWhen(() -> {
            Level level = helper.getLevel();
            for (BlockPos rel : new BlockPos[] {new BlockPos(1, 1, 1), new BlockPos(3, 1, 1)}) {
                PipeStore.Store store = PipeStore.at(level, helper.absolutePos(rel));
                if (store == null || store.amount() <= 0) continue;
                if (!FluidStack.isSameFluidSameComponents(store.fluid(), new FluidStack(Fluids.WATER, 1))) {
                    helper.fail("pipe cell stores the wrong fluid: " + store.fluid().getHoverName().getString());
                }
                return;
            }
            helper.fail("no pipe cell stores fluid while the pump is moving water" + dump(helper));
        });
    }

    /**
     * Two equal tanks joined by a submerged pipe equalize to zero net flow, yet the
     * connecting pipe is full of water and must keep rendering it — the render bridge
     * must show resting fluid below the surface, not blank the pipe when flow stops.
     * (Threshold matches {@link #tanksEqualizeAtEqualSurfaces}: travel time delays the
     * start of delivery, so check "mostly settled with the pipe full" rather than dead level.)
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void restingFullPipesStillRenderFluid(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        fill(helper, left, 8000);

        helper.succeedWhen(() -> {
            int a = amount(helper, left);
            int b = amount(helper, right);
            if (Math.abs(a - b) > 800) helper.fail("not equalized yet: " + a + " vs " + b);
            // Settled (near-zero flow) but the U-pipe under the tanks still HOLDS its water —
            // submerged cells below the waterline keep their real content at rest.
            boolean anyWet = false;
            for (int x = 0; x < 4 && !anyWet; x++)
                for (int y = 0; y < 4 && !anyWet; y++)
                    for (int z = 0; z < 2 && !anyWet; z++) {
                        anyWet = cellMb(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z))) > 0;
                    }
            if (!anyWet) helper.fail("equalized pipe lost its stored fluid" + dump(helper, left));
        });
    }

    /**
     * A running pump dead-headed by a solid block on its push side leaves only ONE reservoir on the
     * network (its supply tank). The SUBMERGED pull pipe between the full tank and the pump sits
     * below the tank's surface, so the settle pass must FILL it from the tank — the user's "no
     * fluid in the pipe though the head is there" now means real stored fluid, not a render stamp.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void deadEndedPumpRendersSubmergedSupplyPipe(GameTestHelper helper) {
        BlockPos pullPipe = new BlockPos(1, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 8000);            // full source → pull pipe sits below it
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE); // cap the output: one reservoir left

        helper.succeedWhen(() -> {
            if (cellMb(helper.getLevel(), helper.absolutePos(pullPipe)) <= 0) {
                helper.fail("submerged supply pipe of a dead-headed pump holds no fluid" + dump(helper));
            }
        });
    }

    /**
     * A horizontal run capped by a solid block ends in a dead-end pipe cell — a JUNCTION NODE. With
     * the tank surface above the run, the whole run INCLUDING that terminal cell must fill: the
     * junction cell is a one-cell buffer on its node, and the settle pass tops it up through the
     * adjacent edge cell, so the fluid no longer stops one cell short of the block. Here the pump
     * of the single_pump template is swapped for a plain pipe and the far cell capped, so the run
     * is tank -> pipe -> dead-end pipe -> stone with NO pump.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200, batch = "levelRender")
    public static void deadEndJunctionCellRendersAgainstTheBlock(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 1, 1), 8000);           // full tank -> run sits below its surface
        helper.setBlock(new BlockPos(2, 1, 1), AllBlocks.FLUID_PIPE.get().defaultBlockState()); // pump -> pipe
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE); // cap the run: (2,1,1) becomes a dead-end junction

        helper.runAfterDelay(5, () -> {
            BlockPos deadEnd = new BlockPos(2, 1, 1);
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
            boolean hasJunction = graph.nodes().stream()
                    .anyMatch(n -> n.isJunction() && n.pos().equals(helper.absolutePos(deadEnd)));
            if (!hasJunction) {
                helper.fail("capped run did not classify (2,1,1) as a dead-end JUNCTION" + dump(helper));
                return;
            }
            helper.succeedWhen(() -> {
                if (cellMb(helper.getLevel(), helper.absolutePos(deadEnd)) <= 0) {
                    helper.fail("dead-end junction cell against the block holds no fluid — the run "
                            + "stops one cell short" + dump(helper));
                }
            });
        });
    }

    /**
     * The waterline INSIDE a settled pipe must match the tank it connects to: with the tank half
     * full, the pipe cells at the tank's base settle at the SAME surface elevation — the stored
     * fraction equals the tank surface over the cell, neither painted full nor left dry. The pump
     * is swapped for a plain pipe and the run capped, so it is a pure resting stub beside the tank.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void settledPipeMatchesTankWaterline(GameTestHelper helper) {
        BlockPos tank = new BlockPos(0, 1, 1);
        BlockPos cell = new BlockPos(1, 1, 1);
        helper.setBlock(new BlockPos(2, 1, 1), AllBlocks.FLUID_PIPE.get().defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE);
        fill(helper, tank, 4000);

        helper.succeedWhen(() -> {
            Level level = helper.getLevel();
            int capacity = PipeStore.capacityMb();
            // The tank's current surface over the pipe cell's bottom, both measured from the tank
            // base (tank and run share one Y level in this template).
            double surface = amount(helper, tank) / 8000.0;
            double expected = Math.clamp(surface, 0.0, 1.0);
            double got = cellMb(level, helper.absolutePos(cell)) / (double) capacity;
            if (Math.abs(got - expected) > 0.2) {
                helper.fail("pipe waterline " + got + " does not match the tank surface "
                        + expected + dump(helper, tank));
            }
            if (got <= 0.05 || got >= 0.95) {
                helper.fail("half-full tank left the pipe " + (got <= 0.05 ? "dry" : "painted full")
                        + " (" + got + ")");
            }
        });
    }

    /**
     * A VERTICAL riser cell renders its fluid across the FULL block, where a horizontal cell's
     * fluid sits only in the 6/16 bore. The settle target must map the tank waterline onto that
     * same window per cell, or a settled riser cell whose surface lands off its centre stores (and
     * so renders) at the wrong height — painted FULL when the tank sits high in the cell, dry when
     * low ("the pipe renderer isn't the same height as the tanks next to it"). A tall tank feeds a
     * capped glass riser; the cell straddling the (rendered) waterline must settle to the full-block
     * fraction, not the bore-clamped one (which would read full here). Mutation-verified against the
     * bore-only mapping.
     */
    @GameTest(template = "display/riser_waterline", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void settledVerticalRiserMatchesTankWaterline(GameTestHelper helper) {
        // riser_waterline: a WIDE 2x2x3 tank (x∈{0,1}, z∈{0,1}) feeds a capped glass riser through one
        // elbow at (2,1,0); the y-axis glass riser (2,2,0)–(2,4,0), stone-capped, rises to the tank's
        // rendered waterline and rests — the cell at (2,3,0) straddles it.
        BlockPos tankBottom = new BlockPos(0, 1, 0);
        BlockPos straddle = new BlockPos(2, 3, 0);
        double tankBaseY = 1.0;
        int tankHeight = 3;
        int tankCapacity = 96000;    // 2x2x3 = 12 blocks x 8000
        double cellBottomY = 3.0;    // riser cell block y=3 → bottom face at world 3

        helper.runAfterDelay(5, () -> {
            // Nearly full: the rendered surface sits high in the straddling cell, so full-block gives
            // a partial fill while the bore-only mapping would clamp it FULL.
            fill(helper, tankBottom, 95000);
            if (amount(helper, tankBottom) < 90000) {
                helper.fail("tank did not form a multiblock (holds " + amount(helper, tankBottom) + ")");
                return;
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 0)));
        });
        helper.runAfterDelay(90, () -> {
            int capacity = PipeStore.capacityMb();
            double surface = tankRenderedSurface(tankBaseY, tankHeight, amount(helper, tankBottom), tankCapacity);
            double expected = Math.clamp(surface - cellBottomY, 0.0, 1.0); // vertical cell fills the full block
            double got = cellMb(helper.getLevel(), helper.absolutePos(straddle)) / (double) capacity;
            if (got >= 0.9) {
                helper.fail("vertical riser cell painted FULL (" + got + ") though the tank surface "
                        + surface + " sits at ~" + expected + " of the cell — the settle mapped the "
                        + "waterline onto the 6/16 bore instead of the full block");
                return;
            }
            if (Math.abs(got - expected) > 0.15) {
                helper.fail("vertical riser waterline " + got + " does not match the tank surface "
                        + expected + " (full-block mapping)" + dump(helper, tankBottom));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A settled horizontal pipe's waterline must land ON the connected tank's RENDERED surface, not
     * a fixed nudge above it. The settle used to add {@code SURFACE_EPS} (0.05 block) to the target;
     * divided by the 6/16 bore that is a 13% overshoot, which clamps the pipe FULL when the tank
     * sits near the bore top. Both tanks rest with their rendered surface near the bore top; the
     * pipe between them must stay PARTIAL at that surface, never painted full. Mutation-verified
     * against the nudged target.
     */
    @GameTest(template = "common/wide_tank_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void settledHorizontalPipeMatchesTankWaterline(GameTestHelper helper) {
        // wide_tank_run: two WIDE 2x2x3 tanks (x∈{0,1} and {5,6}) an x-gap apart with a straight run at
        // their bottom row (2,1,1)–(4,1,1); their rendered surface rests near the bore top.
        List<BlockPos> run = List.of(
                new BlockPos(2, 1, 1), new BlockPos(3, 1, 1), new BlockPos(4, 1, 1));

        BlockPos leftTank = new BlockPos(1, 1, 1);
        double tankBaseY = 1.0;                 // tank block y=1 → bottom face at world 1
        int tankHeight = 3;
        int tankCapacity = 96000;               // 2x2x3 = 12 blocks x 8000
        double boreFloorY = 1.0 + (0.5 - 3.0 / 16); // pipe block y=1 → bore floor at world 1.3125
        double boreHeight = 2 * (3.0 / 16);

        // Rendered surface ~1.65 (near the bore top 1.6875): partial, but the old +0.05 nudge crossed
        // the bore top and clamped the pipe FULL.
        helper.runAfterDelay(5, () -> {
            fill(helper, new BlockPos(0, 1, 1), 13300);
            fill(helper, new BlockPos(5, 1, 1), 13300);
            if (amount(helper, leftTank) < 11000) {
                helper.fail("tank did not form a multiblock (holds " + amount(helper, leftTank) + ")");
                return;
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(1)));
        });
        helper.runAfterDelay(90, () -> {
            int capacity = PipeStore.capacityMb();
            double surface = tankRenderedSurface(tankBaseY, tankHeight, amount(helper, leftTank), tankCapacity);
            double expected = Math.clamp((surface - boreFloorY) / boreHeight, 0.0, 1.0);
            for (BlockPos rel : run) {
                double got = cellMb(helper.getLevel(), helper.absolutePos(rel)) / (double) capacity;
                if (got >= 0.98) {
                    helper.fail("horizontal pipe " + rel.toShortString() + " painted FULL (" + got
                            + ") though the tank surface " + surface + " sits at ~" + expected
                            + " of the bore — the settle nudged the target past the bore top");
                    return;
                }
                if (Math.abs(got - expected) > 0.15) {
                    helper.fail("horizontal pipe " + rel.toShortString() + " waterline " + got
                            + " does not match the tank surface " + expected + dump(helper, leftTank));
                    return;
                }
            }
            helper.succeed();
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
    /**
     * A pump pushing into a full sink backs the pipe up with NO flow this tick. The backed-up run
     * (a SINK_FULL stall or a dead-headed NO_HEAD pump) must KEEP its stored fluid — the settle
     * pass may not drain a column a running pump is holding, so when the sink makes room the flow
     * resumes instantly instead of re-priming the whole run.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void backedUpStallKeepsChargedPipe(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            var level = helper.getLevel();
            BlockPos seed = new BlockPos(1, 1, 1); // piping/single_pump: any pipe cell seeds the whole-network graph

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
            Edge run = edge;

            // Both tanks brim-full, so the live engine's settle can't siphon the held column away
            // between the synthetic executor passes below.
            fill(helper, new BlockPos(0, 1, 1), 8000);
            fill(helper, new BlockPos(4, 1, 1), 8000);

            int capacity = PipeStore.capacityMb();
            for (BlockPos cell : run.pipes()) {
                PipeStore.Store store = PipeStore.at(level, cell);
                if (store != null) {
                    store.insert(new FluidStack(Fluids.WATER, capacity), capacity);
                    store.flush();
                }
            }

            for (boolean noHead : new boolean[]{false, true}) {
                for (int i = 0; i < 10; i++) {
                    PipeFlowExecutor.run((ServerLevel) level, graph,
                            pipesnphysics$backedUpSolution(graph, run.index(), noHead));
                }
                for (BlockPos cell : run.pipes()) {
                    if (cellMb(level, cell) < capacity) {
                        helper.fail("backed-up " + (noHead ? "NO_HEAD" : "SINK_FULL")
                                + " stall drained the held column at " + cell.toShortString());
                        return;
                    }
                }
            }
            helper.succeed();
        });
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
        return new Solution(flows, List.of(), List.of(), new int[graph.edges().size()],
                Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), Map.of(),
                Set.of(), stalled, noHeadEdges, Set.of(), reasons, Map.of(), true);
    }

    /**
     * A run BACKED UP from the very first tick — never charged by a travelling front — must still
     * render FULL, not empty. A pump reverse-blocking a full tank (its check valve stops that tank
     * draining out through it) leaves the pipes between them a pressurized column: SINK_FULL /
     * {@code isBackedUp}, yet no flow ever filled them. The renderer must STAMP such a run full, not
     * merely preserve the (nonexistent) prior charge. Reproduces "a reverse pump renders the pipe
     * empty instead of full". Uses long_pipe (tank - 5 pipes - pump - tank); coords shift at
     * placement, so everything is found from the graph in absolute space.
     */
    @GameTest(template = "common/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void bornBackedUpRunRendersFull(GameTestHelper helper) {
        Level level = helper.getLevel();
        List<BlockPos> run = new ArrayList<>();
        helper.runAfterDelay(10, () -> {
            BlockPos seed = helper.absolutePos(new BlockPos(1, 1, 0)); // long_pipe: any pipe cell seeds the whole-network graph
            Graph g = GraphBuilder.build(level, seed);
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (Node n : g.nodes()) { if (n.isPump()) pump = n.pos(); else if (n.isHandler()) tanks.add(n.pos()); }
            if (pump == null || tanks.size() < 2) { helper.fail("layout not found: " + tanks); return; }
            for (Edge e : g.edges()) run.addAll(e.pipes());
            // The tank the pipe RUN reaches (far from the pump) is on the pump's push side — fill it
            // full so the pump's check valve backs the run up against it; empty the pump's supply side.
            BlockPos backedTank = tanks.get(0).distManhattan(pump) >= tanks.get(1).distManhattan(pump)
                    ? tanks.get(0) : tanks.get(1);
            BlockPos supply = backedTank.equals(tanks.get(0)) ? tanks.get(1) : tanks.get(0);
            IFluidHandler bh = pipesnphysics$sideFallback(level, backedTank);
            if (bh != null) bh.fill(new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
            IFluidHandler sh = pipesnphysics$sideFallback(level, supply);
            if (sh != null) sh.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        });
        helper.runAfterDelay(160, () -> {
            if (run.isEmpty()) { helper.fail("no run cells captured"); return; }
            for (BlockPos abs : run) {
                if (cellMb(level, abs) <= 0) {
                    helper.fail("backed-up run cell holds no fluid (never filled from the full tank) at "
                            + helper.relativePos(abs));
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * A run BLOCKED by an unpowered pump, but connected to a tank that STILL HOLDS fluid, must render
     * the settled water sitting in the pipe up to the pump — not blank. The pump-off branch never
     * assembles, so the run gets no rest fluid or heads unless {@code settleBlockedRuns} supplies them
     * from the filled reservoir. Reproduces "a pump on this line is unpowered, and the pipes render
     * empty". The tank on the pump's FAR side is emptied and must stay dry (no phantom water past the
     * block).
     */
    @GameTest(template = "common/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void blockedRunFromFullTankRendersSettled(GameTestHelper helper) {
        Level level = helper.getLevel();
        List<BlockPos> runToFull = new ArrayList<>();
        List<BlockPos> runToEmpty = new ArrayList<>();
        helper.runAfterDelay(10, () -> {
            BlockPos seed = helper.absolutePos(new BlockPos(1, 1, 0)); // long_pipe: any pipe cell seeds the whole-network graph
            Graph g = GraphBuilder.build(level, seed);
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (Node n : g.nodes()) { if (n.isPump()) pump = n.pos(); else if (n.isHandler()) tanks.add(n.pos()); }
            if (pump == null || tanks.size() < 2) { helper.fail("layout not found: " + tanks); return; }
            BlockPos fullTank = tanks.get(0).distManhattan(pump) >= tanks.get(1).distManhattan(pump) ? tanks.get(0) : tanks.get(1);
            BlockPos emptyTank = fullTank.equals(tanks.get(0)) ? tanks.get(1) : tanks.get(0);
            for (Edge e : g.edges()) {
                boolean touchesFull = g.node(e.a()).pos().equals(fullTank) || g.node(e.b()).pos().equals(fullTank);
                (touchesFull ? runToFull : runToEmpty).addAll(e.pipes());
            }
            // Unpower the pump by clearing its kinetic neighbours (motor/cogwheel).
            for (Direction d : Direction.values()) {
                var st = level.getBlockState(pump.relative(d));
                if (!st.is(AllBlocks.FLUID_PIPE.get()) && !st.is(AllBlocks.FLUID_TANK.get()) && !st.isAir()) {
                    level.setBlockAndUpdate(pump.relative(d), Blocks.AIR.defaultBlockState());
                }
            }
            IFluidHandler fh = pipesnphysics$sideFallback(level, fullTank);
            if (fh != null) fh.fill(new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
            IFluidHandler eh = pipesnphysics$sideFallback(level, emptyTank);
            if (eh != null) eh.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        });
        helper.runAfterDelay(160, () -> {
            if (runToFull.isEmpty()) { helper.fail("no run captured"); return; }
            for (BlockPos abs : runToFull) {
                if (cellMb(level, abs) <= 0) {
                    helper.fail("blocked run from the FULL tank holds no fluid at " + helper.relativePos(abs));
                    return;
                }
            }
            // The far run (to the drained tank) must stay dry — no phantom water past the off pump.
            for (BlockPos abs : runToEmpty) {
                if (cellMb(level, abs) > 0) {
                    helper.fail("run to the EMPTY tank wrongly holds water past the off pump at " + helper.relativePos(abs));
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * The GAS twin of {@code settledHorizontalPipeMatchesTankWaterline}: a settled pipe's gas
     * hangs from the bore top down to the connected tank's RENDERED gas interface — the settle's
     * mirrored targets map that interface onto the cell window exactly as the liquid path maps
     * the waterline, so the fluid the player sees is continuous across the tank wall. Two equal
     * 2-tall tanks hold gas whose interface lands dead-centre of the top-row bore; the pipe
     * between them must settle at ~half-hanging — never dry (the old full freeze) and never
     * full. Tolerance matches the liquid twin (the draw band). Skips without a gas fluid.
     */
    @GameTest(template = "gas/low_tank_pair", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void settledGasPipeMatchesTankInterface(GameTestHelper helper) {
        Fluid gas = lighterThanAirFluid();
        if (gas == null) {
            helper.succeed();
            return;
        }
        // low_tank_pair: two 2-tall tanks at x0/x3 joined by the top-row 2-cell run
        BlockPos tankA = new BlockPos(0, 1, 0);
        BlockPos tankB = new BlockPos(3, 1, 0);
        List<BlockPos> run = List.of(new BlockPos(1, 2, 0), new BlockPos(2, 2, 0));

        // 2-tall tank (16000): interface = top − (0.25 + f·1.4375); 2783 mB puts it at rel 2.5 —
        // the exact centre of the top-row bore (2.3125..2.6875) → expected hanging fill ≈ 50%.
        int fillMb = 2783;
        helper.runAfterDelay(10, () -> {
            handler(helper, tankA).fill(new FluidStack(gas, fillMb), IFluidHandler.FluidAction.EXECUTE);
            handler(helper, tankB).fill(new FluidStack(gas, fillMb), IFluidHandler.FluidAction.EXECUTE);
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(0)));
        });
        helper.runAfterDelay(180, () -> {
            int capacity = PipeStore.capacityMb();
            double interfaceY = tankGasInterface(1.0, 2, amount(helper, tankA), 16000);
            double boreTop = 2.0 + 0.5 + 3.0 / 16;
            double expected = Math.clamp((boreTop - interfaceY) / 0.375, 0.0, 1.0);
            for (BlockPos rel : run) {
                double got = cellMb(helper.getLevel(), helper.absolutePos(rel)) / (double) capacity;
                if (got <= 0.02) {
                    helper.fail("settled gas pipe " + rel.toShortString() + " is DRY though the"
                            + " tank interface " + interfaceY + " sits mid-bore (expected ~" + expected + ")");
                    return;
                }
                if (got >= 0.98) {
                    helper.fail("settled gas pipe " + rel.toShortString() + " painted FULL ("
                            + got + ") though the tank interface " + interfaceY
                            + " sits mid-bore (expected ~" + expected + ")");
                    return;
                }
                if (Math.abs(got - expected) > 0.15) {
                    helper.fail("settled gas pipe " + rel.toShortString() + " hangs " + got
                            + " of the bore but the tank interface " + interfaceY
                            + " expects ~" + expected + dump(helper, tankA));
                    return;
                }
            }
            helper.succeed();
        });
    }
}
