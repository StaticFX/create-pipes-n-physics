package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "what is the pipe at this position doing?" Sent (throttled)
 * while a goggle-wearing player looks at a pipe; answered with a
 * {@link PipeStatusPayload}.
 */
public record PipeStatusRequest(BlockPos pos) implements CustomPacketPayload {
    public static final Type<PipeStatusRequest> TYPE =
            new Type<>(PipesNPhysics.asResource("pipe_status_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PipeStatusRequest> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos),
                    buf -> new PipeStatusRequest(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
