package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import de.devin.pipesnphysics.compat.PipeLevelData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the in-pipe LEVEL renderer's per-cell metadata to Create's pipe behaviour as dedicated,
 * client-synced-but-not-saved fields ({@link PipeLevelData}) — the honest channel that replaced
 * smuggling the waterline into the flow's fluid amount. Three fields: the waterline level, the
 * engine-owned travelling-front state (which replaced reading Create's {@code Flow.progress}),
 * and the rendered fluid (which replaced reading Create's {@code Flow.fluid}).
 *
 * Create serializes each behaviour via {@code write}/{@code read} with a {@code clientPacket} flag
 * ({@code true} for the sync packet, {@code false} for the disk save). We write the fields ONLY on
 * the sync path, so they reach the client (which renders them) but never hit the world save — they
 * are re-stamped from the solve every tick, so a saved value would be stale render junk. Unlike the
 * old amount hack these are small opaque values, never read as a volume, so even a leaked copy
 * cannot dupe fluid. (Create captures a block entity's {@code clientPacket=true} update tag into
 * CONTRAPTION data, so a stamped pipe glued to an assembling contraption can carry the fields
 * along; harmless — they are re-stamped once the sub-level solves and are not volumes.)
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public class FluidTransportBehaviourMixin implements PipeLevelData {
    @Unique
    private int pipesnphysics$levelData = 0;

    @Unique
    private int pipesnphysics$frontData = 0;

    @Unique
    private FluidStack pipesnphysics$renderFluid = FluidStack.EMPTY;

    @Override
    public int pipesnphysics$getLevelData() {
        return pipesnphysics$levelData;
    }

    @Override
    public void pipesnphysics$setLevelData(int data) {
        pipesnphysics$levelData = data;
    }

    @Override
    public int pipesnphysics$getFrontData() {
        return pipesnphysics$frontData;
    }

    @Override
    public void pipesnphysics$setFrontData(int data) {
        pipesnphysics$frontData = data;
    }

    @Override
    public FluidStack pipesnphysics$getRenderFluid() {
        return pipesnphysics$renderFluid;
    }

    @Override
    public void pipesnphysics$setRenderFluid(FluidStack fluid) {
        pipesnphysics$renderFluid = fluid;
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void pipesnphysics$writeLevel(CompoundTag nbt, HolderLookup.Provider registries,
                                          boolean clientPacket, CallbackInfo ci) {
        // Sync the render fields only while the flag is on. Turned off (the default), they are left
        // OUT of the packet, so a client holding stale values reads them as absent on the next sync
        // and stops rendering them — they self-clear instead of lingering, since apply no longer
        // runs resetLevelData once level render is off.
        if (!clientPacket || !CreatePipeRendering.levelRenderEnabled()) return;
        if (pipesnphysics$levelData != 0) nbt.putInt("PnpLevel", pipesnphysics$levelData);
        if (pipesnphysics$frontData != 0) nbt.putInt("PnpFront", pipesnphysics$frontData);
        if (!pipesnphysics$renderFluid.isEmpty()) {
            nbt.put("PnpFluid", pipesnphysics$renderFluid.saveOptional(registries));
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void pipesnphysics$readLevel(CompoundTag nbt, HolderLookup.Provider registries,
                                         boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket) return;
        pipesnphysics$levelData = nbt.getInt("PnpLevel"); // 0 when absent
        pipesnphysics$frontData = nbt.getInt("PnpFront");
        pipesnphysics$renderFluid = nbt.contains("PnpFluid")
                ? FluidStack.parseOptional(registries, nbt.getCompound("PnpFluid"))
                : FluidStack.EMPTY;
    }
}
