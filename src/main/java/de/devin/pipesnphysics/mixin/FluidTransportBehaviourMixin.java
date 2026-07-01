package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import de.devin.pipesnphysics.compat.PipeLevelData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the in-pipe LEVEL renderer's per-cell metadata to Create's pipe behaviour as a dedicated,
 * client-synced-but-not-saved field ({@link PipeLevelData}) — the honest channel that replaced
 * smuggling the waterline into the flow's fluid amount.
 *
 * Create serializes each behaviour via {@code write}/{@code read} with a {@code clientPacket} flag
 * ({@code true} for the sync packet, {@code false} for the disk save). We write the field ONLY on the
 * sync path, so it reaches the client (which renders it) but never hits the world save — it is
 * re-stamped from the solve every tick, so a saved value would be stale render junk. Unlike the old
 * amount hack this is a small opaque int, never read as a volume, so even a leaked copy cannot dupe
 * fluid. (Create captures a block entity's {@code clientPacket=true} update tag into CONTRAPTION data,
 * so a stamped pipe glued to an assembling contraption can carry the field along; harmless — it is
 * re-stamped once the sub-level solves and is not a volume.)
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public class FluidTransportBehaviourMixin implements PipeLevelData {
    @Unique
    private int pipesnphysics$levelData = 0;

    @Override
    public int pipesnphysics$getLevelData() {
        return pipesnphysics$levelData;
    }

    @Override
    public void pipesnphysics$setLevelData(int data) {
        pipesnphysics$levelData = data;
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void pipesnphysics$writeLevel(CompoundTag nbt, HolderLookup.Provider registries,
                                          boolean clientPacket, CallbackInfo ci) {
        // Sync the level field only while the flag is on. Turned off (the default), the field is left
        // OUT of the packet, so a client holding a stale value reads it as absent (0) on the next sync
        // and stops rendering it — the field self-clears instead of lingering, since apply no longer
        // runs resetLevelData once level render is off.
        if (clientPacket && pipesnphysics$levelData != 0 && CreatePipeRendering.levelRenderEnabled()) {
            nbt.putInt("PnpLevel", pipesnphysics$levelData);
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void pipesnphysics$readLevel(CompoundTag nbt, HolderLookup.Provider registries,
                                         boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) pipesnphysics$levelData = nbt.getInt("PnpLevel"); // 0 when absent
    }
}
