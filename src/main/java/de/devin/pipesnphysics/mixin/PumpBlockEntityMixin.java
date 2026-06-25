package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.GoggleText;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.net.PipeStatusClient;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Suppresses Create's pump pressure distribution while the engine is enabled (the
 * engine routes fluid itself), wakes the network whenever the pump's facing
 * changes, and adds the pump's budget to the goggle overlay: head supplied,
 * current throughput against the pump-curve cap, and a boiler-style load bar.
 * When the engine is disabled in config, Create's logic runs untouched.
 */
@Mixin(value = PumpBlockEntity.class, remap = false)
public abstract class PumpBlockEntityMixin extends KineticBlockEntity {
    @Unique
    private Direction pipesnphysics$lastFacing = null;

    private PumpBlockEntityMixin() { super(null, null, null); }

    @Inject(method = "tick", at = @At("HEAD"))
    private void pipesnphysics$detectFlip(CallbackInfo ci) {
        Level world = level;
        if (world == null || world.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof PumpBlock)) return;
        Direction front = state.getValue(PumpBlock.FACING);
        if (pipesnphysics$lastFacing != null && pipesnphysics$lastFacing != front) {
            EngineTickHandler.markChanged(world, worldPosition);
            EngineTickHandler.markChanged(world, worldPosition.relative(front));
            EngineTickHandler.markChanged(world, worldPosition.relative(front.getOpposite()));
        }
        pipesnphysics$lastFacing = front;
    }

    @Inject(method = "distributePressureTo", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$replacePressureDistribution(Direction side, CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        PumpBlockEntity self = (PumpBlockEntity) (Object) this;
        if (self.getLevel() != null && !self.getLevel().isClientSide()) {
            EngineTickHandler.markChanged(self.getLevel(), self.getBlockPos().relative(side));
        }
        ci.cancel();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean base = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!PipesNPhysicsConfig.SHOW_PIPE_GOGGLE_INFO.get()) return base;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return base;
        Level world = level;
        if (world == null || !world.isClientSide()) return base;

        float speed = Math.abs(getSpeed());
        if (speed <= 0.01f) return base;

        long now = world.getGameTime();
        PipeStatusClient.requestIfStale(worldPosition, now);
        PipeStatusPayload data = PipeStatusClient.current(worldPosition, now);

        GoggleText.lang("gui.goggles.pump_stats")
                .style(ChatFormatting.WHITE)
                .forGoggles(tooltip);

        if (data != null && data.status() == PipeStatusPayload.STATUS_NO_HEAD) {
            GoggleText.lang("gui.goggles.no_head")
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip, 1);
        } else if (data != null && data.status() == PipeStatusPayload.STATUS_STALLED) {
            GoggleText.lang("gui.goggles.stalled")
                    .style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        }

        double flowCap = speed * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
        int rate = data != null ? data.mbPerTick() : 0;
        double headSupplied = speed * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();

        GoggleText.lang("gui.goggles.pumping")
                .style(ChatFormatting.GRAY)
                .add(GoggleText.text(LangNumberFormat.format(rate)).style(ChatFormatting.WHITE))
                .add(GoggleText.text(" / ").style(ChatFormatting.DARK_GRAY))
                .add(GoggleText.text(LangNumberFormat.format(flowCap)).style(ChatFormatting.GRAY))
                .add(GoggleText.lang("gui.goggles.mb_per_tick").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 1);

        // Name the binding limiter on the default view when the pump runs below cap.
        if (data != null && data.hasPumpLoad() && rate > 0 && rate < flowCap * 0.95f
                && headSupplied > 1e-6) {
            float headFactor = (float) ((headSupplied - data.pumpHeadAgainst()) / headSupplied);
            String capKey = headFactor < data.pumpFrictionFactor()
                    ? "gui.goggles.pump_cap_lift" : "gui.goggles.pump_cap_friction";
            GoggleText.lang(capKey).style(ChatFormatting.DARK_GRAY).forGoggles(tooltip, 2);
        }

        GoggleText.lang("gui.goggles.head_supplied")
                .style(ChatFormatting.GRAY)
                .add(GoggleText.text(LangNumberFormat.format(headSupplied)).style(ChatFormatting.AQUA))
                .add(GoggleText.lang("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 1);

        if (isPlayerSneaking && data != null && data.hasPumpLoad() && flowCap > 0) {
            pipesnphysics$addLoadBreakdown(tooltip, data, headSupplied, rate, flowCap);
        }
        return true;
    }

    /**
     * Sneak-only load detail: first the one honest summary — throughput as a share
     * of cap, with the bar that used to sit on the default view — then the two
     * independent factors that multiply to it. {@code headFactor = (supplied −
     * against)/supplied} is the share the net lift leaves ("Lift"); {@code
     * frictionFactor} is the share the connected run's width/length passes ("Pipe
     * run"). They are shown as distinct causes, never summed, so they reconstruct
     * the bar exactly — and when gravity assists ({@code against < 0}) the lift
     * line flips to a green bonus.
     */
    @Unique
    private void pipesnphysics$addLoadBreakdown(List<Component> tooltip, PipeStatusPayload data,
                                                double headSupplied, int rate, double flowCap) {
        int filled = Math.clamp(Math.round(10 * rate / (float) flowCap), 0, 10);
        int percent = Math.clamp(Math.round(100 * rate / (float) flowCap), 0, 100);
        LangBuilder bar = GoggleText.lang("gui.goggles.load_throughput")
                .style(ChatFormatting.GRAY)
                .add(GoggleText.text(percent + "%").style(ChatFormatting.WHITE))
                .add(GoggleText.text("  ").style(ChatFormatting.DARK_GRAY));
        GoggleText.appendBars(bar, filled, 10);
        bar.forGoggles(tooltip, 2);

        if (headSupplied <= 1e-6) return;
        float friction = data.pumpFrictionFactor();
        float headFactor = (float) ((headSupplied - data.pumpHeadAgainst()) / headSupplied);

        if (headFactor < 0.985f) {
            GoggleText.lang("gui.goggles.load_backpressure")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(GoggleText.text(Math.round(headFactor * 100f) + "%").style(ChatFormatting.GOLD))
                    .add(GoggleText.text("  " + LangNumberFormat.format(data.pumpHeadAgainst())
                            + " / " + LangNumberFormat.format(headSupplied))
                            .style(ChatFormatting.DARK_GRAY))
                    .add(GoggleText.lang("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 3);
        } else if (headFactor > 1.015f) {
            GoggleText.lang("gui.goggles.load_assist")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(GoggleText.text("+" + Math.round((headFactor - 1f) * 100f) + "%")
                            .style(ChatFormatting.GREEN))
                    .forGoggles(tooltip, 3);
        }
        if (friction < 0.985f) {
            GoggleText.lang("gui.goggles.load_friction")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(GoggleText.text(Math.round(friction * 100f) + "%").style(ChatFormatting.GOLD))
                    .forGoggles(tooltip, 3);
        }
    }
}
