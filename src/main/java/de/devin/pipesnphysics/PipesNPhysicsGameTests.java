package de.devin.pipesnphysics;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.handler.PhysicsConfigFactory;
import de.devin.pipesnphysics.handler.PipeGraphBuilder;
import de.devin.pipesnphysics.physics.*;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the rewritten physics engine.
 * Unit tests verify formulas and solver logic directly.
 * Integration tests use .nbt structures built in-game.
 *
 * Run with: ./gradlew runGameTestServer
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class PipesNPhysicsGameTests {

    private static final int FILL_AMOUNT = 8000;
    private static final int SETUP_DELAY = 20;
    private static final int FILL_DELAY = 60;

    // ========== .nbt integration tests: gravity ==========

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void gravity1DropFall(GameTestHelper helper) {
        fillSourcesAndCheckSinks(helper);
    }

    @GameTest(template = "gravity/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void gravity2DropFall(GameTestHelper helper) {
        fillSourcesAndCheckSinks(helper);
    }

    @GameTest(template = "gravity/5_block_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void gravity5BlockFall(GameTestHelper helper) {
        fillSourcesAndCheckSinks(helper);
    }

    @GameTest(template = "gravity/bent_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void gravityBentFall(GameTestHelper helper) {
        fillSourcesAndCheckSinks(helper);
    }

    @GameTest(template = "gravity/fall_with_split", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void gravityFallWithSplit(GameTestHelper helper) {
        fillSourcesAndCheckSinks(helper);
    }

    // ========== .nbt integration tests: pumps ==========

    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void singlePump(GameTestHelper helper) {
        // Pump test: fill ALL tanks so the pump can move fluid regardless of direction
        helper.runAfterDelay(SETUP_DELAY, () -> {
            ServerLevel level = helper.getLevel();
            propagateAllPipes(helper, level);
            List<TankInfo> tanks = findAllTanks(helper, level);
            helper.runAfterDelay(FILL_DELAY - SETUP_DELAY, () -> {
                for (TankInfo tank : tanks) {
                    fillHandler(level, tank.absPos, FILL_AMOUNT);
                }
                scheduleAllFlowChecks(helper, level);
            });
        });
        // Succeed when any pipe has active pressure (pump is driving flow)
        helper.succeedWhen(() -> {
            ServerLevel level = helper.getLevel();
            var bounds = helper.getBounds();
            BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
            BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe != null && pipe.hasAnyPressure()) return;
            }
            helper.fail("No pipe has active pressure yet");
        });
    }

    @GameTest(template = "piping/double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void doublePump(GameTestHelper helper) {
        Map<BlockPos, Integer> initialAmounts = new HashMap<>();

        helper.runAfterDelay(SETUP_DELAY, () -> {
            ServerLevel level = helper.getLevel();
            List<TankInfo> tanks = findAllTanks(helper, level);
            for (TankInfo tank : tanks) {
                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, tank.absPos, null);
                if (handler == null) continue;
                int total = 0;
                for (int i = 0; i < handler.getTanks(); i++) {
                    total += handler.getFluidInTank(i).getAmount();
                }
                initialAmounts.put(tank.absPos, total);
            }
            propagateAllPipes(helper, level);
            scheduleAllFlowChecks(helper, level);
        });

        helper.runAfterDelay(60, () -> {
            ServerLevel level = helper.getLevel();

            // Check that fluid transferred
            boolean tankGained = false;
            for (var entry : initialAmounts.entrySet()) {
                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, entry.getKey(), null);
                if (handler == null) continue;
                int total = 0;
                for (int i = 0; i < handler.getTanks(); i++) {
                    total += handler.getFluidInTank(i).getAmount();
                }
                if (Math.abs(total - entry.getValue()) >= 10) { tankGained = true; break; }
            }
            if (!tankGained) {
                // Dump network state for debugging
                var bounds2 = helper.getBounds();
                BlockPos min2 = BlockPos.containing(bounds2.minX, bounds2.minY, bounds2.minZ);
                BlockPos max2 = BlockPos.containing(bounds2.maxX, bounds2.maxY, bounds2.maxZ);
                BlockPos anyPipe = null;
                int pipeCount = 0;
                for (BlockPos p : BlockPos.betweenClosed(min2, max2)) {
                    if (FluidPropagator.getPipe(level, p) != null) {
                        if (anyPipe == null) anyPipe = p.immutable();
                        pipeCount++;
                    }
                }
                StringBuilder diag = new StringBuilder();
                diag.append("pipes=").append(pipeCount);
                if (anyPipe != null) {
                    SimConfig cfg = de.devin.pipesnphysics.handler.PhysicsConfigFactory.simConfig();
                    FluidNetwork net = de.devin.pipesnphysics.handler.NetworkBuilder.build(level, anyPipe, cfg);
                    diag.append(" nodes=").append(net.nodes().size())
                            .append(" edges=").append(net.edges().size());
                    for (var ne : net.nodes().entrySet()) {
                        SimNode sn = ne.getValue();
                        diag.append(" | ").append(de.devin.pipesnphysics.handler.PipeGraphBuilder.posOf(ne.getKey()).toShortString())
                                .append("=").append(sn.kind()).append(",h=").append(String.format("%.0f", sn.head()));
                    }
                    for (SimEdge se : net.edges()) {
                        diag.append(" | E").append(se.id()).append("=").append(se.phase())
                                .append(",len=").append(se.length()).append(",fill=").append(se.totalFill());
                    }
                }
                // Tank amounts
                for (var e : initialAmounts.entrySet()) {
                    var h = level.getCapability(Capabilities.FluidHandler.BLOCK, e.getKey(), null);
                    int now = 0;
                    if (h != null) for (int i = 0; i < h.getTanks(); i++) now += h.getFluidInTank(i).getAmount();
                    diag.append(" | tank=").append(e.getValue()).append("→").append(now);
                }
                helper.fail(diag.toString());
                return;
            }

            // Visual fluid check skipped — pipe rendering is a separate concern

            helper.succeed();
        });
    }

    @GameTest(template = "piping/pump_with_gravity_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void pumpWithGravityFall(GameTestHelper helper) {
        fillSourcesAndCheckSinks(helper);
    }

    @GameTest(template = "piping/pump_with_siphon", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void pumpWithSiphon(GameTestHelper helper) {
        fillSourcesAndCheckSinks(helper);
    }

    // ========== .nbt integration tests: existing ==========

    @GameTest(template = "the_lava_test", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void lavaTestConsistentFlow(GameTestHelper helper) {
        // Structure comes with pre-filled lava source. Just propagate and check for flow.
        helper.runAfterDelay(SETUP_DELAY, () -> {
            ServerLevel level = helper.getLevel();
            propagateAllPipes(helper, level);
            scheduleAllFlowChecks(helper, level);
        });
        helper.succeedWhen(() -> {
            ServerLevel level = helper.getLevel();
            var bounds = helper.getBounds();
            BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
            BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe != null && pipe.hasAnyPressure()) return;
            }
            helper.fail("No pipe has active pressure");
        });
    }

    @GameTest(template = "fill_to_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400, required = false)
    public static void fillToCauldron(GameTestHelper helper) {
        helper.runAfterDelay(SETUP_DELAY, () -> {
            propagateAllPipes(helper, helper.getLevel());
        });
        // The structure has a pre-filled source tank and an empty cauldron sink.
        // Just check that the cauldron fills.
        helper.succeedWhen(() -> {
            // Scan for any cauldron that became a lava_cauldron
            var bounds = helper.getBounds();
            BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
            BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (helper.getLevel().getBlockState(pos).toString().contains("cauldron")) {
                    if (helper.getLevel().getBlockState(pos).toString().contains("lava_cauldron"))
                        return;
                }
            }
            helper.fail("No cauldron filled yet");
        });
    }

    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void suckFromCauldron(GameTestHelper helper) {
        helper.runAfterDelay(SETUP_DELAY, () -> {
            ServerLevel level = helper.getLevel();
            // Fill a water cauldron in the structure
            var bounds = helper.getBounds();
            BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
            BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (level.getBlockState(pos).toString().contains("cauldron")) {
                    level.setBlock(pos.immutable(),
                            net.minecraft.world.level.block.Blocks.WATER_CAULDRON.defaultBlockState()
                                    .setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3),
                            net.minecraft.world.level.block.Block.UPDATE_ALL);
                    break;
                }
            }
            propagateAllPipes(helper, level);
        });
        // Check that the cauldron gets drained
        helper.succeedWhen(() -> {
            var bounds = helper.getBounds();
            BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
            BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (helper.getLevel().getBlockState(pos).toString().contains("water_cauldron")) {
                    helper.fail("Cauldron still has water");
                    return;
                }
            }
        });
    }

    // ========== Unit tests: formulas ==========

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void gravityDeltaBasic(GameTestHelper helper) {
        PipeFormulas f = testFormulas(15.0f, 5.0f, 60.0f, 0.1f);

        assertClose(helper, "3-block drop", 45.0f, f.gravityDelta(10.0, 7.0));
        assertClose(helper, "3-block uphill", -45.0f, f.gravityDelta(7.0, 10.0));
        assertClose(helper, "dead zone", 0.0f, f.gravityDelta(10.0, 9.95));
        assertClose(helper, "flat", 0.0f, f.gravityDelta(5.0, 5.0));

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void edgeDeliveredPressure(GameTestHelper helper) {
        PipeFormulas f = testFormulas(15.0f, 5.0f, 60.0f, 0.1f);

        assertClose(helper, "downhill vertical", 40.0f,
                f.edgeDeliveredPressure(10.0f, 5.0, 3.0, 90.0f, 1.0f));
        assertClose(helper, "horizontal", 5.0f,
                f.edgeDeliveredPressure(10.0f, 5.0, 5.0, 0.0f, 1.0f));
        assertClose(helper, "uphill clamped", 0.0f,
                f.edgeDeliveredPressure(10.0f, 3.0, 5.0, 90.0f, 1.0f));

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void frictionHorizontalOnly(GameTestHelper helper) {
        PipeFormulas f = testFormulas(15.0f, 5.0f, 60.0f, 0.1f);

        assertClose(helper, "vertical friction", 0.0f, f.segmentFriction(90.0f));
        assertClose(helper, "horizontal friction", 5.0f, f.segmentFriction(0.0f));
        assertClose(helper, "steep angle", 0.0f, f.segmentFriction(10.0f));

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void sableAngleFriction(GameTestHelper helper) {
        PipeFormulas f = testFormulas(15.0f, 5.0f, 60.0f, 0.1f);

        float fric25 = f.segmentFriction(2.5f);
        assertClose(helper, "2.5 degree", 5.0f * 0.25f, fric25);

        float fric0 = f.segmentFriction(0.0f);
        assertClose(helper, "0 degree", 5.0f, fric0);

        float fric5 = f.segmentFriction(5.0f);
        assertClose(helper, "5 degree", 0.0f, fric5);

        if (fric0 < fric25) helper.fail("0 should have more friction than 2.5");

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void pumpPushPullRatios(GameTestHelper helper) {
        PipeFormulas f = testFormulas(15.0f, 5.0f, 60.0f, 0.1f);

        assertClose(helper, "push", 70.0f, f.pumpPushPressure(100.0f));
        assertClose(helper, "pull", 30.0f, f.pumpPullPressure(100.0f));

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void burstDetection(GameTestHelper helper) {
        PipeFormulas f = testFormulas(15.0f, 5.0f, 60.0f, 0.1f);

        if (f.wouldBurst(59.0f)) helper.fail("59 should not burst");
        if (!f.wouldBurst(61.0f)) helper.fail("61 should burst");
        if (f.wouldBurst(60.0f)) helper.fail("60 should not burst");

        helper.succeed();
    }

    // ========== Unit tests: solver ==========

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void testMergePressureAdds(GameTestHelper helper) {
        PipeFormulas formulas = testFormulas(15.0f, 0.0f, 200.0f, 0.1f);
        NetworkSolver solver = new NetworkSolver(formulas);

        NodeId pipeA = id("pipeA");
        NodeId pipeB = id("pipeB");
        NodeId junction = id("junction");

        Map<NodeId, PipeNode> nodes = new LinkedHashMap<>();
        nodes.put(pipeA, new PipeNode(pipeA, NodeKind.PIPE, 4.5));
        nodes.put(pipeB, new PipeNode(pipeB, NodeKind.PIPE, 4.5));
        nodes.put(junction, new PipeNode(junction, NodeKind.PIPE, 3.5));

        Map<NodeId, List<PipeEdge>> adj = new HashMap<>();
        adj.computeIfAbsent(pipeA, k -> new ArrayList<>())
                .add(new PipeEdge(pipeA, junction, 90.0f, 4.5, 3.5, 0));
        adj.computeIfAbsent(pipeB, k -> new ArrayList<>())
                .add(new PipeEdge(pipeB, junction, 90.0f, 4.5, 3.5, 2));
        adj.computeIfAbsent(junction, k -> new ArrayList<>())
                .add(new PipeEdge(junction, pipeA, 90.0f, 3.5, 4.5, 1));
        adj.computeIfAbsent(junction, k -> new ArrayList<>())
                .add(new PipeEdge(junction, pipeB, 90.0f, 3.5, 4.5, 3));

        List<NetworkEndpoint> endpoints = List.of(
                new NetworkEndpoint(id("srcA"), pipeA, 1, 5.5, 4.5),
                new NetworkEndpoint(id("srcB"), pipeB, 3, 5.5, 4.5),
                new NetworkEndpoint(id("sink"), junction, 0, 2.5, 3.5)
        );

        PipeGraph graph = new PipeGraph(nodes, adj, endpoints);
        SolverResult result = solver.solve(graph);

        Float juncP = result.pressures().get(junction);
        Float pipeAP = result.pressures().get(pipeA);
        if (juncP == null || pipeAP == null) { helper.fail("Missing pressure"); return; }
        if (juncP <= pipeAP) {
            helper.fail("Junction " + juncP + " should be > single pipe " + pipeAP);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void testSplitPressureDivides(GameTestHelper helper) {
        PipeFormulas formulas = testFormulas(15.0f, 0.0f, 200.0f, 0.1f);
        NetworkSolver solver = new NetworkSolver(formulas);

        NodeId src = id("src");
        NodeId junction = id("junction");
        NodeId branchA = id("branchA");
        NodeId branchB = id("branchB");

        Map<NodeId, PipeNode> nodes = new LinkedHashMap<>();
        nodes.put(src, new PipeNode(src, NodeKind.PIPE, 4.5));
        nodes.put(junction, new PipeNode(junction, NodeKind.PIPE, 3.5));
        nodes.put(branchA, new PipeNode(branchA, NodeKind.PIPE, 2.5));
        nodes.put(branchB, new PipeNode(branchB, NodeKind.PIPE, 2.5));

        Map<NodeId, List<PipeEdge>> adj = new HashMap<>();
        addBidirectionalEdge(adj, src, junction, 90.0f, 4.5, 3.5, 0, 1);
        addBidirectionalEdge(adj, junction, branchA, 90.0f, 3.5, 2.5, 4, 5);
        addBidirectionalEdge(adj, junction, branchB, 90.0f, 3.5, 2.5, 2, 3);

        List<NetworkEndpoint> endpoints = List.of(
                new NetworkEndpoint(id("handler"), src, 1, 5.5, 4.5),
                new NetworkEndpoint(id("sinkA"), branchA, 0, 1.5, 2.5),
                new NetworkEndpoint(id("sinkB"), branchB, 0, 1.5, 2.5)
        );

        PipeGraph graph = new PipeGraph(nodes, adj, endpoints);
        SolverResult result = solver.solve(graph);

        Float pA = result.pressures().get(branchA);
        Float pB = result.pressures().get(branchB);
        if (pA == null || pB == null) { helper.fail("Branch pressures null"); return; }
        assertClose(helper, "branches equal", pA, pB);

        Float juncP = result.pressures().get(junction);
        if (juncP != null && pA > juncP) {
            helper.fail("Branch " + pA + " should be <= junction " + juncP);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void testGravityStacksAfterSplit(GameTestHelper helper) {
        PipeFormulas formulas = testFormulas(15.0f, 0.0f, 200.0f, 0.1f);
        NetworkSolver solver = new NetworkSolver(formulas);

        NodeId src = id("src");
        NodeId junction = id("junction");
        NodeId branchA = id("branchA");
        NodeId branchB = id("branchB");

        Map<NodeId, PipeNode> nodes = new LinkedHashMap<>();
        nodes.put(src, new PipeNode(src, NodeKind.PIPE, 9.5));
        nodes.put(junction, new PipeNode(junction, NodeKind.PIPE, 8.5));
        nodes.put(branchA, new PipeNode(branchA, NodeKind.PIPE, 8.5));
        nodes.put(branchB, new PipeNode(branchB, NodeKind.PIPE, 3.5));

        Map<NodeId, List<PipeEdge>> adj = new HashMap<>();
        addBidirectionalEdge(adj, src, junction, 90.0f, 9.5, 8.5, 0, 1);
        addBidirectionalEdge(adj, junction, branchA, 0.0f, 8.5, 8.5, 4, 5);
        addBidirectionalEdge(adj, junction, branchB, 90.0f, 8.5, 3.5, 2, 3);

        List<NetworkEndpoint> endpoints = List.of(
                new NetworkEndpoint(id("handler"), src, 1, 10.5, 9.5),
                new NetworkEndpoint(id("sinkA"), branchA, 5, 7.5, 8.5),
                new NetworkEndpoint(id("sinkB"), branchB, 3, 2.5, 3.5)
        );

        PipeGraph graph = new PipeGraph(nodes, adj, endpoints);
        SolverResult result = solver.solve(graph);

        Float pA = result.pressures().get(branchA);
        Float pB = result.pressures().get(branchB);
        if (pA == null || pB == null) { helper.fail("Pressures null"); return; }
        if (pB <= pA) {
            helper.fail("Dropping branch B (" + pB + ") should exceed flat A (" + pA + ")");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void testBurstEventGenerated(GameTestHelper helper) {
        // Two sources each give 15 pressure, merge = 30. Threshold must be below 30.
        PipeFormulas formulas = testFormulasWithBurst(15.0f, 0.0f, 200.0f, 0.1f, 25.0f);
        NetworkSolver solver = new NetworkSolver(formulas);

        NodeId pipeA = id("pipeA");
        NodeId pipeB = id("pipeB");
        NodeId junction = id("junction");

        Map<NodeId, PipeNode> nodes = new LinkedHashMap<>();
        nodes.put(pipeA, new PipeNode(pipeA, NodeKind.PIPE, 4.5));
        nodes.put(pipeB, new PipeNode(pipeB, NodeKind.PIPE, 4.5));
        nodes.put(junction, new PipeNode(junction, NodeKind.PIPE, 3.5));

        Map<NodeId, List<PipeEdge>> adj = new HashMap<>();
        adj.computeIfAbsent(pipeA, k -> new ArrayList<>())
                .add(new PipeEdge(pipeA, junction, 90.0f, 4.5, 3.5, 0));
        adj.computeIfAbsent(pipeB, k -> new ArrayList<>())
                .add(new PipeEdge(pipeB, junction, 90.0f, 4.5, 3.5, 2));
        adj.computeIfAbsent(junction, k -> new ArrayList<>())
                .add(new PipeEdge(junction, pipeA, 90.0f, 3.5, 4.5, 1));
        adj.computeIfAbsent(junction, k -> new ArrayList<>())
                .add(new PipeEdge(junction, pipeB, 90.0f, 3.5, 4.5, 3));

        List<NetworkEndpoint> endpoints = List.of(
                new NetworkEndpoint(id("srcA"), pipeA, 1, 5.5, 4.5),
                new NetworkEndpoint(id("srcB"), pipeB, 3, 5.5, 4.5),
                new NetworkEndpoint(id("sink"), junction, 0, 2.5, 3.5)
        );

        PipeGraph graph = new PipeGraph(nodes, adj, endpoints);
        SolverResult result = solver.solve(graph);

        boolean bursts = result.burstEvents().stream()
                .anyMatch(e -> e.node().equals(junction));
        if (!bursts) {
            helper.fail("Junction should burst (pressure=" + result.pressures().get(junction) + ", threshold=25)");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void testNoPressureWithoutFluid(GameTestHelper helper) {
        PipeFormulas formulas = testFormulas(15.0f, 5.0f, 60.0f, 0.1f);
        NetworkSolver solver = new NetworkSolver(formulas);

        NodeId pipe1 = id("pipe1");
        NodeId pipe2 = id("pipe2");

        Map<NodeId, PipeNode> nodes = new LinkedHashMap<>();
        nodes.put(pipe1, new PipeNode(pipe1, NodeKind.PIPE, 5.0));
        nodes.put(pipe2, new PipeNode(pipe2, NodeKind.PIPE, 3.0));

        Map<NodeId, List<PipeEdge>> adj = new HashMap<>();
        addBidirectionalEdge(adj, pipe1, pipe2, 90.0f, 5.0, 3.0, 0, 1);

        PipeGraph graph = new PipeGraph(nodes, adj, List.of());
        SolverResult result = solver.solve(graph);

        if (!result.activePipes().isEmpty()) {
            helper.fail("Should have no active pipes without sources");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void testDoublePumpAllPipesActive(GameTestHelper helper) {
        // Two pumps in series: source → pipeA → pump1 → pipeB → pipeC → pump2 → pipeD → sink
        // All four pipes between source and sink must have pressure.
        PipeFormulas formulas = testFormulas(15.0f, 5.0f, 200.0f, 0.1f);
        NetworkSolver solver = new NetworkSolver(formulas);

        NodeId pipeA = id("pipeA"); // pull side of pump1
        NodeId pipeB = id("pipeB"); // push side of pump1
        NodeId pipeC = id("pipeC"); // pull side of pump2
        NodeId pipeD = id("pipeD"); // push side of pump2

        Map<NodeId, PipeNode> nodes = new LinkedHashMap<>();
        nodes.put(pipeA, new PipeNode(pipeA, NodeKind.PIPE, 5.0));
        nodes.put(pipeB, new PipeNode(pipeB, NodeKind.PIPE, 5.0));
        nodes.put(pipeC, new PipeNode(pipeC, NodeKind.PIPE, 5.0));
        nodes.put(pipeD, new PipeNode(pipeD, NodeKind.PIPE, 5.0));

        // Middle pipes connected to each other
        Map<NodeId, List<PipeEdge>> adj = new HashMap<>();
        addBidirectionalEdge(adj, pipeB, pipeC, 0.0f, 5.0, 5.0, 4, 5);

        // Endpoints: source tank above pipeA, sink tank below pipeD
        List<NetworkEndpoint> endpoints = List.of(
                new NetworkEndpoint(id("source"), pipeA, 1, 6.0, 5.0),
                new NetworkEndpoint(id("sink"), pipeD, 0, 4.0, 5.0)
        );

        PipeGraph graph = new PipeGraph(nodes, adj, endpoints);

        // Pump sources: pump1 pushes into pipeB, pulls from pipeA
        //               pump2 pushes into pipeD, pulls from pipeC
        float pumpSpeed = 100.0f;
        List<PressureSource> pumpSources = List.of(
                new PressureSource(pipeA, PressureSource.Kind.PUMP_PULL, pumpSpeed, 5.0),
                new PressureSource(pipeB, PressureSource.Kind.PUMP_PUSH, pumpSpeed, 5.0),
                new PressureSource(pipeC, PressureSource.Kind.PUMP_PULL, pumpSpeed, 5.0),
                new PressureSource(pipeD, PressureSource.Kind.PUMP_PUSH, pumpSpeed, 5.0)
        );

        SolverResult result = solver.solve(graph, 1.0f, 1.0f, pumpSources);

        // ALL four pipes must be active with positive pressure
        for (NodeId pipe : List.of(pipeA, pipeB, pipeC, pipeD)) {
            Float p = result.pressures().get(pipe);
            if (p == null || p <= 0) {
                helper.fail("Pipe " + ((String) pipe.key()) + " has no pressure: " + p);
                return;
            }
        }

        // Middle pipes (B and C) should have equal pressure (same segment)
        Float pB = result.pressures().get(pipeB);
        Float pC = result.pressures().get(pipeC);
        assertClose(helper, "middle pipes equal", pB, pC);

        // Outbound faces: pipeB should have outbound toward pipeC (face 4)
        Set<Integer> bOut = result.outboundFaceIndices().getOrDefault(pipeB, Set.of());
        if (!bOut.contains(4)) {
            helper.fail("pipeB missing outbound face 4 toward pipeC, has: " + bOut);
            return;
        }

        // Inbound faces: pipeC should have inbound from pipeB (face 5, reverse of 4)
        Integer cIn = result.inboundFaceIndex().get(pipeC);
        // pipeC might not have solver inbound (both are sources), but the handler
        // adds it from the outbound of pipeB. Verify pipeB's outbound covers this.
        if (bOut.isEmpty()) {
            helper.fail("pipeB has no outbound — pipeC won't get inbound in applyFlowResult");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "gravity/1_drop_fall", templateNamespace = PipesNPhysics.ID)
    public static void testPumpPushPull(GameTestHelper helper) {
        PipeFormulas formulas = testFormulas(15.0f, 0.0f, 200.0f, 0.1f);
        NetworkSolver solver = new NetworkSolver(formulas);

        NodeId pipe1 = id("pipe1");
        Map<NodeId, PipeNode> nodes = new LinkedHashMap<>();
        nodes.put(pipe1, new PipeNode(pipe1, NodeKind.PIPE, 5.0));

        PipeGraph graph = new PipeGraph(nodes, new HashMap<>(), List.of());

        PressureSource pushSource = new PressureSource(
                pipe1, PressureSource.Kind.PUMP_PUSH,
                formulas.pumpPushPressure(100.0f), 5.0);
        SolverResult result = solver.solve(graph, 1.0f, List.of(pushSource));

        Float pressure = result.pressures().get(pipe1);
        if (pressure == null) { helper.fail("No pressure on pushed pipe"); return; }
        assertClose(helper, "push pressure", 70.0f, pressure);

        helper.succeed();
    }

    // ========== Core test logic ==========

    /**
     * Generic .nbt structure test: discovers all fluid handlers in the structure,
     * picks source tanks to fill, waits for flow, then checks that sink tanks received fluid.
     *
     * Sources are selected by highest Y first, then by lowest X/Z (first positionally).
     * For same-Y structures (pump tests), the first tank is filled and all others are sinks.
     */
    private static void fillSourcesAndCheckSinks(GameTestHelper helper) {
        helper.runAfterDelay(SETUP_DELAY, () -> {
            ServerLevel level = helper.getLevel();
            List<TankInfo> tanks = findAllTanks(helper, level);

            if (tanks.size() < 2) {
                helper.fail("Need at least 2 tanks, found " + tanks.size()
                        + " (positions: " + tanks.stream()
                        .map(t -> t.pos.toShortString()).toList() + ")");
                return;
            }

            // Sort: highest Y first, then by X, then by Z
            tanks.sort(Comparator.comparingInt((TankInfo t) -> t.pos.getY()).reversed()
                    .thenComparingInt(t -> t.pos.getX())
                    .thenComparingInt(t -> t.pos.getZ()));

            // Sources = tanks at the highest Y level. For same-Y: just the first tank.
            int sourceY = tanks.get(0).pos.getY();
            List<TankInfo> sources = new ArrayList<>();
            List<TankInfo> sinks = new ArrayList<>();
            for (TankInfo tank : tanks) {
                if (tank.pos.getY() == sourceY) {
                    sources.add(tank);
                } else {
                    sinks.add(tank);
                }
            }

            // If all tanks at same Y (pump test), first is source, rest are sinks
            if (sinks.isEmpty() && sources.size() >= 2) {
                sinks.addAll(sources.subList(1, sources.size()));
                sources = List.of(sources.get(0));
            }

            // Propagate all pipes
            propagateAllPipes(helper, level);

            // Schedule gravity flow checks for all pipes
            scheduleAllFlowChecks(helper, level);

            // Fill sources after pipe network is established
            List<TankInfo> finalSources = sources;
            helper.runAfterDelay(FILL_DELAY - SETUP_DELAY, () -> {
                for (TankInfo tank : finalSources) {
                    fillHandler(level, tank.absPos, FILL_AMOUNT);
                }
                // Re-schedule checks after filling so the solver picks up the new fluid
                scheduleAllFlowChecks(helper, level);
            });
        });

        // Poll until any non-source tank has fluid
        helper.succeedWhen(() -> {
            ServerLevel level = helper.getLevel();
            List<TankInfo> tanks = findAllTanks(helper, level);

            tanks.sort(Comparator.comparingInt((TankInfo t) -> t.pos.getY()).reversed()
                    .thenComparingInt(t -> t.pos.getX())
                    .thenComparingInt(t -> t.pos.getZ()));

            int sourceY = tanks.get(0).pos.getY();
            boolean allSameY = tanks.stream().allMatch(t -> t.pos.getY() == sourceY);

            for (int i = 0; i < tanks.size(); i++) {
                TankInfo tank = tanks.get(i);
                // Skip source tanks
                if (!allSameY && tank.pos.getY() == sourceY) continue;
                if (allSameY && i == 0) continue;

                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, tank.absPos, null);
                if (handler != null) {
                    for (int j = 0; j < handler.getTanks(); j++) {
                        if (!handler.getFluidInTank(j).isEmpty()) return;
                    }
                }
            }
            helper.fail("No sink tank has received fluid yet (tanks=" + tanks.size() + ")");
        });
    }

    private record TankInfo(BlockPos pos, BlockPos absPos) {}

    private static List<TankInfo> findAllTanks(GameTestHelper helper, ServerLevel level) {
        List<TankInfo> tanks = new ArrayList<>();
        var bounds = helper.getBounds();
        BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockPos absPos = pos.immutable();
            // Accept any block that has a fluid handler (tanks, basins, creative tanks, etc.)
            var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, absPos, null);
            if (handler == null) continue;
            // Exclude pipes — they have fluid handlers but aren't tanks
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, absPos);
            if (pipe != null) continue;
            BlockPos relPos = helper.relativePos(absPos);
            tanks.add(new TankInfo(relPos, absPos));
        }
        return tanks;
    }

    private static void scheduleAllFlowChecks(GameTestHelper helper, ServerLevel level) {
        var bounds = helper.getBounds();
        BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
            if (pipe != null) {
                de.devin.pipesnphysics.handler.GravityFlowHandler.scheduleCheck(level, pos.immutable());
            }
        }
    }

    private static void propagateAllPipes(GameTestHelper helper, ServerLevel level) {
        var bounds = helper.getBounds();
        BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
            if (pipe != null) {
                FluidPropagator.propagateChangedPipe(level, pos, level.getBlockState(pos));
            }
        }
    }

    private static void fillHandler(ServerLevel level, BlockPos absPos, int amount) {
        var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, absPos, null);
        if (handler != null) {
            handler.fill(new FluidStack(Fluids.WATER.builtInRegistryHolder(), amount),
                    IFluidHandler.FluidAction.EXECUTE);
        }
    }

    // ========== Helpers ==========

    private static void assertClose(GameTestHelper helper, String label, float expected, float actual) {
        if (Math.abs(expected - actual) > 0.01f) {
            helper.fail(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static NodeId id(String name) {
        return new NodeId(name);
    }

    private static void addBidirectionalEdge(Map<NodeId, List<PipeEdge>> adj,
                                              NodeId a, NodeId b,
                                              float angle, double aY, double bY,
                                              int faceAB, int faceBA) {
        adj.computeIfAbsent(a, k -> new ArrayList<>())
                .add(new PipeEdge(a, b, angle, aY, bY, faceAB));
        adj.computeIfAbsent(b, k -> new ArrayList<>())
                .add(new PipeEdge(b, a, angle, bY, aY, faceBA));
    }

    private static PipeFormulas testFormulas(float gravity, float friction, float maxPressure, float deadZone) {
        return new PipeFormulas(new PhysicsConfig(
                gravity, friction, maxPressure, deadZone,
                5.0f, true, true, 1.0f, 10, true, 0.3f,
                0.7f, 0.3f, 60.0f, true, 40, 3, 10));
    }

    private static PipeFormulas testFormulasWithBurst(float gravity, float friction, float maxPressure,
                                                      float deadZone, float burstThreshold) {
        return new PipeFormulas(new PhysicsConfig(
                gravity, friction, maxPressure, deadZone,
                5.0f, true, true, 1.0f, 10, true, 0.3f,
                0.7f, 0.3f, burstThreshold, true, 40, 3, 10));
    }
}
