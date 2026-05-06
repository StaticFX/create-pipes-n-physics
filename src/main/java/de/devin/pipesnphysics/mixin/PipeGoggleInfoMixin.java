package de.devin.pipesnphysics.mixin;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.utility.CreateLang;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

import java.util.*;

/**
 * Adds goggle tooltip information to pipe block entities,
 * showing fluid, transfer rate, flow direction, pressure, and remaining pump range.
 */
@Mixin(value = {FluidPipeBlockEntity.class, StraightPipeBlockEntity.class, SmartFluidPipeBlockEntity.class}, remap = false)
public abstract class PipeGoggleInfoMixin extends SmartBlockEntity implements IHaveGoggleInformation {

    private PipeGoggleInfoMixin() {
        super(null, null, null);
    }

    private static LangBuilder lang() {
        return new LangBuilder(PipesNPhysics.ID);
    }

    private static LangBuilder langTranslate(String key) {
        return lang().translate(key);
    }

    private static LangBuilder langNumber(double d) {
        return lang().text(LangNumberFormat.format(d));
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!PipesNPhysicsConfig.SHOW_PIPE_GOGGLE_INFO.get())
            return false;

        FluidTransportBehaviour transport = getBehaviour(FluidTransportBehaviour.TYPE);
        if (transport == null)
            return false;

        List<Direction> pulling = new ArrayList<>();
        List<Direction> pushing = new ArrayList<>();
        net.neoforged.neoforge.fluids.FluidStack fluid = null;
        float inboundPressure = 0;

        for (Direction side : Direction.values()) {
            var flow = transport.getFlow(side);
            if (flow == null || flow.fluid.isEmpty())
                continue;
            if (fluid == null)
                fluid = flow.fluid;
            if (flow.inbound) {
                pulling.add(side);
                if (inboundPressure == 0) {
                    PipeConnection conn = transport.getConnection(side);
                    if (conn != null)
                        inboundPressure = conn.getPressure().getFirst();
                }
            } else {
                pushing.add(side);
            }
        }

        langTranslate("gui.goggles.pipe_stats")
                .style(ChatFormatting.WHITE)
                .forGoggles(tooltip);

        if (fluid == null) {
            langTranslate("gui.goggles.no_flow")
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
            return true;
        }

        // Fluid name
        lang()
                .text(ChatFormatting.AQUA, fluid.getHoverName().getString())
                .forGoggles(tooltip, 1);

        int transferRate = (int) Math.max(1, inboundPressure / 2f);

        langTranslate("gui.goggles.flow")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        lang()
                .add(langNumber(transferRate)
                        .add(CreateLang.translate("generic.unit.millibuckets"))
                        .style(ChatFormatting.AQUA))
                .add(langTranslate("gui.goggles.per_tick")
                        .style(ChatFormatting.AQUA))
                .forGoggles(tooltip, 1);

        if (!pulling.isEmpty() && !pushing.isEmpty()) {
            langTranslate("gui.goggles.direction")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            lang()
                    .text(ChatFormatting.AQUA, formatDirs(pulling) + " \u2192 " + formatDirs(pushing))
                    .forGoggles(tooltip, 1);
        }

        // Remaining pump range or gravity indicator
        int remainingRange = computeRemainingRange();
        if (remainingRange >= 0) {
            langTranslate("gui.goggles.pressure_left")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            lang()
                    .add(langNumber(remainingRange)
                            .style(ChatFormatting.AQUA))
                    .add(langTranslate("gui.goggles.blocks")
                            .style(ChatFormatting.AQUA))
                    .forGoggles(tooltip, 1);
        } else if (inboundPressure > 0) {
            // No pump found but pipe has pressure — gravity flow
            lang()
                    .text(ChatFormatting.DARK_GREEN, "\u2193 ")
                    .add(langTranslate("gui.goggles.gravity_driven")
                            .style(ChatFormatting.DARK_GREEN))
                    .forGoggles(tooltip, 1);
        }

        return true;
    }

    /**
     * BFS from this pipe to find a connected pump, then BFS from the pump
     * with gravity-aware distances to compute how much range this pipe uses.
     * Returns remaining range, or -1 if no pump found.
     */
    private int computeRemainingRange() {
        Level level = getLevel();
        if (level == null) return -1;

        BlockPos thisPos = getBlockPos();

        // BFS from this pipe to find a connected pump
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(thisPos);
        BlockPos pumpPos = null;

        while (!queue.isEmpty() && pumpPos == null) {
            BlockPos current = queue.poll();
            if (!visited.add(current))
                continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) continue;

            BlockState state = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(state, pipe)) {
                BlockPos neighbor = current.relative(face);
                if (visited.contains(neighbor)) continue;
                if (level.getBlockState(neighbor).getBlock() instanceof PumpBlock) {
                    // Only consider active pumps (speed != 0)
                    BlockEntity be = level.getBlockEntity(neighbor);
                    if (be instanceof KineticBlockEntity kbe && kbe.getSpeed() != 0) {
                        pumpPos = neighbor;
                        break;
                    }
                }
                if (FluidPropagator.getPipe(level, neighbor) != null)
                    queue.add(neighbor);
            }
        }

        if (pumpPos == null) return -1;

        // BFS from pump with gravity-aware distances
        int pumpY = pumpPos.getY();
        int upCost = PipesNPhysicsConfig.UPWARD_PIPE_COST.get();
        int maxRange = FluidPropagator.getPumpRange();

        Map<BlockPos, Integer> distanceMap = new HashMap<>();
        Queue<int[]> frontier = new ArrayDeque<>();
        Set<BlockPos> distVisited = new HashSet<>();
        distVisited.add(pumpPos);

        for (Direction side : Direction.values()) {
            BlockPos start = pumpPos.relative(side);
            if (FluidPropagator.getPipe(level, start) != null)
                frontier.add(new int[]{1, start.getX(), start.getY(), start.getZ()});
        }

        while (!frontier.isEmpty()) {
            int[] entry = frontier.poll();
            int distance = entry[0];
            BlockPos current = new BlockPos(entry[1], entry[2], entry[3]);
            if (!distVisited.add(current)) continue;

            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) continue;

            distanceMap.put(current, distance);
            if (current.equals(thisPos)) break;

            BlockState currentState = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos next = current.relative(face);
                if (distVisited.contains(next)) continue;
                if (FluidPropagator.getPipe(level, next) == null) continue;

                int nextDist;
                if (face == Direction.DOWN) nextDist = distance;
                else if (face == Direction.UP && next.getY() <= pumpY) nextDist = distance;
                else if (face == Direction.UP) nextDist = distance + upCost;
                else nextDist = distance + 1;

                frontier.add(new int[]{nextDist, next.getX(), next.getY(), next.getZ()});
            }
        }

        int used = distanceMap.getOrDefault(thisPos, -1);
        if (used < 0) return -1;
        return maxRange - used;
    }

    private static String formatDirs(List<Direction> dirs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dirs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Component.translatable(PipesNPhysics.ID + ".direction." + dirs.get(i).getSerializedName()).getString());
        }
        return sb.toString();
    }
}
