package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.render.OpenEndParticles;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.pump.Pumps;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The engine's per-tick heartbeat, decoupled from Create's transport tick.
 *
 * The engine wakes a network by marking one of its pipes dirty every tick. That mark used to ride
 * {@link GravityFlowMixin}, which cancels {@code FluidTransportBehaviour.tick()}. But an addon that
 * ALSO cancels that behaviour tick at HEAD can win the cancel race and skip our callback, killing the
 * heartbeat: the engine sleeps and no fluid ever moves. CROWNS does exactly this — its
 * {@code FluidTransportBehaviourMixin} reimplements the tick to mix real-gas state and {@code cancel()}s
 * at HEAD (default priority). Betting the whole engine on which HEAD-cancel is woven first is fragile.
 *
 * So the heartbeat lives here instead, on the pipe BLOCK ENTITY's own {@code tick()} — the parent call
 * that dispatches every behaviour tick. No behaviour-level cancel can preempt it, so the network wakes
 * regardless of which addon owns transport. The trigger is "this block entity owns a
 * {@link FluidTransportBehaviour}" — exactly the set whose behaviour tick {@link GravityFlowMixin}
 * cancels, so every pipe kind is covered (encased pipes, valves, addon pipes on their own block
 * entities), not just the handful of concrete Create classes. A behaviour lookup on other smart block
 * entities' ticks is the only cost. Virtual (ponder/schematic) block entities skip.
 *
 * The CLIENT side of the same tick carries the open-mouth pour particles
 * ({@link OpenEndParticles}): Create spawned those inside the cancelled behaviour tick, so they
 * ride this uncancellable host for the same reason the heartbeat does. {@code OpenEndParticles}
 * deliberately carries no client-only imports, so this common mixin stays dist-clean.
 *
 * Targets {@link SmartBlockEntity} directly (the class that declares {@code tick()}) — every
 * {@code FluidTransportBehaviour} host is a {@code SmartBlockEntity}. A mixin may not extend its own
 * target, hence the cast to reach the block-entity methods.
 */
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class PipeHeartbeatMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void pipesnphysics$heartbeat(CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        SmartBlockEntity self = (SmartBlockEntity) (Object) this;
        FluidTransportBehaviour pipe = self.getBehaviour(FluidTransportBehaviour.TYPE);
        if (pipe == null) return;
        if (self.isVirtual()) return;
        Level level = self.getLevel();
        if (level == null) return;
        if (level.isClientSide()) {
            OpenEndParticles.spawnAt(level, pipe);
            return;
        }
        EngineTickHandler.markDirty(level, self.getBlockPos());
        pipesnphysics$clearForeignPressure(level, self, pipe);
    }

    /**
     * Under the engine a PIPE carries no Create pressure. Pressure is only ever meaningful where a
     * pump publishes it on its own two flanks — that is how every pump states its strength and how
     * the engine reads another mod's (§2) — so a pump cell is left alone and everything else is
     * cleared here.
     *
     * What this is for: a foreign pump keeps distributing pressure DOWN the run from its own block
     * entity's tick, which nothing of ours can cancel in general (the method lives on its class).
     * That pressure is the one ingredient a peer which REIMPLEMENTS the pipe tick — CROWNS does,
     * and can win the cancel race — needs to run a second, invisible transport beside ours, which
     * is exactly the leak {@code PumpTransferTickMixin} closed for Create's own pump. Clearing it
     * at HEAD of the uncancellable block-entity tick makes any such transport inert whoever wrote
     * the pressure, instead of one mixin per addon pump. Nothing else of Create's reads it while
     * the engine owns transport: the flow render is hidden, and {@code FluidPropagator}'s only use
     * is a walk that stops at the pump range either way.
     */
    @Unique
    private static void pipesnphysics$clearForeignPressure(Level level, SmartBlockEntity self,
                                                           FluidTransportBehaviour pipe) {
        if (!EngineTickHandler.suppressesCreateTransport(self)) return;
        if (pipe.interfaces == null) return;
        BlockPos pos = self.getBlockPos();
        if (Pumps.isPump(level, pos, self.getBlockState())) return;
        for (PipeConnection connection : pipe.interfaces.values()) {
            Couple<Float> pressure = connection.getPressure();
            if (pressure.getFirst() == 0 && pressure.getSecond() == 0) continue;
            pressure.set(true, 0f);
            pressure.set(false, 0f);
        }
    }
}
