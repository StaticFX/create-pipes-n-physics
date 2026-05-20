package de.devin.pipesnphysics.handler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.physics.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.*;;

/**
 * Debug command: /pipegraph
 * Looks at the block the player is targeting, discovers the pipe network,
 * solves gravity flow, and dumps the full graph into chat.
 */
public class DebugGraphCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pipegraph")
                .requires(source -> source.hasPermission(0))
                .executes(DebugGraphCommand::execute));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be run by a player"));
            return 0;
        }

        // Raycast to find targeted block
        HitResult hit = player.pick(20.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Not looking at a block"));
            return 0;
        }

        BlockPos targetPos = ((BlockHitResult) hit).getBlockPos();
        PipeGraph graph = PipeGraphBuilder.discover(player.serverLevel(), targetPos);

        if (graph.nodes().isEmpty()) {
            source.sendFailure(Component.literal("No pipe network at " + targetPos.toShortString()));
            return 0;
        }

        // Header
        send(player, "§e--- Pipe Graph at " + targetPos.toShortString() + " ---");
        send(player, "§7Nodes: §f" + graph.nodes().size()
                + " §7Endpoints: §f" + graph.endpoints().size()
                + " §7Active pump: §f" + graph.hasActivePump());

        // Nodes
        send(player, "§e--- Nodes ---");
        for (Map.Entry<NodeId, PipeNode> entry : graph.nodes().entrySet()) {
            PipeNode node = entry.getValue();
            BlockPos pos = PipeGraphBuilder.posOf(entry.getKey());
            String flowStr = node.flow() != null
                    ? String.format(" flow=%.1f mB/t in=%d out=%d",
                    node.flow().flowRateMbPerTick(), node.flow().inflowFace(), node.flow().outflowFace())
                    : "";
            send(player, String.format("  §f%s §7kind=§f%s §7Y=§f%.1f%s",
                    pos.toShortString(), node.kind(), node.worldY(), flowStr));
        }

        // Edges
        send(player, "§e--- Edges ---");
        for (Map.Entry<NodeId, List<PipeEdge>> entry : graph.adjacency().entrySet()) {
            BlockPos from = PipeGraphBuilder.posOf(entry.getKey());
            for (PipeEdge edge : entry.getValue()) {
                BlockPos to = PipeGraphBuilder.posOf(edge.to());
                send(player, String.format("  §f%s §7→ §f%s §7elev=§f%.1f° §7dY=§f%.1f",
                        from.toShortString(), to.toShortString(),
                        edge.elevationAngleDegrees(),
                        edge.toWorldY() - edge.fromWorldY()));
            }
        }

        // Endpoints
        send(player, "§e--- Endpoints ---");
        for (NetworkEndpoint ep : graph.endpoints()) {
            BlockPos handler = PipeGraphBuilder.posOf(ep.handlerNode());
            BlockPos pipe = PipeGraphBuilder.posOf(ep.pipeNode());
            send(player, String.format("  §fhandler=%s §7pipe=§f%s §7hY=§f%.1f §7pY=§f%.1f §7above=§f%s",
                    handler.toShortString(), pipe.toShortString(),
                    ep.handlerWorldY(), ep.pipeWorldY(), ep.isHandlerAbovePipe()));
        }

        // Solve gravity flow
        PhysicsConfig config = PhysicsConfigFactory.fromModConfig();
        PipeFormulas formulas = new PipeFormulas(config);
        NetworkSolver solver = new NetworkSolver(formulas);
        GravityFlowResult flow = solver.solveGravityFlow(graph);

        if (flow != null) {
            send(player, "§e--- Gravity Flow ---");
            BlockPos sourceHandler = PipeGraphBuilder.posOf(flow.source().handlerNode());
            send(player, "§7Source: §f" + sourceHandler.toShortString()
                    + " §7Y=§f" + String.format("%.1f", flow.sourceWorldY()));
            send(player, "§7Valid sinks: §f" + flow.validSinks().size());
            send(player, "§7Active pipes: §f" + flow.activePipes().size());

            for (Map.Entry<NodeId, Float> entry : flow.pipePressures().entrySet()) {
                BlockPos pos = PipeGraphBuilder.posOf(entry.getKey());
                float pressure = entry.getValue();
                FlowState flowState = flow.flowStates().get(entry.getKey());
                String flowStr = flowState != null
                        ? String.format(" §7flow=§f%.1f mB/t", flowState.flowRateMbPerTick())
                        : "";
                send(player, String.format("  §f%s §7pressure=§f%.1f%s",
                        pos.toShortString(), pressure, flowStr));
            }
        } else {
            send(player, "§7No gravity flow (pump present or no valid source/sink)");
        }

        // If looking at a pump or network has a pump, simulate pump BFS
        debugPumpReach(player, player.serverLevel(), graph, targetPos);

        send(player, "§e--- End ---");
        return 1;
    }

    private static void debugPumpReach(ServerPlayer player, ServerLevel level, PipeGraph graph, BlockPos targetPos) {
        // Find the pump — either the target itself or from the graph
        BlockPos pumpPos = null;
        if (level.getBlockState(targetPos).getBlock() instanceof PumpBlock) {
            pumpPos = targetPos;
        } else {
            for (Map.Entry<NodeId, PipeNode> entry : graph.nodes().entrySet()) {
                if (entry.getValue().isPump()) {
                    pumpPos = PipeGraphBuilder.posOf(entry.getKey());
                    break;
                }
            }
        }
        if (pumpPos == null) return;

        BlockEntity be = level.getBlockEntity(pumpPos);
        if (!(be instanceof KineticBlockEntity kbe)) return;
        float pumpBase = Math.abs(kbe.getSpeed());
        if (pumpBase == 0) return;

        BlockState pumpState = level.getBlockState(pumpPos);
        if (!(pumpState.getBlock() instanceof PumpBlock)) return;
        Direction facing = pumpState.getValue(PumpBlock.FACING);
        double pumpWorldY = SableCompat.getWorldY(level, pumpPos);

        PipeFormulas formulas = new PipeFormulas(PhysicsConfigFactory.fromModConfig());

        send(player, "§e--- Pump at " + pumpPos.toShortString() + " ---");
        send(player, "§7RPM: §f" + String.format("%.0f", pumpBase)
                + " §7Facing: §f" + facing
                + " §7Y: §f" + String.format("%.1f", pumpWorldY));

        for (Direction side : new Direction[]{facing, facing.getOpposite()}) {
            boolean isPull = side != facing;
            String sideLabel = isPull ? "PULL" : "PUSH";
            BlockPos startPos = pumpPos.relative(side);

            FluidTransportBehaviour startPipe = FluidPropagator.getPipe(level, startPos);
            if (startPipe == null) {
                send(player, "§7  " + sideLabel + " side (" + side + "): §cno pipe at " + startPos.toShortString());
                continue;
            }

            send(player, "§7  " + sideLabel + " side (" + side + "):");

            Map<BlockPos, Float> frictionMap = new HashMap<>();
            Map<BlockPos, Double> peakYMap = new HashMap<>();
            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> frontier = new ArrayDeque<>();
            visited.add(pumpPos);
            float startElev = SableCompat.getPipeElevation(level, pumpPos, side);
            frictionMap.put(startPos, formulas.segmentFriction(startElev));
            double startNodeY = SableCompat.getWorldY(level, startPos);
            peakYMap.put(startPos, Math.max(pumpWorldY, startNodeY));
            frontier.add(startPos);

            int reachCount = 0;
            while (!frontier.isEmpty()) {
                BlockPos current = frontier.poll();
                if (!visited.add(current)) continue;
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
                if (pipe == null) continue;

                float friction = frictionMap.getOrDefault(current, 0f);
                double nodeY = SableCompat.getWorldY(level, current);
                double peakY = peakYMap.getOrDefault(current, pumpWorldY);
                float reachPressure = formulas.pumpPressure(pumpBase, pumpWorldY, peakY, friction);
                float pressure = formulas.pumpPressure(pumpBase, pumpWorldY, nodeY, friction);
                float gravAssist = formulas.config().pumpGravityEnabled()
                        ? (float) (pumpWorldY - nodeY) * formulas.config().gravityPerBlock() * formulas.config().pumpGravityFactor()
                        : 0;

                String siphonTag = peakY > nodeY + 0.5 ? " §b(siphon)" : "";
                send(player, String.format("    §f%s §7fric=§f%.1f §7grav=§f%+.1f §7pres=§f%.1f%s%s",
                        current.toShortString(), friction, gravAssist, pressure,
                        reachPressure <= 0 ? " §c(OUT OF RANGE)" : "", siphonTag));

                if (reachPressure <= 0) continue;
                reachCount++;

                BlockState currentState = level.getBlockState(current);
                for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                    BlockPos next = current.relative(face);
                    if (visited.contains(next)) continue;

                    var handler = level.getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
                            next, face.getOpposite());
                    if (handler != null) {
                        send(player, String.format("    §a→ ENDPOINT at %s (fluid handler)", next.toShortString()));
                        continue;
                    }

                    if (FluidPropagator.getPipe(level, next) == null) continue;
                    float elev = SableCompat.getPipeElevation(level, current, face);
                    frictionMap.put(next, friction + formulas.segmentFriction(elev));
                    double nextY = SableCompat.getWorldY(level, next);
                    peakYMap.put(next, Math.max(peakY, nextY));
                    frontier.add(next);
                }
            }
            send(player, "§7    Reachable pipes: §f" + reachCount);
        }
    }

    private static void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
