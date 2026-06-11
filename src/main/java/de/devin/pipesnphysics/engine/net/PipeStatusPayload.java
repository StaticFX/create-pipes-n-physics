package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * Server → client answer to a {@link PipeStatusRequest}: what the queried pipe
 * cell is doing this tick. Rendered into the goggle tooltip by the pipe goggle
 * mixin via {@link PipeStatusClient}.
 */
public record PipeStatusPayload(
        BlockPos pos,
        byte status,
        int mbPerTick,
        @Nullable Direction flowDirection,
        FluidStack fluid,
        boolean hasPressure,
        float pressureBlocks,
        boolean hasHeadroom,
        float headroomBlocks
) implements CustomPacketPayload {

    public static final byte STATUS_NOT_CONNECTED = 0;
    public static final byte STATUS_NO_FLOW = 1;
    public static final byte STATUS_FLOWING = 2;
    public static final byte STATUS_BLOCKED = 3;
    public static final byte STATUS_STALLED = 4;
    public static final byte STATUS_NO_HEAD = 5;

    public static final Type<PipeStatusPayload> TYPE =
            new Type<>(PipesNPhysics.asResource("pipe_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PipeStatusPayload> STREAM_CODEC =
            StreamCodec.of(PipeStatusPayload::write, PipeStatusPayload::read);

    public static PipeStatusPayload notConnected(BlockPos pos) {
        return new PipeStatusPayload(pos, STATUS_NOT_CONNECTED, 0, null, FluidStack.EMPTY,
                false, 0, false, 0);
    }

    private static void write(RegistryFriendlyByteBuf buf, PipeStatusPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeByte(payload.status);
        buf.writeVarInt(payload.mbPerTick);
        buf.writeByte(payload.flowDirection == null ? -1 : payload.flowDirection.get3DDataValue());
        FluidStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.fluid);
        buf.writeBoolean(payload.hasPressure);
        buf.writeFloat(payload.pressureBlocks);
        buf.writeBoolean(payload.hasHeadroom);
        buf.writeFloat(payload.headroomBlocks);
    }

    private static PipeStatusPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        byte status = buf.readByte();
        int mbPerTick = buf.readVarInt();
        byte directionId = buf.readByte();
        FluidStack fluid = FluidStack.OPTIONAL_STREAM_CODEC.decode(buf);
        boolean hasPressure = buf.readBoolean();
        float pressure = buf.readFloat();
        boolean hasHeadroom = buf.readBoolean();
        float headroom = buf.readFloat();
        return new PipeStatusPayload(pos, status, mbPerTick,
                directionId < 0 ? null : Direction.from3DDataValue(directionId),
                fluid, hasPressure, pressure, hasHeadroom, headroom);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
