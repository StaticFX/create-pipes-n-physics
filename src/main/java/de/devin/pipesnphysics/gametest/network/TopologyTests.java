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
import de.devin.pipesnphysics.engine.graph.GraphCache;
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
import de.devin.pipesnphysics.api.FluidHandlerApi;
import static de.devin.pipesnphysics.gametest.GameTestSupport.*;

/**
 * Graph topology, wake-on-edit, network coupling, closed-barrier isolation, full-sink backup.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class TopologyTests {

    /**
     * A pipe run that leaves a junction and loops back to the SAME junction (a ring main) must
     * stay in the graph. The contraction walk used to record it as a self-loop edge, which the
     * dedup dropped — its cells landed in NO node and NO edge, so they never flowed or settled
     * and /pipegraph silently omitted them (the "pipegraph doesn't include the pipe I'm looking
     * at" report). The builder now splits such a run at its middle cell (a promoted JUNCTION)
     * into two parallel edges, so every ring cell belongs to the graph.
     */
    @GameTest(template = "network/ring_loop", templateNamespace = PipesNPhysics.ID, timeoutTicks = 60)
    public static void loopBackToSameJunctionStaysInTheGraph(GameTestHelper helper) {
        // ring_loop: a 6-cell pipe ring (1,1,1)…(1,1,2) fed by one stub at (0,1,1) — the third
        // connection that promotes (1,1,1) to a junction.
        List<BlockPos> ring = List.of(
                new BlockPos(1, 1, 1), new BlockPos(2, 1, 1), new BlockPos(3, 1, 1),
                new BlockPos(3, 1, 2), new BlockPos(2, 1, 2), new BlockPos(1, 1, 2));
        BlockPos stub = new BlockPos(0, 1, 1);
        helper.runAfterDelay(5, () -> {
            Graph g = GraphBuilder.build(helper.getLevel(), helper.absolutePos(stub));
            for (BlockPos rel : ring) {
                BlockPos abs = helper.absolutePos(rel);
                boolean inGraph = g.nodeAt(abs) != null
                        || g.edges().stream().anyMatch(e -> e.pipes().contains(abs));
                if (!inGraph) {
                    helper.fail("ring cell " + rel.toShortString()
                            + " is in no node and no edge — the self-loop run was dropped");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * A multiblock tank's pipe connection can be far from an edited cell — outside findSeed's one-block
     * ring — so a break/place on a far corner would never wake the settled network. Build a 3-tall tank
     * whose base sits beside a pipe and edit its TOP cell (two blocks up); the wake must walk the whole
     * tank footprint and mark the base pipe URGENT.
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void tankEditFarFromPipeWakesNetwork(GameTestHelper helper) {
        BlockPos bottomRel = new BlockPos(2, 1, 0);       // beside the pipe at (1,1,0)
        BlockPos topRel = new BlockPos(2, 3, 0);          // two blocks up — out of findSeed's ring
        BlockPos pipeRel = new BlockPos(1, 1, 0);
        // The template ships a creative tank at the base slot; build a 3-tall REAL tank.
        helper.setBlock(bottomRel, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(2, 2, 0), AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.setBlock(topRel, AllBlocks.FLUID_TANK.get().defaultBlockState());

        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            if (!(level.getBlockEntity(helper.absolutePos(bottomRel)) instanceof FluidTankBlockEntity tank)
                    || tank.getControllerBE() == null
                    || ((FluidTankAccessor) (Object) tank.getControllerBE()).pipesnphysics$getHeight() < 3) {
                helper.fail("the 3-tall tank did not assemble into one controller");
                return;
            }
            BlockPos pipe = helper.absolutePos(pipeRel);
            NetworkEditHandler.wakeThroughTank(level, helper.absolutePos(topRel));
            if (!EngineTickHandler.hasPendingUrgent(level, pipe)) {
                helper.fail("editing a far multiblock-tank cell did not wake the pipe at its base");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Two pipe runs whose ONLY connection is a shared tank must build as ONE network — fluid flows
     * run→tank→run through the reservoir. Splice a tank into the middle of a straight run: the far tank
     * is then reachable only THROUGH it, and the graph seeded from the near end must still contain it.
     * Before the fix a tank was a terminal node, so the two halves were independent networks (each
     * solving the tank's fill blind to the other — a full pass-through tank then wrongly reported
     * "destination full" on its inflow run, while the pipes visibly flowed).
     */
    @GameTest(template = "common/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void tankCouplesTwoRunsIntoOneNetwork(GameTestHelper helper) {
        BlockPos midTank = new BlockPos(0, 1, 5); // spliced into the straight glass run
        BlockPos seed = new BlockPos(0, 1, 1);    // a pipe near one end
        BlockPos farTank = new BlockPos(0, 1, 9); // reachable only through the mid tank
        helper.setBlock(midTank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.runAfterDelay(4, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            boolean reachesFar = graph.nodes().stream()
                    .anyMatch(n -> n.pos().equals(helper.absolutePos(farTank)));
            if (!reachesFar) {
                helper.fail("a tank between two runs split the network — the far tank is unreachable "
                        + "(the graph has " + graph.nodes().size() + " nodes)");
                return;
            }
            helper.succeed();
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
    @GameTest(template = "common/double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
     * The "goofy_network" freeze: a pump chain lifts water from a source up a series line
     * source → header → BIG TANK → spout, where the terminal spout is FULL. The big tank has room,
     * so the pump-lifted water must back up and fill it (toward 100%). It currently freezes at 92%:
     * the one-shot solve routes a through-current to the full spout, the intermediate big tank reads
     * as a pass-through (net ~0), and {@code planTransfers} zeroes the whole line on the full
     * terminal — so a reservoir with room is starved by a full downstream sink. Reproduces the
     * user's "every pump says no room ahead"; draining the spout (their spout-pump fix) unfreezes it.
     */
    @GameTest(template = "topology/goofy_network", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void pumpFillsIntermediateTankDespiteFullTerminal(GameTestHelper helper) {
        int[] before = {-1};
        helper.runAfterDelay(40, () -> { // spin pumps up, then reproduce the screenshot's stuck fill state
            IFluidHandler big = pipesnphysics$goofyHandler(helper, 32000);
            IFluidHandler header = pipesnphysics$goofyHandler(helper, 8000, /*wantEmpty*/true);
            IFluidHandler spout = pipesnphysics$goofyHandler(helper, 1000);
            if (big == null || header == null) { System.out.println("GOOFY: could not find tanks"); return; }
            big.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            big.fill(new FluidStack(Fluids.WATER, 29558), IFluidHandler.FluidAction.EXECUTE); // 92%, as reported
            header.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            header.fill(new FluidStack(Fluids.WATER, 36), IFluidHandler.FluidAction.EXECUTE);
            if (spout != null) spout.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
            before[0] = big.getFluidInTank(0).getAmount();
        });
        helper.runAfterDelay(250, () -> {
            IFluidHandler big = pipesnphysics$goofyHandler(helper, 32000);
            int after = big == null ? -1 : big.getFluidInTank(0).getAmount();
            // The pump keeps lifting source water; the spout is full so it can't leave — the big tank
            // (which has room) MUST fill toward 100%. It currently freezes at 92% because a full
            // terminal sink zeroes the whole series line (the intermediate reservoir is starved).
            if (after <= before[0] + 100) {
                pipesnphysics$dumpGoofy(helper, "FAIL: intermediate tank starved by a full terminal");
                helper.fail("pump-fed intermediate tank starved by a full downstream sink: "
                        + before[0] + " -> " + after + " mB (expected it to fill toward 32000)");
                return;
            }
            // Force the big tank full and drain the HEADER (the single tank above). With the tank below
            // full, the header is the intermediate reservoir with room — the pump must still refill it.
            big.fill(new FluidStack(Fluids.WATER, 32000), IFluidHandler.FluidAction.EXECUTE);
            IFluidHandler header = pipesnphysics$goofyHandler(helper, 8000, true);
            if (header != null) header.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        });
        int[] hdr = {-1};
        helper.runAfterDelay(256, () -> {
            IFluidHandler header = pipesnphysics$goofyHandler(helper, 8000, true);
            hdr[0] = header == null ? -1 : header.getFluidInTank(0).getAmount();
        });
        helper.runAfterDelay(300, () -> {
            IFluidHandler header = pipesnphysics$goofyHandler(helper, 8000, true);
            int now = header == null ? -1 : header.getFluidInTank(0).getAmount();
            if (now <= hdr[0] + 100) {
                pipesnphysics$dumpGoofy(helper, "FAIL: header above a full tank did not refill");
                helper.fail("the single tank above a FULL tank did not refill: " + hdr[0]
                        + " -> " + now + " mB (the pump must still fill the intermediate reservoir)");
                return;
            }
            helper.succeed();
        });
    }

    private static IFluidHandler pipesnphysics$goofyHandler(GameTestHelper helper, int capacity) {
        return pipesnphysics$goofyHandler(helper, capacity, false);
    }

    /** Find a graph HANDLER whose total capacity matches; wantEmpty picks the highest-Y match (header vs source). */
    private static IFluidHandler pipesnphysics$goofyHandler(GameTestHelper helper, int capacity, boolean topmost) {
        Level level = helper.getLevel();
        BlockPos seed = new BlockPos(2, 1, 1); // goofy_network: encased pipe cell, seeds the whole graph
        Graph g = GraphBuilder.build(level, helper.absolutePos(seed));
        IFluidHandler best = null;
        int bestY = Integer.MIN_VALUE;
        for (Node n : g.nodes()) {
            if (n.kind() != Node.Kind.HANDLER) continue;
            IFluidHandler h = pipesnphysics$sideFallback(level, n.pos());
            if (h == null) continue;
            int cap = 0;
            for (int i = 0; i < h.getTanks(); i++) cap += h.getTankCapacity(i);
            if (cap != capacity) continue;
            if (!topmost) return h;
            if (n.pos().getY() > bestY) { bestY = n.pos().getY(); best = h; }
        }
        return best;
    }

    /** Print the full goofy_network solve to stdout (fail messages truncate at 1024). */
    private static void pipesnphysics$dumpGoofy(GameTestHelper helper, String label) {
        Level level = helper.getLevel();
        BlockPos seed = new BlockPos(2, 1, 1); // goofy_network: encased pipe cell, seeds the whole graph
        Graph g = GraphBuilder.build(level, helper.absolutePos(seed));
        Solution sol = FlowSolver.solve(level, g);
        StringBuilder sb = new StringBuilder("\nGOOFY === " + label + " ===\n");
        sb.append("GOOFY pumps=").append(g.pumps().size())
                .append(" runningPump=").append(EngineTickHandler.hasRunningPump(helper.getLevel(), g))
                .append(" active=").append(sol.active())
                .append(" transfers=").append(sol.transfers().size()).append("\n");
        for (Node n : g.nodes())
            sb.append(String.format("GOOFY  N%-2d %-8s head=%.3f ceil=%.3f%s%n",
                    n.index(), n.kind(), sol.nodeHeads().getOrDefault(n.index(), 0.0),
                    sol.nodeCeilings().getOrDefault(n.index(), 0.0), pipesnphysics$roomAt(level, n.pos())));
        for (Edge e : g.edges()) {
            String tag = sol.blockedEdges().contains(e.index()) ? " BLOCKED"
                    : sol.stalledEdges().contains(e.index()) ? " STALLED"
                    : sol.noHeadEdges().contains(e.index()) ? " NOHEAD" : "";
            Solution.Reason r = sol.edgeReasons().get(e.index());
            boolean rest = !sol.restFluids().getOrDefault(e.index(), FluidStack.EMPTY).isEmpty();
            boolean ef = !sol.edgeFluids().getOrDefault(e.index(), FluidStack.EMPTY).isEmpty();
            sb.append(String.format("GOOFY  E%-2d %d-%d len%d dir=%s%s%s rest=%b edgeFluid=%b%s%n",
                    e.index(), e.a(), e.b(), e.length(),
                    sol.edgeFlows().get(e.index()).direction(), tag, r == null ? "" : " (" + r + ")",
                    rest, ef, sol.heldEdges().contains(e.index()) ? " HELD" : ""));
        }
        for (Solution.Transfer t : sol.transfers())
            sb.append("GOOFY  T ").append(t.from().toShortString()).append(" -> ")
                    .append(t.to().toShortString()).append(" ").append(t.fluid().getAmount()).append("\n");
        System.out.println(sb);
    }

    /** " content/capacity mB (has room)" for a fluid handler at pos, or "" if none — for diagnostics. */
    private static String pipesnphysics$roomAt(Level level, BlockPos pos) {
        IFluidHandler h = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (h == null) return "";
        int content = 0, capacity = 0;
        for (int i = 0; i < h.getTanks(); i++) {
            content += h.getFluidInTank(i).getAmount();
            capacity += h.getTankCapacity(i);
        }
        return String.format("  %d/%d mB%s", content, capacity, content < capacity ? " (ROOM)" : " (full)");
    }

    /**
     * Two live networks may legitimately share a coverage cell — the gap block two open mouths face
     * after a run breaks — which the cache claims first-come (putIfAbsent). The graph→entry lookup
     * must resolve by IDENTITY: it used to go through an arbitrary (hash-ordered) coverage cell, so
     * a network whose shared cell came up first lost its own entry — its solves went unrecorded (a
     * busy network kept the IDLE TTL) and its expiry never clamped the sleep. Six stacked pairs
     * make the arbitrary-cell miss near-certain without the identity map.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 60)
    public static void cachedSiblingsSharingAMouthCellKeepTheirOwnEntries(GameTestHelper helper) {
        var pipe = AllBlocks.FLUID_PIPE.get();
        int[] layers = {3, 5, 7, 9, 11, 13}; // spaced so the auto-connecting pipes never merge
        for (int y : layers) {
            helper.setBlock(new BlockPos(0, y, 1), pipeState(pipe, Direction.EAST));
            helper.setBlock(new BlockPos(2, y, 1), pipeState(pipe, Direction.WEST));
        }
        helper.runAfterDelay(5, () -> {
            ServerLevel level = helper.getLevel();
            boolean cacheWasOn = PipesNPhysicsConfig.ENABLE_NETWORK_CACHE.get();
            PipesNPhysicsConfig.ENABLE_NETWORK_CACHE.set(true);
            try {
                long now = level.getGameTime();
                for (int y : layers) {
                    Graph a = GraphBuilder.build(level, helper.absolutePos(new BlockPos(0, y, 1)));
                    Graph b = GraphBuilder.build(level, helper.absolutePos(new BlockPos(2, y, 1)));
                    GraphCache.store(level, a, now);
                    GraphCache.store(level, b, now);
                    if (GraphCache.expiry(level, a) == Long.MAX_VALUE
                            || GraphCache.expiry(level, b) == Long.MAX_VALUE) {
                        helper.fail("a cached network sharing its mouth cell with a sibling lost its"
                                + " own cache entry (layer y=" + y + ")");
                        return;
                    }
                }
            } finally {
                PipesNPhysicsConfig.ENABLE_NETWORK_CACHE.set(cacheWasOn);
            }
            helper.succeed();
        });
    }

    /**
     * A block DECLARED multi-port keeps its pipe runs in SEPARATE networks. The engine otherwise
     * couples every run touching a handler into one graph — right for a tank or a basin, where two
     * pipes really do open into one body of fluid, and wrong for a machine whose connections are
     * separate internal tanks behind one combined capability: joining them is what lets a fluid
     * enter by one port and leave by another (a TFMG engine's fuel, air and exhaust manifolds
     * arrive as ONE network, so exhaust can back-fill a fuel tank that has run dry).
     *
     * Declared through {@link FluidHandlerApi} on the {@link TestSideHandlers} machine fixture,
     * which is side-AGNOSTIC — one handler object on every face — so nothing but the declaration
     * can decouple it, and the same rig with the declaration cleared is the control: it must come
     * back as one network, or the test would pass against a rig that never joined up.
     * Own batch: the declaration is global and outlives a tick.
     */
    @GameTest(template = "physics/collision_u_below", templateNamespace = PipesNPhysics.ID,
            timeoutTicks = 120, batch = "separatePorts")
    public static void declaredMultiPortMachineKeepsItsRunsApart(GameTestHelper helper) {
        Block pipe = AllBlocks.FLUID_PIPE.get();
        BlockPos machine = new BlockPos(3, 1, 1);
        BlockPos westRun = new BlockPos(2, 1, 1);
        BlockPos eastRun = new BlockPos(4, 1, 1);
        TestSideHandlers.clear();
        // Solid caps: Create straightens a pipe left with one connection, so a lone end pipe would
        // open its far face and join the graph as a mouth instead of a dead end.
        helper.setBlock(new BlockPos(0, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(6, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, 1), pipeState(pipe, Direction.EAST));
        helper.setBlock(westRun, pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(machine, Blocks.DIAMOND_BLOCK);
        helper.setBlock(eastRun, pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(5, 1, 1), pipeState(pipe, Direction.WEST));

        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos machinePos = helper.absolutePos(machine);
            TestSideHandlers.machineInputAt(machinePos)
                    .setFluid(new FluidStack(Fluids.WATER, TestSideHandlers.TANK_CAPACITY));
            try {
                // The CONTROL first: undeclared, this is an ordinary side-agnostic handler and both
                // runs must land in one graph.
                FluidHandlerApi.clearSeparatePorts(Blocks.DIAMOND_BLOCK);
                if (!GraphBuilder.build(level, helper.absolutePos(westRun)).coverage()
                        .contains(helper.absolutePos(eastRun))) {
                    helper.fail("the rig never joined up undeclared — a coupled handler must put"
                            + " both runs in one network, so the declared case proves nothing");
                    return;
                }
                FluidHandlerApi.declareSeparatePorts(Blocks.DIAMOND_BLOCK);
                Graph west = GraphBuilder.build(level, helper.absolutePos(westRun));
                if (west.coverage().contains(helper.absolutePos(eastRun))) {
                    helper.fail("a declared multi-port machine still coupled its runs: the west"
                            + " network reaches the east one through the block");
                    return;
                }
                // It must still be an ENDPOINT of the run that reaches it — decoupled, not dropped.
                Node node = west.nodeAt(machinePos);
                if (node == null || !node.isHandler()) {
                    helper.fail("the declared machine stopped being a handler node entirely");
                    return;
                }
                if (node.accessFace() != null) {
                    helper.fail("decoupling must not make the machine side-specific (accessFace="
                            + node.accessFace() + ") — its handler is the same object on every face");
                    return;
                }
                helper.succeed();
            } finally {
                FluidHandlerApi.clearSeparatePorts(Blocks.DIAMOND_BLOCK);
                TestSideHandlers.clear();
            }
        });
    }
}
