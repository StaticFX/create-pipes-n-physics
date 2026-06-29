package de.devin.pipesnphysics.client;

import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import net.minecraft.ChatFormatting;

/**
 * Shared mapping from a {@link PipeStatusPayload} to the goggle's "No Flow: &lt;reason&gt;"
 * line and its severity colour, so the pipe and valve goggles word a non-flowing run
 * identically (and a new status only has to be handled once).
 */
public final class PipeStatusText {
    private PipeStatusText() {}

    /** The most specific "No Flow" wording for a non-flowing status. */
    public static String reasonKey(PipeStatusPayload data) {
        return switch (data.status()) {
            case PipeStatusPayload.STATUS_NOT_CONNECTED -> "gui.goggles.not_connected";
            case PipeStatusPayload.STATUS_NO_HEAD -> "gui.goggles.no_head";
            case PipeStatusPayload.STATUS_NO_FLOW -> noFlowKey(data);
            case PipeStatusPayload.STATUS_BLOCKED, PipeStatusPayload.STATUS_STALLED -> detailKey(data);
            default -> "gui.goggles.no_flow_dry";
        };
    }

    /** Severity colour, kept per status so the uniform "No Flow:" prefix still reads at a glance. */
    public static ChatFormatting color(byte status) {
        return switch (status) {
            case PipeStatusPayload.STATUS_NO_HEAD -> ChatFormatting.RED;
            case PipeStatusPayload.STATUS_BLOCKED, PipeStatusPayload.STATUS_STALLED -> ChatFormatting.GOLD;
            case PipeStatusPayload.STATUS_NOT_CONNECTED -> ChatFormatting.DARK_GRAY;
            default -> ChatFormatting.GRAY;
        };
    }

    /**
     * Whether the goggle's "Lift left / Reach limit" reach readout is worth showing. It answers
     * "how much higher can fluid still climb from here" — only meaningful while fluid is actually
     * moving (FLOWING) or a pump is being asked to lift it (BLOCKED/STALLED/NO_HEAD). On an idle
     * NO_FLOW run it is noise: a settled pipe sitting a hair above a low waterline would otherwise
     * read an alarming "Reach limit — raise the supply or add a pump" though nothing is trying to
     * deliver. The "No Flow: settled…" reason line already explains that state.
     */
    public static boolean showsReach(PipeStatusPayload data) {
        return data.hasHeadroom() && data.status() != PipeStatusPayload.STATUS_NO_FLOW;
    }

    private static String noFlowKey(PipeStatusPayload data) {
        // "Held" first: a pump holding a pressurized column up to a shut valve has fluid, but it
        // is NOT idly settled — say so, rather than mislabelling the held column "balanced".
        if (data.statusDetail() == PipeStatusPayload.DETAIL_HELD) return "gui.goggles.held";
        if (!data.fluid().isEmpty()) return "gui.goggles.no_flow_settled";
        // A pump with nothing on its push side isn't short of supply — it has nowhere to
        // deliver. Naming the OUTPUT side keeps the player from chasing a healthy source.
        if (data.statusDetail() == PipeStatusPayload.DETAIL_PUMP_NO_OUTPUT) return "gui.goggles.no_flow_no_output";
        if (data.statusDetail() == PipeStatusPayload.DETAIL_PUMP_STARVED) return "gui.goggles.no_flow_starved";
        return "gui.goggles.no_flow_dry";
    }

    private static String detailKey(PipeStatusPayload data) {
        return switch (data.statusDetail()) {
            case PipeStatusPayload.DETAIL_VALVE -> "gui.goggles.detail.valve";
            case PipeStatusPayload.DETAIL_PUMP_OFF -> "gui.goggles.detail.pump_off";
            case PipeStatusPayload.DETAIL_CREST -> "gui.goggles.detail.crest";
            case PipeStatusPayload.DETAIL_SINK_FULL -> "gui.goggles.detail.sink_full";
            case PipeStatusPayload.DETAIL_SOURCE_DRY -> "gui.goggles.detail.source_dry";
            default -> data.status() == PipeStatusPayload.STATUS_STALLED
                    ? "gui.goggles.stalled" : "gui.goggles.blocked";
        };
    }
}
