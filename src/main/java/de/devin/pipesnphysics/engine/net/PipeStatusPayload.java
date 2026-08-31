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
 *
 * STATUS_* and DETAIL_* are two independent enums, each riding its own byte on
 * the wire ({@code status} and {@code statusDetail}): the status is the headline
 * state, the detail names the culprit behind it — not a sub-code of the status.
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
        float headroomBlocks,
        float headTotalBlocks,
        byte statusDetail,
        boolean hasSuctionMargin,
        float suctionMarginBlocks,
        boolean hasPumpLoad,
        float pumpHeadAgainst,
        float pumpFrictionFactor,
        int holdsMb
) implements CustomPacketPayload {
    // The headline state — the "status" byte on the wire.
    public static final byte STATUS_NOT_CONNECTED = 0;
    public static final byte STATUS_NO_FLOW = 1;
    public static final byte STATUS_FLOWING = 2;
    public static final byte STATUS_BLOCKED = 3;
    public static final byte STATUS_STALLED = 4;
    public static final byte STATUS_NO_HEAD = 5;

    // The culprit behind a non-flowing status — the independent "statusDetail" byte on the wire.
    public static final byte DETAIL_NONE = 0;
    public static final byte DETAIL_VALVE = 1;
    public static final byte DETAIL_PUMP_OFF = 2;
    public static final byte DETAIL_CREST = 3;
    public static final byte DETAIL_SINK_FULL = 4;
    public static final byte DETAIL_SOURCE_DRY = 5;
    /** A dry no-flow run whose powered pump is running but can't pull a supply. */
    public static final byte DETAIL_PUMP_STARVED = 6;
    /** A pump-fed run holding its pressurized column up to a shut valve (the head doesn't reset). */
    public static final byte DETAIL_HELD = 7;
    /** A dry no-flow run whose powered pump has nothing on its push side — nowhere to deliver. */
    public static final byte DETAIL_PUMP_NO_OUTPUT = 8;
    /** A CREST-gated LEVEL run whose supply fluid stands below the pipe's aperture — no climb involved. */
    public static final byte DETAIL_BELOW_OPENING = 9;
    /** A run stopped by a ONE-WAY valve: the pressure wants to flow against its direction. */
    public static final byte DETAIL_CHECK_VALVE = 10;
    /** A run into a TURBINE whose fall is short of the turbine's rating — nothing turns it. */
    public static final byte DETAIL_TURBINE_FALL = 11;
    /** A run another fluid is standing in: a pipe carries one at a time, so this one waits. */
    public static final byte DETAIL_OTHER_FLUID = 12;

    public static final Type<PipeStatusPayload> TYPE =
            new Type<>(PipesNPhysics.asResource("pipe_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PipeStatusPayload> STREAM_CODEC =
            StreamCodec.of(PipeStatusPayload::write, PipeStatusPayload::read);

    /** The all-zero answer for a position with no network: every optional section is absent. */
    public static PipeStatusPayload notConnected(BlockPos pos) {
        return new PipeStatusPayload(pos, STATUS_NOT_CONNECTED, 0, null, FluidStack.EMPTY,
                false, 0, false, 0, 0, DETAIL_NONE, false, 0, false, 0, 0, 0);
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
        buf.writeFloat(payload.headTotalBlocks);
        buf.writeByte(payload.statusDetail);
        buf.writeBoolean(payload.hasSuctionMargin);
        buf.writeFloat(payload.suctionMarginBlocks);
        buf.writeBoolean(payload.hasPumpLoad);
        buf.writeFloat(payload.pumpHeadAgainst);
        buf.writeFloat(payload.pumpFrictionFactor);
        buf.writeVarInt(payload.holdsMb);
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
        float headTotal = buf.readFloat();
        byte statusDetail = buf.readByte();
        boolean hasSuctionMargin = buf.readBoolean();
        float suctionMargin = buf.readFloat();
        boolean hasPumpLoad = buf.readBoolean();
        float pumpHeadAgainst = buf.readFloat();
        float pumpFrictionFactor = buf.readFloat();
        int holdsMb = buf.readVarInt();
        return new PipeStatusPayload(pos, status, mbPerTick,
                directionId < 0 ? null : Direction.from3DDataValue(directionId),
                fluid, hasPressure, pressure, hasHeadroom, headroom, headTotal,
                statusDetail, hasSuctionMargin, suctionMargin,
                hasPumpLoad, pumpHeadAgainst, pumpFrictionFactor, holdsMb);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
