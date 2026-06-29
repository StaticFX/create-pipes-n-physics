package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.GoggleText;
import de.devin.pipesnphysics.client.PipeStatusText;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.ValveThrottle;
import de.devin.pipesnphysics.engine.net.PipeStatusClient;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Gives Create's fluid valve a fine-grained throttle: a 0-90 degree opening the valve passes
 * proportionally (90 = fully open). A Valve Handle on its shaft cranks it — each turn adds the
 * handle's set angle (handled in {@link ValveHandleBlockEntityMixin} via {@code adjustThrottle},
 * which applies the handle's INTENT rather than its imprecise actual rotation) — and a
 * scroll-value box on the side faces sets it directly. The handle visual tracks the angle, the
 * solver reads it through {@link ValveThrottle} to scale the run's conductance, and the goggle
 * shows the throughput. Inert when the engine or the throttle feature is off in config.
 */
@Mixin(value = FluidValveBlockEntity.class, remap = false)
public abstract class FluidValveBlockEntityMixin extends KineticBlockEntity implements ValveThrottle {
    @Unique
    private static final int FULL_OPEN_DEGREES = 90;
    @Unique
    private ScrollValueBehaviour pipesnphysics$throttle;
    @Shadow
    LerpedFloat pointer;

    private FluidValveBlockEntityMixin() { super(null, null, null); }

    @Inject(method = "addBehaviours", at = @At("TAIL"))
    private void pipesnphysics$addThrottle(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        ScrollValueBehaviour throttle = new ScrollValueBehaviour(
                Component.translatable("pipesnphysics.gui.valve.throttle"),
                (SmartBlockEntity) (Object) this,
                new CenteredSideValueBoxTransform((state, side) -> {
                    if (!(state.getBlock() instanceof FluidValveBlock)) return false;
                    Axis shaft = state.getValue(FluidValveBlock.FACING).getAxis();
                    return side.getAxis() != shaft && side.getAxis() != FluidValveBlock.getPipeAxis(state);
                }))
                .between(0, FULL_OPEN_DEGREES)
                .withFormatter(angle -> angle + "°")
                .withCallback(angle -> {
                    pipesnphysics$wakeNetwork();
                    pipesnphysics$aimPointer();
                })
                .onlyActiveWhen(() -> PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get());
        throttle.value = FULL_OPEN_DEGREES;
        pipesnphysics$throttle = throttle;
        behaviours.add(throttle);
    }

    /**
     * A valve saved before this feature has no {@code "ScrollValue"} tag, and Create's
     * {@link ScrollValueBehaviour#read} reads an absent key as 0 — which would load every
     * existing valve fully shut. Re-assert the open default whenever the tag lacks the key
     * (a synced packet always carries it, so the client is unaffected).
     */
    @Inject(method = "read", at = @At("TAIL"))
    private void pipesnphysics$keepThrottleDefault(CompoundTag tag, HolderLookup.Provider registries,
                                                   boolean clientPacket, CallbackInfo ci) {
        if (pipesnphysics$throttle != null && !tag.contains("ScrollValue")) {
            pipesnphysics$throttle.value = FULL_OPEN_DEGREES;
        }
    }

    @Override
    public float pipesnphysics$valveThrottle() {
        if (pipesnphysics$throttle == null || !PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return 1f;
        return pipesnphysics$throttle.getValue() / (float) FULL_OPEN_DEGREES;
    }

    @Override
    public void pipesnphysics$adjustThrottle(int delta) {
        if (pipesnphysics$throttle == null || !PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return;
        int next = Mth.clamp(pipesnphysics$throttle.getValue() + delta, 0, FULL_OPEN_DEGREES);
        if (next != pipesnphysics$throttle.getValue()) pipesnphysics$throttle.setValue(next); // syncs + re-aims the handle
    }

    @Unique
    private void pipesnphysics$wakeNetwork() {
        if (level != null && !level.isClientSide()) EngineTickHandler.markChanged(level, worldPosition);
    }

    /** Aim the handle at the current opening: the pointer chases the throttle fraction, so the
     *  valve's handle sits at exactly however far it has been cranked. Inert when the feature is off. */
    @Unique
    private void pipesnphysics$aimPointer() {
        if (pipesnphysics$throttle == null || !PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return;
        float target = pipesnphysics$throttle.getValue() / (float) FULL_OPEN_DEGREES;
        float chaseSpeed = Math.max(0.05f, Mth.clamp(Math.abs(getSpeed()) / 16f / 20f, 0f, 1f));
        pointer.chase(target, chaseSpeed, LerpedFloat.Chaser.LINEAR);
        if (level != null && !level.isClientSide()) sendData();
    }

    @Inject(method = "onSpeedChanged", at = @At("TAIL"))
    private void pipesnphysics$retargetOnSpeed(float previousSpeed, CallbackInfo ci) {
        pipesnphysics$aimPointer(); // hold the handle at the throttle even after the shaft stops
    }

    /**
     * The throttle IS the valve's open position now, so feed Create's open/close checks a gate
     * derived from it: OPEN whenever the angle is above 0, shut at 0. Create otherwise flips
     * {@code ENABLED} only at a fully-turned (pointer == 1) handle, which a partially cranked
     * valve never reaches. Falls back to the real pointer when the feature is off.
     */
    @Redirect(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/animation/LerpedFloat;getValue()F"))
    private float pipesnphysics$gateEnabled(LerpedFloat instance) {
        if (pipesnphysics$throttle == null || !PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) {
            return instance.getValue();
        }
        return pipesnphysics$throttle.getValue() > 0 ? 1f : 0f;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean base = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!PipesNPhysicsConfig.SHOW_PIPE_GOGGLE_INFO.get()) return base;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return base;
        if (!PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return base;
        Level world = level;
        if (world == null || !world.isClientSide() || pipesnphysics$throttle == null) return base;

        int angle = pipesnphysics$throttle.getValue();

        GoggleText.lang("gui.goggles.valve_stats").style(ChatFormatting.WHITE).forGoggles(tooltip);

        // The opening bar is the share the valve passes — only meaningful when it actually
        // passes; a closed (0-degree) valve shows just the reason, never a green "all good" bar.
        if (angle > 0) {
            int percent = Math.round(100f * angle / FULL_OPEN_DEGREES);
            LangBuilder opening = GoggleText.lang("gui.goggles.valve_opening")
                    .style(ChatFormatting.GRAY)
                    .add(GoggleText.text(percent + "%").style(ChatFormatting.WHITE))
                    .add(GoggleText.text("  ").style(ChatFormatting.DARK_GRAY));
            GoggleText.appendBars(opening, Math.round(10f * angle / FULL_OPEN_DEGREES), 10);
            opening.forGoggles(tooltip, 1);
        }

        pipesnphysics$addStateLine(tooltip, world, angle);
        return true;
    }

    /** Why fluid is or isn't moving: cranked shut, the live rate, or the run's real stop. */
    @Unique
    private void pipesnphysics$addStateLine(List<Component> tooltip, Level world, int angle) {
        if (angle == 0) {
            GoggleText.lang("gui.goggles.valve_shut_throttle").style(ChatFormatting.GOLD).forGoggles(tooltip, 1);
            return;
        }
        long now = world.getGameTime();
        PipeStatusClient.requestIfStale(worldPosition, now);
        PipeStatusPayload data = PipeStatusClient.current(worldPosition, now);
        if (data == null) return;
        if (data.status() == PipeStatusPayload.STATUS_FLOWING) {
            LangBuilder line = GoggleText.lang("gui.goggles.flow")
                    .style(ChatFormatting.GRAY)
                    .add(GoggleText.text(LangNumberFormat.format(data.mbPerTick())).style(ChatFormatting.WHITE))
                    .add(GoggleText.lang("gui.goggles.mb_per_tick").style(ChatFormatting.DARK_GRAY));
            if (!data.fluid().isEmpty()) {
                line.add(GoggleText.text("(" + data.fluid().getHoverName().getString() + ")")
                        .style(ChatFormatting.AQUA));
            }
            line.forGoggles(tooltip, 1);
            return;
        }
        // Open and unthrottled but nothing moving — show the run's actual stop (sink full,
        // a shut valve elsewhere, settled levels) rather than a misleading "nothing to move".
        GoggleText.lang(PipeStatusText.reasonKey(data)).style(PipeStatusText.color(data.status())).forGoggles(tooltip, 1);
    }
}
