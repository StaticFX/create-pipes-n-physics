package de.devin.pipesnphysics.engine.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import de.devin.pipesnphysics.engine.Edge;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.Graph;
import de.devin.pipesnphysics.engine.Node;
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

        sendText(player, graph, solution);
        PacketDistributor.sendToPlayer(player, buildPayload(level, graph, solution));
        return 1;
    }

    private static void sendText(ServerPlayer player, Graph g, Solution s) {
        send(player, "§e--- Pipe Graph ---");
        send(player, "§7Nodes: §f" + g.nodes().size() + "  §7Edges: §f" + g.edges().size());
        for (Node n : g.nodes()) {
            Double head = s.nodeHeads().get(n.index());
            Double ceiling = s.nodeCeilings().get(n.index());
            send(player, String.format("  §f%s §7%s §7y=§f%.1f%s%s%s",
                    n.pos().toShortString(), n.kind(),
                    n.worldY(),
                    head != null ? String.format(" §7head=§f%.2f", head) : "",
                    ceiling != null ? String.format(" §7ceil=§b%.2f", ceiling) : " §8ceil=∅",
                    n.pumpFacing() != null ? " §7face=§f" + n.pumpFacing() : ""));
        }
        send(player, "§e--- Edges ---");
        for (Edge e : g.edges()) {
            EdgeFlow flow = s.edgeFlows().get(e.index());
            String dir = switch (flow.direction()) {
                case A_TO_B -> "a→b";
                case B_TO_A -> "b→a";
                case NONE -> "idle";
            };
            if (s.stalledEdges().contains(e.index())) dir = "§6stalled§7";
            if (s.noHeadEdges().contains(e.index())) dir = "§cno head§7";
            Node a = g.node(e.a()), b = g.node(e.b());
            send(player, String.format("  §e%s §f%s §7↔ §f%s §7len=%d §7%s §7%d mB/t",
                    GraphOverlayPayload.edgeLetter(e.index()),
                    a.pos().toShortString(), b.pos().toShortString(),
                    e.length(), dir, flow.mbPerTick()));
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
                case JUNCTION -> GraphOverlayPayload.NodeEntry.KIND_JUNCTION;
                case OPEN_END -> GraphOverlayPayload.NodeEntry.KIND_OPEN_END;
            };
            nodes.add(new GraphOverlayPayload.NodeEntry(
                    n.pos().getX(), n.pos().getY(), n.pos().getZ(), kind));
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

            byte dir = flow.direction() == EdgeFlow.Direction.NONE
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

    private static void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
