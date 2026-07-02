package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler;
import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a hose pulley actually supply the engine, and wakes the network when it does.
 *
 * Two problems, both because our engine replaces Create's fluid transport:
 *
 * <p>1. Create's drainer only STARTS searching the fluid body when something {@code drain()}s the
 * pulley — vanilla's fluid network does that as it pulls. Our engine, though, models the pulley as a
 * source only ONCE it already advertises fluid ({@code BoundaryColumn.resolve}), and never
 * {@code drain()}s it before then — and {@code getFluidInTank}/{@code getDrainableFluid} short-circuit
 * on {@code fluid == null} WITHOUT kicking the search. So the search never begins and the pulley stays
 * forever empty (the "won't pull from a hose pulley" report, e.g. over a Nether lava ocean). We drive
 * it ourselves: a harmless {@code SIMULATE} drain each tick while it is priming bootstraps and advances
 * the search. This plays no sound — {@code pullNext} returns early while searching, and we STOP driving
 * the tick it goes ready, before Create's infinite-branch effect.
 *
 * <p>2. Once it goes ready, a DRY network plumbed before it primed would never re-solve to notice
 * (its pipes hold no fluid, so nothing re-marks it dirty; a pulley is a HANDLER, not a PUMP, so arming
 * can't reach it). The pulley is a ticking kinetic block entity, so on the priming -&gt; ready flip we
 * {@code markChanged} the neighbours once to wake the network; once it delivers, fluid enters the pipes
 * (or it pressurises a full sink) and the network stays live on its own.
 *
 * Readiness is read side-effect-free via {@link FluidDrainingBehaviourAccessor}. No-op when the engine
 * is disabled, so vanilla pulley behaviour is untouched.
 */
@Mixin(value = HosePulleyBlockEntity.class, remap = false)
public class HosePulleyBlockEntityMixin {
    @Shadow boolean isMoving;
    @Shadow private FluidDrainingBehaviour drainer;
    @Shadow private HosePulleyFluidHandler handler;

    @Unique
    private boolean pipesnphysics$wasReady = false;

    @Inject(method = "tick", at = @At("TAIL"))
    private void pipesnphysics$driveAndArm(CallbackInfo ci) {
        HosePulleyBlockEntity self = (HosePulleyBlockEntity) (Object) this;
        Level world = self.getLevel();
        if (world == null || world.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        // The hose is still lowering (rootPos moving) or the drainer is not built yet: nothing to do.
        if (isMoving || drainer == null || handler == null) {
            pipesnphysics$wasReady = false;
            return;
        }

        boolean ready = pipesnphysics$canSupply();
        if (!ready) {
            // Kick / advance Create's body search from the pulley's own tick (see class doc). SIMULATE,
            // so it drains nothing and — while still searching — plays no sound.
            handler.drain(1, FluidAction.SIMULATE);
        } else if (!pipesnphysics$wasReady) {
            BlockPos pos = self.getBlockPos();
            for (Direction d : Direction.values()) {
                EngineTickHandler.markChanged(world, pos.relative(d));
            }
        }
        pipesnphysics$wasReady = ready;
    }

    /** Whether the drainer now advertises a drained body (found a fluid and finished searching). */
    @Unique
    private boolean pipesnphysics$canSupply() {
        FluidDrainingBehaviourAccessor acc = (FluidDrainingBehaviourAccessor) (Object) drainer;
        return acc.pipesnphysics$getFluid() != null && !acc.pipesnphysics$isSearching();
    }
}
