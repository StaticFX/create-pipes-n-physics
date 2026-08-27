package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.api.PumpApi;
import de.devin.pipesnphysics.api.TurbineApi;
import de.devin.pipesnphysics.engine.pump.Pumps;
import de.devin.pipesnphysics.engine.turbine.HydroTurbine;
import de.devin.pipesnphysics.engine.turbine.PumpModeBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Puts the pump role dial (PUMP / TURBINE / AUTO, §5.4) on EVERY pump, not just the ones that
 * inherit Create's {@code addBehaviours}.
 *
 * A block entity is free to REPLACE {@code addBehaviours} rather than extend it, and TFMG's electric
 * pump does exactly that — it adds its own transfer behaviour and never calls {@code super} — so an
 * inject on the pump's own method never ran for it and the block had no dial at all: it could never
 * be a turbine, and its goggle showed no role. Nothing the player could see, let alone fix.
 *
 * So the dial is attached where Create COLLECTS behaviours, in {@link SmartBlockEntity}'s
 * constructor, after the subclass has had its say. That is also why it is not attached from Create's
 * own {@code BlockEntityBehaviourEvent}, the obvious hook: that fires in {@code initialize()}, AFTER
 * the block entity has read its NBT, so a dial attached there would forget its dialed mode on every
 * reload. Identity is read off the BLOCK STATE (Create's pump, the {@code pipesnphysics:pumps} tag,
 * or {@link PumpApi}) since a constructing block entity has no level to ask.
 */
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class PumpDialMixin {
    @Shadow @Final
    private Map<BehaviourType<?>, BlockEntityBehaviour> behaviours;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void pipesnphysics$addPumpDial(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                           CallbackInfo ci) {
        if (!(state.getBlock() instanceof PumpBlock) && !state.is(Pumps.PUMPS)
                && !PumpApi.isDeclaredPump(state.getBlock())) {
            return;
        }
        // Only where the dial can actually be HONOURED. A pump whose block entity carries the
        // turbine machinery (Create's, and anything extending it) can run backwards, and so can one
        // with a registered TurbineAdapter — but a foreign pump with neither would just wear a
        // control that does nothing, and dialing it TURBINE would leave a head-eating restriction
        // that produces no power at all. Power Grid's electric pump is exactly that case: no
        // rotation to give, and electricity out lives in its own mod's circuit simulation.
        if (!((Object) this instanceof HydroTurbine)
                && !TurbineApi.isTurbineBlock(state.getBlock())) {
            return;
        }
        if (behaviours.containsKey(PumpModeBehaviour.TYPE)) return;
        behaviours.put(PumpModeBehaviour.TYPE,
                PumpModeBehaviour.create((SmartBlockEntity) (Object) this));
    }
}
