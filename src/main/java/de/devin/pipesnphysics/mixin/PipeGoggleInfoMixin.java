package de.devin.pipesnphysics.mixin;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.GoggleText;
import de.devin.pipesnphysics.client.PipeStatusText;
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
        }

        if (!data.fluid().isEmpty()) {
            // When flowing, the fluid name rides on the flow line; otherwise it gets its own.
            if (!flowing) {
                pipesnphysics$text(data.fluid().getHoverName().getString())
                        .style(ChatFormatting.AQUA)
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
     * Gauge pressure as a friendly local label: a positive column pushing down on
     * this cell ("Push here") or a negative one pulling at it ("Pull here", shown
     * as a positive magnitude rather than a minus sign). Under suction, the margin
     * left before the column breaks ("Air-break in", in blocks of further lift).
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
                    .add(pipesnphysics$lang("gui.goggles.pressure_unit").style(ChatFormatting.DARK_GRAY))
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
            pipesnphysics$lang("gui.goggles.reach_limit")
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip, 1);
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
                .forGoggles(tooltip, 2);
        int viscosity = type.getViscosity();
        String tag = viscosity <= 1000 ? "thin" : viscosity <= 3000 ? "syrupy" : "thick";
        pipesnphysics$lang("gui.goggles.viscosity")
                .style(ChatFormatting.DARK_GRAY)
                .add(pipesnphysics$text(LangNumberFormat.format(viscosity)).style(ChatFormatting.GRAY))
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
