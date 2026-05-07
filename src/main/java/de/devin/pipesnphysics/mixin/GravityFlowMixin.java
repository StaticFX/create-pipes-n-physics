package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.GravityFlowHandler;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.level.Level;
import org.joml.Quaterniondc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hooks into FluidTransportBehaviour to trigger gravity flow checks:
 * 1. When wipePressure() is called (network rebuild)
 * 2. Periodically during tick() if the pipe is idle with no pressure
 * 3. When a Sable sub-level rotates (detects orientation change, wipes stale pressure)
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public abstract class GravityFlowMixin extends BlockEntityBehaviour {

    @Shadow public FluidTransportBehaviour.UpdatePhase phase;
    @Shadow public abstract boolean hasAnyPressure();

    /** Tracks last known orientation per sub-level to detect rotation changes. */
    @Unique
    private static final Map<UUID, float[]> pipesnphysics$lastOrientations = new ConcurrentHashMap<>();

    private GravityFlowMixin() { super(null); }

    @Inject(method = "wipePressure", at = @At("TAIL"))
    private void onWipePressure(CallbackInfo ci) {
        Level level = blockEntity.getLevel();
        if (level != null && !level.isClientSide()) {
            GravityFlowHandler.scheduleCheck(level, blockEntity.getBlockPos());
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void periodicGravityCheck(CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_GRAVITY_FLOW.get()) return;
        if (phase != FluidTransportBehaviour.UpdatePhase.IDLE) return;

        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide()) return;

        // Stagger checks across ticks (configurable rate)
        int recheckTicks = PipesNPhysicsConfig.GRAVITY_RECHECK_TICKS.get();
        if (level.getGameTime() % recheckTicks != (blockEntity.getBlockPos().hashCode() & 0x7FFFFFFF) % recheckTicks) return;

        // For pipes WITH pressure: check if sub-level rotated (stale gravity data)
        if (hasAnyPressure()) {
            if (pipesnphysics$hasSubLevelRotated(level)) {
                // Orientation changed — wipe stale pressure, triggering recomputation via onWipePressure hook
                ((FluidTransportBehaviour) (Object) this).wipePressure();
            }
            return;
        }

        // For pipes WITHOUT pressure: schedule normal gravity check
        GravityFlowHandler.scheduleCheck(level, blockEntity.getBlockPos());
    }

    /** Returns true if this pipe is on a Sable sub-level whose orientation changed since last check. */
    @Unique
    private boolean pipesnphysics$hasSubLevelRotated(Level level) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, blockEntity.getBlockPos());
        if (sub == null) return false;

        Pose3dc pose = sub.logicalPose();
        if (pose == null) return false;

        Quaterniondc q = pose.orientation();
        UUID id = sub.getUniqueId();
        float[] current = {(float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()};
        float[] last = pipesnphysics$lastOrientations.get(id);

        if (last == null) {
            pipesnphysics$lastOrientations.put(id, current);
            return false;
        }

        // Check if orientation changed significantly (dot product < threshold)
        float dot = last[0]*current[0] + last[1]*current[1] + last[2]*current[2] + last[3]*current[3];
        float threshold = PipesNPhysicsConfig.GRAVITY_ROTATION_THRESHOLD.get().floatValue();
        if (Math.abs(dot) < threshold) {
            pipesnphysics$lastOrientations.put(id, current);
            return true;
        }

        return false;
    }
}
