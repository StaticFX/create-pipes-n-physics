package de.devin.pipesnphysics;

import de.devin.pipesnphysics.engine.valve.ValveCharacteristic;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server + client config for the v2 hydraulic engine (see CLAUDE.md). Sim-affecting knobs live on
 * the SERVER spec (synced to clients); rendering toggles live on the CLIENT spec.
 */
public class PipesNPhysicsConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    // Server
    public static final ModConfigSpec.BooleanValue ENABLE_ENGINE;
    public static final ModConfigSpec.DoubleValue PIPE_CONDUCTANCE;
    public static final ModConfigSpec.DoubleValue PIPE_FITTING_LENGTH;
    public static final ModConfigSpec.DoubleValue PUMP_HEAD_PER_RPM;
    public static final ModConfigSpec.DoubleValue PUMP_FLOW_PER_RPM;
    public static final ModConfigSpec.IntValue MAX_FLOW_PER_ENDPOINT;
    public static final ModConfigSpec.IntValue PIPE_VOLUME_PER_CELL;
    public static final ModConfigSpec.DoubleValue SUCTION_LIMIT;
    public static final ModConfigSpec.DoubleValue ULTRAWARM_VISCOSITY_THINNING;
    public static final ModConfigSpec.IntValue MOLTEN_TEMPERATURE_K;
    public static final ModConfigSpec.DoubleValue PUMP_PULL_HEAD_FRACTION;
    public static final ModConfigSpec.BooleanValue PUMP_DRAIN_ANY_LEVEL;
    public static final ModConfigSpec.BooleanValue ENABLE_OPEN_END_INTAKE;
    public static final ModConfigSpec.IntValue OPEN_END_INTAKE_COOLDOWN_TICKS;
    public static final ModConfigSpec.BooleanValue FORCE_OPEN_END_OUTPUT;
    public static final ModConfigSpec.BooleanValue ENABLE_HYDRO_TURBINE;
    public static final ModConfigSpec.DoubleValue TURBINE_RPM;
    public static final ModConfigSpec.DoubleValue TURBINE_HEAD_PER_RPM;
    public static final ModConfigSpec.DoubleValue TURBINE_FLOW_PER_RPM;
    public static final ModConfigSpec.DoubleValue TURBINE_SU_PER_POWER;
    public static final ModConfigSpec.BooleanValue ENABLE_VALVE_THROTTLE;
    public static final ModConfigSpec.EnumValue<ValveCharacteristic> VALVE_CHARACTERISTIC;
    public static final ModConfigSpec.BooleanValue ENABLE_VALVE_ONE_WAY;
    public static final ModConfigSpec.BooleanValue AUTO_DETECT_RELAY_HANDLERS;
    public static final ModConfigSpec.BooleanValue ENABLE_FOREIGN_PUMPS;
    public static final ModConfigSpec.DoubleValue FOREIGN_PUMP_STRENGTH_SCALE;
    public static final ModConfigSpec.BooleanValue ENABLE_NETWORK_CACHE;
    public static final ModConfigSpec.BooleanValue ENABLE_DYNAMIC_TANK_MASS;
    public static final ModConfigSpec.BooleanValue EXPERIMENTAL_TANK_COG;
    public static final ModConfigSpec.BooleanValue ENABLE_GAS_BUOYANCY;
    public static final ModConfigSpec.BooleanValue ENABLE_CENTRIFUGE;
    public static final ModConfigSpec.DoubleValue CENTRIFUGE_STRENGTH;
    public static final ModConfigSpec.BooleanValue ENABLE_CENTRIFUGE_UNMIX;
    public static final ModConfigSpec.IntValue CENTRIFUGE_UNMIX_RATE;
    public static final ModConfigSpec.DoubleValue CENTRIFUGE_UNMIX_MIN_SPEED;
    public static final ModConfigSpec.DoubleValue CENTRIFUGE_MIN_ANGULAR_SPEED;
    public static final ModConfigSpec.BooleanValue ENABLE_MOMENTUM_HEAD;
    public static final ModConfigSpec.DoubleValue MOMENTUM_STRENGTH;
    public static final ModConfigSpec.DoubleValue MOMENTUM_MIN_ACCEL;
    public static final ModConfigSpec.BooleanValue DEBUG_SUBLEVEL_SPIN;
    public static final ModConfigSpec.BooleanValue ENABLE_OPEN_END_WORLD_PLACEMENT;
    public static final ModConfigSpec.BooleanValue ENABLE_SUBLEVEL_CONNECTION_REFRESH;
    public static final ModConfigSpec.BooleanValue ENABLE_CROSS_LEVEL_PIPING;
    public static final ModConfigSpec.DoubleValue FLUID_MASS_PER_BUCKET;
    public static final ModConfigSpec.DoubleValue FLUID_LIFT_PER_BUCKET;

    // Client
    public static final ModConfigSpec.BooleanValue SHOW_PIPE_GOGGLE_INFO;
    public static final ModConfigSpec.BooleanValue SHOW_PUMP_REACH_OVERLAY;
    public static final ModConfigSpec.BooleanValue SHOW_VALVE_DIRECTION_ARROWS;
    public static final ModConfigSpec.BooleanValue PRESERVE_PUMP_RANGE;
    public static final ModConfigSpec.IntValue PUMP_RANGE_PRESERVE_SECONDS;
    public static final ModConfigSpec.BooleanValue ENABLE_PIPE_SWAP;
    public static final ModConfigSpec.BooleanValue PIPE_LEVEL_RENDER;
    public static final ModConfigSpec.BooleanValue ENABLE_PONDER_ENGINE;
    public static final ModConfigSpec.DoubleValue PIPE_LEVEL_FLOW_SPEED;
    public static final ModConfigSpec.BooleanValue FLUID_TILT_ENABLED;
    public static final ModConfigSpec.BooleanValue FLUID_WAVE_MESH;
    public static final ModConfigSpec.IntValue FLUID_SURFACE_RESOLUTION;
    public static final ModConfigSpec.BooleanValue FLUID_DEBUG_RENDER;
    public static final ModConfigSpec.BooleanValue FLUID_HIDE_TEXTURE;
    public static final ModConfigSpec.BooleanValue FLYWHEEL_TANK_VISUAL;
    public static final ModConfigSpec.BooleanValue FLUID_OPAQUE;
    public static final ModConfigSpec.DoubleValue FLUID_RESTING_WAVES;

    static {
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();

        // ================================================================= Engine
        server.push("engine");
        ENABLE_ENGINE = server
                .comment("Master switch. When false, Create's vanilla pipe transport runs unmodified.")
                .define("enableEngine", true);

        // -------------------------------------------------------------- Behaviors
        server.comment("Optional engine behaviors — how open ends, pumps, relays, and the graph",
                        "cache act. Toggles (and their tuning), separate from the raw flow numbers.")
                .push("behaviors");
        PUMP_DRAIN_ANY_LEVEL = server
                .comment("Let a pump drain a tank from a connection ABOVE the fluid's surface — i.e. pull a",
                        "tank down past a side or top pipe instead of stopping once the level drops below it",
                        "(as if the pump had a dip tube reaching the bottom). Only applies where a pump is",
                        "actively drawing from the tank; plain gravity flow still can't leave an opening above",
                        "the waterline. The suction limit still bounds how far the pump can lift.")
                .define("pumpDrainAnyLevel", false);
        ENABLE_OPEN_END_INTAKE = server
                .comment("Let an open pipe end draw fluid IN from the world when the network is",
                        "under suction (its head sits below the pipe mouth): a self-regenerating source",
                        "(a lake), a cauldron, or a finite/hand-placed source block. Unpumped, this",
                        "needs a VERTICAL mouth — a sideways one is a spill outlet only, or it would",
                        "just reclaim its own spill — but a mouth a RUNNING PUMP pulls on drinks",
                        "through any face (a pump cannot spill out of its own suction flank). To keep",
                        "a mouth from sucking back what it spilled, a network that spilled from ANY",
                        "open end recently will not pull a finite source (lakes/cauldrons are",
                        "unaffected).")
                .define("enableOpenEndIntake", true);
        OPEN_END_INTAKE_COOLDOWN_TICKS = server
                .comment("After an open end on a network spills, how many ticks before that network",
                        "may pull a finite source again (the anti-reclaim window). Lakes and cauldrons",
                        "ignore this. Larger = safer against flicker on networks that both push and pull.")
                .defineInRange("openEndIntakeCooldownTicks", 20, 0, 200);
        FORCE_OPEN_END_OUTPUT = server
                .comment("Let an open-ended pipe keep draining fluid OUT even when the space it faces is",
                        "already filled by a fluid source block (its own earlier spill, or a natural pool),",
                        "instead of backing the network up. The space is already full, so the extra fluid",
                        "is DISCARDED. Off by default because it destroys fluid; turn it on for an open end",
                        "used as an overflow/drain into a body of fluid.")
                .define("forceOpenEndOutput", false);
        AUTO_DETECT_RELAY_HANDLERS = server
                .comment("Automatically detect fluid-handler blocks that are NOT passive tanks — relays",
                        "like a docking connector or a flexible hose — and stop equalizing them as",
                        "reservoirs. A real tank only changes fill by our transfers; a relay spontaneously",
                        "GAINS fluid from its own pairing/cascade (a consumer only ever loses it). A block",
                        "type seen gaining fluid on its own several times is treated as a drain-priority",
                        "relay endpoint (a one-way source while it holds fluid, a one-way sink while empty),",
                        "so it is drained/filled on demand instead of being wrongly held 'balanced'. Override",
                        "with the block tags is_reservoir (force normal tank), relay_endpoint (force relay),",
                        "sink_only (receive-only), or ignore_fluid_handler (skip); Create tanks and basins",
                        "are never demoted.")
                .define("autoDetectRelayHandlers", true);
        ENABLE_FOREIGN_PUMPS = server
                .comment("Drive pumps from OTHER mods with the hydraulic engine — an electric pump, a",
                        "centrifugal one, anything that pumps through Create's pipes. Such a pump states",
                        "how hard it pushes as pipe PRESSURE (the currency Create's own transport runs on,",
                        "and the same scale as a Mechanical Pump's RPM), so the engine reads that and gives",
                        "it head and throughput like any pump. A pump that extends Create's own is always",
                        "recognized; anything else has to be named by the block tag pipesnphysics:pumps or",
                        "registered through the mod's API. Off = they act as plain pipe again (fluid passes",
                        "through them, but they add no head).")
                .define("enableForeignPumps", true);
        FOREIGN_PUMP_STRENGTH_SCALE = server
                .comment("Scales how strong another mod's pump is, on top of the pressure it publishes.",
                        "1.0 keeps whatever that mod intended relative to a Mechanical Pump — a pump that",
                        "pushes twice Create's pressure lifts twice as high here. Lower it to rein in an",
                        "addon pump that dwarfs the rest of your plumbing.")
                .defineInRange("foreignPumpStrengthScale", 1.0, 0.0, 100.0);
        ENABLE_NETWORK_CACHE = server
                .comment("Reuse each pipe network's discovered graph across ticks instead of re-scanning",
                        "it from the world every solve. Every edit that can change a network's shape",
                        "(break/place, pump flips, valve angles, chunk loads) evicts the cached graph,",
                        "and entries expire after a few ticks anyway so event-less edits (pistons,",
                        "contraption assembly) are picked up promptly. Also governs the Sable sub-level",
                        "pipe-scan cache. Turn off only to rule caching out while debugging odd network",
                        "behavior.")
                .define("enableNetworkCache", true);
        server.pop();

        // ----------------------------------------------------------- Flow scaling
        server.comment("The numeric tuning of the hydraulics — how fast, how much, and how high",
                        "fluid moves. These set the feel of every network.")
                .push("flowScaling");
        PIPE_CONDUCTANCE = server
                .comment("Flow in mB/tick that one pipe segment passes per block of head difference.",
                        "Higher values equalize tanks faster and raise throughput everywhere.")
                .defineInRange("pipeConductance", 240.0, 0.1, 10000.0);
        PIPE_FITTING_LENGTH = server
                .comment("How many blocks of straight pipe a run's FITTINGS are worth — the tee it",
                        "branches off, its elbows, and the entry and exit at its ends. Real plumbing",
                        "loses far more head to fittings than to a few blocks of pipe, so this is what",
                        "keeps a short branch from beating a longer one by its length ratio: at a",
                        "junction, two branches split their flow as 1/(length + this) each, which at",
                        "the default tracks the square-root split of real turbulent pipe flow closely",
                        "(2 blocks against 8 splits 65/35, where raw length alone would say 75/25).",
                        "Raise it to make pipe length matter even less; 1 restores pure length.")
                .defineInRange("pipeFittingLength", 5.0, 1.0, 64.0);
        PUMP_HEAD_PER_RPM = server
                .comment("Blocks of head a pump adds per RPM.",
                        "At 0.25, a pump running at 64 RPM can lift fluid 16 blocks.")
                .defineInRange("pumpHeadPerRpm", 0.25, 0.01, 100.0);
        PUMP_FLOW_PER_RPM = server
                .comment("Pump throughput in mB/tick per RPM when pumping freely.",
                        "Together with pumpHeadPerRpm this defines the pump curve:",
                        "flow falls toward zero as the opposing head approaches the pump's head.")
                .defineInRange("pumpFlowPerRpm", 1.0, 0.01, 100.0);
        PUMP_PULL_HEAD_FRACTION = server
                .comment("How deep a running pump can start pulling through its own DRY suction line,",
                        "as a fraction of the head it pushes with. A pump SUCKS far more weakly than",
                        "it pushes: at 0.1 a 16 RPM pump lifts 4 blocks but only draws a dry line down",
                        "0.4 blocks below the pipe it opens into, so a supply well under the pump still",
                        "has to be primed once by hand (or reached with more RPM). Only ESTABLISHING",
                        "costs this; once the line holds fluid the column sustains down to the full",
                        "suction limit, and unpowered siphons are governed by that limit alone.",
                        "0 = never self-prime (a dry pump above the waterline churns air until primed).")
                .defineInRange("pumpPullHeadFraction", 0.1, 0.0, 1.0);
        MAX_FLOW_PER_ENDPOINT = server
                .comment("Hard cap on fluid moved into or out of a single tank or machine per tick, in mB.")
                .defineInRange("maxFlowPerEndpoint", 256, 1, 8192);
        PIPE_VOLUME_PER_CELL = server
                .comment("How much fluid one pipe block holds, in mB. Fluid really resides in the pipes:",
                        "a starting flow drains the source while the pipe fills, the sink only receives",
                        "what exits the pipe, and idle runs settle their contents back into the tanks",
                        "they can reach (only real traps - a U-dip below the waterline - keep fluid, and",
                        "breaking a pipe spills what it holds). Higher values mean more working volume",
                        "and slower visible fronts at a given flow; 0 disables in-pipe volume entirely",
                        "(instant endpoint-to-endpoint transfers, as before this feature).")
                .defineInRange("pipeVolumePerCell", 250, 0, 8000);
        SUCTION_LIMIT = server
                .comment("How many blocks the head at a pipe's highest point may sit below that point",
                        "before the liquid column breaks (the siphon / cavitation limit).")
                .defineInRange("suctionLimitBlocks", 8.0, 0.0, 256.0);
        ULTRAWARM_VISCOSITY_THINNING = server
                .comment("Molten fluids run thinner in an ultrawarm dimension: their viscosity is",
                        "divided by this factor there, so they flow that much faster (vanilla",
                        "parity: lava spreads 3x faster in the Nether). 1 disables the effect.")
                .defineInRange("ultrawarmViscosityThinning", 3.0, 1.0, 100.0);
        MOLTEN_TEMPERATURE_K = server
                .comment("Fluids whose registered temperature reaches this many Kelvin count as",
                        "MOLTEN for the ultrawarm thinning above (lava is 1300, water 300 — modded",
                        "molten metals typically 1000+).")
                .defineInRange("moltenTemperatureKelvin", 1000, 0, 100000);
        server.pop();

        // --------------------------------------------------------------- Turbine
        server.comment("The Mechanical Pump run BACKWARDS: dial a pump to TURBINE and fluid",
                        "falling through it turns it into a Create generator instead of a",
                        "consumer. A turbine is the exact dual of a pump — it SUBTRACTS its rated",
                        "head instead of adding it — so it needs a real drop before it passes",
                        "anything, and it swallows at most its rated flow.",
                        "Its RPM is a fixed tier ON PURPOSE and must never be derived from the",
                        "flow: Create breaks a generator whose speed flickers or flips sign, and",
                        "the fixed tier is also what caps the power a pump-fed loop can win back.")
                .push("turbine");
        ENABLE_HYDRO_TURBINE = server
                .comment("Whether a Mechanical Pump can run backwards as a turbine at all.",
                        "A pump placed from now on comes up AUTOMATIC: it pumps while a shaft",
                        "drives it and turbines while nothing does. Pumps already built keep",
                        "their old behaviour until you dial them.")
                .define("enableHydroTurbine", true);
        TURBINE_RPM = server
                .comment("The fixed speed a generating turbine produces, in RPM (a water wheel is 8).")
                .defineInRange("turbineRpm", 8.0, 1.0, 256.0);
        TURBINE_HEAD_PER_RPM = server
                .comment("Blocks of head a turbine takes out of the line per RPM of its rating.",
                        "At 0.25 and 8 RPM a turbine needs a 2-block fall before it turns at all.")
                .defineInRange("turbineHeadPerRpm", 0.25, 0.01, 100.0);
        TURBINE_FLOW_PER_RPM = server
                .comment("How much a turbine can swallow per RPM of its rating, in mB/tick.",
                        "At 8.0 and 8 RPM it passes at most 64 mB/t; a stronger supply backs up.")
                .defineInRange("turbineFlowPerRpm", 8.0, 0.01, 1000.0);
        TURBINE_SU_PER_POWER = server
                .comment("Stress units produced per block of rated fall per mB/tick passed.",
                        "A bigger drop drives more water through and earns more; what bounds one",
                        "turbine is the piping, not a cap. At 2.0 a pump feeding a turbine breaks",
                        "even at every speed, which turns a closed loop into free power, so the",
                        "default leaves a factor of two of loss.")
                .defineInRange("turbineSuPerPower", 1.0, 0.0, 100.0);
        server.pop();

        // ---------------------------------------------------------------- Valves
        server.comment("The crank-to-open fluid valve throttle.").push("valves");
        ENABLE_VALVE_THROTTLE = server
                .comment("Let a fluid valve throttle its flow by a 0-90 degree angle, cranked by its",
                        "shaft or set precisely by a Valve Handle. The angle caps how much flows while",
                        "the valve is open (90 = fully open). When false, valves stay plain on/off.")
                .define("enableValveThrottle", true);
        VALVE_CHARACTERISTIC = server
                .comment("The valve's opening curve — how its 0-90 degree angle maps to the share of flow",
                        "it passes. LINEAR: angle = flow (45 deg passes half; predictable, matches the",
                        "goggle %). QUICK_OPENING: reaches most flow early. EQUAL_PERCENTAGE: slow to open,",
                        "rushes near full. BALL_VALVE: the lens-shaped bore overlap of a real ball valve",
                        "(very restrictive until near open). Only reshapes the knob's feel; the engine's",
                        "flow model stays linear.")
                .defineEnum("valveCharacteristic", ValveCharacteristic.LINEAR);
        ENABLE_VALVE_ONE_WAY = server
                .comment("Let a fluid valve be set to pass fluid ONE WAY (a check valve) via a second",
                        "scroll box on its side faces. Default is both ways; one-way blocks reverse",
                        "flow in the solve AND at rest (idle levels never leak backward through it).")
                .define("enableValveOneWay", true);
        server.pop();
        server.pop(); // engine

        // ================================================================= Sable
        server.comment("Integration with the Sable physics mod — everything below only does anything",
                        "when a contraption is an assembled Sable sub-level (inert otherwise).")
                .push("sable");
        ENABLE_OPEN_END_WORLD_PLACEMENT = server
                .comment("When an open-ended pipe on a Sable sub-level spills fluid,",
                        "place the fluid block in the real world at the projected position.")
                .define("enableOpenEndWorldPlacement", true);
        ENABLE_SUBLEVEL_CONNECTION_REFRESH = server
                .comment("Recompute pipe connection state on Sable sub-levels. Sable assembles a",
                        "contraption with raw setBlockState (no neighbour update), so a pipe's",
                        "connection shape can stay stale and drop real edges — the network then",
                        "solves 'no flow' until a manual pipe edit fixes it. This refreshes each",
                        "sub-level pipe once so pumps/networks work without the manual poke.")
                .define("enableSubLevelConnectionRefresh", true);
        ENABLE_CROSS_LEVEL_PIPING = server
                .comment("Cross-level piping: let an open pipe end draw fluid IN from a block on ANY OTHER",
                        "Sable level whose physical bounds overlap the pipe mouth — a contraption drinking",
                        "a fluid block on another contraption where the two touch, OR a main-level (dimension)",
                        "pipe drinking from a contraption passing over it, OR a contraption drinking the",
                        "dimension it overlaps. Uses the same one-way vacuum intake and anti-reclaim guards",
                        "as ordinary open-end intake, plus a spatial (broadphase) query per mouth.")
                .define("enableCrossLevelPiping", true);

        // ------------------------------------------------------------- Tank mass
        server.comment("Fluid weight and buoyancy on a sub-level (heavier when full, lift from a gas).")
                .push("tankMass");
        ENABLE_DYNAMIC_TANK_MASS = server
                .comment("Enable dynamic mass for fluid tanks on Sable sub-levels.",
                        "Fuller tanks become heavier, affecting sub-level physics.")
                .define("enableDynamicTankMass", true);
        FLUID_MASS_PER_BUCKET = server
                .comment("Mass in kg added per bucket of fluid stored in a tank.")
                .defineInRange("fluidMassPerBucket", 0.1, 0.001, 100.0);
        EXPERIMENTAL_TANK_COG = server
                .comment("EXPERIMENTAL: settle the fluid's mass toward the low side of the tank by fill",
                        "level (a fuller tank sits lower). Off still adds the mass — just at the block",
                        "centre — so a tank's weight always shifts the contraption's centre of gravity.")
                .define("experimentalTankCenterOfGravity", true);
        ENABLE_GAS_BUOYANCY = server
                .comment("Let a tank holding a lighter-than-air fluid provide upward LIFT instead of",
                        "weight (a gas cell acts like a balloon). Applied as an upward force, never as",
                        "negative mass, so it can't destabilise the contraption's mass distribution.")
                .define("enableGasBuoyancy", true);
        FLUID_LIFT_PER_BUCKET = server
                .comment("Lift in kg per bucket of a lighter-than-air fluid. Deliberately independent of",
                        "the fluid's (sub-zero) density: a gas cell lifts by its fill volume, not by how",
                        "far below air its density sits. 0 disables the effect while keeping the toggle on.")
                .defineInRange("fluidLiftPerBucket", 0.1, 0.0, 100.0);
        server.pop();

        // ------------------------------------------------------------ Centrifuge
        server.comment("Fling fluid outward on a spinning contraption, and reverse-mix separation.")
                .push("centrifuge");
        ENABLE_CENTRIFUGE = server
                .comment("Fling fluid outward on a spinning Sable contraption: a cell's orbital speed adds",
                        "a centrifugal term to its effective elevation, so fluid collects in the faster-moving",
                        "(outer) tanks and drains back when the spin stops. Inert on anything not rotating.")
                .define("enableCentrifuge", true);
        CENTRIFUGE_STRENGTH = server
                .comment("Multiplier on the centrifugal pull. 1.0 is physically scaled (½v²/g); raise it to",
                        "make the outward push more aggressive, lower it to soften.")
                .defineInRange("centrifugeStrength", 1.0, 0.0, 100.0);
        ENABLE_CENTRIFUGE_UNMIX = server
                .comment("Reverse-mix separation: a fast-enough spinning tank holding a fluid a centrifuging",
                        "recipe accepts is consumed and its component fluids pushed into the other tanks on",
                        "the network (which then fling outward). Recipes live in data/<ns>/centrifuging/.")
                .define("enableCentrifugeUnmix", true);
        CENTRIFUGE_UNMIX_RATE = server
                .comment("Max input fluid (mB) a spinning tank un-mixes per tick.")
                .defineInRange("centrifugeUnmixRate", 100, 1, 100000);
        CENTRIFUGE_UNMIX_MIN_SPEED = server
                .comment("Minimum orbital speed (m/s) a tank must be moving at before it un-mixes — mount it",
                        "off the spin axis and spin fast enough to clear this.")
                .defineInRange("centrifugeUnmixMinSpeed", 4.0, 0.0, 1000.0);
        CENTRIFUGE_MIN_ANGULAR_SPEED = server
                .comment("Minimum spin rate (radians/second) the contraption must turn at before the centrifuge",
                        "does anything — both the outward push and un-mixing. Unlike orbital speed this is",
                        "independent of how far the tanks sit from the axis, so it reads pure spin. 0 disables",
                        "the gate (any rotation works). 1 rad/s is about 9.5 RPM, so 3.0 is roughly 29 RPM.")
                .defineInRange("centrifugeMinAngularSpeed", 0.0, 0.0, 1000.0);
        server.pop();

        // -------------------------------------------------------------- Momentum
        server.comment("Slosh fluid opposite the acceleration of an accelerating contraption.")
                .push("momentum");
        ENABLE_MOMENTUM_HEAD = server
                .comment("Slosh fluid on an ACCELERATING contraption: when its linear velocity changes, fluid is",
                        "pushed opposite the acceleration (toward the back when speeding up, forward when braking),",
                        "so momentum tilts the fluid surface. The linear counterpart of the centrifuge. Inert at",
                        "constant velocity and off a physics sub-level.")
                .define("enableMomentumHead", true);
        MOMENTUM_STRENGTH = server
                .comment("Multiplier on the momentum tilt. 1.0 is physically scaled ((a·r)/g); raise for a stronger",
                        "slosh, lower to soften.")
                .defineInRange("momentumStrength", 1.0, 0.0, 100.0);
        MOMENTUM_MIN_ACCEL = server
                .comment("Minimum acceleration (m/s²) before momentum tilts the fluid at all. 0 uses only the small",
                        "internal noise floor; raise it if a jittery ship sloshes when you don't want it to.")
                .defineInRange("momentumMinAccel", 0.0, 0.0, 1000.0);
        server.pop();

        // ----------------------------------------------------------------- Debug
        server.comment("Throwaway sub-level diagnostics.").push("debug");
        DEBUG_SUBLEVEL_SPIN = server
                .comment("DEBUG SPIKE: read a Sable sub-level's rigid-body angular velocity server-side",
                        "and action-bar the spin rate (rad/s + RPM), axis, and linear speed to players in",
                        "the level. Needs a fluid tank on the contraption (it rides the tank physics tick).",
                        "Off by default; a throwaway probe for the centrifuge-recipe investigation.")
                .define("debugSubLevelSpin", false);
        server.pop();
        server.pop(); // sable

        SERVER_SPEC = server.build();

        ModConfigSpec.Builder client = new ModConfigSpec.Builder();

        // ================================================================= Engine
        client.push("engine");
        client.comment("Engineer's Goggles readouts for pipes and pumps.").push("goggles");
        SHOW_PIPE_GOGGLE_INFO = client
                .comment("Show engine stats (status, fluid, flow, pressure) when looking",
                        "at a pipe with Engineer's Goggles.")
                .define("showPipeGoggleInfo", true);
        SHOW_PUMP_REACH_OVERLAY = client
                .comment("Colour the pipes a pump can reach GREEN while looking at it with",
                        "Engineer's Goggles: the run from its supply's surface up to the",
                        "elevation it can still push to (or draw up from). Pipe past that",
                        "limit is left alone, so where the green stops is how far it goes.")
                .define("showPumpReachOverlay", true);
        SHOW_VALVE_DIRECTION_ARROWS = client
                .comment("Show a sliding arrow through every ONE-WAY fluid valve (its allowed",
                        "flow direction) while wearing Engineer's Goggles or holding a wrench.")
                .define("showValveDirectionArrows", true);
        PRESERVE_PUMP_RANGE = client
                .comment("Keep showing the pump range indicator for a few seconds after",
                        "looking away from the pump.")
                .define("preservePumpRangeIndicator", true);
        PUMP_RANGE_PRESERVE_SECONDS = client
                .comment("How many seconds the pump range indicator lingers after looking away.")
                .defineInRange("pumpRangePreserveSeconds", 5, 1, 60);
        client.pop();

        client.comment("In-hand editing of a pipe network.").push("controls");
        ENABLE_PIPE_SWAP = client
                .comment("Shift + right-click a pipe element (pump, valve, smart fluid pipe, pipe) held in",
                        "hand onto another pipe element to replace it in place — the old block's drops are",
                        "refunded and one held block is consumed. Handy for editing a run without breaking it",
                        "first. (Read on the client, so it only takes effect in singleplayer / on your own",
                        "integrated server; a dedicated server always allows the swap.)")
                .define("enablePipeSwap", true);
        client.pop();

        client.comment("Drawing the fluid inside pipes, and running the engine inside Ponder.")
                .push("rendering");
        PIPE_LEVEL_RENDER = client
                .comment("Draw the fluid inside (glass) pipes. What is drawn is the pipe's REAL stored",
                        "content, synced from the server, so the fill always matches the actual fluid",
                        "state. Turning this off only hides the fluid on your client - the pipes still",
                        "hold and move it.")
                .define("pipeLevelRender", true);
        PIPE_LEVEL_FLOW_SPEED = client
                .comment("Speed multiplier for the in-pipe fluid scroll animation (the level renderer above).",
                        "1.0 = default; lower it to calm a fast flow, raise it to make it livelier. Only the",
                        "animation speed changes, not the actual fluid transfer.")
                .defineInRange("pipeLevelFlowSpeed", 1.0, 0.0, 10.0);
        ENABLE_PONDER_ENGINE = client
                .comment("Run the fluid engine live inside Ponder scenes, so the mod's own physics",
                        "(reach bounded by height, siphons, communicating vessels) drive the animation",
                        "instead of Create's stock pipe transport. Off = Ponder shows Create's behavior.")
                .define("enablePonderEngine", true);
        client.pop();
        client.pop(); // engine

        // ================================================================= Sable
        client.comment("Client rendering of fluid on Sable sub-levels (inert without Sable).")
                .push("sable");
        client.comment("Tilted, wavy fluid surfaces in tanks on a moving sub-level.").push("fluidPhysics");
        FLUID_TILT_ENABLED = client
                .comment("Enable tilted fluid rendering in tanks on Sable sub-levels.")
                .define("fluidTiltEnabled", true);
        FLUID_WAVE_MESH = client
                .comment("Enable wavy fluid surface mesh on Sable sub-levels.")
                .define("fluidWaveMesh", true);
        FLUID_SURFACE_RESOLUTION = client
                .comment("Grid resolution for the fluid surface mesh. The mesh is rebuilt on the CPU every",
                        "frame, so this is the dominant cost of a tank in view: 16 costs a sixteenth of 64",
                        "and the waves are amplitude-clamped far below what 64 could express.")
                .defineInRange("fluidSurfaceResolution", 16, 2, 128);
        FLUID_DEBUG_RENDER = client
                .comment("Show debug wireframe, corner dots, and grid lines on fluid surfaces.")
                .define("fluidDebugRender", false);
        FLUID_HIDE_TEXTURE = client
                .comment("Hide fluid textures, showing only debug wireframe.")
                .define("fluidHideTexture", false);
        FLUID_RESTING_WAVES = client
                .comment("How lively the fluid looks when its tank is sitting still, as a share of how it",
                        "looks being thrown about. 0 is a mirror, 1 never settles. Only the resting end:",
                        "a tank in motion ripples the same whatever this says.")
                .defineInRange("fluidRestingWaves", 0.10, 0.0, 1.0);
        FLUID_OPAQUE = client
                .comment("Draw tank fluid solid rather than see-through. Taste, not correctness: opaque",
                        "reads like Create's own tank fluid, translucent shows the far wall through the",
                        "near one but lets a wavy surface blend against itself at grazing angles.")
                .define("fluidOpaque", false);
        FLYWHEEL_TANK_VISUAL = client
                .comment("SPIKE: draw a Flywheel-instanced marker cube at every Create tank, to find out",
                        "whether a Flywheel visual reaches a tank on a moving Sable contraption at the right",
                        "place. Purely additive — the tank still renders itself. Not a player-facing feature,",
                        "and default ON only while the spike is being run: turn it off before release, or",
                        "delete it along with TankFluidVisual once the question is answered.")
                .define("flywheelTankVisual", true);
        client.pop();
        client.pop(); // sable
        CLIENT_SPEC = client.build();
    }
}
