package de.devin.pipesnphysics;

import net.neoforged.neoforge.common.ModConfigSpec;

public class PipesNPhysicsConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    // Server
    public static final ModConfigSpec.BooleanValue ENABLE_GRAVITY_FLOW;
    public static final ModConfigSpec.DoubleValue GRAVITY_PRESSURE_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue PIPE_FRICTION_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue MAX_GRAVITY_PRESSURE;
    public static final ModConfigSpec.BooleanValue ENABLE_PUMP_GRAVITY;
    public static final ModConfigSpec.DoubleValue PUMP_GRAVITY_FACTOR;
    public static final ModConfigSpec.IntValue GRAVITY_RECHECK_TICKS;
    public static final ModConfigSpec.DoubleValue GRAVITY_ROTATION_THRESHOLD;
    public static final ModConfigSpec.DoubleValue GRAVITY_DEAD_ZONE;
    public static final ModConfigSpec.BooleanValue ENABLE_PIPE_ANGLE_PHYSICS;
    public static final ModConfigSpec.DoubleValue MIN_GRAVITY_ANGLE;
    public static final ModConfigSpec.IntValue MAX_GRAVITY_RANGE;
    public static final ModConfigSpec.BooleanValue FRICTION_AFFECTS_FLOW;
    public static final ModConfigSpec.BooleanValue ENABLE_DYNAMIC_TANK_MASS;
    public static final ModConfigSpec.DoubleValue FLUID_MASS_PER_BUCKET;

    // Client
    public static final ModConfigSpec.BooleanValue SHOW_PUMP_RANGE_ARROWS;
    public static final ModConfigSpec.IntValue ARROW_RENDER_MODE;
    public static final ModConfigSpec.BooleanValue SHOW_PIPE_GOGGLE_INFO;
    public static final ModConfigSpec.BooleanValue FLUID_TILT_ENABLED;
    public static final ModConfigSpec.BooleanValue FLUID_WAVE_MESH;
    public static final ModConfigSpec.IntValue FLUID_SURFACE_RESOLUTION;
    public static final ModConfigSpec.BooleanValue COMPLEX_TOOLTIPS;
    public static final ModConfigSpec.BooleanValue FLUID_DEBUG_RENDER;
    public static final ModConfigSpec.BooleanValue FLUID_HIDE_TEXTURE;

    static {
        // Server config
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();

        server.push("pipePhysics");
        ENABLE_GRAVITY_FLOW = server
                .comment("Enable gravity-driven fluid flow through pipes without a pump.",
                        "Fluid will flow from a higher source to a lower sink based on height difference.")
                .define("enableGravityFlow", true);
        GRAVITY_PRESSURE_PER_BLOCK = server
                .comment("Pressure gained per block of height difference.",
                        "Used by both gravity and pump networks. Lower values = longer drops needed for max flow.",
                        "At default 3, a 7-block drop gives max flow (10 mB/t). Angle scales naturally via height.")
                .defineInRange("gravityPressurePerBlock", 3.0, 0.1, 40.0);
        ENABLE_PIPE_ANGLE_PHYSICS = server
                .comment("Enable angle-based pipe friction scaling.",
                        "When true, friction scales smoothly with pipe elevation angle on Sable sub-levels:",
                        "  vertical = 0 friction, horizontal = full, angled = proportional (sin-based).",
                        "When false, friction is binary: vertical pipes = 0, everything else = full.")
                .define("enablePipeAnglePhysics", true);
        PIPE_FRICTION_PER_BLOCK = server
                .comment("Friction per horizontal pipe segment. Vertical = zero friction, angled = proportional.",
                        "Higher values = shorter horizontal range from gravity flow.",
                        "Set to 0 for frictionless pipes.")
                .defineInRange("pipeFrictionPerBlock", 5.0, 0.0, 20.0);
        MIN_GRAVITY_ANGLE = server
                .comment("Minimum pipe elevation angle (degrees) for gravity-assisted flow.",
                        "Pipes steeper than this angle have zero friction — gravity drives the flow.",
                        "Pipes below this angle ramp smoothly to full friction at 0° (flat).",
                        "Lower values = shallower pipes can flow by gravity. 5 = nearly flat pipes work.")
                .defineInRange("minGravityAngle", 5.0, 0.0, 45.0);
        MAX_GRAVITY_PRESSURE = server
                .comment("Maximum pressure in pipe networks. Transfer rate = pressure / 2 mB/t.",
                        "Default 20 = max 10 mB/t. Applies to both gravity and pump+gravity combined.")
                .defineInRange("maxGravityPressure", 20.0, 1.0, 256.0);
        ENABLE_PUMP_GRAVITY = server
                .comment("Enable gravity assist/penalty for pump networks.",
                        "When true, pumps push further downhill and shorter uphill.",
                        "When false, pumps behave like vanilla Create — uniform range regardless of direction.")
                .define("enablePumpGravity", true);
        PUMP_GRAVITY_FACTOR = server
                .comment("How much gravity affects pump networks (when enablePumpGravity is true).",
                        "1.0 = full physics, 0.5 = half effect, 2.0 = exaggerated.")
                .defineInRange("pumpGravityFactor", 1.0, 0.0, 2.0);
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
                .comment("Minimum height difference (in blocks) for gravity flow to activate.",
                        "Prevents phantom flow from floating point noise on nearly-flat pipes.",
                        "Default 0.1 = pipes need at least 0.1 blocks of height drop to flow.")
                .defineInRange("gravityDeadZone", 0.1, 0.0, 10.0);
        MAX_GRAVITY_RANGE = server
                .comment("Maximum horizontal range (in blocks) that gravity pressure can push fluid.",
                        "A taller drop builds more pressure, but it caps at this many blocks of range.",
                        "Range = accumulated pressure / friction per block, capped at this value.")
                .defineInRange("maxGravityRange", 10, 1, 256);
        FRICTION_AFFECTS_FLOW = server
                .comment("Whether pipe friction reduces pump flow rate (not just range).",
                        "When true, friction accumulates with diminishing returns (logarithmic),",
                        "reducing the bottleneck pressure and thus flow rate for the whole network.",
                        "When false, friction only limits range — all reachable pipes flow at RPM/2 (vanilla-like).")
                .define("frictionAffectsFlow", true);
        server.pop();

        server.push("tankMass");
        ENABLE_DYNAMIC_TANK_MASS = server
                .comment("Enable dynamic mass for fluid tanks on Sable sub-levels.",
                        "Fuller tanks become heavier, affecting sub-level physics (rotation, momentum).",
                        "Requires Sable (full) to be installed — has no effect with only Sable Companion.")
                .define("enableDynamicTankMass", true);
        FLUID_MASS_PER_BUCKET = server
                .comment("Mass in kg added per bucket (1000 mB) of fluid stored in a tank.",
                        "Sable blocks weigh ~1 kg each. A single tank holds 8 buckets.",
                        "At 0.1, a full single tank adds 0.8 kg (nearly doubling a 1-block tank's weight).",
                        "At 1.0, a full single tank adds 8 kg (9x heavier — very aggressive).")
                .defineInRange("fluidMassPerBucket", 0.1, 0.001, 100.0);
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
        COMPLEX_TOOLTIPS = client
                .comment("Show detailed pressure breakdown (head, friction, net) in pipe goggle tooltips.",
                        "When disabled, only shows flow rate and direction.")
                .define("complexTooltips", true);
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
