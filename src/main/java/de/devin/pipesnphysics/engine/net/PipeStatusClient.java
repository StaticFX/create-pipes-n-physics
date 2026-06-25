package de.devin.pipesnphysics.engine.net;

import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side holder for the latest pipe status answer. A player can only look
 * at one block at a time, so a single slot suffices; the goggle mixin reads it
 * and re-requests on a small interval while the crosshair stays on a pipe.
 */
public final class PipeStatusClient {
    private static final int REQUEST_INTERVAL_TICKS = 5;
    private static final int STALE_AGE_TICKS = 40;

    private static PipeStatusPayload latest;
    private static long latestReceivedAt;
    private static BlockPos lastRequestedPos;
    private static long lastRequestedAt;

    private PipeStatusClient() {}

    public static void receive(PipeStatusPayload payload, long gameTime) {
        latest = payload;
        latestReceivedAt = gameTime;
    }

    /** The latest answer for this position, or null if none or outdated. */
    public static PipeStatusPayload current(BlockPos pos, long gameTime) {
        if (latest == null || !latest.pos().equals(pos)) return null;
        if (gameTime - latestReceivedAt > STALE_AGE_TICKS) return null;
        return latest;
    }

    /** Ask the server about pos, at most once per interval per position. */
    public static void requestIfStale(BlockPos pos, long gameTime) {
        if (pos.equals(lastRequestedPos) && gameTime - lastRequestedAt < REQUEST_INTERVAL_TICKS) return;
        lastRequestedPos = pos.immutable();
        lastRequestedAt = gameTime;
        PacketDistributor.sendToServer(new PipeStatusRequest(pos));
    }
}
