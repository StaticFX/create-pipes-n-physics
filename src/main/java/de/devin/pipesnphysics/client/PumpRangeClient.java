package de.devin.pipesnphysics.client;

import de.devin.pipesnphysics.engine.net.PumpRangePayload;
import de.devin.pipesnphysics.engine.net.PumpRangeRequest;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side holder for the latest pump range answer plus the "preserve" timer:
 * the renderer keeps showing (and refreshing) the last pump's range for a short
 * configurable window after the player looks away.
 */
public final class PumpRangeClient {
    private static final int REQUEST_INTERVAL_TICKS = 10;

    private static PumpRangePayload latest;
    private static long lastLookedAt = Long.MIN_VALUE;
    private static BlockPos lastRequestedPos;
    private static long lastRequestedAt;

    private PumpRangeClient() {}

    public static void receive(PumpRangePayload payload, long gameTime) {
        if (latest == null || latest.pumpPos().equals(payload.pumpPos())
                || gameTime - lastLookedAt <= 1) {
            latest = payload;
        }
    }

    /** The player is looking at this pump right now. */
    public static void looking(BlockPos pumpPos, long gameTime) {
        if (latest != null && !latest.pumpPos().equals(pumpPos)) latest = null;
        lastLookedAt = gameTime;
        requestIfStale(pumpPos, gameTime);
    }

    /**
     * What to render this frame: the current pump's range while looking at it, or
     * the preserved one within the grace window (refreshed so it stays live).
     * Returns null when nothing should show.
     */
    public static PumpRangePayload active(long gameTime, boolean preserve, int preserveTicks) {
        if (latest == null) return null;
        long sinceLook = gameTime - lastLookedAt;
        if (sinceLook <= 1) return latest;
        if (!preserve || sinceLook > preserveTicks) return null;
        requestIfStale(latest.pumpPos(), gameTime);
        return latest;
    }

    /** 1 → fresh, falling toward 0 across the preserve window (for fade-out). */
    public static float preserveFraction(long gameTime, int preserveTicks) {
        long sinceLook = gameTime - lastLookedAt;
        if (sinceLook <= 1) return 1;
        return Math.clamp(1f - (float) sinceLook / preserveTicks, 0f, 1f);
    }

    private static void requestIfStale(BlockPos pos, long gameTime) {
        if (pos.equals(lastRequestedPos) && gameTime - lastRequestedAt < REQUEST_INTERVAL_TICKS) return;
        lastRequestedPos = pos.immutable();
        lastRequestedAt = gameTime;
        PacketDistributor.sendToServer(new PumpRangeRequest(pos));
    }
}
