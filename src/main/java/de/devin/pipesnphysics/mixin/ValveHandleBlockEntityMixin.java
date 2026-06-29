package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.ValveThrottle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a Create Valve Handle crank our fluid-valve throttle DIRECTLY: each successful turn adds
 * the handle's set angle to every fluid valve on its kinetic network (the crank direction —
 * which sneak flips — chooses open vs close). The handle spins the shaft at a fixed 32 RPM for a
 * couple of ticks, so a small set angle overshoots by ~one tick of spin (a 1° handle turns the
 * shaft ~17°); applying the handle's INTENT instead of reading that rotation keeps it exact.
 */
@Mixin(value = ValveHandleBlockEntity.class, remap = false)
public abstract class ValveHandleBlockEntityMixin extends KineticBlockEntity {
    @Shadow
    public ScrollValueBehaviour angleInput;

    @Shadow
    protected abstract boolean clockwise();

    private ValveHandleBlockEntityMixin() { super(null, null, null); }

    @Inject(method = "activate", at = @At("TAIL"))
    private void pipesnphysics$crankConnectedValves(boolean sneak, CallbackInfoReturnable<Boolean> cir) {
        if (level == null || level.isClientSide() || angleInput == null) return;
        if (!PipesNPhysicsConfig.ENABLE_VALVE_THROTTLE.get()) return;
        int step = Math.abs(angleInput.getValue());
        if (step == 0) return;
        int delta = clockwise() ? -step : step;
        KineticNetwork network = getOrCreateNetwork();
        if (network == null) return;
        for (KineticBlockEntity member : network.members.keySet()) {
            if (member instanceof ValveThrottle valve) valve.pipesnphysics$adjustThrottle(delta);
        }
    }
}
