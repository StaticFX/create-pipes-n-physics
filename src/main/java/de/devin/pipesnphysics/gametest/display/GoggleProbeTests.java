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
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import de.devin.pipesnphysics.display.PipeDisplayMetric;
import de.devin.pipesnphysics.display.PnpDisplaySources;
import de.devin.pipesnphysics.display.TankContentsDisplaySource;
import de.devin.pipesnphysics.display.TankDisplayMetric;
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
import de.devin.pipesnphysics.engine.FlowTrace;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.net.PumpRangePayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.probe.PumpRangeProbe;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.valve.ValveCharacteristic;
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import de.devin.pipesnphysics.handler.NetworkEditHandler;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.MutableComponent;
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
 * Goggle/overlay readouts: actual rate, suction margin, reach line, resting fluid.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class GoggleProbeTests {

    /**
     * The goggle's flow number must be the fluid ACTUALLY moved, not the solver's hydraulic
     * flow (which the lip / max-flow caps — or an unprimed pipe — hold below). The executor
     * records the real per-edge movement into {@code Solution.actualFlow}; a recorded 37 mB on
     * an edge whose hydraulic flow is 200 must report 37, so a near-empty source no longer
     * reads a brisk flow while only a trickle leaves the tank.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void gogglePipeRateReflectsActualTransfer(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            BlockPos seed = new BlockPos(1, 1, 1); // piping/single_pump: any pipe cell seeds the whole-network graph

            Graph g = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            Edge edge = null;
            for (Edge e : g.edges()) {
                if (!e.pipes().isEmpty()) { edge = e; break; }
            }
            if (edge == null) { helper.fail("no edge with pipes"); return; }

            List<EdgeFlow> flows = new ArrayList<>();
            for (Edge e : g.edges()) {
                flows.add(e.index() == edge.index()
                        ? new EdgeFlow(edge.index(), EdgeFlow.Direction.A_TO_B, 200)
                        : EdgeFlow.none(e.index()));
            }
            int[] actualFlow = new int[g.edges().size()];
            actualFlow[edge.index()] = 37;
            Solution sol = new Solution(flows, List.of(), List.of(), actualFlow,
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), true);

            int actual = PipeProbe.actualEdgeFlow(g, sol, edge);
            if (actual != 37) {
                helper.fail("actualEdgeFlow=" + actual + " — expected the recorded 37 mB, "
                        + "not the hydraulic 200");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The air-break (suction) margin is a LIFT diagnostic; a SETTLED run isn't lifting anything, so
     * it must report NONE — even when the pipe rests a hair above its SOLVED waterline (which, after
     * the Create-tank render inset, sits BELOW the visible fill, so the cell is actually submerged).
     * Two balanced 3-tall tanks feed a resting run at their bottom row, whose cell centres sit just
     * above the low waterline; the goggle must not warn of an air break there (the reported "why does
     * this settled pipe show air break 7.88?"). Mirrors the reach-line suppression on NO_FLOW.
     */
    @GameTest(template = "display/settled_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 160)
    public static void settledRunReportsNoSuctionMargin(GameTestHelper helper) {
        // settled_run: two balanced 3-tall tanks (columns at x=0 and x=4) feed a resting run at their
        // bottom row (1,1,0)–(3,1,0), cell centres just above the low waterline.
        List<BlockPos> run = List.of(new BlockPos(1, 1, 0), new BlockPos(2, 1, 0), new BlockPos(3, 1, 0));
        helper.runAfterDelay(5, () -> {
            // Equal fills → nothing flows; ~12.5% of a 3-tall tank puts the solved waterline at 1.375,
            // just below the run's cell centres (1.5) — a mild solved suction the old readout warned on.
            fill(helper, new BlockPos(0, 1, 0), 3000);
            fill(helper, new BlockPos(4, 1, 0), 3000);
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(1)));
        });
        helper.runAfterDelay(60, () -> {
            PipeStatusPayload probe = PipeProbe.probe(helper.getLevel(), helper.absolutePos(run.get(1)));
            if (probe.status() != PipeStatusPayload.STATUS_NO_FLOW) {
                helper.fail("run should be settled (NO_FLOW), got status " + probe.status() + dump(helper));
                return;
            }
            if (probe.hasSuctionMargin()) {
                helper.fail("a settled run reported an air-break margin of " + probe.suctionMarginBlocks()
                        + " — nothing is lifting, so it must be suppressed" + dump(helper));
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
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
            BlockPos probeCell = pipeCell;

            // Poll: the run first PRIMES with real fluid (reads as flow), then settles.
            helper.succeedWhen(() -> {
                PipeStatusPayload settled = PipeProbe.probe(helper.getLevel(), probeCell);
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
                        true, 1f, true, 3f, 5f, PipeStatusPayload.DETAIL_NONE, false, 0, false, 0, 0, 0);
                if (!PipeStatusText.showsReach(flowing)) {
                    helper.fail("a flowing pipe with headroom must still show the reach line");
                }
            });
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
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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

                // Poll: the settle pass needs a few ticks to draw the submerged cell's fill in.
                BlockPos dry = highest;
                BlockPos wet = lowest;
                helper.succeedWhen(() -> {
                    PipeStatusPayload top = PipeProbe.probe(level, dry);
                    PipeStatusPayload bottom = PipeProbe.probe(level, wet);
                    if (!top.fluid().isEmpty()) {
                        helper.fail("dry riser cell above the surface still reports fluid — the goggle "
                                + "would call an empty pipe 'settled, levels balanced'");
                    }
                    if (bottom.fluid().isEmpty()) {
                        helper.fail("submerged riser cell below the surface lost its resting fluid");
                    }
                });
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
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
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
                return;
            }
            // The display SUMMARY of a non-flowing pipe carries its held volume — a board
            // watching a settling run shows the fluid it still holds, not a bare "Idle".
            if (payload.holdsMb() <= 0) { helper.fail("settled full pipe reports holds=0"); return; }
            PipeDisplayMetric.Readout readout = new PipeDisplayMetric.Readout(payload, 0, 0);
            String summary = PipeDisplayMetric.SUMMARY.format(readout).getString();
            if (!summary.contains(LangNumberFormat.format(payload.holdsMb()))) {
                helper.fail("idle pipe summary hides the held volume: '" + summary + "'");
                return;
            }
            String fillLine = PipeDisplayMetric.FILL.format(readout).getString();
            if (!fillLine.startsWith(LangNumberFormat.format(payload.holdsMb()))) {
                helper.fail("pipe fill metric did not reflect the stored volume: '" + fillLine + "'");
            }
        });
    }

    /**
     * A display link reads a pipe network cell through the same server-side {@link PipeProbe} the
     * goggle uses, and every metric folds that into one non-empty line. Locks the source wiring:
     * probing a spun-up pump yields the pump-curve cap/lift, FLOW reflects the probed rate, and no
     * metric (pipe or pump) throws or renders blank. The link BE / GUI are Create's — verify those
     * visually in-game.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void displaySourcesReportPipeAndPumpMetrics(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        fill(helper, source, 8000);
        helper.runAfterDelay(20, () -> { // let the kinetics spin the pump up
            ServerLevel level = helper.getLevel();
            // single_pump: pinned from NBT — pump at (2,1,1), a downstream pipe cell at (3,1,1)
            BlockPos pumpRel = new BlockPos(2, 1, 1);
            BlockPos pipeRel = new BlockPos(3, 1, 1);

            BlockPos pumpAbs = helper.absolutePos(pumpRel);
            float speed = level.getBlockEntity(pumpAbs) instanceof KineticBlockEntity k ? Math.abs(k.getSpeed()) : 0f;
            if (speed <= 0.01f) { helper.fail("pump is not spinning"); return; }
            double cap = speed * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
            double canLift = speed * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();

            PipeStatusPayload pumpData = PipeProbe.probe(level, pumpAbs);
            PipeDisplayMetric.Readout pump = new PipeDisplayMetric.Readout(pumpData, cap, canLift);
            String capText = PipeDisplayMetric.CAPACITY.format(pump).getString();
            if (cap <= 0 || !capText.startsWith(LangNumberFormat.format(cap))) {
                helper.fail("pump capacity metric did not reflect the curve cap: " + capText);
                return;
            }
            if (!PipeDisplayMetric.FLOW.format(pump).getString().startsWith(LangNumberFormat.format(pumpData.mbPerTick()))) {
                helper.fail("pump flow metric did not reflect the probed rate");
                return;
            }
            for (PipeDisplayMetric m : PipeDisplayMetric.PUMP_METRICS)
                if (m.format(pump).getString().isEmpty()) { helper.fail("blank pump metric: " + m); return; }

            PipeStatusPayload pipeData = PipeProbe.probe(level, helper.absolutePos(pipeRel));
            PipeDisplayMetric.Readout pipe = new PipeDisplayMetric.Readout(pipeData, 0, 0);
            for (PipeDisplayMetric m : PipeDisplayMetric.PIPE_METRICS)
                if (m.format(pipe).getString().isEmpty()) { helper.fail("blank pipe metric: " + m); return; }

            helper.succeed();
        });
    }

    /**
     * A display link glued to ANY cell of a multiblock tank reads the WHOLE vessel: the
     * fluid capability resolves through the controller, so amount and capacity sum both
     * cells (4,000 of the 2-cell 16,000 = 25%), and the link's stored Metric index routes
     * to the selected readout through the real {@code provideText} path. The link GUI and
     * board rendering are Create's — verify those visually in-game.
     */
    @GameTest(template = "display/tank_with_link", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void tankDisplaySourceReadsTheMultiblockTotal(GameTestHelper helper) {
        // tank_with_link: a 2-tall tank with a display link glued on top (faces up = reads below)
        BlockPos lower = new BlockPos(0, 1, 0);
        BlockPos link = new BlockPos(0, 3, 0);
        helper.runAfterDelay(20, () -> { // let the two cells merge into one vessel
            fill(helper, lower, 4000);
            if (!(helper.getBlockEntity(link) instanceof DisplayLinkBlockEntity linkBe)) {
                helper.fail("no display link block entity");
                return;
            }
            DisplayLinkContext context = new DisplayLinkContext(helper.getLevel(), linkBe);
            DisplayTargetStats stats = new DisplayTargetStats(1, 32, null);
            TankContentsDisplaySource source = PnpDisplaySources.TANK.get();

            linkBe.getSourceConfig().putInt("Metric", TankDisplayMetric.METRICS.indexOf(TankDisplayMetric.FILL));
            String fillLine = line(source.provideText(context, stats));
            if (!"25%".equals(fillLine)) {
                helper.fail("fill metric through the upper cell read '" + fillLine + "', expected 25%");
                return;
            }
            linkBe.getSourceConfig().putInt("Metric", TankDisplayMetric.METRICS.indexOf(TankDisplayMetric.FLUID));
            String fluidLine = line(source.provideText(context, stats));
            String water = new FluidStack(Fluids.WATER, 1).getHoverName().getString();
            if (!water.equals(fluidLine)) {
                helper.fail("fluid metric read '" + fluidLine + "', expected '" + water + "'");
                return;
            }
            helper.succeed();
        });
    }

    private static String line(List<MutableComponent> lines) {
        return lines.isEmpty() ? "" : lines.get(0).getString();
    }

    /**
     * A separation rig's DRY basin must read "pump can't pull its supply", not "a valve or
     * filter is shut here": with the basin empty and both tanks holding their separated fluids,
     * each suction pipe carries a VALVE flag only because the OTHER fluid's pass was rejected by
     * its filter — a wall with nothing to stop (dry edge, empty far reservoir), which used to
     * outrank the starved-pump story on the goggle and mask it in /pipegraph ("it shows valve
     * shut, but in reality the source is dry"). A BINDING filter — fluid really standing behind
     * it — keeps the VALVE message (valveHasSomethingToStop stays conservative).
     */
    @GameTest(template = "common/multi_fluid_basin", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void dryBasinReadsStarvedPumpNotForeignFilterWall(GameTestHelper helper) {
        BlockPos waterFilterCell = new BlockPos(1, 1, 3); // the water line's smart pipe at the basin
        fill(helper, new BlockPos(3, 1, 3), 2000);        // water tank — its pass flags the MILK line
        fillFluid(helper, new BlockPos(0, 1, 0), NeoForgeMod.MILK.value(), 2000); // milk tank → flags the WATER line
        helper.runAfterDelay(25, () -> {
            PipeStatusPayload probe = PipeProbe.probe(helper.getLevel(), helper.absolutePos(waterFilterCell));
            if (probe.status() != PipeStatusPayload.STATUS_NO_FLOW
                    || probe.statusDetail() != PipeStatusPayload.DETAIL_PUMP_STARVED) {
                helper.fail("a dry basin's suction pipe should read the starved-pump story, got"
                        + " status=" + probe.status() + " detail=" + probe.statusDetail()
                        + " (a foreign fluid's filter wall outranked it)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A CREST-gated run that never rises must not read "air break over the crest": a nearly-empty
     * TANK beside a same-level pump stalls because its rendered fluid stands below the pipe's
     * APERTURE (500 of 8000 mB renders 0.34 up the block, under the 6/16 lip — low enough that
     * the settle's film target stays inside the hysteresis band and the pipe stays DRY; ~900 mB
     * would wet the pipe and legitimately flow), and the crest wording sent players hunting a
     * climb on a dead-flat run. The probe upgrades that case to the honest "supply sits below
     * this pipe's opening" detail; the discriminator is the missing RISE, so a real siphon crest
     * keeps the air-break wording. (A BASIN never stalls this way — an open bowl's surface reads
     * at its top, see {@code basinGivesFromAnyFillLevel}.)
     *
     * PINNED at {@code pumpPullHeadFraction = 0}, in its own batch (a config held across ticks is
     * shared by every test running beside it). This wall lives in a window ONE PIXEL wide: below
     * the opening cell's BLOCK floor the pumped draw lip walls the branch before it assembles, and
     * above the 6/16 aperture lip the supply simply reaches — so only a surface between 5/16 and
     * 6/16 reads this way at all. Any real pull allowance (§3) covers that pixel, which is the
     * point of the feature and is asserted next door by {@link
     * de.devin.pipesnphysics.gametest.physics.GravitySiphonTests#pumpPullAllowanceClearsASupplyUnderTheOpening};
     * the wording still serves a pack that dials the fraction to 0, and the probe's split is live
     * code either way.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 100, batch = "pumpPullOff")
    public static void levelRunBelowApertureReadsSupplyLowNotCrest(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos probeCell = new BlockPos(1, 1, 1); // the suction pipe between tank and pump
        BlockPos sink = new BlockPos(4, 1, 1);
        double pullFraction = PipesNPhysicsConfig.PUMP_PULL_HEAD_FRACTION.get();
        PipesNPhysicsConfig.PUMP_PULL_HEAD_FRACTION.set(0.0);
        fill(helper, source, 500); // rendered surface ~0.34 up the block — under the 0.375 lip, film-banded
        drain(helper, sink); // the sink must accept, so the pump really attempts the draw
        helper.runAfterDelay(25, () -> {
            try {
                PipeStatusPayload probe =
                        PipeProbe.probe(helper.getLevel(), helper.absolutePos(probeCell));
                if (probe.statusDetail() != PipeStatusPayload.DETAIL_BELOW_OPENING) {
                    helper.fail("a level run below the aperture should read DETAIL_BELOW_OPENING,"
                            + " got status=" + probe.status() + " detail=" + probe.statusDetail());
                    return;
                }
                helper.succeed();
            } finally {
                PipesNPhysicsConfig.PUMP_PULL_HEAD_FRACTION.set(pullFraction);
            }
        });
    }

    /**
     * {@code /pipegraph}'s per-edge flow HISTORY ({@link FlowTrace}): every solve records the
     * edge's actual movement, keyed by the edge's endpoint POSITIONS — so the trace survives the
     * graph cache's 4/20-tick rebuilds (an entry-stored history would truncate at the TTL) and
     * resolves through a freshly built graph, exactly how the command reads it. An equalizing
     * run must show multiple samples with real movement, capped at {@code SAMPLES}.
     */
    @GameTest(template = "common/top_row_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void flowTraceRecordsPerEdgeHistoryAcrossRebuilds(GameTestHelper helper) {
        // top_row_run: two 2-tall tanks at x0/x4 joined by the top-row 3-cell run
        BlockPos tankA = new BlockPos(0, 1, 0);
        List<BlockPos> run = List.of(
                new BlockPos(1, 2, 0), new BlockPos(2, 2, 0), new BlockPos(3, 2, 0));
        BlockPos tankB = new BlockPos(4, 1, 0);
        helper.runAfterDelay(5, () -> {
            fill(helper, tankA, 14000); // well above the lip equilibrium: a sustained flow
            fill(helper, tankB, 4000);
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(1)));
        });
        helper.runAfterDelay(60, () -> {
            Graph fresh = GraphBuilder.build(helper.getLevel(), helper.absolutePos(run.get(1)));
            if (fresh.edges().size() != 1) {
                helper.fail("expected the single tank-to-tank edge, got " + fresh.edges().size());
                return;
            }
            List<FlowTrace.Sample> samples =
                    FlowTrace.recent(helper.getLevel(), fresh, fresh.edges().getFirst());
            if (samples.size() < 2) {
                helper.fail("flow trace holds " + samples.size()
                        + " samples after 50+ solved ticks — history did not survive rebuilds");
                return;
            }
            if (samples.size() > FlowTrace.SAMPLES) {
                helper.fail("flow trace grew past its ring (" + samples.size() + ")");
                return;
            }
            if (samples.stream().noneMatch(s -> s.mb() > 0)) {
                helper.fail("no recorded sample shows movement though the run is equalizing");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The pump's reach sleeve and the pipe goggle's "Lift left" are the SAME quantity, so they
     * must agree cell for cell: both are {@code nodeCeilings − cellY} off the engine's planning
     * field. {@link PumpRangeProbe} used to re-derive its own ceiling as {@code pumpY + boost},
     * which is only right when the pump sits AT its supply — a pump lifting out of a tank BELOW
     * it then painted reach it did not have (reported live: a 16 RPM pump reading {@code
     * ceil=60.59} off a source surface at 56.59 showed its sink at 62 as reachable while the
     * run was really NO_HEAD).
     *
     * The rig ({@code physics/pump_lift_beyond_reach}) reproduces that geometry, and every part
     * of it is load-bearing. The pump taps a tank three blocks BELOW it through a riser, so the
     * supply surface and the pump's own elevation are far apart — the whole gap the bug lived in.
     * The tank is tapped HORIZONTALLY, above the opening's draw lip, so a reservoir really can
     * feed the run and the ceiling anchors on its surface (a lip-gated draw self-anchors the
     * ceiling at the pump instead, where both formulas agree and the bug hides — which is why
     * {@code pump_above_waterline} cannot catch this). And the sink sits beyond the pump's lift,
     * so the push edge is NO_HEAD and the solve records no head at the pump — exactly what sent
     * the old code down its {@code pump.worldY()} fallback. A pump that IS pumping has a node
     * head near its supply, so the two formulas agree there too.
     *
     * Mutation check: seed the reach from {@code pump.worldY() + pumpHead} again and the sleeve
     * reads ~3 blocks of lift left where the goggle reads it running out.
     */
    @GameTest(template = "physics/pump_lift_beyond_reach", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void reachSleeveAgreesWithTheGoggleLiftLine(GameTestHelper helper) {
        BlockPos pump = new BlockPos(2, 4, 0);

        helper.succeedWhen(() -> {
            var payload = PumpRangeProbe.probe(helper.getLevel(), helper.absolutePos(pump));
            var push = payload.paths().stream().filter(p -> !p.pull()).findFirst().orElse(null);
            if (push == null) {
                helper.fail("the pump reported no push-side reach path" + dump(helper, pump));
                return;
            }
            var cell = push.cells().stream().filter(PumpRangePayload.RangeCell::pipe)
                    .findFirst().orElse(null);
            if (cell == null) {
                helper.fail("the push path carries no pipe cell to paint" + dump(helper, pump));
                return;
            }

            BlockPos at = BlockPos.of(cell.pos());
            var goggle = PipeProbe.probe(helper.getLevel(), at);
            if (!goggle.hasHeadroom()) {
                helper.fail("no goggle lift value at " + at + " to check the sleeve against"
                        + dump(helper, pump));
                return;
            }
            if (Math.abs(cell.margin() - goggle.headroomBlocks()) > 0.05f) {
                helper.fail("the reach sleeve says " + cell.margin() + " blocks of lift left at "
                        + at + " but the goggle says " + goggle.headroomBlocks()
                        + " — the overlay is not reading the engine's ceiling" + dump(helper, pump));
            }
        });
    }

    /**
     * With NOTHING supplying a pump, the push side must be painted by its REACH alone: the supply
     * surface that normally keeps a flat push run honest does not exist, and the reach field's
     * self-anchor fiction (§6, at the pump's own centre) must not be mistaken for one. A cell then
     * reports NO supply — not "level with it" — so the paint's `below the supply is gravity's work`
     * rule cannot fire and blank a run standing under the pump.
     *
     * The rig dead-ends its suction in a capped pipe, so no source endpoint participates and no
     * reservoir anchors the field — the same fiction that once capped the PULL side's paint at one
     * block regardless of RPM, still live on the push side until 2026-08-27.
     *
     * Mutation check: anchor the fallback at the pump's own head again and the cells report a real
     * (0-ish) above-supply figure instead of none. NOT covered by a rig: the visible consequence
     * needs a push run standing a block or more BELOW the pump, which no template has.
     */
    @GameTest(template = "physics/pump_dead_suction", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void unfedPushRunIsPaintedByReachNotByAPhantomSupply(GameTestHelper helper) {
        BlockPos pump = new BlockPos(2, 1, 1);

        helper.succeedWhen(() -> {
            var payload = PumpRangeProbe.probe(helper.getLevel(), helper.absolutePos(pump));
            var push = payload.paths().stream().filter(p -> !p.pull()).findFirst().orElse(null);
            if (push == null) {
                helper.fail("the pump reported no push-side reach path" + dump(helper, pump));
                return;
            }
            var cell = push.cells().stream().filter(PumpRangePayload.RangeCell::pipe)
                    .findFirst().orElse(null);
            if (cell == null) {
                helper.fail("the push path carries no pipe cell to paint" + dump(helper, pump));
                return;
            }
            if (!Float.isNaN(cell.aboveSupply())) {
                helper.fail("the outlet cell at " + BlockPos.of(cell.pos()) + " reports standing "
                        + cell.aboveSupply() + " above a supply surface, but nothing supplies this"
                        + " pump — that phantom surface blanks a push run below it"
                        + dump(helper, pump));
            }
        });
    }

    /**
     * A pump parked above the waterline with a DRY suction riser reaches NOTHING down it, however
     * deep its nominal suction limit would allow: suction HOLDS a column, it never creates one, so
     * the solve's crest gate refuses the branch outright until the supply itself rises to the
     * crest cell's lip (§3, no self-priming). The sleeve has to say the same — it used to paint a
     * full {@code SUCTION_LIMIT} of reach down a riser the pump could not draw through at all
     * ("the visual overlay looks like the pump could suck in the fluid, but the pump itself tells
     * a different story", reported 2026-07-31).
     *
     * This is why the pull limit goes through {@link FlowSolver#drawableFloor} rather than a local
     * {@code pumpY − SUCTION_LIMIT}: the crest gate is the engine's real wall, and a second copy
     * of the rule in the overlay is exactly how the two came to disagree.
     *
     * Mutation check: floor the pull side at {@code pumpY − SUCTION_LIMIT} again and the cell down
     * at the tank reports blocks of reach to spare instead of being out of reach.
     */
    @GameTest(template = "physics/pump_lift_beyond_reach", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void dryRiserPaintsNoSuctionReach(GameTestHelper helper) {
        BlockPos pump = new BlockPos(2, 4, 0);

        helper.succeedWhen(() -> {
            var payload = PumpRangeProbe.probe(helper.getLevel(), helper.absolutePos(pump));
            var pull = payload.paths().stream().filter(PumpRangePayload.RangePath::pull)
                    .findFirst().orElse(null);
            if (pull == null) {
                helper.fail("the pump reported no suction reach path" + dump(helper, pump));
                return;
            }
            // Walked outward from the pump, so the last pipe cell is the one down at the supply.
            var deepest = pull.cells().stream().filter(PumpRangePayload.RangeCell::pipe)
                    .reduce((first, second) -> second).orElse(null);
            if (deepest == null) {
                helper.fail("the suction path carries no pipe cell to paint" + dump(helper, pump));
                return;
            }
            if (deepest.margin() >= 0) {
                helper.fail("the sleeve claims " + deepest.margin() + " blocks of suction reach at "
                        + BlockPos.of(deepest.pos()) + ", but the riser is dry so the pump cannot"
                        + " draw through it at all" + dump(helper, pump));
            }
        });
    }

    /**
     * The same crest gate has to hold when the pump's suction flank is a JUNCTION. The graph
     * contracts runs BETWEEN junctions, so that flank is an edge with NO cells and no crest of its
     * own — and reading the crest per edge then reported the bare {@code pumpY − SUCTION_LIMIT}
     * fallback, a full 8 blocks of reach on a bone-dry line, while the ordinary runs one hop away
     * reported the true half-block. In a LATTICE, where nearly every pipe is a junction and nearly
     * every edge is zero-length, that alternates along one flat run: adjacent pipes came out red
     * next to green with nothing on screen to explain it (reported 2026-08-26, "half the pipes are
     * red while the other are green — that makes no sense").
     *
     * The junction's own cell is a real pipe the column has to come up through, so it gates like
     * any other ({@link FlowSolver#drawableFloorAt}), and the walk can only ever get MORE
     * restrictive as it goes.
     *
     * Mutation check: drop the seed floor (or the max in {@code reachOn}) and the reach jumps from
     * a fraction of a block to the whole suction limit.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void junctionSuctionFlankKeepsTheCrestGate(GameTestHelper helper) {
        BlockPos pump = new BlockPos(2, 1, 1);
        BlockPos flank = new BlockPos(1, 1, 1);  // the pump's suction-side pipe
        // A third connection turns that flank pipe into a JUNCTION node, which makes the pump's
        // own suction edge zero-length — the shape the gate used to fall through.
        helper.setBlock(new BlockPos(1, 2, 1), pipeState(AllBlocks.FLUID_PIPE.get(), Direction.DOWN));
        for (Direction side : Direction.values()) {
            BlockPos abs = helper.absolutePos(flank).relative(side);
            helper.getLevel().setBlock(abs, Block.updateFromNeighbourShapes(
                    helper.getLevel().getBlockState(abs), helper.getLevel(), abs), 3);
        }
        // The rig's motor runs at 256 RPM, whose pulling share (a tenth of a 64-block head) is
        // most of the suction limit anyway — dial it down so the gated answer and the ungated
        // fallback are far apart and the assert below can tell them apart.
        ScrollValueBehaviour motor = BlockEntityBehaviour.get(
                helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 0)), ScrollValueBehaviour.TYPE);
        if (motor != null) motor.setValue(16);
        drain(helper, new BlockPos(0, 1, 1)); // bone dry, so the crest gate is the DRY one
        drain(helper, new BlockPos(4, 1, 1));

        helper.runAtTickTime(40, () -> {
            var payload = PumpRangeProbe.probe(helper.getLevel(), helper.absolutePos(pump));
            var pull = payload.paths().stream().filter(PumpRangePayload.RangePath::pull)
                    .findFirst().orElse(null);
            if (pull == null || pull.cells().isEmpty()) {
                helper.fail("the pump reported no suction reach path" + dump(helper, pump));
                return;
            }
            // The first entry is the pump itself: this run's whole reach, and the span its colour
            // ramp normalizes over. A dry line gives the pump only its pulling share of head — a
            // fraction of a block at 16 RPM, against the 8-block fallback of an ungated edge.
            float reach = pull.cells().get(0).margin();
            if (reach > 1) {
                helper.fail("a junction suction flank claims " + reach + " blocks of reach on a dry"
                        + " line — the zero-length edge skipped the crest gate" + dump(helper, pump));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * You cannot reach the top of a run but not its middle. The pull floor is a RUNNING quantity —
     * a supply here has to be lifted over every cell already crossed — so along one path a cell
     * that is out of reach can never be followed by one that is in reach.
     *
     * Read per EDGE it was: one crest applied to every cell of its run, including the cells BELOW
     * that crest, which are then measured against a floor their own crest puts ABOVE them. An
     * ASCENDING suction run therefore came out with its lower half unpainted and its top painted —
     * a hole in the middle of a painted run ("the pipe in the middle is not painted at all",
     * 2026-08-26, `margin -0.47 of 0.52` on a cell standing a block ABOVE the pump).
     *
     * The invariant is asserted rather than a number, so it holds for any rig: mutation check —
     * apply the edge-wide floor to every cell again and the lower cell of this riser reports out
     * of reach while the one above it reports in reach.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void climbingSuctionRunHasNoUnreachableMiddle(GameTestHelper helper) {
        BlockPos pump = new BlockPos(2, 1, 1);
        BlockPos flank = new BlockPos(1, 1, 1);
        // A two-cell run CLIMBING off the pump's suction flank, ending in an open mouth: the shape
        // whose lower cell fell below its own crest's floor.
        helper.setBlock(new BlockPos(1, 2, 1),
                pipeState(AllBlocks.FLUID_PIPE.get(), Direction.UP, Direction.DOWN));
        helper.setBlock(new BlockPos(1, 3, 1),
                pipeState(AllBlocks.FLUID_PIPE.get(), Direction.UP, Direction.DOWN));
        for (Direction side : Direction.values()) {
            BlockPos abs = helper.absolutePos(flank).relative(side);
            helper.getLevel().setBlock(abs, Block.updateFromNeighbourShapes(
                    helper.getLevel().getBlockState(abs), helper.getLevel(), abs), 3);
        }
        // The rig's 256 RPM pump can establish 6.4 blocks down a dry line, which swamps a
        // two-block riser and leaves nothing negative to catch. Dial it to where the allowance
        // (0.4) is smaller than the run it climbs — the reporter's own 16 RPM.
        ScrollValueBehaviour motor = BlockEntityBehaviour.get(
                helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 0)), ScrollValueBehaviour.TYPE);
        if (motor != null) motor.setValue(16);
        drain(helper, new BlockPos(0, 1, 1));
        drain(helper, new BlockPos(4, 1, 1));

        helper.runAtTickTime(40, () -> {
            var payload = PumpRangeProbe.probe(helper.getLevel(), helper.absolutePos(pump));
            for (var path : payload.paths()) {
                if (!path.pull()) continue;
                PumpRangePayload.RangeCell out = null;
                for (var cell : path.cells()) {
                    if (cell.margin() < 0) {
                        out = cell;
                    } else if (out != null) {
                        helper.fail("the suction walk reports " + BlockPos.of(out.pos())
                                + " out of reach (" + out.margin() + ") but "
                                + BlockPos.of(cell.pos()) + " further along it in reach ("
                                + cell.margin() + ") — a hole in the middle of the run"
                                + dump(helper, pump));
                        return;
                    }
                }
            }
            helper.succeed();
        });
    }
}
