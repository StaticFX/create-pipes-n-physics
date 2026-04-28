package de.devin.pipesnphysics;

import net.neoforged.neoforge.common.ModConfigSpec;

public class PipesNPhysicsConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    // Server
    public static final ModConfigSpec.IntValue UPWARD_PIPE_COST;

    // Client
    public static final ModConfigSpec.BooleanValue SHOW_PUMP_RANGE_ARROWS;
    public static final ModConfigSpec.BooleanValue SHOW_PIPE_GOGGLE_INFO;

    static {
        // Server config
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();

        server.push("fluids");
        UPWARD_PIPE_COST = server
                .comment("Range cost for pipes going upward above the pump's Y level.",
                        "Higher values mean upward pipes consume more pump range.",
                        "Downward pipes always cost 0. Horizontal pipes always cost 1.")
                .defineInRange("upwardPipeCost", 2, 1, 16);
        server.pop();

        SERVER_SPEC = server.build();

        // Client config
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();

        client.push("overlays");
        SHOW_PUMP_RANGE_ARROWS = client
                .comment("Show animated arrows along pipes when looking at a pump with goggles.")
                .define("showPumpRangeArrows", true);
        SHOW_PIPE_GOGGLE_INFO = client
                .comment("Show fluid transport info when looking at a pipe with goggles.")
                .define("showPipeGoggleInfo", true);
        client.pop();

        CLIENT_SPEC = client.build();
    }
}
