package de.devin.pipesnphysics;

import net.neoforged.neoforge.common.ModConfigSpec;

public class PipesNPhysicsConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    // Server
    public static final ModConfigSpec.IntValue UPWARD_PIPE_COST;
    public static final ModConfigSpec.BooleanValue ENABLE_GRAVITY_FLOW;
    public static final ModConfigSpec.DoubleValue GRAVITY_PRESSURE_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue PIPE_FRICTION_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue MAX_GRAVITY_PRESSURE;
    public static final ModConfigSpec.IntValue GRAVITY_RECHECK_TICKS;
    public static final ModConfigSpec.DoubleValue GRAVITY_ROTATION_THRESHOLD;
    public static final ModConfigSpec.DoubleValue GRAVITY_DEAD_ZONE;
    public static final ModConfigSpec.IntValue MAX_GRAVITY_RANGE;

    // Client
    public static final ModConfigSpec.BooleanValue SHOW_PUMP_RANGE_ARROWS;
    public static final ModConfigSpec.IntValue ARROW_RENDER_MODE;
    public static final ModConfigSpec.BooleanValue SHOW_PIPE_GOGGLE_INFO;
    public static final ModConfigSpec.BooleanValue FLUID_TILT_ENABLED;
    public static final ModConfigSpec.BooleanValue FLUID_WAVE_MESH;
    public static final ModConfigSpec.IntValue FLUID_SURFACE_RESOLUTION;
    public static final ModConfigSpec.BooleanValue FLUID_DEBUG_RENDER;
    public static final ModConfigSpec.BooleanValue FLUID_HIDE_TEXTURE;

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

        server.push("gravity");
        ENABLE_GRAVITY_FLOW = server
                .comment("Enable gravity-driven fluid flow through pipes without a pump.",
                        "Fluid will flow from a higher source to a lower sink based on height difference.")
                .define("enableGravityFlow", true);
        GRAVITY_PRESSURE_PER_BLOCK = server
                .comment("Pressure gained per block of vertical drop.",
                        "Higher = more flow per block of height. At default 10, a 1-block drop gives 5 mB/t,",
                        "a 2-block drop gives 10 mB/t (max). Angled pipes scale with sin(angle).")
                .defineInRange("gravityPressurePerBlock", 10.0, 0.1, 40.0);
        PIPE_FRICTION_PER_BLOCK = server
                .comment("Pressure lost per pipe segment due to friction.",
                        "Longer pipe runs reduce flow. If friction exceeds gravity, flow stops.",
                        "Set to 0 for frictionless pipes (only height matters).")
                .defineInRange("pipeFrictionPerBlock", 0.5, 0.0, 5.0);
        MAX_GRAVITY_PRESSURE = server
                .comment("Maximum gravity pressure. Transfer rate = pressure / 2 mB/t.",
                        "Default 20 = max 10 mB/t at full vertical (90 degrees).")
                .defineInRange("maxGravityPressure", 20.0, 1.0, 256.0);
        GRAVITY_RECHECK_TICKS = server
                .comment("How often (in ticks) to recheck gravity flow for pipes on sub-levels.",
                        "Lower = more responsive to rotation, higher = less CPU.",
                        "20 ticks = 1 second.")
                .defineInRange("gravityRecheckTicks", 5, 1, 100);
        GRAVITY_ROTATION_THRESHOLD = server
                .comment("Minimum rotation change (quaternion dot threshold) to trigger gravity recheck.",
                        "Lower = more sensitive to small rotations. 0.999 = ~2.5 degrees, 0.99 = ~8 degrees.")
                .defineInRange("gravityRotationThreshold", 0.999, 0.9, 1.0);
        GRAVITY_DEAD_ZONE = server
                .comment("Minimum angle (degrees) or height (blocks) for gravity flow to activate.",
                        "Prevents phantom flow from floating point noise on flat pipes.",
                        "Angle-based: pipes below this angle get 0 flow. Height-based: height diff below this is ignored.")
                .defineInRange("gravityDeadZone", 1.0, 0.0, 10.0);
        MAX_GRAVITY_RANGE = server
                .comment("Maximum horizontal range (in blocks) that gravity pressure can push fluid.",
                        "A taller drop builds more pressure, but it caps at this many blocks of range.",
                        "Range = accumulated pressure / friction per block, capped at this value.")
                .defineInRange("maxGravityRange", 10, 1, 256);
        server.pop();

        SERVER_SPEC = server.build();

        // Client config
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();

        client.push("overlays");
        SHOW_PUMP_RANGE_ARROWS = client
                .comment("Show animated arrows along pipes when looking at a pump with goggles.")
                .define("showPumpRangeArrows", true);
        ARROW_RENDER_MODE = client
                .comment("Arrow animation style. 0 = per-segment (arrow on every pipe, sliding in sync),",
                        "1 = traveling (single arrow travels the full path from source to sink, faster).")
                .defineInRange("arrowRenderMode", 0, 0, 1);
        SHOW_PIPE_GOGGLE_INFO = client
                .comment("Show fluid transport info when looking at a pipe with goggles.")
                .define("showPipeGoggleInfo", true);
        client.pop();

        client.push("fluidPhysics");
        FLUID_TILT_ENABLED = client
                .comment("Enable tilted fluid rendering in tanks on Sable sub-levels.")
                .define("fluidTiltEnabled", true);
        FLUID_WAVE_MESH = client
                .comment("Enable wavy fluid surface mesh on Sable sub-levels.",
                        "When disabled, the surface is a flat plane (less GPU cost).")
                .define("fluidWaveMesh", true);
        FLUID_SURFACE_RESOLUTION = client
                .comment("Grid resolution for the fluid surface mesh. Higher = smoother waves, more GPU cost.",
                        "4 = low, 8 = medium, 16 = high, 32 = ultra. Only used when fluidWaveMesh is true.")
                .defineInRange("fluidSurfaceResolution", 64, 2, 128);
        FLUID_DEBUG_RENDER = client
                .comment("Show debug wireframe, corner dots, and grid lines on fluid surfaces.")
                .define("fluidDebugRender", false);
        FLUID_HIDE_TEXTURE = client
                .comment("Hide fluid textures, showing only debug wireframe. Useful for inspecting the mesh.")
                .define("fluidHideTexture", false);
        client.pop();

        CLIENT_SPEC = client.build();
    }
}
