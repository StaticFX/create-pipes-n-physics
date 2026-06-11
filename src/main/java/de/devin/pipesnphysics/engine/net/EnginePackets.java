package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.engine.PipeProbe;
import de.devin.pipesnphysics.engine.PumpRangeProbe;
import de.devin.pipesnphysics.engine.render.GraphOverlay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One-stop registration for all engine packets.
 */
public final class EnginePackets {

    /** Goggle probes run a full network solve; cap how often one player may ask. */
    private static final int PROBE_THROTTLE_TICKS = 4;
    private static final double MAX_PROBE_DISTANCE_SQ = 64 * 64;

    private EnginePackets() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PipesNPhysics.ID).versioned("2");
        registrar.playToClient(
                GraphOverlayPayload.TYPE,
                GraphOverlayPayload.STREAM_CODEC,
                EnginePackets::onGraphOverlay);
        registrar.playToClient(
                PipeStatusPayload.TYPE,
                PipeStatusPayload.STREAM_CODEC,
                EnginePackets::onPipeStatus);
        registrar.playToServer(
                PipeStatusRequest.TYPE,
                PipeStatusRequest.STREAM_CODEC,
                EnginePackets::onPipeStatusRequest);
        registrar.playToClient(
                PumpRangePayload.TYPE,
                PumpRangePayload.STREAM_CODEC,
                EnginePackets::onPumpRange);
        registrar.playToServer(
                PumpRangeRequest.TYPE,
                PumpRangeRequest.STREAM_CODEC,
                EnginePackets::onPumpRangeRequest);
    }

    private static void onPumpRange(PumpRangePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> de.devin.pipesnphysics.client.PumpRangeClient.receive(
                payload, ctx.player().level().getGameTime()));
    }

    private static void onPumpRangeRequest(PumpRangeRequest request, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = player.serverLevel();
            long now = level.getGameTime();
            if (now - player.getPersistentData().getLong("pipesnphysics_range_at") < PROBE_THROTTLE_TICKS) return;
            player.getPersistentData().putLong("pipesnphysics_range_at", now);

            if (request.pos().distSqr(player.blockPosition()) > MAX_PROBE_DISTANCE_SQ) return;
            if (!level.isLoaded(request.pos())) return;

            PacketDistributor.sendToPlayer(player, PumpRangeProbe.probe(level, request.pos()));
        });
    }

    private static void onGraphOverlay(GraphOverlayPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> GraphOverlay.receive(payload));
    }

    private static void onPipeStatus(PipeStatusPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PipeStatusClient.receive(payload, ctx.player().level().getGameTime()));
    }

    private static void onPipeStatusRequest(PipeStatusRequest request, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = player.serverLevel();
            long now = level.getGameTime();
            if (now - player.getPersistentData().getLong("pipesnphysics_probe_at") < PROBE_THROTTLE_TICKS) return;
            player.getPersistentData().putLong("pipesnphysics_probe_at", now);

            if (request.pos().distSqr(player.blockPosition()) > MAX_PROBE_DISTANCE_SQ) return;
            if (!level.isLoaded(request.pos())) return;

            PacketDistributor.sendToPlayer(player, PipeProbe.probe(level, request.pos()));
        });
    }
}
