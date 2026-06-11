package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "how far can the pump at this position reach?" Sent (throttled)
 * while a goggle-wearing player looks at a pump; answered with a {@link PumpRangePayload}.
 */
public record PumpRangeRequest(BlockPos pos) implements CustomPacketPayload {

    public static final Type<PumpRangeRequest> TYPE =
            new Type<>(PipesNPhysics.asResource("pump_range_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PumpRangeRequest> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos),
                    buf -> new PumpRangeRequest(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
