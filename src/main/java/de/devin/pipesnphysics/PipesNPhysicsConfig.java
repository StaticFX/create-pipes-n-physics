package de.devin.pipesnphysics;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server + client config for the v1 engine. Most knobs from the v0 build are
 * gone with the old engine; only the surviving features (Sable tank mass,
 * tilted/wave fluid rendering) and the master enable flag remain.
 */
public class PipesNPhysicsConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    // Server
    public static final ModConfigSpec.BooleanValue ENABLE_ENGINE;
    public static final ModConfigSpec.DoubleValue PIPE_CONDUCTANCE;
    public static final ModConfigSpec.DoubleValue PUMP_HEAD_PER_RPM;
    public static final ModConfigSpec.DoubleValue PUMP_FLOW_PER_RPM;
    public static final ModConfigSpec.IntValue MAX_FLOW_PER_ENDPOINT;
    public static final ModConfigSpec.DoubleValue SUCTION_LIMIT;
    public static final ModConfigSpec.BooleanValue ENABLE_OPEN_END_INTAKE;
    public static final ModConfigSpec.IntValue OPEN_END_INTAKE_COOLDOWN_TICKS;
    public static final ModConfigSpec.BooleanValue ENABLE_VALVE_THROTTLE;
    public static final ModConfigSpec.BooleanValue ENABLE_DYNAMIC_TANK_MASS;
    public static final ModConfigSpec.BooleanValue EXPERIMENTAL_TANK_COG;
    public static final ModConfigSpec.BooleanValue ENABLE_OPEN_END_WORLD_PLACEMENT;
    public static final ModConfigSpec.DoubleValue FLUID_MASS_PER_BUCKET;

    // Client
    public static final ModConfigSpec.BooleanValue SHOW_PIPE_GOGGLE_INFO;
    public static final ModConfigSpec.BooleanValue SHOW_PUMP_RANGE_ARROWS;
    public static final ModConfigSpec.BooleanValue PRESERVE_PUMP_RANGE;
    public static final ModConfigSpec.IntValue PUMP_RANGE_PRESERVE_SECONDS;
    public static final ModConfigSpec.BooleanValue FLUID_TILT_ENABLED;
    public static final ModConfigSpec.BooleanValue FLUID_WAVE_MESH;
    public static final ModConfigSpec.IntValue FLUID_SURFACE_RESOLUTION;
    public static final ModConfigSpec.BooleanValue FLUID_DEBUG_RENDER;
    public static final ModConfigSpec.BooleanValue FLUID_HIDE_TEXTURE;
    public static final ModConfigSpec.BooleanValue EXPERIMENTAL_PIPE_LEVEL_RENDER;

    static {
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();

        server.push("engine");
        ENABLE_ENGINE = server
                .comment("Master switch. When false, Create's vanilla pipe transport runs unmodified.")
                .define("enableEngine", true);
        PIPE_CONDUCTANCE = server
                .comment("Flow in mB/tick that one pipe segment passes per block of head difference.",
                        "Higher values equalize tanks faster and raise throughput everywhere.")
                .defineInRange("pipeConductance", 120.0, 0.1, 10000.0);
        PUMP_HEAD_PER_RPM = server
                .comment("Blocks of head a pump adds per RPM.",
                        "At 0.25, a pump running at 64 RPM can lift fluid 16 blocks.")
                .defineInRange("pumpHeadPerRpm", 0.25, 0.01, 100.0);
        PUMP_FLOW_PER_RPM = server
                .comment("Pump throughput in mB/tick per RPM when pumping freely.",
                        "Together with pumpHeadPerRpm this defines the pump curve:",
                        "flow falls toward zero as the opposing head approaches the pump's head.")
                .defineInRange("pumpFlowPerRpm", 1.0, 0.01, 100.0);
        MAX_FLOW_PER_ENDPOINT = server
                .comment("Hard cap on fluid moved into or out of a single tank or machine per tick, in mB.")
                .defineInRange("maxFlowPerEndpoint", 256, 1, 8192);
        SUCTION_LIMIT = server
                .comment("How many blocks the head at a pipe's highest point may sit below that point",
                        "before the liquid column breaks (the siphon / cavitation limit).")
                .defineInRange("suctionLimitBlocks", 8.0, 0.0, 256.0);
        ENABLE_OPEN_END_INTAKE = server
                .comment("Let an open pipe end draw fluid IN from the world when the network is under",
                        "suction (its head sits below the pipe mouth): a self-regenerating source (a",
                        "lake), a cauldron, or a finite/hand-placed source block. To keep it from",
                        "sucking back what it just spilled, a network that spilled from ANY open end",
                        "recently will not pull a finite source (lakes/cauldrons are unaffected).")
                .define("enableOpenEndIntake", true);
        OPEN_END_INTAKE_COOLDOWN_TICKS = server
                .comment("After an open end on a network spills, how many ticks before that network",
                        "may pull a finite source again (the anti-reclaim window). Lakes and cauldrons",
                        "ignore this. Larger = safer against flicker on networks that both push and pull.")
                .defineInRange("openEndIntakeCooldownTicks", 20, 0, 200);
        ENABLE_VALVE_THROTTLE = server
                .comment("Let a fluid valve throttle its flow by a 0-90 degree angle set with the scroll",
                        "value (the box on its side faces). The shaft still opens and closes the valve;",
                        "the angle only caps how much flows while it is open (90 = fully open). When",
                        "false, valves stay plain on/off.")
                .define("enableValveThrottle", true);
        server.pop();

        server.push("sableCompat");
        ENABLE_OPEN_END_WORLD_PLACEMENT = server
                .comment("When an open-ended pipe on a Sable sub-level spills fluid,",
                        "place the fluid block in the real world at the projected position.")
                .define("enableOpenEndWorldPlacement", true);
        server.pop();

        server.push("tankMass");
        ENABLE_DYNAMIC_TANK_MASS = server
                .comment("Enable dynamic mass for fluid tanks on Sable sub-levels.",
                        "Fuller tanks become heavier, affecting sub-level physics.")
                .define("enableDynamicTankMass", true);
        FLUID_MASS_PER_BUCKET = server
                .comment("Mass in kg added per bucket of fluid stored in a tank.")
                .defineInRange("fluidMassPerBucket", 0.1, 0.001, 100.0);
        EXPERIMENTAL_TANK_COG = server
                .comment("EXPERIMENTAL: shift center of gravity based on fluid fill level.")
                .define("experimentalTankCenterOfGravity", true);
        server.pop();

        SERVER_SPEC = server.build();

        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        client.push("goggles");
        SHOW_PIPE_GOGGLE_INFO = client
                .comment("Show engine stats (status, fluid, flow, pressure) when looking",
                        "at a pipe with Engineer's Goggles.")
                .define("showPipeGoggleInfo", true);
        SHOW_PUMP_RANGE_ARROWS = client
                .comment("Show animated reach arrows along the pipes when looking at a",
                        "pump with Engineer's Goggles: green where the pump can push,",
                        "blue where it can pull, red where its head cannot reach.")
                .define("showPumpRangeArrows", true);
        PRESERVE_PUMP_RANGE = client
                .comment("Keep showing the pump range indicator for a few seconds after",
                        "looking away from the pump.")
                .define("preservePumpRangeIndicator", true);
        PUMP_RANGE_PRESERVE_SECONDS = client
                .comment("How many seconds the pump range indicator lingers after looking away.")
                .defineInRange("pumpRangePreserveSeconds", 5, 1, 60);
        client.pop();
        client.push("fluidPhysics");
        FLUID_TILT_ENABLED = client
                .comment("Enable tilted fluid rendering in tanks on Sable sub-levels.")
                .define("fluidTiltEnabled", true);
        FLUID_WAVE_MESH = client
                .comment("Enable wavy fluid surface mesh on Sable sub-levels.")
                .define("fluidWaveMesh", true);
        FLUID_SURFACE_RESOLUTION = client
                .comment("Grid resolution for the fluid surface mesh.")
                .defineInRange("fluidSurfaceResolution", 64, 2, 128);
        FLUID_DEBUG_RENDER = client
                .comment("Show debug wireframe, corner dots, and grid lines on fluid surfaces.")
                .define("fluidDebugRender", false);
        FLUID_HIDE_TEXTURE = client
                .comment("Hide fluid textures, showing only debug wireframe.")
                .define("fluidHideTexture", false);
        EXPERIMENTAL_PIPE_LEVEL_RENDER = client
                .comment("EXPERIMENTAL (spike): draw fluid inside pipes at its actual waterline (a partial",
                        "fill at the solved surface) instead of Create's binary full-or-empty fill. Resting",
                        "fluid in straight glass pipes only. The per-cell waterline is computed server-side",
                        "onto the synced pipe flow; in singleplayer the integrated server reads this client",
                        "flag directly, so it works without a per-world server-config edit. (Spike: the",
                        "server-side encode is gated on the CLIENT being present, so it is singleplayer-only",
                        "for now.) When false, pipes render exactly as stock Create.")
                .define("experimentalPipeLevelRender", false);
        client.pop();
        CLIENT_SPEC = client.build();
    }
}
