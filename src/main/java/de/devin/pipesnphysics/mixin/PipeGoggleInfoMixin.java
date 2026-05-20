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
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.handler.PhysicsConfigFactory;
import de.devin.pipesnphysics.handler.PipeGraphBuilder;
import de.devin.pipesnphysics.physics.GravityFlowResult;
import de.devin.pipesnphysics.physics.NetworkSolver;
import de.devin.pipesnphysics.physics.NodeId;
import de.devin.pipesnphysics.physics.PhysicsConfig;
import de.devin.pipesnphysics.physics.PipeFormulas;
import de.devin.pipesnphysics.physics.PipeGraph;
import de.devin.pipesnphysics.physics.PressureBreakdown;
import de.devin.pipesnphysics.physics.PumpPressureBreakdown;
import de.devin.pipesnphysics.handler.GravityFlowHandler;
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
import org.spongepowered.asm.mixin.Unique;

import java.util.*;

@Mixin(value = {FluidPipeBlockEntity.class, StraightPipeBlockEntity.class, SmartFluidPipeBlockEntity.class}, remap = false)
public abstract class PipeGoggleInfoMixin extends SmartBlockEntity implements IHaveGoggleInformation {

    private static final float MIN_DIVISOR = 0.001f;

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

        lang()
                .text(ChatFormatting.WHITE, fluid.getHoverName().getString())
                .forGoggles(tooltip, 1);

        if (PipesNPhysicsConfig.COMPLEX_TOOLTIPS.get()) {
            int viscosity = fluid.getFluid().getFluidType().getViscosity(fluid);
            int density = fluid.getFluid().getFluidType().getDensity(fluid);
            float viscosityMult = viscosity / 1000f;
            float scaling = PipesNPhysicsConfig.VISCOSITY_SCALING.get().floatValue();
            float effectiveMult = 1.0f + (viscosityMult - 1.0f) * scaling;

            lang().text(ChatFormatting.DARK_GRAY, "  " + density + " kg/m\u00B3")
                    .add(lang().text(ChatFormatting.DARK_GRAY, "  \u00B7 "))
                    .add(lang().text(ChatFormatting.DARK_GRAY, viscosity + " cP"))
                    .forGoggles(tooltip, 1);

            if (effectiveMult > 1.01f) {
                lang().text(ChatFormatting.RED, "  " + String.format("%.1f", effectiveMult) + "x")
                        .add(langTranslate("gui.goggles.viscosity_penalty")
                                .style(ChatFormatting.DARK_GRAY))
                        .forGoggles(tooltip, 1);
            }
        }

        PressureBreakdown gravityBreakdown = computeGravityBreakdown();
        int remainingRange = computeRemainingRange();
        boolean isPumpDriven = remainingRange >= 0;
        boolean isGravityDriven = !isPumpDriven && (gravityBreakdown != null || inboundPressure > 0);

        float transferRate;
        if (isGravityDriven && gravityBreakdown != null) {
            transferRate = gravityBreakdown.net() / 2f;
        } else {
            transferRate = inboundPressure / 2f;
        }

        langTranslate("gui.goggles.flow")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        lang()
                .add(lang().text(ChatFormatting.GOLD, String.format("%.1f", transferRate))
                        .add(CreateLang.translate("generic.unit.millibuckets")))
                .add(langTranslate("gui.goggles.per_tick")
                        .style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 1);

        boolean onSubLevel = SableCompat.isOnSubLevelClient(getBlockPos());

        if (!onSubLevel && !pulling.isEmpty() && !pushing.isEmpty()) {
            langTranslate("gui.goggles.direction")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            lang()
                    .text(ChatFormatting.GOLD, formatDirs(pulling) + " \u2192 " + formatDirs(pushing))
                    .forGoggles(tooltip, 1);
        }

        if (isPumpDriven) {
            lang()
                    .text(ChatFormatting.GREEN, "\u26A1 ")
                    .add(langTranslate("gui.goggles.pump_driven")
                            .style(ChatFormatting.GREEN))
                    .forGoggles(tooltip, 1);

            PumpPressureBreakdown pumpBreakdown = computePumpBreakdown();
            if (PipesNPhysicsConfig.COMPLEX_TOOLTIPS.get() && pumpBreakdown != null) {
                float frictionPB = PipesNPhysicsConfig.PIPE_FRICTION_PER_BLOCK.get().floatValue();

                langTranslate("gui.goggles.reach")
                        .style(ChatFormatting.GRAY)
                        .forGoggles(tooltip);

                if (pumpBreakdown.friction() > 0.1f) {
                    langTranslate("gui.goggles.friction")
                            .style(ChatFormatting.GRAY)
                            .add(lang().text(ChatFormatting.RED,
                                    " -" + String.format("%.0f", pumpBreakdown.friction())))
                            .forGoggles(tooltip, 1);
                }

                float gravAssist = pumpBreakdown.gravityAssist();
                if (Math.abs(gravAssist) > 0.1f) {
                    ChatFormatting gravColor = gravAssist >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
                    String gravSign = gravAssist >= 0 ? " +" : " ";
                    langTranslate("gui.goggles.gravity_assist")
                            .style(ChatFormatting.GRAY)
                            .add(lang().text(gravColor, gravSign + String.format("%.0f", gravAssist)))
                            .forGoggles(tooltip, 1);
                }

                int remainingBlocks = frictionPB > MIN_DIVISOR
                        ? (int) (pumpBreakdown.net() / frictionPB) : 999;
                langTranslate("gui.goggles.remaining")
                        .style(ChatFormatting.GRAY)
                        .add(lang().text(ChatFormatting.WHITE,
                                " " + remainingBlocks))
                        .add(langTranslate("gui.goggles.more_blocks")
                                .style(ChatFormatting.DARK_GRAY))
                        .forGoggles(tooltip, 1);
            }
        } else if (isGravityDriven) {
            lang()
                    .text(ChatFormatting.BLUE, "\u2193 ")
                    .add(langTranslate("gui.goggles.gravity_driven")
                            .style(ChatFormatting.BLUE))
                    .forGoggles(tooltip, 1);

            float frictionPB = PipesNPhysicsConfig.PIPE_FRICTION_PER_BLOCK.get().floatValue();
            if (fluid != null) {
                int visc = fluid.getFluid().getFluidType().getViscosity(fluid);
                float vm = visc / 1000f;
                float sc = PipesNPhysicsConfig.VISCOSITY_SCALING.get().floatValue();
                frictionPB *= 1.0f + (vm - 1.0f) * sc;
            }
            int maxRange = PipesNPhysicsConfig.MAX_GRAVITY_RANGE.get();
            PressureBreakdown breakdown = gravityBreakdown;

            if (PipesNPhysicsConfig.COMPLEX_TOOLTIPS.get() && breakdown != null) {
                langTranslate("gui.goggles.head")
                        .style(ChatFormatting.GRAY)
                        .add(lang().text(ChatFormatting.GREEN,
                                " +" + String.format("%.0f", breakdown.head())))
                        .forGoggles(tooltip, 1);

                if (breakdown.friction() > 0.1f) {
                    langTranslate("gui.goggles.friction")
                            .style(ChatFormatting.GRAY)
                            .add(lang().text(ChatFormatting.RED,
                                    " -" + String.format("%.0f", breakdown.friction())))
                            .forGoggles(tooltip, 1);
                }

                langTranslate("gui.goggles.net")
                        .style(ChatFormatting.GRAY)
                        .add(lang().text(ChatFormatting.GOLD,
                                " " + String.format("%.0f", breakdown.net())))
                        .add(lang().text(ChatFormatting.DARK_GRAY,
                                " \u2192 " + String.format("%.1f", breakdown.net() / 2f) + " mB/t"))
                        .forGoggles(tooltip, 1);
                if (breakdown.capped()) {
                    lang().text(ChatFormatting.DARK_GRAY,
                                    "  (capped at " + String.format("%.0f", PipesNPhysicsConfig.MAX_GRAVITY_PRESSURE.get()) + ")")
                            .forGoggles(tooltip, 1);
                }
            }

            float localPressure = breakdown != null ? breakdown.localPressure() : inboundPressure;
            int gravRange = frictionPB > MIN_DIVISOR
                    ? Math.min(Math.round(localPressure / frictionPB), maxRange) : maxRange;
            langTranslate("gui.goggles.remaining")
                    .style(ChatFormatting.GRAY)
                    .add(lang().text(ChatFormatting.WHITE,
                            " " + gravRange))
                    .add(langTranslate("gui.goggles.more_blocks")
                            .style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        if ((isPumpDriven || isGravityDriven) && onSubLevel) {
            Direction pipeDir = !pulling.isEmpty() ? pulling.get(0) : (!pushing.isEmpty() ? pushing.get(0) : null);
            if (pipeDir != null) {
                float angle = SableCompat.getClientPipeElevation(getBlockPos(), pipeDir);
                if (angle > 0.5f) {
                    lang().text(ChatFormatting.DARK_GRAY, "  " + String.format("%.1f", angle) + "\u00B0")
                            .forGoggles(tooltip, 1);
                }
            }
        }

        return true;
    }

    private PressureBreakdown computeGravityBreakdown() {
        Level level = getLevel();
        if (level == null) return null;

        BlockPos thisPos = getBlockPos();
        PressureBreakdown cached = GravityFlowHandler.getCachedBreakdown(thisPos);
        if (cached != null) return cached;

        PipeGraph graph = PipeGraphBuilder.discover(level, thisPos);
        PhysicsConfig config = PhysicsConfigFactory.fromModConfig();
        PipeFormulas formulas = new PipeFormulas(config);
        NetworkSolver solver = new NetworkSolver(formulas);
        GravityFlowResult flow = solver.solveGravityFlow(graph);
        NodeId nodeId = PipeGraphBuilder.nodeOf(thisPos);
        return solver.computeAllBreakdowns(graph, flow).get(nodeId);
    }

    @Unique
    private PumpPressureBreakdown pipesnphysics$lastPumpBreakdown;

    private int computeRemainingRange() {
        pipesnphysics$lastPumpBreakdown = null;
        Level level = getLevel();
        if (level == null) return -1;

        BlockPos thisPos = getBlockPos();
        PipeFormulas formulas = new PipeFormulas(PhysicsConfigFactory.fromModConfig());

        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(thisPos);
        BlockPos pumpPos = null;

        while (!queue.isEmpty() && pumpPos == null) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) continue;
            BlockState state = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(state, pipe)) {
                BlockPos neighbor = current.relative(face);
                if (visited.contains(neighbor)) continue;
                if (level.getBlockState(neighbor).getBlock() instanceof PumpBlock) {
                    BlockEntity be = level.getBlockEntity(neighbor);
                    if (be instanceof KineticBlockEntity kbe && kbe.getSpeed() != 0) {
                        pumpPos = neighbor;
                        break;
                    }
                }
                if (FluidPropagator.getPipe(level, neighbor) != null) queue.add(neighbor);
            }
        }
        if (pumpPos == null) return -1;

        float pumpBase = 0;
        BlockEntity pumpBe = level.getBlockEntity(pumpPos);
        if (pumpBe instanceof KineticBlockEntity kbe) pumpBase = Math.abs(kbe.getSpeed());
        double pumpWorldY = SableCompat.getWorldY(level, pumpPos);

        Map<BlockPos, Float> frictionMap = new HashMap<>();
        Queue<BlockPos> bfs = new ArrayDeque<>();
        Set<BlockPos> bfsVisited = new HashSet<>();
        bfsVisited.add(pumpPos);

        Map<BlockPos, Double> peakYMap = new HashMap<>();
        for (Direction side : Direction.values()) {
            BlockPos start = pumpPos.relative(side);
            if (FluidPropagator.getPipe(level, start) != null) {
                float elevation = SableCompat.getPipeElevation(level, pumpPos, side);
                frictionMap.put(start, formulas.segmentFriction(elevation));
                double startY = SableCompat.getWorldY(level, start);
                peakYMap.put(start, Math.max(pumpWorldY, startY));
                bfs.add(start);
            }
        }

        while (!bfs.isEmpty()) {
            BlockPos current = bfs.poll();
            if (!bfsVisited.add(current)) continue;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current);
            if (pipe == null) continue;
            if (current.equals(thisPos)) break;
            BlockState currentState = level.getBlockState(current);
            for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos next = current.relative(face);
                if (bfsVisited.contains(next) || FluidPropagator.getPipe(level, next) == null) continue;
                float elevation = SableCompat.getPipeElevation(level, current, face);
                frictionMap.put(next, frictionMap.getOrDefault(current, 0f) + formulas.segmentFriction(elevation));
                double nextY = SableCompat.getWorldY(level, next);
                peakYMap.put(next, Math.max(peakYMap.getOrDefault(current, pumpWorldY), nextY));
                bfs.add(next);
            }
        }

        float friction = frictionMap.getOrDefault(thisPos, -1f);
        if (friction < 0) return -1;

        double nodeY = SableCompat.getWorldY(level, thisPos);
        float gravityAssist = formulas.config().pumpGravityEnabled()
                ? (float) (pumpWorldY - nodeY) * formulas.config().gravityPerBlock() * formulas.config().pumpGravityFactor()
                : 0;
        float netPressure = formulas.pumpPressure(pumpBase, pumpWorldY, nodeY, friction);

        pipesnphysics$lastPumpBreakdown = new PumpPressureBreakdown(
                pumpBase, gravityAssist, friction, netPressure);

        float frictionPerBlock = formulas.config().frictionPerBlock();
        return frictionPerBlock > MIN_DIVISOR ? (int) (netPressure / frictionPerBlock) : 999;
    }

    private PumpPressureBreakdown computePumpBreakdown() {
        return pipesnphysics$lastPumpBreakdown;
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
