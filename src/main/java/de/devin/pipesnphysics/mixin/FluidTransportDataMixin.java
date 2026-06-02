package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import de.devin.pipesnphysics.physics.EdgePhase;
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
 *
 * Also syncs flow progress per direction — Create's own deserializeNBT
 * does not restore progress from NBT, so we handle it ourselves.
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
        CompoundTag tag = nbt.contains("PipesNPhysics") ? nbt.getCompound("PipesNPhysics") : new CompoundTag();

        if (pipesnphysics$breakdown != null) {
            tag.putFloat("gravity", pipesnphysics$breakdown.gravityContribution());
            tag.putFloat("pump", pipesnphysics$breakdown.pumpContribution());
            tag.putFloat("merge", pipesnphysics$breakdown.mergeContribution());
            tag.putFloat("split", pipesnphysics$breakdown.splitPenalty());
            tag.putFloat("friction", pipesnphysics$breakdown.friction());
            tag.putFloat("net", pipesnphysics$breakdown.net());
            tag.putBoolean("capped", pipesnphysics$breakdown.capped());
            tag.putBoolean("bursting", pipesnphysics$breakdown.bursting());
            tag.putInt("phase", pipesnphysics$breakdown.phase().ordinal());
            tag.putFloat("frontProgress", pipesnphysics$breakdown.frontProgress());
            tag.putFloat("deltaPhi", pipesnphysics$breakdown.deltaPhi());
            tag.putFloat("headRemaining", pipesnphysics$breakdown.headRemaining());
            tag.putInt("edgeLength", pipesnphysics$breakdown.edgeLength());
            tag.putFloat("headAtUpstream", pipesnphysics$breakdown.headAtUpstream());
        }

        // Sync flow progress per direction — Create doesn't restore progress from NBT
        FluidTransportBehaviour self = (FluidTransportBehaviour) (Object) this;
        for (Direction dir : Direction.values()) {
            PipeConnection.Flow flow = self.getFlow(dir);
            if (flow != null && !flow.fluid.isEmpty()) {
                CompoundTag flowTag = new CompoundTag();
                flowTag.putBoolean("in", flow.inbound);
                flowTag.putFloat("p", (float) flow.progress.getValue());
                tag.put("f_" + dir.ordinal(), flowTag);
            } else {
                tag.remove("f_" + dir.ordinal());
            }
        }

        nbt.put("PipesNPhysics", tag);
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("TAIL"), remap = false)
    private void onRead(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!nbt.contains("PipesNPhysics")) {
            pipesnphysics$breakdown = null;
            return;
        }
        CompoundTag tag = nbt.getCompound("PipesNPhysics");

        if (tag.contains("gravity")) {
            EdgePhase phase = EdgePhase.EMPTY;
            if (tag.contains("phase")) {
                int ordinal = tag.getInt("phase");
                EdgePhase[] phases = EdgePhase.values();
                if (ordinal >= 0 && ordinal < phases.length) phase = phases[ordinal];
            }
            pipesnphysics$breakdown = new PressureBreakdown(
                    tag.getFloat("gravity"),
                    tag.getFloat("pump"),
                    tag.getFloat("merge"),
                    tag.getFloat("split"),
                    tag.getFloat("friction"),
                    tag.getFloat("net"),
                    tag.getBoolean("capped"),
                    tag.getBoolean("bursting"),
                    phase,
                    tag.getFloat("frontProgress"),
                    tag.getFloat("deltaPhi"),
                    tag.getFloat("headRemaining"),
                    tag.getInt("edgeLength"),
                    tag.getFloat("headAtUpstream")
            );
        }

        // Restore flow progress per direction from our own sync data
        FluidTransportBehaviour self = (FluidTransportBehaviour) (Object) this;
        for (Direction dir : Direction.values()) {
            String key = "f_" + dir.ordinal();
            PipeConnection.Flow flow = self.getFlow(dir);
            if (tag.contains(key) && flow != null) {
                CompoundTag flowTag = tag.getCompound(key);
                flow.inbound = flowTag.getBoolean("in");
                flow.progress.startWithValue(flowTag.getFloat("p"));
                flow.complete = flowTag.getFloat("p") >= 15f / 16f;
            }
        }
    }
}
