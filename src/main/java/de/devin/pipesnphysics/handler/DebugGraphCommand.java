package de.devin.pipesnphysics.handler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.physics.*;
import de.devin.pipesnphysics.physics.FluidPhase;
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

import java.util.*;

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

        // Detect fluid in the network
        ServerLevel level = player.serverLevel();
        net.neoforged.neoforge.fluids.FluidStack networkFluid = net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        for (NetworkEndpoint ep : graph.endpoints()) {
            BlockPos hPos = PipeGraphBuilder.posOf(ep.handlerNode());
            Direction hFace = PipeGraphBuilder.directionOf(ep.faceIndex()).getOpposite();
            var hCap = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, hPos, hFace);
            if (hCap != null) {
                for (int i = 0; i < hCap.getTanks(); i++) {
                    if (!hCap.getFluidInTank(i).isEmpty()) { networkFluid = hCap.getFluidInTank(i); break; }
                }
            }
            if (!networkFluid.isEmpty()) break;
        }
        // Also check pipes for flowing fluid
        if (networkFluid.isEmpty()) {
            for (var entry : graph.nodes().entrySet()) {
                if (!entry.getValue().isPipe()) continue;
                BlockPos pPos = PipeGraphBuilder.posOf(entry.getKey());
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(level, pPos);
                if (pipe == null) continue;
                for (Direction d : Direction.values()) {
                    var flow = pipe.getFlow(d);
                    if (flow != null && !flow.fluid.isEmpty()) { networkFluid = flow.fluid; break; }
                }
                if (!networkFluid.isEmpty()) break;
            }
        }

        float gravityDirection = 1.0f;
        if (!networkFluid.isEmpty()) {
            int density = networkFluid.getFluid().getFluidType().getDensity(networkFluid);
            int viscosity = networkFluid.getFluid().getFluidType().getViscosity(networkFluid);
            boolean lighterThanAir = networkFluid.getFluid().getFluidType().isLighterThanAir();
            gravityDirection = PipeFormulas.gravityDirection(lighterThanAir);
            String fluidName = networkFluid.getHoverName().getString();
            String gasLabel = lighterThanAir ? " §e(GAS)" : "";
            send(player, "§e--- Fluid ---");
            send(player, "§7Name: §f" + fluidName + gasLabel);
            send(player, "§7Density: §f" + density + " §7Viscosity: §f" + viscosity
                    + " §7LighterThanAir: §f" + lighterThanAir
                    + " §7Gravity: §f" + (gravityDirection > 0 ? "DOWN" : "UP"));
        }

        // Build contracted network and simulate
        SimConfig simConfig = PhysicsConfigFactory.simConfig();
        FluidNetwork network = NetworkBuilder.build(level, targetPos, simConfig);

        send(player, "§e--- Contracted Network ---");
        send(player, "§7Nodes: §f" + network.nodes().size()
                + " §7Edges: §f" + network.edges().size());

        for (var nodeEntry : network.nodes().entrySet()) {
            SimNode node = nodeEntry.getValue();
            BlockPos pos = PipeGraphBuilder.posOf(nodeEntry.getKey());
            send(player, String.format("  §f%s §7kind=§f%s §7Y=§f%.1f §7staticP=§f%.1f",
                    pos.toShortString(), node.kind(), node.elevation(), node.staticPressure()));
        }

        for (SimEdge edge : network.edges()) {
            BlockPos posA = PipeGraphBuilder.posOf(edge.a());
            BlockPos posB = PipeGraphBuilder.posOf(edge.b());
            send(player, String.format("  §7Edge %d: §f%s §7↔ §f%s §7len=%d cap=%d fric=%.1f fill=%d",
                    edge.id(), posA.toShortString(), posB.toShortString(),
                    edge.length(), edge.capacity(), edge.resistance(), edge.totalFill()));
        }

        // Run simulation
        Map<String, SimFluid> fluids = new HashMap<>();
        if (!networkFluid.isEmpty()) {
            String fluidId = networkFluid.getFluid().builtInRegistryHolder().key().location().toString();
            boolean lighter = networkFluid.getFluid().getFluidType().isLighterThanAir();
            float dens = networkFluid.getFluid().getFluidType().getDensity(networkFluid) / 1000f;
            if (dens <= 0) dens = 1.0f;
            float visc = networkFluid.getFluid().getFluidType().getViscosity(networkFluid) / 1000f;
            if (visc <= 0) visc = 1.0f;
            fluids.put(fluidId, new SimFluid(fluidId, lighter ? FluidPhase.GAS : FluidPhase.LIQUID, dens, visc));
        }

        FluidSimulator simulator = new FluidSimulator(simConfig);
        SimResult simResult = simulator.tick(network, fluids);

        send(player, "§e--- Simulation ---");
        for (int i = 0; i < network.edges().size(); i++) {
            SimEdge edge = network.edges().get(i);
            float rate = simResult.flowRates()[i];
            String dir = rate > 0 ? "a→b" : rate < 0 ? "b→a" : "none";
            // Derive effective phase for display: CHARGING with flow = FLOWING,
            // CHARGING with no flow but front advanced = STALLED.
            EdgePhase displayPhase = edge.phase();
            if (displayPhase == EdgePhase.CHARGING && rate > 0) {
                displayPhase = EdgePhase.FLOWING;
            } else if (displayPhase == EdgePhase.CHARGING && rate == 0 && edge.frontPos() > 0) {
                displayPhase = EdgePhase.STALLED;
            }
            String phaseColor = switch (displayPhase) {
                case EMPTY -> "§8";
                case CHARGING -> "§e";
                case STALLED -> "§c";
                case FLOWING -> "§a";
                case DRAINING -> "§6";
            };
            send(player, String.format("  §7Edge %d: %s%s §7front=§f%.1f§7/§f%d §7flow=§f%.1f §7dir=§f%s §7head=§f%.2f",
                    edge.id(), phaseColor, displayPhase.name(),
                    edge.frontPos(), edge.length(),
                    Math.abs(rate), dir,
                    network.headAt(edge.upstreamNode() != null ? edge.upstreamNode() : edge.a())));
        }
        if (!simResult.collisions().isEmpty()) {
            send(player, "§c" + simResult.collisions().size() + " collision(s)!");
        }

        // Show pump info
        for (Map.Entry<NodeId, PipeNode> entry : graph.nodes().entrySet()) {
            if (!entry.getValue().isPump()) continue;
            BlockPos pumpPos = PipeGraphBuilder.posOf(entry.getKey());
            BlockEntity be = level.getBlockEntity(pumpPos);
            if (!(be instanceof KineticBlockEntity kbe)) continue;
            float speed = Math.abs(kbe.getSpeed());
            if (speed == 0) continue;

            BlockState pumpState = level.getBlockState(pumpPos);
            if (!(pumpState.getBlock() instanceof PumpBlock)) continue;
            Direction facing = pumpState.getValue(PumpBlock.FACING);

            send(player, "§e--- Pump at " + pumpPos.toShortString() + " ---");
            send(player, "§7RPM: §f" + String.format("%.0f", speed)
                    + " §7Facing: §f" + facing
                    + " §7Pressure: §f" + String.format("%.0f", speed) + " psi");
        }

        send(player, "§e--- End ---");
        return 1;
    }

    private static void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
