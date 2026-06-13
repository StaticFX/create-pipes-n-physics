package de.devin.pipesnphysics.mixin;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.GoggleText;
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

        switch (data.status()) {
            case PipeStatusPayload.STATUS_NOT_CONNECTED -> pipesnphysics$lang("gui.goggles.not_connected")
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
            case PipeStatusPayload.STATUS_BLOCKED -> pipesnphysics$lang("gui.goggles.blocked")
                    .style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            case PipeStatusPayload.STATUS_NO_FLOW -> pipesnphysics$lang("gui.goggles.no_flow")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip, 1);
            case PipeStatusPayload.STATUS_STALLED -> pipesnphysics$lang("gui.goggles.stalled")
                    .style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            case PipeStatusPayload.STATUS_NO_HEAD -> pipesnphysics$lang("gui.goggles.no_head")
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip, 1);
            case PipeStatusPayload.STATUS_FLOWING -> pipesnphysics$addFlowLine(tooltip, data);
            default -> { }
        }
        if (isPlayerSneaking) pipesnphysics$addStatusDetail(tooltip, data);

        if (!data.fluid().isEmpty()) {
            pipesnphysics$text(data.fluid().getHoverName().getString())
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1);
            if (isPlayerSneaking) pipesnphysics$addFluidProperties(tooltip, data);
        }

        pipesnphysics$addHeadLeftLine(tooltip, data);
        if (isPlayerSneaking) pipesnphysics$addPressureLines(tooltip, data);
        return true;
    }

    /** Names the culprit behind a blocked/stalled status when the solver knows it. */
    @Unique
    private void pipesnphysics$addStatusDetail(List<Component> tooltip, PipeStatusPayload data) {
        String key = switch (data.statusDetail()) {
            case PipeStatusPayload.DETAIL_VALVE -> "valve";
            case PipeStatusPayload.DETAIL_PUMP_OFF -> "pump_off";
            case PipeStatusPayload.DETAIL_CREST -> "crest";
            case PipeStatusPayload.DETAIL_SINK_FULL -> "sink_full";
            case PipeStatusPayload.DETAIL_SOURCE_DRY -> "source_dry";
            default -> null;
        };
        if (key == null) return;
        pipesnphysics$lang("gui.goggles.detail." + key)
                .style(ChatFormatting.GOLD)
                .forGoggles(tooltip, 2);
    }

    /**
     * Gauge pressure — the fluid column actually sitting on (positive) or pulling
     * at (negative) this cell — and, under suction, the margin left before the
     * cavitation limit breaks the column.
     */
    @Unique
    private void pipesnphysics$addPressureLines(List<Component> tooltip, PipeStatusPayload data) {
        if (data.hasPressure()) {
            float pressure = data.pressureBlocks();
            pipesnphysics$lang("gui.goggles.pressure")
                    .style(ChatFormatting.GRAY)
                    .add(pipesnphysics$text(LangNumberFormat.format(pressure))
                            .style(pressure < 0 ? ChatFormatting.GOLD : ChatFormatting.AQUA))
                    .add(pipesnphysics$lang("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }
        if (data.hasSuctionMargin()) {
            float margin = data.suctionMarginBlocks();
            pipesnphysics$lang("gui.goggles.suction_margin")
                    .style(ChatFormatting.GRAY)
                    .add(pipesnphysics$text(LangNumberFormat.format(margin))
                            .style(margin < 1 ? ChatFormatting.RED : ChatFormatting.GOLD))
                    .add(pipesnphysics$lang("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }
    }

    /**
     * The one pressure stat a builder needs at a pipe: how many blocks of head the
     * system (reservoirs plus pumps) still has at this point. Positive means fluid
     * can climb that much further from here; negative means this pipe sits beyond
     * what anything can push to. Gold warns when the budget is nearly spent.
     */
    @Unique
    private void pipesnphysics$addHeadLeftLine(List<Component> tooltip, PipeStatusPayload data) {
        if (!data.hasHeadroom()) return;
        float left = data.headroomBlocks();
        float total = data.headTotalBlocks();
        boolean hasBudget = total > 0.05f;
        ChatFormatting color = left < 0 ? ChatFormatting.RED
                : left < 2 ? ChatFormatting.GOLD
                : ChatFormatting.GREEN;
        LangBuilder line = pipesnphysics$lang("gui.goggles.head_left")
                .style(ChatFormatting.GRAY)
                .add(pipesnphysics$text(LangNumberFormat.format(left)).style(color));
        if (hasBudget) {
            line.add(pipesnphysics$text(" / ").style(ChatFormatting.DARK_GRAY))
                    .add(pipesnphysics$text(LangNumberFormat.format(total)).style(ChatFormatting.GRAY));
        }
        line.add(pipesnphysics$lang("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 1);
        if (hasBudget) pipesnphysics$addBudgetBar(tooltip, left, total);
    }

    /**
     * Boiler-style budget bar: green bars are head still available, dark red is
     * what the climb from the supply has already consumed. One bar per block of
     * head while the budget fits a boiler-width bar; compressed proportionally
     * beyond that.
     */
    @Unique
    private void pipesnphysics$addBudgetBar(List<Component> tooltip, float left, float total) {
        int segments = Math.clamp(Math.round(total), 1, 18);
        int remaining = Math.clamp(Math.round(segments * left / total), 0, segments);
        GoggleText.barLine("gui.goggles.head_budget", remaining, segments)
                .forGoggles(tooltip, 1);
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
        line.forGoggles(tooltip, 1);
    }

    @Unique
    private void pipesnphysics$addFluidProperties(List<Component> tooltip, PipeStatusPayload data) {
        FluidType type = data.fluid().getFluid().getFluidType();
        pipesnphysics$lang("gui.goggles.density")
                .style(ChatFormatting.DARK_GRAY)
                .add(pipesnphysics$text(LangNumberFormat.format(type.getDensity())).style(ChatFormatting.GRAY))
                .forGoggles(tooltip, 2);
        pipesnphysics$lang("gui.goggles.viscosity")
                .style(ChatFormatting.DARK_GRAY)
                .add(pipesnphysics$text(LangNumberFormat.format(type.getViscosity())).style(ChatFormatting.GRAY))
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
