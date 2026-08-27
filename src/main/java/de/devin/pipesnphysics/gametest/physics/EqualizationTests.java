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
 * Communicating vessels & pump-less gravity feed: tanks settle at equal surface lines.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class EqualizationTests {

    /**
     * Two identical tanks joined by a U-shaped pipe under them (communicating
     * vessels). One starts full; both must converge to equal fill at gameplay
     * speed, conserving fluid throughout.
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void tanksEqualizeAtEqualSurfaces(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        fill(helper, left, 8000);

        helper.succeedWhen(() -> {
            int a = amount(helper, left);
            int b = amount(helper, right);
            int pipes = pipesnphysics$areaPipeContent(helper, 4, 4, 2);
            if (a + b + pipes != 8000) {
                helper.fail("fluid not conserved: " + a + " + " + b + " + pipes " + pipes);
            }
            if (Math.abs(a - b) > 800) helper.fail("not equalized yet: " + a + " vs " + b);
        });
    }

    /** A raised tank must drain completely into the tank below it, no pump needed. */
    @GameTest(template = "common/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void gravityDrainsUpperTankCompletely(GameTestHelper helper) {
        BlockPos top = new BlockPos(0, 4, 0);
        BlockPos bottom = new BlockPos(0, 1, 0);
        fill(helper, top, 8000);

        helper.succeedWhen(() -> {
            int left = amount(helper, top);
            int below = amount(helper, bottom);
            int pipes = pipesnphysics$areaPipeContent(helper, 4, 6, 4);
            if (left + below + pipes != 8000) {
                helper.fail("fluid not conserved: " + left + " + " + below + " + pipes " + pipes);
            }
            if (left != 0) helper.fail("upper tank still holds " + left + " mB");
        });
    }

    /**
     * Two level 1x1 tanks joined by a flat pipe run (tank-pipe-pipe-tank). Partly
     * filled, they equalize with the waterline settling INSIDE the connecting pipe
     * cells — those cells are still full and must keep rendering, not revert to empty
     * the instant flow stops. (Regression: the submersion test used the cell centre,
     * so an equalized level below centre wrongly read as above the waterline.)
     */
    @GameTest(template = "common/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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

            // The connecting cells settle at the shared waterline INSIDE them: partial content,
            // neither drained dry nor painted full.
            helper.succeedWhen(() -> {
                int wet = 0;
                for (int x = 0; x < 12; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 12; z++) {
                    if (cellMb(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z))) > 0) wet++;
                }
                if (wet == 0) helper.fail("flat resting pipe (surface inside the cell) holds no fluid");
            });
        });
    }

    /**
     * A molten fluid runs THINNER in an ultrawarm dimension — the engine's effective viscosity
     * divides by the configured thinning there (vanilla parity: lava spreads 3× faster in the
     * Nether), so lava pipes in the Nether flow that much faster. Water (below the molten
     * temperature) is untouched, and the overworld reads the registered value. Unit-style: the
     * rule is a pure function of level + fluid, so it is asserted against the server's REAL
     * Nether level rather than a rig no GameTest could build there (the template rig is
     * unused scenery).
     */
    @GameTest(template = "common/top_row_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 40)
    public static void moltenFluidThinsInUltrawarmDimensions(GameTestHelper helper) {
        var overworld = helper.getLevel();
        var nether = overworld.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("game test server has no nether level");
            return;
        }
        double thinning = PipesNPhysicsConfig.ULTRAWARM_VISCOSITY_THINNING.get();
        FluidStack lava = new FluidStack(Fluids.LAVA, 1000);
        FluidStack water = new FluidStack(Fluids.WATER, 1000);
        double lavaHome = FlowSolver.effectiveViscosity(overworld, lava);
        double lavaNether = FlowSolver.effectiveViscosity(nether, lava);
        if (lavaHome != lava.getFluid().getFluidType().getViscosity()) {
            helper.fail("overworld lava viscosity " + lavaHome + " is not the registered value");
            return;
        }
        if (Math.abs(lavaNether - lavaHome / thinning) > 1) {
            helper.fail("nether lava viscosity " + lavaNether + " is not thinned by " + thinning
                    + " from " + lavaHome);
            return;
        }
        if (FlowSolver.effectiveViscosity(nether, water)
                != FlowSolver.effectiveViscosity(overworld, water)) {
            helper.fail("water viscosity changed in the nether — the molten temperature gate leaked");
            return;
        }
        helper.succeed();
    }

    /**
     * A run packed FULL by an earlier fast phase must TRACK the falling waterline WHILE two
     * tanks equalize through it — not ride 250 mB until the flow dies and only then settle
     * ("the pipes stay full of fluid until it has settled"). The flowing shed drains each
     * cell toward the grade line hung between the tanks' RENDERED surfaces, floored at the
     * plug's flow depth, so mid-equalization the top-row run reads partial while fluid still
     * moves. Both surfaces sit inside the run's bore, so the line wets the conduit end to
     * end (the shed's flooded gate); conservation must hold throughout and the tanks must
     * not yet be equalized at the sample tick (else the idle settle would mask the shed).
     */
    @GameTest(template = "common/top_row_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void flowingRunShedsTowardTheFallingWaterline(GameTestHelper helper) {
        // top_row_run: two 2-tall tanks at x0/x4 joined by the top-row 3-cell run
        BlockPos source = new BlockPos(0, 1, 0);
        BlockPos sink = new BlockPos(4, 1, 0);
        List<BlockPos> run = List.of(
                new BlockPos(1, 2, 0), new BlockPos(2, 2, 0), new BlockPos(3, 2, 0));

        int sourceStart = 13800, sinkStart = 12200; // both rendered surfaces inside the bore
        int packed = PipeStore.capacityMb();
        helper.runAfterDelay(5, () -> {
            fill(helper, source, sourceStart);
            fill(helper, sink, sinkStart);
            for (BlockPos rel : run) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                cell.insert(new FluidStack(Fluids.WATER, packed), packed);
            }
            helper.runAfterDelay(75, () -> {
                int sourceMb = amount(helper, source);
                int sinkMb = amount(helper, sink);
                int pipeMb = 0;
                for (BlockPos rel : run) {
                    pipeMb += cellMb(helper.getLevel(), helper.absolutePos(rel));
                }
                if (sourceMb + sinkMb + pipeMb != sourceStart + sinkStart + 3 * packed) {
                    helper.fail("fluid not conserved: " + sourceMb + " + " + sinkMb + " + " + pipeMb);
                    return;
                }
                if (sourceMb - sinkMb < 150) {
                    helper.fail("tanks already equalized at the sample tick (diff "
                            + (sourceMb - sinkMb) + ") — the idle settle would mask the shed");
                    return;
                }
                if (sinkMb <= sinkStart) {
                    helper.fail("no flow reached the sink");
                    return;
                }
                for (BlockPos rel : run) {
                    int mb = cellMb(helper.getLevel(), helper.absolutePos(rel));
                    if (mb >= 200) {
                        helper.fail("mid-equalization cell " + rel + " still holds " + mb
                                + " mB — the packed run is not tracking the falling waterline");
                        return;
                    }
                    if (mb < 15) {
                        helper.fail("cell " + rel + " shed below the flow-depth floor: " + mb + " mB");
                        return;
                    }
                }
                helper.succeed();
            });
        });
    }

    /**
     * A run dropping into a BRIMMING tank from above must still settle: take the last of the
     * supply tank over it and level out its own level stretch. The brimming end can give nothing
     * (its visible fluid stands a block below the pipe's opening) and take nothing (it is full),
     * so it is hydraulically a WALL — but the settle read its low surface as the run's resting
     * line anyway, flattening every target to zero. Nothing then had a deficit to level toward,
     * every draw off the supply was refused, and because the solve calls such a dead conduit
     * SINK_FULL (fill-only, it may give nothing back either) the column FROZE: the reported rig
     * sat at 57|235|56|232|228 across its five cells forever while the supply kept its 192 mB,
     * {@code /pipegraph} showing {@code stalled solved=0 actual=0} and a flat {@code recent 0 …}
     * strip. With the wall deferring, the run rests at the only end it can exchange with.
     */
    @GameTest(template = "physics/drop_into_full_tank", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void runIntoABrimmingTankStillLevelsAndTakesItsSupply(GameTestHelper helper) {
        // drop_into_full_tank: supply tank - glass riser - 4-cell level run - brimming tank BELOW
        BlockPos supply = new BlockPos(0, 4, 0);
        BlockPos brimming = new BlockPos(3, 1, 0);
        BlockPos riser = new BlockPos(0, 3, 0);
        List<BlockPos> level = List.of(new BlockPos(0, 2, 0), new BlockPos(1, 2, 0),
                new BlockPos(2, 2, 0), new BlockPos(3, 2, 0));
        int supplyStart = 192;
        int[] preloaded = {57, 235, 56, 232, 228}; // the reported profile, riser first
        int stored = Arrays.stream(preloaded).sum();

        helper.runAfterDelay(5, () -> {
            fill(helper, brimming, 8000);
            fill(helper, supply, supplyStart);
            List<BlockPos> run = new ArrayList<>(List.of(riser));
            run.addAll(level);
            for (int i = 0; i < run.size(); i++) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(run.get(i)));
                cell.insert(new FluidStack(Fluids.WATER, preloaded[i]), preloaded[i]);
                cell.flush();
            }

            helper.runAfterDelay(300, () -> {
                int supplyMb = amount(helper, supply);
                int brimmingMb = amount(helper, brimming);
                int pipeMb = cellMb(helper.getLevel(), helper.absolutePos(riser));
                int low = Integer.MAX_VALUE, high = 0;
                for (BlockPos rel : level) {
                    int mb = cellMb(helper.getLevel(), helper.absolutePos(rel));
                    pipeMb += mb;
                    low = Math.min(low, mb);
                    high = Math.max(high, mb);
                }
                if (supplyMb + brimmingMb + pipeMb != supplyStart + 8000 + stored) {
                    helper.fail("fluid not conserved: supply " + supplyMb + " + brimming "
                            + brimmingMb + " + pipes " + pipeMb);
                    return;
                }
                if (brimmingMb != 8000) {
                    helper.fail("the brimming tank must neither gain nor give: " + brimmingMb + " mB");
                    return;
                }
                if (supplyMb != 0) {
                    helper.fail("the supply tank still holds " + supplyMb
                            + " mB — the run below it refused to take it");
                    return;
                }
                // The spread's anti-slosh gate leaves up to DREGS_MB standing at each of the
                // stretch's 3 boundaries, so 12 mB end to end is the settled state — against
                // the 179 mB (56 vs 235) the frozen column reported.
                if (high - low > 12) {
                    helper.fail("the level stretch never leveled: " + low + "…" + high + " mB"
                            + " (riser " + cellMb(helper.getLevel(), helper.absolutePos(riser))
                            + ", cells " + level.stream()
                                    .map(r -> String.valueOf(cellMb(helper.getLevel(), helper.absolutePos(r))))
                                    .toList() + ")");
                }
                helper.succeed();
            });
        });
    }
}
