package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Server → client answer to a {@link PumpRangeRequest}: where the queried pump can
 * reach. Each path is a run of positions starting at the pump, with a reachable
 * flag per position (true = the pump's head covers this cell, false = starved)
 * and whether the path is on the pump's pull (suction) side.
 */
public record PumpRangePayload(BlockPos pumpPos, List<RangePath> paths)
        implements CustomPacketPayload {
    public static final Type<PumpRangePayload> TYPE =
            new Type<>(PipesNPhysics.asResource("pump_range"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PumpRangePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PumpRangePayload::pumpPos,
                    RangePath.CODEC.apply(ByteBufCodecs.list()), PumpRangePayload::paths,
                    PumpRangePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record RangePath(List<Long> points, List<Boolean> reachable, boolean pull) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RangePath> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), RangePath::points,
                        ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), RangePath::reachable,
                        ByteBufCodecs.BOOL, RangePath::pull,
                        RangePath::new);
    }
}
