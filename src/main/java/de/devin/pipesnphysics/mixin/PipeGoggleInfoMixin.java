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
import de.devin.pipesnphysics.physics.EdgePhase;
import de.devin.pipesnphysics.physics.PipeFlowData;
import de.devin.pipesnphysics.physics.PipeFormulas;
import de.devin.pipesnphysics.physics.PressureBreakdown;
import de.devin.pipesnphysics.handler.FluidTransportHandler;
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

        for (Direction side : Direction.values()) {
            var flow = transport.getFlow(side);
            if (flow == null || flow.fluid.isEmpty())
                continue;
            if (fluid == null)
                fluid = flow.fluid;
            if (flow.inbound) pulling.add(side);
            else pushing.add(side);
        }

        PressureBreakdown breakdown = computeBreakdown();
        EdgePhase phase = breakdown != null ? breakdown.phase() : EdgePhase.EMPTY;

        // Header
        langTranslate("gui.goggles.pipe_stats")
                .style(ChatFormatting.WHITE)
                .forGoggles(tooltip);

        // -- EMPTY --
        if (phase == EdgePhase.EMPTY) {
            langTranslate("gui.goggles.no_flow")
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
            return true;
        }

        // Fluid name (shared by all non-empty phases)
        if (fluid != null) {
            lang().text(ChatFormatting.WHITE, fluid.getHoverName().getString())
                    .forGoggles(tooltip, 1);

            if (PipesNPhysicsConfig.COMPLEX_TOOLTIPS.get()) {
                addFluidProperties(tooltip, fluid);
            }
        }

        // -- Phase-specific sections --
        switch (phase) {
            case CHARGING -> addChargingTooltip(tooltip, breakdown, fluid);
            case FLOWING  -> addFlowingTooltip(tooltip, breakdown, fluid, pulling, pushing);
            case STALLED  -> addStalledTooltip(tooltip, breakdown);
            case DRAINING -> addDrainingTooltip(tooltip);
            default -> {}
        }

        return true;
    }

    @Unique
    private void addFluidProperties(List<Component> tooltip, net.neoforged.neoforge.fluids.FluidStack fluid) {
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

    @Unique
    private void addChargingTooltip(List<Component> tooltip, PressureBreakdown breakdown,
                                     net.neoforged.neoforge.fluids.FluidStack fluid) {
        // Phase label
        langTranslate("gui.goggles.phase_charging")
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);

        // Progress bar
        float progress = breakdown.frontProgress();
        int filledBlocks = (int) (progress * 10);
        StringBuilder bar = new StringBuilder("  ");
        for (int i = 0; i < 10; i++) bar.append(i < filledBlocks ? "\u2588" : "\u2591");
        bar.append(String.format(" %d%%", (int) (progress * 100)));

        lang().text(ChatFormatting.GRAY, bar.toString())
                .forGoggles(tooltip, 1);

        // Show progress in blocks
        if (breakdown.edgeLength() > 0) {
            int frontBlocks = (int) (progress * breakdown.edgeLength());
            lang().text(ChatFormatting.DARK_GRAY,
                            "  " + frontBlocks + " / " + breakdown.edgeLength())
                    .add(langTranslate("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        // Drive type
        addDriveType(tooltip, breakdown, fluid);
    }

    @Unique
    private void addFlowingTooltip(List<Component> tooltip, PressureBreakdown breakdown,
                                    net.neoforged.neoforge.fluids.FluidStack fluid,
                                    List<Direction> pulling, List<Direction> pushing) {
        // Phase label
        langTranslate("gui.goggles.phase_flowing")
                .style(ChatFormatting.GREEN)
                .forGoggles(tooltip);

        // Flow rate
        langTranslate("gui.goggles.flow")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        lang().text(ChatFormatting.GOLD, String.format("%.1f", breakdown.net()))
                .add(CreateLang.translate("generic.unit.millibuckets"))
                .add(langTranslate("gui.goggles.per_tick").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 1);

        // Overpressure warning
        if (breakdown.bursting()) {
            lang().text(ChatFormatting.RED, "  \u26A0 ")
                    .add(langTranslate("gui.goggles.overpressure").style(ChatFormatting.RED))
                    .forGoggles(tooltip, 1);
        }

        // Drive type
        addDriveType(tooltip, breakdown, fluid);

        // Direction
        boolean onSubLevel = SableCompat.isOnSubLevelClient(getBlockPos());
        if (!onSubLevel && !pulling.isEmpty() && !pushing.isEmpty()) {
            langTranslate("gui.goggles.direction")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            lang().text(ChatFormatting.GOLD, formatDirs(pulling) + " \u2192 " + formatDirs(pushing))
                    .forGoggles(tooltip, 1);
        }

        // Pressure breakdown (complex tooltips — how flow is computed)
        if (PipesNPhysicsConfig.COMPLEX_TOOLTIPS.get()) {
            addPressureBreakdown(tooltip, breakdown);
        }

        // Sub-level angle
        if (onSubLevel) {
            Direction pipeDir = !pulling.isEmpty() ? pulling.get(0) : (!pushing.isEmpty() ? pushing.get(0) : null);
            if (pipeDir != null) {
                float angle = SableCompat.getClientPipeElevation(getBlockPos(), pipeDir);
                if (angle > 0.5f) {
                    lang().text(ChatFormatting.DARK_GRAY, "  " + String.format("%.1f", angle) + "\u00B0")
                            .forGoggles(tooltip, 1);
                }
            }
        }
    }

    @Unique
    private void addStalledTooltip(List<Component> tooltip, PressureBreakdown breakdown) {
        langTranslate("gui.goggles.phase_stalled")
                .style(ChatFormatting.RED)
                .forGoggles(tooltip);

        // Show how far the front got
        if (breakdown.edgeLength() > 0) {
            int frontBlocks = (int) (breakdown.frontProgress() * breakdown.edgeLength());
            lang().text(ChatFormatting.DARK_GRAY,
                            "  " + frontBlocks + " / " + breakdown.edgeLength())
                    .add(langTranslate("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        // Head budget breakdown: show full-edge costs so the math is self-consistent.
        // Available - Gravity - Friction = deficit (negative = need more head).
        float headBudget = breakdown.headAtUpstream();

        if (headBudget > 0.01f) {
            float gravityCost = breakdown.gravityContribution();
            float frictionCost = breakdown.friction();
            float deficit = gravityCost + frictionCost - headBudget;

            langTranslate("gui.goggles.stall_head_budget")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            // Head available
            langTranslate("gui.goggles.stall_available")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(lang().text(ChatFormatting.GREEN,
                            " " + String.format("%.1f", headBudget)))
                    .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);

            // Gravity cost for the full edge
            if (gravityCost > 0.05f) {
                langTranslate("gui.goggles.stall_gravity_cost")
                        .style(ChatFormatting.DARK_GRAY)
                        .add(lang().text(ChatFormatting.RED,
                                " -" + String.format("%.1f", gravityCost)))
                        .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                        .forGoggles(tooltip, 1);
            }

            // Friction cost for the full edge
            if (frictionCost > 0.05f) {
                langTranslate("gui.goggles.stall_friction_cost")
                        .style(ChatFormatting.DARK_GRAY)
                        .add(lang().text(ChatFormatting.RED,
                                " -" + String.format("%.1f", frictionCost)))
                        .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                        .forGoggles(tooltip, 1);
            }

            // Deficit — how much more head is needed to reach the end
            if (deficit > 0.05f) {
                langTranslate("gui.goggles.stall_need_more")
                        .style(ChatFormatting.RED)
                        .add(lang().text(ChatFormatting.RED,
                                " +" + String.format("%.1f", deficit)))
                        .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                        .forGoggles(tooltip, 1);
            }
        } else {
            // No head budget — dead end or no pump
            langTranslate("gui.goggles.stalled_dead_end")
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
        }
    }

    @Unique
    private void addDrainingTooltip(List<Component> tooltip) {
        langTranslate("gui.goggles.phase_draining")
                .style(ChatFormatting.RED)
                .forGoggles(tooltip);
    }

    @Unique
    private void addDriveType(List<Component> tooltip, PressureBreakdown breakdown,
                               net.neoforged.neoforge.fluids.FluidStack fluid) {
        boolean isPumpDriven = breakdown.pumpContribution() > 0;
        boolean isGravityDriven = !isPumpDriven && breakdown.gravityContribution() > 0;
        boolean isGas = fluid != null && fluid.getFluid().getFluidType().isLighterThanAir();

        if (isPumpDriven) {
            lang().text(ChatFormatting.GREEN, "\u26A1 ")
                    .add(langTranslate("gui.goggles.pump_driven").style(ChatFormatting.GREEN))
                    .forGoggles(tooltip, 1);
        } else if (isGravityDriven && isGas) {
            lang().text(ChatFormatting.YELLOW, "\u2191 ")
                    .add(langTranslate("gui.goggles.gas_driven").style(ChatFormatting.YELLOW))
                    .forGoggles(tooltip, 1);
        } else if (isGravityDriven) {
            lang().text(ChatFormatting.BLUE, "\u2193 ")
                    .add(langTranslate("gui.goggles.gravity_driven").style(ChatFormatting.BLUE))
                    .forGoggles(tooltip, 1);
        }
    }

    @Unique
    private void addPressureBreakdown(List<Component> tooltip, PressureBreakdown breakdown) {
        langTranslate("gui.goggles.pressure")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        // ΔΦ (potential difference driving the edge)
        if (Math.abs(breakdown.deltaPhi()) > 0.1f) {
            langTranslate("gui.goggles.potential_diff")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(lang().text(ChatFormatting.GOLD,
                            " " + String.format("%.1f", Math.abs(breakdown.deltaPhi()))))
                    .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        if (breakdown.gravityContribution() > 0.1f) {
            langTranslate("gui.goggles.head")
                    .style(ChatFormatting.GRAY)
                    .add(lang().text(ChatFormatting.GREEN,
                            " +" + String.format("%.1f", breakdown.gravityContribution())))
                    .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        if (breakdown.pumpContribution() > 0.1f) {
            langTranslate("gui.goggles.pump_base")
                    .style(ChatFormatting.GRAY)
                    .add(lang().text(ChatFormatting.GREEN,
                            " +" + String.format("%.1f", breakdown.pumpContribution())))
                    .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        if (breakdown.mergeContribution() > 0.1f) {
            langTranslate("gui.goggles.merge")
                    .style(ChatFormatting.GRAY)
                    .add(lang().text(ChatFormatting.AQUA,
                            " +" + String.format("%.1f", breakdown.mergeContribution())))
                    .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        if (breakdown.splitPenalty() > 0.1f) {
            langTranslate("gui.goggles.split")
                    .style(ChatFormatting.GRAY)
                    .add(lang().text(ChatFormatting.YELLOW,
                            " -" + String.format("%.1f", breakdown.splitPenalty())))
                    .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        if (breakdown.friction() > 0.1f) {
            langTranslate("gui.goggles.friction")
                    .style(ChatFormatting.GRAY)
                    .add(lang().text(ChatFormatting.RED,
                            " -" + String.format("%.1f", breakdown.friction())))
                    .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        // Head budget remaining
        if (breakdown.headRemaining() > 0.1f || breakdown.pumpContribution() > 0.1f) {
            langTranslate("gui.goggles.head_remaining")
                    .style(ChatFormatting.GRAY)
                    .add(lang().text(
                            breakdown.headRemaining() > breakdown.pumpContribution() * 0.2f
                                    ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
                            " " + String.format("%.1f", breakdown.headRemaining())))
                    .add(langTranslate("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }

        if (breakdown.capped()) {
            lang().text(ChatFormatting.DARK_GRAY,
                            "  (capped at " + String.format("%.0f", PipesNPhysicsConfig.MAX_GRAVITY_PRESSURE.get()) + " psi)")
                    .forGoggles(tooltip, 1);
        }
    }

    @Unique
    private PressureBreakdown computeBreakdown() {
        FluidTransportBehaviour transport = getBehaviour(FluidTransportBehaviour.TYPE);
        if (transport instanceof PipeFlowData data) {
            return data.pipesnphysics$getBreakdown();
        }
        return null;
    }

    @Unique
    private int computeRemainingRange() {
        Level level = getLevel();
        if (level == null) return -1;

        BlockPos thisPos = getBlockPos();
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

        PipeFormulas formulas = new PipeFormulas(PhysicsConfigFactory.fromModConfig());
        float frictionPerBlock = formulas.config().frictionPerBlock();

        PressureBreakdown breakdown = computeBreakdown();
        if (breakdown == null) return -1;
        return frictionPerBlock > MIN_DIVISOR ? (int) (breakdown.net() / frictionPerBlock) : 999;
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
