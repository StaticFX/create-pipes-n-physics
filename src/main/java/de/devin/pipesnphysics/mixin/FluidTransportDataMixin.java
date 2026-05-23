package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import de.devin.pipesnphysics.physics.PipeFlowData;
import de.devin.pipesnphysics.physics.PressureBreakdown;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Attaches physics flow data to FluidTransportBehaviour.
 * Serialized via NBT so it syncs to clients automatically through
 * Create's block entity sync system.
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public class FluidTransportDataMixin implements PipeFlowData {

    @Unique
    private PressureBreakdown pipesnphysics$breakdown;

    @Override
    public PressureBreakdown pipesnphysics$getBreakdown() {
        return pipesnphysics$breakdown;
    }

    @Override
    public void pipesnphysics$setBreakdown(PressureBreakdown breakdown) {
        this.pipesnphysics$breakdown = breakdown;
    }

    @Override
    public void pipesnphysics$setFlowOnConnection(Direction side, boolean inbound, FluidStack fluid) {
        FluidTransportBehaviour self = (FluidTransportBehaviour) (Object) this;
        PipeConnection conn = self.getConnection(side);
        if (conn == null || fluid.isEmpty()) return;
        try {
            java.lang.reflect.Field flowField = PipeConnection.class.getDeclaredField("flow");
            flowField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Optional<PipeConnection.Flow> current = (Optional<PipeConnection.Flow>) flowField.get(conn);
            if (current.isEmpty()) {
                flowField.set(conn, Optional.of(conn.new Flow(inbound, fluid.copy())));
            }
        } catch (ReflectiveOperationException ignored) {}
    }

    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("TAIL"), remap = false)
    private void onWrite(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (pipesnphysics$breakdown == null) return;
        CompoundTag tag = new CompoundTag();
        tag.putFloat("gravity", pipesnphysics$breakdown.gravityContribution());
        tag.putFloat("pump", pipesnphysics$breakdown.pumpContribution());
        tag.putFloat("merge", pipesnphysics$breakdown.mergeContribution());
        tag.putFloat("split", pipesnphysics$breakdown.splitPenalty());
        tag.putFloat("friction", pipesnphysics$breakdown.friction());
        tag.putFloat("net", pipesnphysics$breakdown.net());
        tag.putBoolean("capped", pipesnphysics$breakdown.capped());
        tag.putBoolean("bursting", pipesnphysics$breakdown.bursting());
        nbt.put("PipesNPhysics", tag);
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("TAIL"), remap = false)
    private void onRead(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!nbt.contains("PipesNPhysics")) {
            pipesnphysics$breakdown = null;
            return;
        }
        CompoundTag tag = nbt.getCompound("PipesNPhysics");
        pipesnphysics$breakdown = new PressureBreakdown(
                tag.getFloat("gravity"),
                tag.getFloat("pump"),
                tag.getFloat("merge"),
                tag.getFloat("split"),
                tag.getFloat("friction"),
                tag.getFloat("net"),
                tag.getBoolean("capped"),
                tag.getBoolean("bursting")
        );
    }
}
