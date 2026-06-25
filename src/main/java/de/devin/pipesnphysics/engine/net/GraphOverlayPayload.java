package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Sent server → client when a player runs {@code /pipegraph}. Contains a snapshot
 * of one network's nodes and directional edges; the client stores it and draws
 * an in-world overlay for a fixed time.
 */
public record GraphOverlayPayload(List<NodeEntry> nodes, List<EdgeEntry> edges) implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(PipesNPhysics.ID, "graph_overlay");
    public static final Type<GraphOverlayPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphOverlayPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NodeEntry.CODEC.apply(ByteBufCodecs.list()), GraphOverlayPayload::nodes,
                    EdgeEntry.CODEC.apply(ByteBufCodecs.list()), GraphOverlayPayload::edges,
                    GraphOverlayPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Human-friendly name for the edge at a given index: A, B, … Z, AA, AB, …
     * Used by both the /pipegraph chat dump and the in-world overlay labels so the
     * two always agree.
     */
    public static String edgeLetter(int index) {
        StringBuilder name = new StringBuilder();
        int i = index + 1;
        while (i > 0) {
            i--;
            name.insert(0, (char) ('A' + i % 26));
            i /= 26;
        }
        return name.toString();
    }

    /** One node, by world position with its kind. */
    public record NodeEntry(int x, int y, int z, byte kind) {
        public static final byte KIND_HANDLER = 0;
        public static final byte KIND_PUMP = 1;
        public static final byte KIND_JUNCTION = 2;
        public static final byte KIND_OPEN_END = 3;

        public static final StreamCodec<RegistryFriendlyByteBuf, NodeEntry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, NodeEntry::x,
                        ByteBufCodecs.VAR_INT, NodeEntry::y,
                        ByteBufCodecs.VAR_INT, NodeEntry::z,
                        ByteBufCodecs.BYTE, NodeEntry::kind,
                        NodeEntry::new
                );
    }

    /**
     * One edge as an ordered list of points (from source-side endpoint through the
     * pipe cells to the sink-side endpoint), plus a flow rate. The client draws
     * a polyline and an arrowhead pointing toward the last point.
     *
     * direction: 0 = NONE (drawn dim/no arrow), 1 = flowing along points order,
     * 2 = stalled (pressurized along points order but nothing moves — no arrow).
     *
     * pressures holds the gauge pressure (head minus elevation, in blocks) at each
     * point, aligned with the points list; empty when the edge had no solved heads.
     * The client colors the run as a gradient over it.
     */
    public record EdgeEntry(List<Long> points, int mbPerTick, byte direction, List<Float> pressures) {
        public static final byte DIR_NONE = 0;
        public static final byte DIR_FORWARD = 1;
        public static final byte DIR_STALLED = 2;

        public static final StreamCodec<RegistryFriendlyByteBuf, EdgeEntry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), EdgeEntry::points,
                        ByteBufCodecs.VAR_INT, EdgeEntry::mbPerTick,
                        ByteBufCodecs.BYTE, EdgeEntry::direction,
                        ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), EdgeEntry::pressures,
                        EdgeEntry::new
                );
    }
}
