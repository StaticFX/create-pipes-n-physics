package de.devin.pipesnphysics.engine.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.engine.BoundaryColumn;
import de.devin.pipesnphysics.engine.Edge;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.Graph;
import de.devin.pipesnphysics.engine.Node;
import de.devin.pipesnphysics.engine.PipeProbe;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.net.GraphOverlayPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pipegraph}: build the network at the player's crosshair, run the solver,
 * dump the result as chat lines, and send an in-world overlay to the same player.
 *
 * Intended for inspecting topology and verifying flow direction during development.
 */
public final class PipeGraphCommand {
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

        Graph graph = FluidEngine.buildGraph(level, target);
        if (graph.isEmpty()) {
            source.sendFailure(Component.literal("No pipe network at " + target.toShortString()));
            return 0;
        }
        Solution solution = FluidEngine.simulate(level, target);

        sendText(player, level, graph, solution);
        PacketDistributor.sendToPlayer(player, buildPayload(level, graph, solution));
        return 1;
    }

    private static void sendText(ServerPlayer player, ServerLevel level, Graph g, Solution s) {
        send(player, "§e--- Pipe Graph ---");
        send(player, "§7Nodes: §f" + g.nodes().size() + "  §7Edges: §f" + g.edges().size());
        for (Node n : g.nodes()) {
            Double head = s.nodeHeads().get(n.index());
            Double ceiling = s.nodeCeilings().get(n.index());
            String block = blockName(level, n);
            send(player, String.format("  §f%s §7%s §b%s §7y=§f%.1f%s%s%s%s",
                    n.pos().toShortString(), n.kind(), block,
                    n.worldY(),
                    head != null ? String.format(" §7head=§f%.2f", head) : "",
                    ceiling != null ? String.format(" §7ceil=§b%.2f", ceiling) : " §8ceil=∅",
                    n.pumpFacing() != null ? " §7face=§f" + n.pumpFacing() : "",
                    n.isPump() ? String.format(" §7rpm=§f%.0f", pumpSpeed(level, n)) : ""));
            BoundaryColumn column = columnOf(level, n);
            if (column != null && !column.contents().isEmpty() && column.contentMb() > 0) {
                send(player, "      §7" + (n.isOpenEnd()
                        ? "draws §f" + column.contents().getHoverName().getString()
                        : fluidSummary(column)));
            }
            String pulley = pulleyDiagnostic(level, n);
            if (pulley != null) send(player, "      §c" + pulley);
        }
        sendFluidStats(player, level, g);
        send(player, "§e--- Edges ---");
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
            Node a = g.node(e.a()), b = g.node(e.b());
            send(player, String.format("  §e%s §f%s §7↔ §f%s §7len=%d §7%s §7%d mB/t",
                    GraphOverlayPayload.edgeLetter(e.index()),
                    a.pos().toShortString(), b.pos().toShortString(),
                    e.length(), dir, rate));
        }
        if (s.hasTransfer()) {
            for (Solution.Transfer transfer : s.transfers()) {
                send(player, String.format("§a> %d mB %s : %s → %s",
                        transfer.fluid().getAmount(),
                        transfer.fluid().getHoverName().getString(),
                        transfer.from().toShortString(), transfer.to().toShortString()));
            }
        } else {
            send(player, "§7> no transfer this tick");
        }
    }

    private static GraphOverlayPayload buildPayload(ServerLevel level, Graph g, Solution s) {
        List<GraphOverlayPayload.NodeEntry> nodes = new ArrayList<>(g.nodes().size());
        for (Node n : g.nodes()) {
            byte kind = switch (n.kind()) {
                case HANDLER -> GraphOverlayPayload.NodeEntry.KIND_HANDLER;
                case PUMP -> GraphOverlayPayload.NodeEntry.KIND_PUMP;
                case JUNCTION, CLOSED_GATE -> GraphOverlayPayload.NodeEntry.KIND_JUNCTION;
                case OPEN_END -> GraphOverlayPayload.NodeEntry.KIND_OPEN_END;
            };
            nodes.add(new GraphOverlayPayload.NodeEntry(
                    n.pos().getX(), n.pos().getY(), n.pos().getZ(), kind,
                    nodeLabel(level, n, s.nodeHeads().get(n.index()))));
        }

        List<GraphOverlayPayload.EdgeEntry> edges = new ArrayList<>(g.edges().size());
        for (Edge e : g.edges()) {
            EdgeFlow flow = s.edgeFlows().get(e.index());
            Node a = g.node(e.a()), b = g.node(e.b());

            List<BlockPos> orderedFromA = new ArrayList<>();
            orderedFromA.add(a.pos());
            orderedFromA.addAll(e.pipes());
            orderedFromA.add(b.pos());
            List<Float> pressuresFromA = pointPressures(level, s, e, orderedFromA);

            boolean reversed = flow.direction() == EdgeFlow.Direction.B_TO_A;
            List<BlockPos> ordered = reversed ? reverse(orderedFromA) : orderedFromA;
            List<Float> pressures = reversed ? reverse(pressuresFromA) : pressuresFromA;

            byte dir = s.heldEdges().contains(e.index())
                    ? GraphOverlayPayload.EdgeEntry.DIR_HELD
                    : flow.direction() == EdgeFlow.Direction.NONE
                    ? GraphOverlayPayload.EdgeEntry.DIR_NONE
                    : s.stalledEdges().contains(e.index())
                    ? GraphOverlayPayload.EdgeEntry.DIR_STALLED
                    : GraphOverlayPayload.EdgeEntry.DIR_FORWARD;

            List<Long> packed = new ArrayList<>(ordered.size());
            for (BlockPos p : ordered) packed.add(p.asLong());
            edges.add(new GraphOverlayPayload.EdgeEntry(packed, flow.mbPerTick(), dir, pressures));
        }

        return new GraphOverlayPayload(nodes, edges);
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

        int n = orderedFromA.size();
        List<Float> pressures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double frac = n == 1 ? 0 : (double) i / (n - 1);
            double head = headA + (headB - headA) * frac;
            pressures.add((float) (head - SableCompat.getWorldY(level, orderedFromA.get(i))));
        }
        return pressures;
    }

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
     * The fluid column behind a source/sink node, or null for pumps, junctions, and
     * handlers that no longer expose a capability. Open ends report the fluid their
     * mouth would draw in (empty for a plain spill outlet).
     */
    private static BoundaryColumn columnOf(ServerLevel level, Node n) {
        if (n.isHandler()) return BoundaryColumn.resolve(level, n);
        if (n.isOpenEnd()) return BoundaryColumn.forOpenEnd(level, n, false);
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
     * The floating in-world label for a node: the block it is, plus a fluid line for
     * sources/sinks (and RPM for pumps), then the stored head it carries. Empty for
     * junctions, which the overlay leaves unannotated to avoid clutter. The head is the
     * value /pipegraph prints in chat, now shown in-world so you can SEE where it sits.
     * Lines are {@code \n}-separated for the client.
     */
    private static String nodeLabel(ServerLevel level, Node n, Double head) {
        String block = blockName(level, n);
        String body = switch (n.kind()) {
            case HANDLER -> {
                BoundaryColumn column = columnOf(level, n);
                yield block + "\n" + (column != null && !column.contents().isEmpty() && column.contentMb() > 0
                        ? String.format("%d mB %s", column.contentMb(), column.contents().getHoverName().getString())
                        : "empty");
            }
            case OPEN_END -> {
                BoundaryColumn column = columnOf(level, n);
                yield block + "\n" + (column != null && !column.contents().isEmpty()
                        ? "draws " + column.contents().getHoverName().getString()
                        : "open end");
            }
            case PUMP -> {
                float rpm = level.getBlockEntity(n.pos()) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
                yield block + "\n" + String.format("%.0f RPM%s", rpm,
                        n.pumpFacing() != null ? " →" + n.pumpFacing() : "");
            }
            case CLOSED_GATE -> block + "\nvalve shut (holding)";
            case JUNCTION -> "";
        };
        // Append the stored head where the node has one — except a junction, kept unannotated.
        return head != null && !body.isEmpty() ? body + String.format("\nhead %.2f", head) : body;
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
    private static void sendFluidStats(ServerPlayer player, ServerLevel level, Graph g) {
        List<FluidStack> totals = new ArrayList<>();
        for (Node n : g.nodes()) {
            BoundaryColumn column = columnOf(level, n);
            if (column == null || column.contents().isEmpty() || column.contentMb() <= 0) continue;
            FluidStack running = null;
            for (FluidStack present : totals) {
                if (FluidStack.isSameFluidSameComponents(present, column.contents())) { running = present; break; }
            }
            if (running != null) running.grow(column.contentMb());
            else totals.add(column.contents().copyWithAmount(column.contentMb()));
        }
        if (totals.isEmpty()) return;

        send(player, "§e--- Fluids ---");
        for (FluidStack fluid : totals) {
            FluidType type = fluid.getFluid().getFluidType();
            send(player, String.format("  §b%s§7: §f%d mB  §7density §f%d §7visc §f%d §7temp §f%dK%s",
                    fluid.getHoverName().getString(), fluid.getAmount(),
                    type.getDensity(), type.getViscosity(), type.getTemperature(),
                    type.isLighterThanAir() ? "  §e(lighter than air ↑)" : ""));
        }
    }

    private static void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
