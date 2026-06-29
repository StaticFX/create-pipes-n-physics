package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.client.PumpRangeClient;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.PipeProbe;
import de.devin.pipesnphysics.engine.PumpRangeProbe;
import de.devin.pipesnphysics.engine.render.GraphOverlay;
import net.minecraft.core.BlockPos;
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

    /**
     * Range-gate a probe by the pipe's REAL world position, not its raw BlockPos. A pipe on a
     * Sable sub-level lives at far-away plot coordinates (~30M blocks out), so a raw distSqr
     * always exceeds the range and the goggle never updates — projecting through the sub-level
     * pose puts it back where the player actually sees it.
     */
    private static boolean pipesnphysics$tooFar(ServerLevel level, BlockPos pos, ServerPlayer player) {
        return SableCompat.getWorldPos(level, pos).distanceToSqr(player.position()) > MAX_PROBE_DISTANCE_SQ;
    }

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
        ctx.enqueueWork(() -> PumpRangeClient.receive(
                payload, ctx.player().level().getGameTime()));
    }

    private static void onPumpRangeRequest(PumpRangeRequest request, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = player.serverLevel();
            long now = level.getGameTime();
            if (now - player.getPersistentData().getLong("pipesnphysics_range_at") < PROBE_THROTTLE_TICKS) return;
            player.getPersistentData().putLong("pipesnphysics_range_at", now);

            if (pipesnphysics$tooFar(level, request.pos(), player)) return;
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

            if (pipesnphysics$tooFar(level, request.pos(), player)) return;
            if (!level.isLoaded(request.pos())) return;

            PacketDistributor.sendToPlayer(player, PipeProbe.probe(level, request.pos()));
        });
    }
}
