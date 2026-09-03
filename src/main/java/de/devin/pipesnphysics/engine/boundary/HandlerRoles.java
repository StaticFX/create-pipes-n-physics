package de.devin.pipesnphysics.engine.boundary;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.FluidHandlerApi;
import de.devin.pipesnphysics.api.FluidHandlerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides what ROLE a fluid-handler block plays in the network — the single place that separates a
 * real reservoir from a relay device (a docking connector, a hose, a passthrough), so the engine
 * stops equalizing the latter as a tank. See CLAUDE.md §2.
 *
 * A behavioural {@link RelayDetector} learns relays automatically; four block tags override it.
 * Precedence for any block exposing an {@code IFluidHandler} (first match wins):
 *   1. is_reservoir         → normal capacitor (drain + equalize). Vetoes the detector.
 *   2. fluid_conduits       → passthrough conduit: chained AND equalized as a shared buffer
 *                             (handled in {@link GraphBuilder}; a relay we WANT to equalize, e.g. a
 *                             row of liquid burners feeding each other).
 *   3. ignore_fluid_handler → skipped entirely: not a graph node at all, as if the block held no
 *                             fluid — for a device that corrupts on both drain AND fill.
 *   4. relay_endpoint, OR a block the detector has learned is a relay
 *                           → drain-priority BOTTOMLESS endpoint (a docking connector, a hose): a
 *                             one-way source while it holds fluid, a one-way sink while empty, never
 *                             surface-equalized. {@link BoundaryColumn#resolve} builds it.
 *   5. sink_only            → receive-only: the engine may fill it but never drains or equalizes it.
 *   6. otherwise            → normal capacitor.
 *
 * All tags are {@code required: false}, so an entry for a missing mod is silently ignored.
 */
public final class HandlerRoles {
    public static final TagKey<Block> FLUID_CONDUITS = tag("fluid_conduits");
    public static final TagKey<Block> IS_RESERVOIR = tag("is_reservoir");
    public static final TagKey<Block> IGNORE = tag("ignore_fluid_handler");
    public static final TagKey<Block> SINK_ONLY = tag("sink_only");
    public static final TagKey<Block> RELAY_ENDPOINT = tag("relay_endpoint");
    public static final TagKey<Block> SEPARATE_PORTS = tag("separate_ports");

    private HandlerRoles() {}

    /**
     * Whether this block's pipe connections are separate PORTS rather than one shared plenum — a
     * TOPOLOGY question, not a role, and the one thing here that changes the GRAPH instead of the
     * endpoint's behaviour. A tank or a basin is a plenum: fluid entering by one pipe may leave by
     * another, so {@link GraphBuilder} couples every run touching it into ONE network. A machine
     * that keeps its ports in separate internal tanks behind one combined capability is not, and
     * coupling it is what lets one line's fluid leave through another line's port — a TFMG engine
     * joins its fuel, air and exhaust manifolds into a single network, so exhaust can back-fill a
     * fuel tank that has run dry and the refuelled line then stalls on the wrong fluid.
     *
     * Declared, never sniffed: through {@code IFluidHandler} a plenum and a multi-port machine are
     * indistinguishable (a Create basin is FOUR tanks and a genuine plenum, so "more than one tank"
     * is not the test — it fails {@code multiFluidBasinSeparatesCompletely}). A block whose faces
     * already expose DIFFERENT handler objects needs no declaration: identity alone makes it
     * side-specific, which decouples it anyway.
     */
    public static boolean hasSeparatePorts(BlockState state) {
        return state.is(SEPARATE_PORTS) || FluidHandlerApi.hasSeparatePorts(state.getBlock());
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(PipesNPhysics.ID, path));
    }

    /**
     * The block's explicitly-assigned role: a matching role TAG (in the precedence order above), else a
     * code role registered through {@link FluidHandlerApi}, else null (no explicit role — a plain
     * capacitor, subject to the learned relay detector). Tags win over code so a pack can override.
     */
    public static FluidHandlerRole explicitRole(BlockState state) {
        if (state.is(IS_RESERVOIR)) return FluidHandlerRole.RESERVOIR;
        if (state.is(FLUID_CONDUITS)) return FluidHandlerRole.CONDUIT;
        if (state.is(IGNORE)) return FluidHandlerRole.IGNORE;
        if (state.is(RELAY_ENDPOINT)) return FluidHandlerRole.RELAY;
        if (state.is(SINK_ONLY)) return FluidHandlerRole.SINK_ONLY;
        return FluidHandlerApi.role(state.getBlock());
    }

    /**
     * Whether the block carries any explicit role — a role TAG or a code registration through
     * {@link FluidHandlerApi}. An explicit role vetoes the learned demotion: the relay detector
     * never observes or demotes an explicitly-classified block.
     */
    public static boolean hasExplicitRole(BlockState state) {
        return explicitRole(state) != null;
    }

    /**
     * A one-line, human-readable verdict of the role the engine gives this block and WHERE that verdict
     * comes from — a role tag, a code registration, a learned demotion, or the default. The reusable
     * explainer behind the {@code /pipegraph} block report and any compat tooling; mirrors the
     * {@link #explicitRole} precedence.
     */
    public static String explain(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(IS_RESERVOIR)) return "reservoir (is_reservoir tag) — a normal tank, drained + equalized";
        if (state.is(FLUID_CONDUITS)) return "conduit (fluid_conduits tag) — chained + equalized with its neighbours";
        if (state.is(IGNORE)) return "ignored (ignore_fluid_handler tag) — not a network node at all";
        if (state.is(RELAY_ENDPOINT)) return "relay (relay_endpoint tag) — bottomless one-way endpoint, never equalized";
        if (state.is(SINK_ONLY)) return "sink-only (sink_only tag) — the engine fills it but never drains it";
        FluidHandlerRole code = FluidHandlerApi.role(state.getBlock());
        if (code != null) return code.name().toLowerCase() + " (registered in code via the API)";
        if (PipesNPhysicsConfig.AUTO_DETECT_RELAY_HANDLERS.get() && RelayDetector.isRelay(pos)) {
            return "relay (learned by the detector this session) — bottomless one-way endpoint";
        }
        return "plain capacitor (no explicit role) — a normal tank unless the detector demotes it";
    }

    /**
     * Whether the block at {@code pos} should be skipped as a fluid target: the pipe treats it as if
     * it had no handler (a dead end / open face). The is_reservoir role vetoes ignore.
     */
    public static boolean isIgnored(Level level, BlockPos pos) {
        return explicitRole(level.getBlockState(pos)) == FluidHandlerRole.IGNORE;
    }

    /**
     * Whether the block at {@code pos} is a passthrough conduit — chained to its neighbours and
     * equalized with them as one shared buffer (see {@link GraphBuilder}). is_reservoir vetoes it.
     */
    public static boolean isConduit(Level level, BlockPos pos) {
        return explicitRole(level.getBlockState(pos)) == FluidHandlerRole.CONDUIT;
    }

    /**
     * Whether the handler at {@code pos} is a RELAY endpoint — a paired/passthrough device (a docking
     * connector, a hose) that moves fluid through its own logic and must NOT be modelled as a
     * surface-elevation capacitor. {@link BoundaryColumn} resolves these drain-priority and bottomless
     * (like a hose pulley): a one-way SOURCE while they hold fluid, a one-way SINK while empty — so the
     * engine always drains a receiving connector and always fills a sending one, no matter the levels,
     * instead of calling them "balanced" and refusing to move fluid (the equalization stall). True for
     * the relay role (tag or code) or a detector-learned relay position, unless pinned as a real
     * tank / conduit.
     */
    public static boolean isRelayEndpoint(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidHandlerRole role = explicitRole(state);
        if (role == FluidHandlerRole.RELAY) return true;
        return role == null && !level.isClientSide() && PipesNPhysicsConfig.AUTO_DETECT_RELAY_HANDLERS.get()
                && RelayDetector.isRelay(pos); // learned relays are a server-only, pos-keyed notion
    }

    /**
     * Whether the handler at {@code pos} is receive-only — the engine may fill it but never drains or
     * equalizes it. Only the sink_only role (tag or code) opts in; a detector-learned relay is a
     * {@link #isRelayEndpoint relay endpoint} instead (the bidirectional-friendly demotion).
     */
    public static boolean isReceiveOnly(Level level, BlockPos pos) {
        return explicitRole(level.getBlockState(pos)) == FluidHandlerRole.SINK_ONLY;
    }
}
