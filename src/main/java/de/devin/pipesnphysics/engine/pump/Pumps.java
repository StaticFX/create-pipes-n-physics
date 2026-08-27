package de.devin.pipesnphysics.engine.pump;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.FluidPump;
import de.devin.pipesnphysics.api.PumpApi;
import de.devin.pipesnphysics.engine.turbine.Turbines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * What the engine knows about a pump: whether a block IS one, how hard it pumps, and which way.
 * The single place those three questions are answered, so the graph, the solve, the goggles, the
 * reach overlay and {@code /pipegraph} can never disagree about the same machine.
 *
 * Create's own Mechanical Pump is read off its shaft. Every OTHER mod's pump — an electric one, a
 * steam one, one with a mode dial — is read off the pressure it PUBLISHES to Create's pipe
 * transport: that is the one currency a pump on the pipe network already has to speak, whatever
 * powers it, and Create's own pump publishes exactly its RPM there, so the two scales are one
 * number. A block only becomes a pump node once the engine has been told it is a pump (the
 * {@code pipesnphysics:pumps} block tag, {@link PumpApi}, or a block entity implementing
 * {@link FluidPump}) — pressure alone is no identity, since a plain pipe carries a pump's pressure
 * too.
 */
public final class Pumps {
    /** Blocks a pack declares to be pumps; entries are {@code required: false}, so unknown mods are ignored. */
    public static final TagKey<Block> PUMPS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(PipesNPhysics.ID, "pumps"));

    private Pumps() {}

    /**
     * Whether this block is a pump-kind NODE: Create's own pump (and anything extending it), a
     * declared foreign pump, or a registered turbine — a turbine is the same node with its head
     * negated (§5.4), so identity is one question. It must really sit on Create's pipe network; a
     * declared block carrying no pipe behaviour is left alone, since the engine's pump is a two-flank
     * node on a pipe run and a machine that moves fluid by its own logic needs no help from us.
     */
    public static boolean isPump(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof PumpBlock) return true;
        if (!state.hasBlockEntity()) return false;
        boolean turbine = Turbines.isRegistered(state);
        if (!turbine && !PipesNPhysicsConfig.ENABLE_FOREIGN_PUMPS.get()) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (pipeOf(be) == null) return false;
        return turbine || state.is(PUMPS) || PumpApi.isDeclaredPump(state.getBlock())
                || be instanceof FluidPump;
    }

    /**
     * How hard the pump at this position is pushing, in Create RPM — 0 when it is stopped, which
     * leaves it a closed valve. The engine turns this into head ({@code PUMP_HEAD_PER_RPM}) and
     * throughput ({@code PUMP_FLOW_PER_RPM}) exactly as it always has for a Mechanical Pump.
     */
    public static double strength(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FluidPump pump) return Math.max(0, pump.pumpStrength());
        double published = publishedPressure(be);
        if (published > 0) return published * PipesNPhysicsConfig.FOREIGN_PUMP_STRENGTH_SCALE.get();
        return be instanceof KineticBlockEntity kinetic ? Math.abs(kinetic.getSpeed()) : 0;
    }

    /**
     * The side this pump pushes toward, or null while it is unresolved (which the solver reads as a
     * closed valve). Create's own pump answers from its blockstate FACING, which Create keeps aligned
     * with its rotation. A foreign pump answers from the flank it publishes OUTWARD pressure on, so a
     * pump with a reversing dial is followed wherever it points; its facing is the resting fallback.
     */
    public static Direction pushSide(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof PumpBlock) return state.getValue(PumpBlock.FACING);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FluidPump pump && pump.pumpPushSide() != null) return pump.pumpPushSide();
        Direction published = publishedPushSide(be);
        if (published != null) return published;
        // A turbine publishes nothing — it is being pushed THROUGH — so an adapted one states its
        // own outlet, for the block whose orientation no `facing` property describes.
        Direction adapted = Turbines.pushSide(level, pos);
        return adapted != null ? adapted : facingOf(state);
    }

    /**
     * The strength this pump PUBLISHES, in Create RPM — 0 for one nothing powers, and 0 for Create's
     * own pump whatever its shaft does. This is the part of {@link #strength} that says "something
     * other than the fluid is driving this block", which is what the AUTO dial asks: a spinning
     * turbine's own shaft speed must NOT read as being driven, or it would flip back to a pump the
     * moment it started turning.
     */
    public static double publishedStrength(Level level, BlockPos pos) {
        double published = publishedPressure(level.getBlockEntity(pos));
        return published * PipesNPhysicsConfig.FOREIGN_PUMP_STRENGTH_SCALE.get();
    }

    /** The block's own facing, whatever property carries it — the pump's axis for a value box. */
    public static Direction facing(BlockState state) {
        return facingOf(state);
    }

    /**
     * The pressure a pump publishes on its own pipe connections, in Create RPM. Stock Create's pump
     * publishes NOTHING under the engine ({@code PumpTransferTickMixin} cancels the behaviour that
     * would), so pressure standing on its connections was distributed there by a neighbour and is not
     * its own strength — hence the exact-class test. Anything else, including a pump that merely
     * EXTENDS Create's, speaks for itself.
     */
    private static double publishedPressure(BlockEntity be) {
        if (!PipesNPhysicsConfig.ENABLE_FOREIGN_PUMPS.get()) return 0;
        FluidTransportBehaviour pipe = publishingPipe(be);
        if (pipe == null) return 0;
        double published = 0;
        for (PipeConnection connection : pipe.interfaces.values()) {
            published = Math.max(published, Math.max(connection.getPressure().getFirst(),
                    connection.getPressure().getSecond()));
        }
        return published;
    }

    /** The flank a pump publishes OUTWARD pressure on — the side it pushes fluid out of. */
    private static Direction publishedPushSide(BlockEntity be) {
        FluidTransportBehaviour pipe = publishingPipe(be);
        if (pipe == null) return null;
        for (var connection : pipe.interfaces.entrySet()) {
            if (connection.getValue().getPressure().getSecond() > 0) return connection.getKey();
        }
        return null;
    }

    private static FluidTransportBehaviour publishingPipe(BlockEntity be) {
        if (be == null || be.getClass() == PumpBlockEntity.class) return null;
        FluidTransportBehaviour pipe = pipeOf(be);
        return pipe == null || pipe.interfaces == null ? null : pipe;
    }

    private static FluidTransportBehaviour pipeOf(BlockEntity be) {
        return be instanceof SmartBlockEntity smart ? smart.getBehaviour(FluidTransportBehaviour.TYPE) : null;
    }

    /** The block's own facing, whatever property carries it (a pump may be directional or horizontal). */
    private static Direction facingOf(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() == Direction.class && "facing".equals(property.getName())) {
                return (Direction) state.getValue(property);
            }
        }
        return null;
    }
}
