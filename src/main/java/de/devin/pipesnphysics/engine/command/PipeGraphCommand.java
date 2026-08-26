package de.devin.pipesnphysics.engine.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.pipes.VanillaFluidTargets;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.FlowTrace;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.MouthConditions;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.FluidCaps;
import de.devin.pipesnphysics.engine.boundary.HandlerRoles;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphCache;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.net.GraphOverlayPayload;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.net.PumpRangePayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.probe.PumpRangeProbe;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.store.PipeWindow;
import de.devin.pipesnphysics.engine.turbine.HydroTurbine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pipegraph}: build the network at the player's crosshair, run the solver,
 * dump the result as chat lines, and send an in-world overlay to the same player.
 *
 * Intended for inspecting topology and verifying flow direction during development.
 */
public final class PipeGraphCommand {
    /** Read the engine's own solution when it is at most this many ticks old — the goggle's window. */
    private static final int SOLUTION_MAX_AGE_TICKS = 4;

    private PipeGraphCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pipegraph")
                .requires(s -> s.hasPermission(0))
                .executes(PipeGraphCommand::run));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be run by a player"));
            return 0;
        }

        HitResult hit = player.pick(20.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Not looking at a block"));
            return 0;
        }
        BlockPos target = ((BlockHitResult) hit).getBlockPos();
        ServerLevel level = player.serverLevel();

        Report report = new Report(player);
        Graph graph = FluidEngine.buildGraph(level, target);
        if (graph.isEmpty()) {
            // Not a pipe seed — inspect the block itself: what the engine classifies it as and why fluid
            // does (or does not) flow to it, then dump the network it touches, if any.
            sendBlockReport(report, level, target);
            report.flush();
            return 1;
        }
        // Read the engine's OWN recent solution (the same source the goggle reads), not an
        // independent single-tick solve — else a bursty flow the goggle shows reads as idle here.
        Resolved resolved = recentSolve(level, target);
        if (resolved == null) return 1; // network vanished between build and solve

        sendText(report, level, resolved.graph(), resolved.solution(), target);
        report.flush();
        PacketDistributor.sendToPlayer(player,
                buildPayload(level, resolved.graph(), resolved.solution(), target));
        return 1;
    }

    /**
     * Build a live /pipegraph overlay for a seed — the server side of the {@code GraphOverlayRequest}
     * refresh the client fires while an overlay is on screen. Null when the seed no longer roots a
     * network. Uses the engine's recent solution so the in-world graph tracks a live/bursty flow.
     */
    public static GraphOverlayPayload buildOverlay(ServerLevel level, BlockPos seed) {
        Resolved resolved = recentSolve(level, seed);
        return resolved == null ? null
                : buildPayload(level, resolved.graph(), resolved.solution(), seed);
    }

    /**
     * The engine's cached graph + recent solution for a seed — the SAME data the goggle reads, so a
     * flow the goggle shows appears here too — falling back to a fresh build+solve when nothing is
     * cached. Null when the seed no longer roots a network.
     */
    private static Resolved recentSolve(ServerLevel level, BlockPos seed) {
        long now = level.getGameTime();
        Graph graph = GraphCache.get(level, seed, now);
        Solution solution = graph == null ? null
                : GraphCache.recentSolution(level, graph, now, SOLUTION_MAX_AGE_TICKS);
        if (graph == null) {
            graph = FluidEngine.buildGraph(level, seed);
            if (graph.isEmpty()) return null;
            GraphCache.store(level, graph, now);
        }
        if (solution == null) solution = FlowSolver.solve(level, graph);
        return new Resolved(graph, solution);
    }

    private record Resolved(Graph graph, Solution solution) {}

    /**
     * "What the engine sees" for a block that is NOT a pipe seed — a foreign tank/machine, or an
     * arbitrary block the crosshair landed on. Walks the whole classification chain that decides whether
     * fluid flows TO it: its fluid capability (and on which faces), whether it is a vanilla fluid target
     * routed to an open end, the ROLE the engine assigns and why, the live drain/fill probe, how the
     * boundary column resolves (the wall that can leave a block holding fluid it still won't move), and
     * whether an adjacent pipe actually opens toward it. When it touches a network the normal graph dump
     * follows. This is the compat-authoring counterpart to the player pipe goggle.
     */
    private static void sendBlockReport(Report report, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        report.line("§e--- Engine view: §f" + state.getBlock().getName().getString()
                + " §7@ " + pos.toShortString() + " §e---");

        // Fluid capability, and on which faces. No handler anywhere and not a vanilla target → the engine
        // can never see it as a tank; a side-specific block serves its own network per face.
        IFluidHandler nullSide = FluidCaps.at(level, pos, null);
        List<Direction> faces = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (FluidCaps.at(level, pos, d) != null) faces.add(d);
        }
        boolean vanilla = VanillaFluidTargets.canProvideFluidWithoutCapability(state);
        if (vanilla) {
            // Even with a NeoForge cap (a cauldron), the engine deliberately skips these in the handler
            // branch and drinks them through an open pipe MOUTH — so its handler role is moot.
            report.line("§7Fluid cap: §evanilla fluid target §7— drained as an OPEN_END through an open pipe mouth, not a tank node");
        } else if (nullSide != null) {
            report.line("§7Fluid cap: §aside-agnostic §7(couples every run that touches it)");
        } else if (!faces.isEmpty()) {
            report.line("§7Fluid cap: §eside-specific §7on faces §f" + faces
                    + " §7(each face is its own tank / network)");
        } else {
            report.line("§7Fluid cap: §cnone §7— the engine cannot treat this block as a tank");
        }

        // Role + relay-detector state, only meaningful for a real handler node (not a vanilla open-end target).
        if (!vanilla && (nullSide != null || !faces.isEmpty())) {
            report.line("§7Role: §f" + HandlerRoles.explain(level, pos));
            if (!HandlerRoles.hasExplicitRole(state) && PipesNPhysicsConfig.AUTO_DETECT_RELAY_HANDLERS.get()) {
                int strikes = RelayDetector.strikeCount(pos);
                if (RelayDetector.isRelay(pos)) {
                    report.line("§7Detector: §elearned relay §7this session (this position)");
                } else if (strikes > 0) {
                    report.line("§7Detector: §f" + strikes + " §7spontaneous-gain strike(s) so far");
                }
            }
            sendFaceProbe(report, level, pos, nullSide);
            report.line("§7Column (what the engine resolves): §f"
                    + columnReport(level, pos, resolveAccessFace(level, pos)));
        }

        // Is an adjacent pipe/pump actually plumbed toward this block? Then dump the network it touches.
        BlockPos seed = reportNeighbors(report, level, pos);
        if (seed != null) {
            Graph g = FluidEngine.buildGraph(level, seed);
            if (!g.isEmpty()) {
                Solution s = FluidEngine.solveFresh(level, seed);
                report.line("§7— network it connects to (seed " + seed.toShortString() + ") —");
                sendText(report, level, g, s, pos);
                PacketDistributor.sendToPlayer(report.player(), buildPayload(level, g, s, seed));
            }
        }
    }

    /**
     * The fluid handler PER FACE — the crux of "why won't it flow to this block." A machine can expose its
     * OUTPUT on only one face (a coke oven's CO2 on top) alongside a separate empty/input handler on the
     * {@code null} side and other faces; the engine resolves the {@code null} side FIRST, so it reads the
     * block EMPTY and never drains the face that actually gives. This dumps the {@code null} side and every
     * face's holds/give/take, flagging any face that gives MORE than the {@code null} side — the handler the
     * engine should be reading through.
     */
    private static void sendFaceProbe(Report report, ServerLevel level, BlockPos pos, IFluidHandler nullSide) {
        FluidStack probe = probeFluid(level, pos);
        int nullGive = nullSide == null ? -1 : nullSide.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
        report.line("§7Handler per face §8(probe " + probe.getHoverName().getString() + "):");
        if (nullSide != null) report.line("§d  null-side: " + oneProbe(nullSide, probe));
        for (Direction d : Direction.values()) {
            IFluidHandler cap = FluidCaps.at(level, pos, d);
            if (cap == null) continue;
            int give = cap.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
            String flag = nullSide != null && cap != nullSide && give > nullGive
                    ? " §e← gives more than null-side; engine reads null → sees this block EMPTY" : "";
            report.line("§d  " + d + ": " + oneProbe(cap, probe) + flag);
        }
    }

    /** One handler's live HOLDS / GIVE (drain) / TAKE (fill) probe against {@code probe}, with its fluid name. */
    private static String oneProbe(IFluidHandler cap, FluidStack probe) {
        FluidStack held = cap.getTanks() > 0 ? cap.getFluidInTank(0) : FluidStack.EMPTY;
        int give = cap.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
        int take = cap.fill(probe, FluidAction.SIMULATE);
        return String.format("holds=%d give=%d take=%d%s", held.getAmount(), give, take,
                held.isEmpty() ? "" : " (" + held.getHoverName().getString() + ")");
    }

    /**
     * The face the engine resolves this block through, mirroring {@code GraphBuilder}: a connecting
     * (pipe/pump) face whose handler DIFFERS from the null side — the per-face endpoint (a coke oven's CO2
     * top) — else null for a side-agnostic block that resolves through its shared null handler. Lets the
     * "Column" line report the SAME tank the network actually reads, not the null side.
     */
    private static Direction resolveAccessFace(ServerLevel level, BlockPos pos) {
        IFluidHandler nullCap = FluidCaps.at(level, pos, null);
        Direction differing = null;
        for (Direction d : Direction.values()) {
            IFluidHandler cap = FluidCaps.at(level, pos, d);
            if (cap == null || cap == nullCap) continue; // side-agnostic on this face → resolve null
            BlockPos neighbor = pos.relative(d);
            if (FluidPropagator.getPipe(level, neighbor) != null
                    || level.getBlockState(neighbor).getBlock() instanceof PumpBlock) {
                return d; // a plumbed face that differs from null — exactly the engine's access face
            }
            if (differing == null) differing = d;
        }
        return differing;
    }

    /** The first fluid any face (or the null side) of this block holds, or water — what to probe fills with. */
    private static FluidStack probeFluid(ServerLevel level, BlockPos pos) {
        IFluidHandler nullCap = FluidCaps.at(level, pos, null);
        if (nullCap != null && nullCap.getTanks() > 0 && !nullCap.getFluidInTank(0).isEmpty()) {
            return nullCap.getFluidInTank(0).copyWithAmount(1000);
        }
        for (Direction d : Direction.values()) {
            IFluidHandler cap = FluidCaps.at(level, pos, d);
            if (cap != null && cap.getTanks() > 0 && !cap.getFluidInTank(0).isEmpty()) {
                return cap.getFluidInTank(0).copyWithAmount(1000);
            }
        }
        return new FluidStack(Fluids.WATER, 1000);
    }

    /**
     * How the engine's boundary column resolves this handler — the wall that can leave a block holding
     * fluid it still will not move: a finite reservoir surface-equalizes and lip-gates, an EMPTY one is
     * receive-only, a FULL one is give-only, a relay/pulley is a bottomless one-way endpoint. Builds the
     * same synthetic HANDLER node the solver would (its {@code accessFace} for a side-specific block) and
     * runs the real {@link BoundaryColumn#resolve}.
     */
    private static String columnReport(ServerLevel level, BlockPos pos, Direction accessFace) {
        Node synthetic = new Node(0, pos, Node.Kind.HANDLER, SableCompat.getWorldY(level, pos), null, null, accessFace);
        BoundaryColumn column = BoundaryColumn.resolve(level, synthetic);
        if (column == null) return "does not resolve (no live handler / multiblock mid-assembly)";
        String role = !column.isFiniteReservoir()
                ? (column.isInfiniteSource()
                        ? "bottomless SOURCE — one-way, drain-priority"
                        : "bottomless SINK — one-way, receive-only")
                : column.isEmpty() ? "finite reservoir, EMPTY → receive-only (fills, never drains)"
                : column.contentMb() >= column.capacityMb() ? "finite reservoir, FULL → give-only (drains, never fills)"
                : "finite reservoir — surface-equalized, lip-gated";
        return String.format("%s §7[%d/%d mB, %.0f%%]", role,
                column.contentMb(), column.capacityMb(), column.fillFraction() * 100);
    }

    /**
     * Report, per face, whether an adjacent pipe or pump is actually plumbed toward this block — the
     * silent "no connection" cause (a pipe that does not open back, common on stale Sable assemblies).
     * Returns a seed to dump the touched network, preferring a pipe that genuinely opens toward the
     * block; null when nothing adjacent connects.
     */
    private static BlockPos reportNeighbors(Report report, ServerLevel level, BlockPos pos) {
        BlockPos seed = null;
        boolean any = false;
        for (Direction face : Direction.values()) {
            BlockPos neighbor = pos.relative(face);
            if (!level.isLoaded(neighbor)) continue;
            BlockState nState = level.getBlockState(neighbor);
            if (nState.getBlock() instanceof PumpBlock) {
                report.line("§7  " + face + ": §badjacent pump");
                any = true;
                if (seed == null) seed = neighbor.immutable();
                continue;
            }
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, neighbor);
            if (pipe == null) continue;
            any = true;
            boolean opensBack = pipe.canHaveFlowToward(nState, face.getOpposite());
            report.line("§7  " + face + ": pipe " + (opensBack
                    ? "§aopens toward it ✓"
                    : "§cpresent but does NOT open back ✗ §7(stale connection / not plumbed this side)"));
            if (opensBack) seed = neighbor.immutable();          // prefer a genuinely-connected pipe
            else if (seed == null) seed = neighbor.immutable();  // still dump the network so the graph shows
        }
        if (!any) report.line("§7Neighbours: §cno adjacent pipe or pump §7— nothing to connect to");
        return seed;
    }

    private static void sendText(Report report, ServerLevel level, Graph g, Solution s, BlockPos target) {
        report.line("§e--- Pipe Graph ---");
        report.line("§7Nodes: §f" + g.nodes().size() + "  §7Edges: §f" + g.edges().size());
        report.line(locateTarget(g, target));
        String reached = targetReach(level, g, target);
        if (reached != null) report.line(reached);
        printNodes(report, level, g, s, target);
        sendFluidStats(report, level, g);
        report.line("§e--- Edges --- §8(recent = actual mB/t per solve, newest→oldest; → a→b, ← b→a, · no solved direction, … skipped ticks)");
        printEdges(report, level, g, s, target);
        printTransfers(report, s);
    }

    /**
     * Where the flagged (crosshair) position sits in the contracted graph — the whole reason a
     * player runs /pipegraph on a specific pipe. A mid-run cell appears in no node or edge line
     * (an edge prints only its two endpoints), so without this callout the dump never contains
     * the coordinates of the very pipe being flagged.
     */
    private static String locateTarget(Graph g, BlockPos target) {
        Node node = g.nodeAt(target);
        if (node != null) {
            return "§6Flagged: §f" + target.toShortString() + " §7— node §f" + node.kind();
        }
        for (Edge e : g.edges()) {
            int i = e.pipes().indexOf(target);
            if (i < 0) continue;
            return String.format("§6Flagged: §f%s §7— edge §e%s §7cell §f%d§7/%d (%s ↔ %s)",
                    target.toShortString(), GraphOverlayPayload.edgeLetter(e.index()),
                    i + 1, e.pipes().size(),
                    g.node(e.a()).pos().toShortString(), g.node(e.b()).pos().toShortString());
        }
        return "§6Flagged: §f" + target.toShortString() + " §7— §cnot part of this network";
    }

    /**
     * What the range overlay knows about the FLAGGED cell, from every pump that reaches it: the
     * margin it carries, the span its colour ramp normalizes over, and how far it stands above the
     * supply surface. That is the whole input to the tint, so a pipe that comes out an unexpected
     * colour — or comes out bare in the middle of a painted run — can be read off the dump instead
     * of guessed at from a screenshot. Null when no pump's walk reaches it, which is itself the
     * answer for an unpainted cell: the walk never went there.
     */
    private static String targetReach(ServerLevel level, Graph g, BlockPos target) {
        StringBuilder out = new StringBuilder();
        for (Node pump : g.pumps()) {
            PumpRangePayload range = PumpRangeProbe.probe(level, pump.pos());
            for (PumpRangePayload.RangePath path : range.paths()) {
                if (path.cells().isEmpty()) continue;
                float span = path.cells().get(0).margin();
                for (PumpRangePayload.RangeCell cell : path.cells()) {
                    if (cell.pos() != target.asLong()) continue;
                    out.append(String.format("      §7overlay: pump §f%s §7%s §7margin §f%+.2f§7"
                                    + " of §f%.2f§7, above supply §f%+.2f§7%s",
                            pump.pos().toShortString(), path.pull() ? "pull" : "push",
                            cell.margin(), span, cell.aboveSupply(),
                            cell.pipe() ? "" : " §8(not a pipe — never painted)"));
                    return out.toString(); // one report is enough; a second path repeats it
                }
            }
        }
        return g.pumps().isEmpty() ? null
                : "      §8overlay: no pump's reach walk visits this cell (so it is never painted)";
    }

    /**
     * A pump's reach, read from the range probe itself rather than re-derived, so the dump and the
     * overlay cannot disagree: how many blocks of margin each flank has AT THE PUMP (which is also
     * the span its colour ramp normalizes over) and the elevation each one runs out at — the push
     * CEILING above and the drawable FLOOR below. A stopped pump reaches nowhere and says so.
     */
    private static String pumpReachLine(ServerLevel level, Node pump) {
        PumpRangePayload range = PumpRangeProbe.probe(level, pump.pos());
        Float push = null;
        Float pull = null;
        for (PumpRangePayload.RangePath path : range.paths()) {
            if (path.cells().isEmpty()) continue;
            float margin = path.cells().get(0).margin();
            if (path.pull()) {
                if (pull == null) pull = margin;
            } else if (push == null) {
                push = margin;
            }
        }
        if (push == null && pull == null) return "      §8reach: none (the pump is not turning)";
        double y = pump.worldY();
        return String.format("      §7reach: push §f%s §7· pull §f%s",
                push == null ? "—" : String.format("%.2f ↑ (ceiling %.2f)", push, y + push),
                pull == null ? "—" : String.format("%.2f ↓ (floor %.2f)", pull, y - pull));
    }

    /** One chat line per node (position, kind, heads, RPM) plus its fluid/pulley/probe/dock detail lines. */
    private static void printNodes(Report report, ServerLevel level, Graph g, Solution s, BlockPos target) {
        MouthConditions mouths = MouthConditions.of(level, g);
        FluidStack probeFluid = firstPresentFluid(level, g);
        for (Node n : g.nodes()) {
            Double head = s.nodeHeads().get(n.index());
            Double ceiling = s.nodeCeilings().get(n.index());
            String block = blockName(level, n);
            report.line(String.format("  §f%s §7%s §b%s §7y=§f%.1f%s%s%s%s%s%s",
                    n.pos().toShortString(), n.kind(), block,
                    n.worldY(),
                    head != null ? String.format(" §7head=§f%.2f", head) : "",
                    ceiling != null ? String.format(" §7ceil=§b%.2f", ceiling) : " §8ceil=∅",
                    n.pumpFacing() != null ? " §7face=§f" + n.pumpFacing() : "",
                    n.isPump() ? String.format(" §7rpm=§f%.0f", pumpSpeed(level, n)) : "",
                    n.isPump() && FlowSolver.isTurbine(level, n)
                            ? String.format(" §aturbine §7su=§f%.0f", turbineStress(level, n)) : "",
                    n.isOneWayGate() ? " §done-way §f" + n.gateFlow() : "",
                    n.pos().equals(target) ? " §6← flagged" : ""));
            // A pump's REACH — the very numbers the range overlay paints from. Without them a
            // surprising tint ("why is half of this red?") can only be guessed at: both bounds
            // are elevations, and no other line in the dump carries them.
            if (n.isPump()) {
                String reach = pumpReachLine(level, n);
                if (reach != null) report.line(reach);
            }
            BoundaryColumn column = columnOf(level, mouths, n);
            if (column != null && !column.contents().isEmpty() && column.contentMb() > 0) {
                report.line("      §7" + (n.isOpenEnd()
                        ? "draws §f" + column.contents().getHoverName().getString()
                        : fluidSummary(column)));
            }
            // A junction/gate cell is a one-cell slot fluid must traverse — show its live content,
            // else the dump reads as if junctions could not hold fluid at all.
            if (n.isClosedGate() || n.kind() == Node.Kind.JUNCTION) {
                PipeStore.Store slot = PipeStore.at(level, n.pos());
                if (slot != null) {
                    report.line(String.format("      §7slot holds §f%d§7/%d mB%s", slot.amount(),
                            PipeStore.capacityMb(), slot.amount() > 0
                                    ? " §f" + slot.fluid().getHoverName().getString() : ""));
                }
            }
            // A non-default handler role (tag, code, or a learned relay) changes how the column
            // resolves — surface it here, or a demoted handler is indistinguishable from a tank.
            if (n.isHandler()) {
                BlockState state = level.getBlockState(n.pos());
                if (HandlerRoles.hasExplicitRole(state) || HandlerRoles.isRelayEndpoint(level, n.pos())) {
                    report.line("      §erole: " + HandlerRoles.explain(level, n.pos()));
                }
            }
            String pulley = pulleyDiagnostic(level, n);
            if (pulley != null) report.line("      §c" + pulley);
            String probe = handlerProbe(level, n, probeFluid);
            if (probe != null) report.line("      §d" + probe);
            String dock = dockingDiagnostic(level, n);
            if (dock != null) report.line("      §6" + dock);
        }
    }

    /**
     * One chat line per edge — endpoints, length, status word, the solved vs actual rate — plus
     * a holds sub-line with the run's stored fluid cell by cell, and the gravity draw-lip
     * elevation at each handler end's opening (where that tank stops giving).
     */
    private static void printEdges(Report report, ServerLevel level, Graph g, Solution s, BlockPos target) {
        for (Edge e : g.edges()) {
            EdgeFlow flow = s.edgeFlows().get(e.index());
            int rate = PipeProbe.actualEdgeFlow(g, s, e); // mB actually moved, not the hydraulic flow
            String dir = switch (flow.direction()) {
                case A_TO_B -> "a→b";
                case B_TO_A -> "b→a";
                case NONE -> "idle";
            };
            if (rate == 0) dir = "idle";
            if (s.stalledEdges().contains(e.index())) dir = "§6stalled§7";
            if (s.noHeadEdges().contains(e.index())) dir = "§cno head§7";
            if (s.heldEdges().contains(e.index())) {
                Double h = heldHead(s, e);
                dir = h != null ? String.format("§dheld §7(stored §f%.2f§7)", h) : "§dheld§7";
            }
            Solution.Reason reason = s.edgeReasons().get(e.index());
            // The goggle's story hierarchy, mirrored so this dump never contradicts it: (1) a
            // path wall (VALVE/CREST) with nothing to stop is really a starved/dead-ended pump
            // (PipeProbe.starvedDryEdges); (2) a no-rise CREST with real fluid is "the supply
            // can't reach the opening" (PipeProbe.supplyBelowOpening); (3) else the wall itself.
            Byte starvedCause = reason == Solution.Reason.VALVE || reason == Solution.Reason.CREST
                    || reason == Solution.Reason.CHECK_VALVE
                    ? PipeProbe.starvedDryEdges(level, g, s).get(e.index()) : null;
            String reasonTag = reason == null ? ""
                    : starvedCause != null
                            ? " §8[" + reason + " — nothing to "
                                    + (reason == Solution.Reason.VALVE ? "filter"
                                            : reason == Solution.Reason.CHECK_VALVE ? "pass" : "lift") + "; "
                                    + (starvedCause == PipeStatusPayload.DETAIL_PUMP_NO_OUTPUT
                                            ? "pump has no output" : "pump can't pull its supply")
                                    + "]"
                    : reason == Solution.Reason.CREST && PipeProbe.supplyBelowOpening(level, g, e)
                            ? " §8[CREST — supply below opening]"
                            : " §8[" + reason + "]";
            Node a = g.node(e.a()), b = g.node(e.b());
            report.line(String.format("  §e%s §f%s §7↔ §f%s §7len=%d §7%s §7solved=%d actual=%d mB/t%s%s",
                    GraphOverlayPayload.edgeLetter(e.index()),
                    a.pos().toShortString(), b.pos().toShortString(),
                    e.length(), dir, flow.mbPerTick(), rate,
                    reasonTag,
                    e.pipes().contains(target) ? " §6← flagged" : ""));
            String holds = holdsLine(level, e);
            if (holds != null) report.line("      " + holds);
            String lips = lipLine(level, g, e);
            if (lips != null) report.line("      " + lips);
            String recent = recentLine(level, g, e);
            if (recent != null) report.line("      " + recent);
        }
    }

    /**
     * The edge's last few solves' ACTUAL movement as one strip, newest first — an oscillation
     * (a limit cycle ping-ponging a few mB) reads directly off a single dump instead of needing
     * dumps taken ticks apart. Ticks the network slept between samples show as an ellipsis.
     */
    private static String recentLine(ServerLevel level, Graph g, Edge e) {
        List<FlowTrace.Sample> samples = FlowTrace.recent(level, g, e);
        if (samples.size() < 2) return null;
        StringBuilder line = new StringBuilder("§7recent §f");
        long prev = Long.MIN_VALUE;
        for (FlowTrace.Sample s : samples) {
            if (prev != Long.MIN_VALUE && prev - s.tick() > 1) line.append("§8…§f ");
            prev = s.tick();
            line.append(s.mb() == 0 ? "0" : switch (s.dir()) {
                case A_TO_B -> "→" + s.mb();
                case B_TO_A -> "←" + s.mb();
                case NONE -> "·" + s.mb();
            }).append(' ');
        }
        return line.toString().stripTrailing();
    }

    /**
     * The run's stored fluid: total over capacity plus the cell-by-cell breakdown, a-end first. Each
     * non-empty cell shows what it holds — just its mB when the whole run carries one fluid, or
     * {@code mB:Fluid} per cell once the run is MIXED (a collision front, a switched fluid mid-flow),
     * so "what each pipe holds in terms of fluid" is legible rather than a single run-wide label.
     */
    private static String holdsLine(ServerLevel level, Edge e) {
        if (PipeStore.capacityMb() <= 0 || e.pipes().isEmpty()) return null;
        int count = e.pipes().size();
        int[] amounts = new int[count];
        FluidStack[] fluids = new FluidStack[count];
        FluidStack header = FluidStack.EMPTY;
        int total = 0;
        boolean mixed = false;
        for (int i = 0; i < count; i++) {
            PipeStore.Store cell = PipeStore.at(level, e.pipes().get(i));
            amounts[i] = cell == null ? 0 : cell.amount();
            fluids[i] = cell == null ? FluidStack.EMPTY : cell.fluid();
            total += amounts[i];
            if (amounts[i] > 0) {
                if (header.isEmpty()) header = fluids[i];
                else if (!FluidStack.isSameFluidSameComponents(header, fluids[i])) mixed = true;
            }
        }
        StringBuilder cells = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) cells.append(' ');
            cells.append(amounts[i]);
            if (mixed && amounts[i] > 0) cells.append(':').append(fluids[i].getHoverName().getString());
        }
        return String.format("§7holds §f%d§7/%d mB%s §8cells %s", total,
                count * PipeStore.capacityMb(),
                header.isEmpty() ? "" : " §f" + header.getHoverName().getString() + "§7", cells);
    }

    /** The gravity draw-lip elevation of each handler end's opening cell ({@code PipeWindow.lipY}). */
    private static String lipLine(ServerLevel level, Graph g, Edge e) {
        String a = endLip(level, g, e, e.a(), "a");
        String b = endLip(level, g, e, e.b(), "b");
        if (a == null && b == null) return null;
        return "§7lip" + (a != null ? " " + a : "") + (b != null ? " " + b : "");
    }

    private static String endLip(ServerLevel level, Graph g, Edge e, int nodeIndex, String label) {
        if (!g.node(nodeIndex).isHandler()) return null;
        BlockPos opening = PipeGeometry.adjacentCell(g, e, nodeIndex);
        if (opening == null) return null;
        return String.format("%s=§f%.2f§7", label, PipeWindow.lipY(level, opening));
    }

    /** The tick's planned endpoint transfers (diagnostics — execution is the brigade). */
    private static void printTransfers(Report report, Solution s) {
        if (s.hasTransfer()) {
            for (Solution.Transfer transfer : s.transfers()) {
                report.line(String.format("§a> %d mB %s : %s → %s",
                        transfer.fluid().getAmount(),
                        transfer.fluid().getHoverName().getString(),
                        transfer.from().toShortString(), transfer.to().toShortString()));
            }
        } else {
            report.line("§7> no transfer this tick");
        }
    }

    private static GraphOverlayPayload buildPayload(ServerLevel level, Graph g, Solution s, BlockPos seed) {
        MouthConditions mouths = MouthConditions.of(level, g);
        List<GraphOverlayPayload.NodeEntry> nodes = new ArrayList<>(g.nodes().size());
        for (Node n : g.nodes()) {
            nodes.add(nodeEntry(level, mouths, s, n));
        }
        List<GraphOverlayPayload.EdgeEntry> edges = new ArrayList<>(g.edges().size());
        for (Edge e : g.edges()) {
            edges.add(edgeEntry(level, g, s, e));
        }
        return new GraphOverlayPayload(seed.asLong(), nodes, edges);
    }

    /** One node's overlay entry: position, box kind, and its two-line floating label. */
    private static GraphOverlayPayload.NodeEntry nodeEntry(ServerLevel level, MouthConditions mouths,
                                                           Solution s, Node n) {
        byte kind = switch (n.kind()) {
            case HANDLER -> GraphOverlayPayload.NodeEntry.KIND_HANDLER;
            case PUMP -> GraphOverlayPayload.NodeEntry.KIND_PUMP;
            case JUNCTION, CLOSED_GATE -> GraphOverlayPayload.NodeEntry.KIND_JUNCTION;
            case OPEN_END -> GraphOverlayPayload.NodeEntry.KIND_OPEN_END;
        };
        return new GraphOverlayPayload.NodeEntry(
                n.pos().getX(), n.pos().getY(), n.pos().getZ(), kind,
                surfaceHeight(level, mouths, n),
                nodeLabel(level, mouths, n, s.nodeHeads().get(n.index())));
    }

    /**
     * The engine's computed fluid surface elevation for a finite reservoir ({@code baseY + fill}),
     * or {@code NaN} for anything without a liquid waterline (pumps, junctions, open ends, empty or
     * gas columns). This is the height a settled pipe equalizes to — the overlay draws it in-world
     * so it can be compared against Create's own rendered tank fluid, which may sit at a different
     * height ("the surface level inside the tanks is bumped from Create's model").
     */
    private static float surfaceHeight(ServerLevel level, MouthConditions mouths, Node n) {
        BoundaryColumn column = columnOf(level, mouths, n);
        if (column == null || !column.isFiniteReservoir() || column.contentMb() <= 0) return Float.NaN;
        if (column.contents().getFluid().getFluidType().isLighterThanAir()) return Float.NaN;
        return (float) column.renderedSurface();
    }

    /** One edge's overlay entry: its cells ordered along the flow, per-point pressures, and arrow state. */
    private static GraphOverlayPayload.EdgeEntry edgeEntry(ServerLevel level, Graph g, Solution s, Edge e) {
        EdgeFlow flow = s.edgeFlows().get(e.index());
        // The overlay reflects the ACTUAL fluid moved, like the pipe/pump goggle and the chat
        // dump's actual= column — NOT the solver's hydraulic flow. So an edge whose source/sink
        // throttles a solved flow down to nothing draws no arrow instead of a phantom one.
        int actual = PipeProbe.actualEdgeFlow(g, s, e);
        Node a = g.node(e.a()), b = g.node(e.b());

        List<BlockPos> orderedFromA = new ArrayList<>();
        orderedFromA.add(a.pos());
        orderedFromA.addAll(e.pipes());
        orderedFromA.add(b.pos());
        List<Float> pressuresFromA = pointPressures(level, s, e, orderedFromA);

        boolean reversed = flow.direction() == EdgeFlow.Direction.B_TO_A;
        List<BlockPos> ordered = reversed ? reverse(orderedFromA) : orderedFromA;
        List<Float> pressures = reversed ? reverse(pressuresFromA) : pressuresFromA;

        // Arrow only when fluid actually moves; a solved-but-stalled run still shows its rod
        // (no arrow), a held column stays magenta.
        byte dir = s.heldEdges().contains(e.index())
                ? GraphOverlayPayload.EdgeEntry.DIR_HELD
                : actual > 0
                ? GraphOverlayPayload.EdgeEntry.DIR_FORWARD
                : s.stalledEdges().contains(e.index())
                ? GraphOverlayPayload.EdgeEntry.DIR_STALLED
                : GraphOverlayPayload.EdgeEntry.DIR_NONE;

        List<Long> packed = new ArrayList<>(ordered.size());
        for (BlockPos p : ordered) packed.add(p.asLong());
        return new GraphOverlayPayload.EdgeEntry(packed, actual, dir, pressures);
    }

    /**
     * Gauge pressure at each point of the run: the head interpolated between the
     * solved endpoint heads, minus the point's elevation. Empty when the edge was
     * not part of any solved fluid pass.
     */
    private static List<Float> pointPressures(ServerLevel level, Solution s, Edge e,
                                              List<BlockPos> orderedFromA) {
        Double headA = s.nodeHeads().get(e.a());
        Double headB = s.nodeHeads().get(e.b());
        if (headA == null || headB == null) return List.of();

        int pointCount = orderedFromA.size();
        List<Float> pressures = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            double frac = pointCount == 1 ? 0 : (double) i / (pointCount - 1);
            double head = headA + (headB - headA) * frac;
            pressures.add((float) (head - SableCompat.getWorldY(level, orderedFromA.get(i))));
        }
        return pressures;
    }

    /**
     * A reversed COPY — Collections.reverse mutates in place, and the A-ordered input may be
     * immutable (the empty pressures list) or still aliased by the unreversed branch.
     */
    private static <T> List<T> reverse(List<T> in) {
        List<T> out = new ArrayList<>(in.size());
        for (int i = in.size() - 1; i >= 0; i--) out.add(in.get(i));
        return out;
    }

    /** Localized name of the block at a node — the tank, basin, pump, cauldron, etc. */
    private static String blockName(ServerLevel level, Node n) {
        return level.getBlockState(n.pos()).getBlock().getName().getString();
    }

    /** A pump node's current rotation speed (RPM), 0 if it is not a kinetic block. */
    private static float pumpSpeed(ServerLevel level, Node n) {
        return level.getBlockEntity(n.pos()) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
    }

    /** What a turbine node is currently producing, so a dialed-but-idle one is distinguishable. */
    private static double turbineStress(ServerLevel level, Node n) {
        return level.getBlockEntity(n.pos()) instanceof HydroTurbine t ? t.pipesnphysics$turbineStress() : 0;
    }

    /**
     * Why a hose pulley node is (or is not) supplying the network, or null when the node is not a
     * pulley. A pulley only feeds the engine once its hose is wound down INTO a fluid body and the
     * drainer has searched it — so this reports the missing precondition rather than leaving the
     * player guessing why a plumbed pulley moves nothing (the "won't pull" report). When the pulley
     * IS a source the normal fluid line already shows it, so this returns null.
     */
    private static String pulleyDiagnostic(ServerLevel level, Node n) {
        if (!n.isHandler() || !(level.getBlockEntity(n.pos()) instanceof HosePulleyBlockEntity)) {
            return null;
        }
        if (OpenEndPipes.isPulleyOutput(level, n.pos())) {
            return "pulley: pinned OUTPUT (has deposited) — a one-way sink; break and re-place to drain again";
        }
        IFluidHandler cap = BoundaryColumn.findHandler(level, n.pos());
        if (cap == null) return "pulley: no fluid capability";
        FluidStack drainable = cap.getFluidInTank(0);
        if (drainable.isEmpty()) {
            return "pulley: NOT supplying — no drainable fluid at the hose end "
                    + "(wind the hose DOWN into the fluid with rotation; a large/searching body needs a few ticks)";
        }
        if (cap.drain(drainable.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()) {
            return "pulley: sees " + drainable.getHoverName().getString()
                    + " but can't draw yet (still lowering / settling)";
        }
        return null; // it IS a source — the fluid line above reports it
    }

    /**
     * What the LIVE handler behind a HANDLER node actually reports to the engine's probes, or null for
     * non-handlers: what it holds, how much it will GIVE ({@code drain} SIMULATE), and how much of
     * {@code probe} it will TAKE ({@code fill} SIMULATE). This is exactly what the solver keys
     * participation on, so a handler that shows {@code give=0 take=0} is why a run past it moves
     * nothing — the case for a paired device (a docking connector) whose capability is gated on its
     * own state, which the fill/fraction summary above cannot reveal. Resolved through the node's
     * ACCESS FACE — the handler the engine actually transfers through, labelled on the line — and a
     * multi-tank handler (a machine port combining an input and an output tank, like TFMG's blast
     * stove) lists every tank: {@code holds=0} for tank 0 alone hid the port's real contents.
     */
    private static String handlerProbe(ServerLevel level, Node n, FluidStack probe) {
        if (!n.isHandler()) return null;
        IFluidHandler cap = BoundaryColumn.findHandler(level, n.pos(), n.accessFace());
        if (cap == null) return "probe: no live fluid capability";
        int give = cap.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
        int take = probe.isEmpty() ? 0 : cap.fill(probe.copyWithAmount(1000), FluidAction.SIMULATE);
        String line = String.format("probe%s: %s give=%d take=%d%s",
                n.accessFace() != null ? "[via " + n.accessFace() + "]" : "",
                tankSummary(cap), give, take,
                probe.isEmpty() ? "" : " (" + probe.getHoverName().getString() + ")");
        // The null-side handler is what our engine actually uses. If a SPECIFIC face accepts/gives more
        // (as Create's face-specific transport would see), the block is sided and our null resolution is
        // the bug — surface the best face so we can point the engine at it.
        String bestGive = "", bestTake = "";
        int maxGive = give, maxTake = take;
        for (Direction side : Direction.values()) {
            IFluidHandler faceCap = FluidCaps.at(level, n.pos(), side);
            if (faceCap == null) continue;
            int faceGive = faceCap.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
            int faceTake = probe.isEmpty() ? 0
                    : faceCap.fill(probe.copyWithAmount(1000), FluidAction.SIMULATE);
            if (faceGive > maxGive) { maxGive = faceGive; bestGive = side.toString(); }
            if (faceTake > maxTake) { maxTake = faceTake; bestTake = side.toString(); }
        }
        if (!bestGive.isEmpty()) line += " | face give=" + maxGive + "@" + bestGive;
        if (!bestTake.isEmpty()) line += " | face take=" + maxTake + "@" + bestTake;
        return line;
    }

    /** "holds=N" for a single tank; every tank of a multi-tank port: "tanks=[Air 500/16000, 0/16000]". */
    private static String tankSummary(IFluidHandler cap) {
        if (cap.getTanks() <= 1) {
            return "holds=" + (cap.getTanks() > 0 ? cap.getFluidInTank(0).getAmount() : 0);
        }
        StringBuilder tanks = new StringBuilder("tanks=[");
        for (int i = 0; i < cap.getTanks(); i++) {
            if (i > 0) tanks.append(", ");
            FluidStack in = cap.getFluidInTank(i);
            if (!in.isEmpty()) tanks.append(in.getHoverName().getString()).append(" ");
            tanks.append(in.getAmount()).append("/").append(cap.getTankCapacity(i));
        }
        return tanks.append("]").toString();
    }

    /**
     * Reflective, mod-optional readout of an aeronautics docking connector's internal fluid state, or
     * null for any other block. It answers WHY the connector's {@code insert()} returns 0: whether it is
     * paired at all ({@code connectedPos}/{@code connectedTank}), whether its own {@code canInteract()}
     * gate passes right now, and whether the PAIRED connector's buffer (on the other ship) is full. Uses
     * reflection so the mod stays an optional dependency; field/method names match the decompiled
     * {@code DockingConnectorTank}.
     */
    private static String dockingDiagnostic(ServerLevel level, Node n) {
        Object be = level.getBlockEntity(n.pos());
        if (be == null || !be.getClass().getSimpleName().equals("DockingConnectorBlockEntity")) return null;
        try {
            Object tank = be.getClass().getField("tank").get(be);
            Object connectedPos = readField(tank, "connectedPos");
            Object connectedTank = readField(tank, "connectedTank");
            Method canInteract = tank.getClass().getDeclaredMethod("canInteract");
            canInteract.setAccessible(true);
            Object interacts = canInteract.invoke(tank);
            String pair = "";
            if (connectedTank != null) {
                Object amt = connectedTank.getClass().getField("amount").get(connectedTank);
                Object cap = connectedTank.getClass().getField("capacity").get(connectedTank);
                pair = " pairBuffer=" + amt + "/" + cap;
            }
            return String.format("dock: connectedPos=%s connectedTank=%s canInteract=%s%s",
                    connectedPos, connectedTank != null, interacts, pair);
        } catch (Throwable thrown) {
            return "dock: reflect failed (" + thrown.getClass().getSimpleName() + ")";
        }
    }

    /** A private field's value off the (mod-optional) docking connector internals, by name. */
    private static Object readField(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** The first non-empty fluid found on any column of the network, used to probe what sinks will take. */
    private static FluidStack firstPresentFluid(ServerLevel level, Graph g) {
        MouthConditions mouths = MouthConditions.of(level, g);
        for (Node n : g.nodes()) {
            BoundaryColumn column = columnOf(level, mouths, n);
            if (column != null && !column.contents().isEmpty() && column.contentMb() > 0) {
                return column.contents();
            }
        }
        return FluidStack.EMPTY;
    }

    /**
     * The fluid column behind a source/sink node, or null for pumps, junctions, and
     * handlers that no longer expose a capability. Open ends report the fluid their
     * mouth would draw in (empty for a plain spill outlet).
     */
    private static BoundaryColumn columnOf(ServerLevel level, MouthConditions mouths, Node n) {
        if (n.isHandler()) return BoundaryColumn.resolve(level, n);
        if (n.isOpenEnd()) return mouths.column(level, n);
        return null;
    }

    /** "4000/8000 mB Water (50%)" — a column's contents, capacity, and fill. */
    private static String fluidSummary(BoundaryColumn column) {
        return String.format("%d/%d mB §f%s §7(%.0f%%)",
                column.contentMb(), column.capacityMb(),
                column.contents().getHoverName().getString(),
                column.fillFraction() * 100);
    }

    /**
     * The floating in-world label for a node, kept to TWO lines to stay legible: the block on line
     * one, and a compact status on line two — its fluid (sources/sinks), RPM+facing (pumps), or
     * "valve shut" — with the stored head merged onto that same line ({@code h4.0}) rather than a
     * third line. Empty for junctions, which the overlay leaves unannotated. The head is the value
     * /pipegraph prints in chat, shown in-world so you can SEE where it sits. Lines are
     * {@code \n}-separated for the client.
     */
    private static String nodeLabel(ServerLevel level, MouthConditions mouths, Node n, Double head) {
        String info = switch (n.kind()) {
            case HANDLER -> {
                BoundaryColumn column = columnOf(level, mouths, n);
                yield column != null && !column.contents().isEmpty() && column.contentMb() > 0
                        ? String.format("%d mB %s", column.contentMb(), column.contents().getHoverName().getString())
                        : "empty";
            }
            case OPEN_END -> {
                BoundaryColumn column = columnOf(level, mouths, n);
                yield column != null && !column.contents().isEmpty()
                        ? "draws " + column.contents().getHoverName().getString()
                        : "open end";
            }
            case PUMP -> {
                float rpm = level.getBlockEntity(n.pos()) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
                yield String.format("%.0f RPM%s", rpm, n.pumpFacing() != null ? " →" + n.pumpFacing() : "");
            }
            case CLOSED_GATE -> "valve shut";
            case JUNCTION -> null;
        };
        if (info == null) return ""; // junction: unannotated to avoid clutter
        // Merge the stored head onto the status line rather than a third line.
        if (head != null) info += String.format("  h%.1f", head);
        return blockName(level, n) + "\n" + info;
    }

    /** The stored head a HELD edge holds: the higher of its two endpoint display heads. */
    private static Double heldHead(Solution s, Edge e) {
        Double a = s.nodeHeads().get(e.a());
        Double b = s.nodeHeads().get(e.b());
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    /**
     * A per-fluid summary of everything held across the network's sources/sinks: total
     * volume plus the physical properties that drive the engine — density (gravity/
     * buoyancy sign), viscosity (flow rate), and temperature. Flags a lighter-than-air
     * fluid, which inverts the gravity model.
     */
    private static void sendFluidStats(Report report, ServerLevel level, Graph g) {
        MouthConditions mouths = MouthConditions.of(level, g);
        List<FluidStack> totals = new ArrayList<>();
        for (Node n : g.nodes()) {
            BoundaryColumn column = columnOf(level, mouths, n);
            if (column == null || column.contents().isEmpty() || column.contentMb() <= 0) continue;
            FluidStack running = null;
            for (FluidStack present : totals) {
                if (FluidStack.isSameFluidSameComponents(present, column.contents())) { running = present; break; }
            }
            if (running != null) running.grow(column.contentMb());
            else totals.add(column.contents().copyWithAmount(column.contentMb()));
        }
        if (totals.isEmpty()) return;

        report.line("§e--- Fluids ---");
        for (FluidStack fluid : totals) {
            FluidType type = fluid.getFluid().getFluidType();
            // The EFFECTIVE viscosity the solve uses in THIS dimension; a thinned molten fluid
            // says so, or the number silently disagrees with the registered one.
            int viscosity = (int) Math.round(FlowSolver.effectiveViscosity(level, fluid));
            String thinned = viscosity < type.getViscosity()
                    ? String.format("  §6(thinned from %d — ultrawarm)", type.getViscosity()) : "";
            report.line(String.format("  §b%s§7: §f%d mB  §7density §f%d §7visc §f%d §7temp §f%dK%s%s",
                    fluid.getHoverName().getString(), fluid.getAmount(),
                    type.getDensity(), viscosity, type.getTemperature(),
                    type.isLighterThanAir() ? "  §e(lighter than air ↑)" : "", thinned));
        }
    }

    /**
     * Accumulates the whole /pipegraph dump so it can be sent as one clickable block: every line
     * is emitted carrying a COPY_TO_CLIPBOARD click event whose payload is the ENTIRE report as
     * plain text, so a click anywhere on the output drops the full report onto the clipboard (handy
     * for pasting a network's state into a bug report). Lines keep their {@code §} colour codes on
     * screen; the clipboard copy is stripped to plain text.
     */
    private static final class Report {
        private final ServerPlayer player;
        private final List<String> lines = new ArrayList<>();

        Report(ServerPlayer player) {
            this.player = player;
        }

        ServerPlayer player() {
            return player;
        }

        void line(String text) {
            lines.add(text);
        }

        /** Emit every accumulated line, each click-to-copy of the full plain-text dump. */
        void flush() {
            String clipboard = plainText();
            Style style = Style.EMPTY
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, clipboard))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("§eClick to copy the full report to clipboard")));
            for (String text : lines) {
                player.sendSystemMessage(Component.literal(text).withStyle(style));
            }
        }

        /** The accumulated lines joined with newlines, colour codes removed — what lands on the clipboard. */
        private String plainText() {
            StringBuilder out = new StringBuilder();
            for (String text : lines) {
                out.append(text.replaceAll("§.", "")).append('\n');
            }
            return out.toString();
        }
    }
}
