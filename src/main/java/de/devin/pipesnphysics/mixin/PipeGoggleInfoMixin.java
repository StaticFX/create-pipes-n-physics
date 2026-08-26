package de.devin.pipesnphysics.mixin;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.GoggleText;
import de.devin.pipesnphysics.client.PipeStatusText;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.net.PipeStatusClient;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * Adds engine information to the Engineer's Goggles overlay when looking at a
 * pipe: status, fluid, flow rate and direction, and the gauge pressure at the
 * cell. Sneaking adds the fluid's physical properties.
 *
 * The simulation is server-authoritative, so the tooltip is fed by a throttled
 * request/answer packet pair ({@link PipeStatusClient}); the displayed data is
 * at most a few ticks old.
 */
@Mixin(value = {FluidPipeBlockEntity.class, StraightPipeBlockEntity.class,
        SmartFluidPipeBlockEntity.class}, remap = false)
public abstract class PipeGoggleInfoMixin extends SmartBlockEntity implements IHaveGoggleInformation {
    private PipeGoggleInfoMixin() {
        super(null, null, null);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!PipesNPhysicsConfig.SHOW_PIPE_GOGGLE_INFO.get()) return false;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return false;
        Level level = getLevel();
        if (level == null || !level.isClientSide()) return false;

        long now = level.getGameTime();
        PipeStatusClient.requestIfStale(getBlockPos(), now);
        PipeStatusPayload data = PipeStatusClient.current(getBlockPos(), now);
        if (data == null) return false;

        pipesnphysics$lang("gui.goggles.pipe_stats")
                .style(ChatFormatting.WHITE)
                .forGoggles(tooltip);

        boolean flowing = data.status() == PipeStatusPayload.STATUS_FLOWING;
        if (flowing) {
            pipesnphysics$addFlowLine(tooltip, data);
        } else {
            // Every non-flowing state reads as one consistent, always-visible line —
            // "No Flow: <reason>" — with the specific culprit folded straight in.
            pipesnphysics$lang(PipeStatusText.reasonKey(data))
                    .style(PipeStatusText.color(data.status()))
                    .forGoggles(tooltip, 1);
            // An air break has already snapped the column over the crest, so there is no "margin
            // left" to report — name the concrete fix instead (the same shape as the "Reach limit"
            // action line). The suppressed suction-margin readout lives in the sneak block below.
            if (data.statusDetail() == PipeStatusPayload.DETAIL_CREST) {
                pipesnphysics$lang("gui.goggles.air_break_fix")
                        .style(ChatFormatting.RED)
                        .forGoggles(tooltip, 1);
            } else if (data.statusDetail() == PipeStatusPayload.DETAIL_BELOW_OPENING) {
                // No climb involved — the honest fix is simply more fluid in the supply.
                pipesnphysics$lang("gui.goggles.below_opening_fix")
                        .style(ChatFormatting.GOLD)
                        .forGoggles(tooltip, 1);
            }
        }

        if (!data.fluid().isEmpty()) {
            // When flowing, the fluid name rides on the flow line; otherwise it gets its own.
            if (!flowing) {
                pipesnphysics$text(data.fluid().getHoverName().getString())
                        .style(ChatFormatting.AQUA)
                        .forGoggles(tooltip, 1);
            }
            // The cell's real stored volume. Pipes HOLD fluid, so this is a first-class fact about
            // the block being looked at — not a detail to sneak for (owner call, 2026-08-26).
            if (data.holdsMb() > 0) {
                pipesnphysics$lang("gui.goggles.holds")
                        .style(ChatFormatting.GRAY)
                        .add(pipesnphysics$text(LangNumberFormat.format(data.holdsMb()))
                                .style(ChatFormatting.AQUA))
                        .add(pipesnphysics$lang("gui.goggles.mb").style(ChatFormatting.DARK_GRAY))
                        .forGoggles(tooltip, 1);
            }
            if (isPlayerSneaking) pipesnphysics$addFluidProperties(tooltip, data);
        }

        // The "Lift left / Reach limit" reach readout is suppressed on ANY idle NO_FLOW run — dry OR
        // settled. A balanced pipe a hair above a low waterline would otherwise read a false "Reach
        // limit" though nothing is trying to deliver; the reason line above already explains the stop.
        // It stays for FLOWING (spare reach) and for a pump being asked to lift (NO_HEAD/BLOCKED/STALLED).
        if (PipeStatusText.showsReach(data)) pipesnphysics$addHeadLeftLine(tooltip, data, isPlayerSneaking);
        if (isPlayerSneaking) pipesnphysics$addPressureLines(tooltip, data);
        return true;
    }

    /**
     * Gauge pressure in plain speech, each number carrying the words that say what it measures:
     * a positive gauge is the fluid standing OVER this cell ("3.2 blocks of fluid overhead"), a
     * negative one is how far the cell hangs ABOVE the waterline, held by suction ("2.1 blocks
     * above the waterline") — never one shared unit for the two opposite ideas, and no minus
     * signs. The air-break margin is anchored to its real reference ("of climb left on this
     * run"): it is measured at the run's WORST point (the crest), so it deliberately does not do
     * arithmetic with the local suction number, and a comfortable margin renders CALM (green)
     * rather than reading as a standing warning — gold only under 2, red under 1.
     */
    @Unique
    private void pipesnphysics$addPressureLines(List<Component> tooltip, PipeStatusPayload data) {
        if (data.hasPressure()) {
            float pressure = data.pressureBlocks();
            boolean push = pressure >= 0;
            pipesnphysics$lang(push ? "gui.goggles.pressure" : "gui.goggles.suction")
                    .style(ChatFormatting.GRAY)
                    .add(pipesnphysics$text(LangNumberFormat.format(Math.abs(pressure)))
                            .style(push ? ChatFormatting.AQUA : ChatFormatting.GOLD))
                    .add(pipesnphysics$lang(push ? "gui.goggles.pressure_unit" : "gui.goggles.suction_unit")
                            .style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }
        if (data.hasSuctionMargin()) {
            float margin = data.suctionMarginBlocks();
            ChatFormatting color = margin < 1 ? ChatFormatting.RED
                    : margin < 2 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
            pipesnphysics$lang("gui.goggles.suction_margin")
                    .style(ChatFormatting.GRAY)
                    .add(pipesnphysics$text(LangNumberFormat.format(margin)).style(color))
                    .add(pipesnphysics$lang("gui.goggles.suction_margin_unit").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }
    }

    /**
     * The one number a builder reads first: how many blocks higher fluid can still
     * climb from this point (reservoir surfaces plus pump lift, minus elevation).
     * The remaining/consumed bar rides on the same line — one block of head per bar
     * (green left, dark red spent), compressed past boiler width. When the pipe sits
     * beyond reach the number would go negative, so a self-explaining line replaces
     * it instead. Sneaking reveals the exact remaining/total budget. Gold warns when
     * little lift is left.
     */
    @Unique
    private void pipesnphysics$addHeadLeftLine(List<Component> tooltip, PipeStatusPayload data,
                                               boolean sneaking) {
        if (!data.hasHeadroom()) return;
        float left = data.headroomBlocks();
        float total = data.headTotalBlocks();
        boolean hasBudget = total > 0.05f;
        if (left < 0) {
            // A run that is actually MOVING fluid has obviously reached — flag "Reach limit" only
            // when nothing is flowing. A downhill equalization between two tanks solves a small
            // positive flow while its friction-free ceiling sits below the probed cell (negative
            // left), and warning "raise the supply or add a pump" there flatly contradicts the
            // flow line right above it.
            if (data.status() != PipeStatusPayload.STATUS_FLOWING) {
                pipesnphysics$lang("gui.goggles.reach_limit")
                        .style(ChatFormatting.RED)
                        .forGoggles(tooltip, 1);
            }
            return;
        }
        ChatFormatting color = left < 2 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
        LangBuilder line = pipesnphysics$lang("gui.goggles.head_left")
                .style(ChatFormatting.GRAY)
                .add(pipesnphysics$text(LangNumberFormat.format(left)).style(color));
        if (sneaking && hasBudget) {
            line.add(pipesnphysics$text(" / ").style(ChatFormatting.DARK_GRAY))
                    .add(pipesnphysics$text(LangNumberFormat.format(total)).style(ChatFormatting.GRAY));
        }
        line.add(pipesnphysics$lang("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY));
        if (hasBudget) {
            int segments = Math.clamp(Math.round(total), 1, 18);
            int remaining = Math.clamp(Math.round(segments * left / total), 0, segments);
            line.add(pipesnphysics$text("  ").style(ChatFormatting.DARK_GRAY));
            GoggleText.appendBars(line, remaining, segments);
        }
        line.forGoggles(tooltip, 1);
    }

    @Unique
    private void pipesnphysics$addFlowLine(List<Component> tooltip, PipeStatusPayload data) {
        LangBuilder line = pipesnphysics$lang("gui.goggles.flow")
                .style(ChatFormatting.GRAY)
                .add(pipesnphysics$text(LangNumberFormat.format(data.mbPerTick()))
                        .style(ChatFormatting.WHITE))
                .add(pipesnphysics$lang("gui.goggles.mb_per_tick").style(ChatFormatting.DARK_GRAY));
        if (data.flowDirection() != null) {
            line.add(pipesnphysics$lang("direction." + data.flowDirection().getName())
                    .style(ChatFormatting.WHITE));
        }
        if (!data.fluid().isEmpty()) {
            line.add(pipesnphysics$text(" (" + data.fluid().getHoverName().getString() + ")")
                    .style(ChatFormatting.AQUA));
        }
        line.forGoggles(tooltip, 1);
    }

    @Unique
    private void pipesnphysics$addFluidProperties(List<Component> tooltip, PipeStatusPayload data) {
        FluidType type = data.fluid().getFluid().getFluidType();
        pipesnphysics$lang("gui.goggles.density")
                .style(ChatFormatting.DARK_GRAY)
                .add(pipesnphysics$text(LangNumberFormat.format(type.getDensity())).style(ChatFormatting.GRAY))
                .add(pipesnphysics$lang("gui.goggles.density_unit"))
                .forGoggles(tooltip, 2);
        // The EFFECTIVE viscosity the engine flows this fluid at HERE — a molten fluid reads
        // thinner in an ultrawarm dimension, so the number matches how the pipe behaves.
        Integer viscosity = (int) Math.round(FlowSolver.effectiveViscosity(getLevel(), data.fluid()));

        String tag = switch (viscosity) {
            case Integer v when v <= 1000 ->   "thin";
            case Integer v when v <= 3000 ->   "syrupy";
            case Integer v when v <= 15000 ->  "thick";
            default ->                         "sluggish";
        };

        pipesnphysics$lang("gui.goggles.viscosity")
                .style(ChatFormatting.DARK_GRAY)
                .add(pipesnphysics$text(LangNumberFormat.format(viscosity)).style(ChatFormatting.GRAY))
                .add(pipesnphysics$lang("gui.goggles.viscosity_unit"))
                .add(pipesnphysics$text(" (").style(ChatFormatting.DARK_GRAY))
                .add(pipesnphysics$lang("gui.goggles.visc_" + tag).style(ChatFormatting.GRAY))
                .add(pipesnphysics$text(")").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 2);
    }

    @Unique
    private static LangBuilder pipesnphysics$lang(String key) {
        return GoggleText.lang(key);
    }

    @Unique
    private static LangBuilder pipesnphysics$text(String text) {
        return GoggleText.text(text);
    }
}
