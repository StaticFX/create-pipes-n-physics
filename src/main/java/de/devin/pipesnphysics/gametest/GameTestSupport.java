package de.devin.pipesnphysics.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.FluidHandlerApi;
import de.devin.pipesnphysics.api.FluidHandlerRole;
import de.devin.pipesnphysics.client.PipeStatusText;
import de.devin.pipesnphysics.display.PipeDisplayMetric;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.PipeFlowExecutor;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.FluidCaps;
import de.devin.pipesnphysics.engine.boundary.HandlerRoles;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.flow.FlowNetwork;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.valve.ValveCharacteristic;
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import de.devin.pipesnphysics.handler.NetworkEditHandler;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.server.level.ServerLevel;
import net.createmod.catnip.lang.LangNumberFormat;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.TestSideHandlers;

/**
 * Shared helpers for the pipesnphysics GameTests (fill/drain/read/probe utilities). Static-imported by the domain test classes; not a @GameTestHolder.
 */
public final class GameTestSupport {
    private GameTestSupport() {}

    /**
     * The first registered lighter-than-air SOURCE fluid (TFMG's carbon dioxide in the dev
     * runtime), or null — gas tests skip when the runtime ships none.
     */
    public static Fluid lighterThanAirFluid() {
        for (Fluid f : BuiltInRegistries.FLUID) {
            if (f.defaultFluidState().isSource() && f.getFluidType().isLighterThanAir()) return f;
        }
        return null;
    }

    public static int basinFluid(GameTestHelper helper, BlockPos relativePos, Fluid fluid) {
        IFluidHandler h = handler(helper, relativePos);
        int sum = 0;
        for (int i = 0; i < h.getTanks(); i++) {
            FluidStack f = h.getFluidInTank(i);
            if (f.getFluid() == fluid) sum += f.getAmount();
        }
        return sum;
    }

    public static int pipeAmount(GameTestHelper helper, BlockPos rel) {
        PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
        return cell == null ? 0 : cell.amount();
    }

    /** What a pipe cell is carrying, or EMPTY when it is dry (or no longer a pipe at all). */
    public static FluidStack pipeFluid(GameTestHelper helper, BlockPos rel) {
        PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
        return cell == null || cell.amount() <= 0 ? FluidStack.EMPTY : cell.fluid();
    }

    public static IFluidHandler pipesnphysics$tankHandler(net.minecraft.server.level.ServerLevel level, BlockPos absPos) {
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, absPos, null);
        return handler != null ? handler : new net.neoforged.neoforge.fluids.capability.templates.FluidTank(0);
    }

    public static boolean pipeAt(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get());
    }

    /** The stored mB in the pipe cell at an absolute position, 0 when empty or not a pipe. */
    public static int cellMb(Level level, BlockPos absolutePos) {
        PipeStore.Store store = PipeStore.at(level, absolutePos);
        return store == null ? 0 : store.amount();
    }

    /**
     * Where Create's {@code FluidTankRenderer} DRAWS a tank's fluid surface (world Y): inset
     * {@code 0.3125} above the base, filling only {@code height − 0.5625} of the column. A settled
     * pipe tracks THIS ({@link BoundaryColumn#renderedSurface}), not the full-range {@code baseY +
     * fill} the solver equalizes, so its waterline meets the tank's VISIBLE fluid.
     */
    public static double tankRenderedSurface(double baseY, int heightBlocks, int amount, int capacity) {
        return baseY + 0.3125 + (amount / (double) capacity) * (heightBlocks - 0.5625);
    }

    /**
     * The elevation a Create tank DRAWS a lighter-than-air content down to — the gas hangs from
     * {@code capHeight} below the ceiling over the same compressed span ({@link
     * BoundaryColumn#gasSurface}); a settled gas pipe's hanging fill tracks THIS interface.
     */
    public static double tankGasInterface(double baseY, int heightBlocks, int amount, int capacity) {
        return baseY + heightBlocks - (0.25 + (amount / (double) capacity) * (heightBlocks - 0.5625));
    }

    /** Sum of all pipe-cell contents within the template-relative area. */
    public static int pipesnphysics$areaPipeContent(GameTestHelper helper, int sx, int sy, int sz) {
        int sum = 0;
        for (int x = 0; x < sx; x++)
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++) {
                    sum += cellMb(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
                }
        return sum;
    }

    public static IFluidHandler pipesnphysics$sideFallback(Level level, BlockPos pos) {
        IFluidHandler h = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (h != null) return h;
        for (Direction d : Direction.values()) {
            h = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d);
            if (h != null) return h;
        }
        return null;
    }

    public static String dump(GameTestHelper helper) {
        return dump(helper, new BlockPos(2, 1, 1));
    }

    public static String dump(GameTestHelper helper, BlockPos probe) {
        var graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(probe));
        var solution = FlowSolver.solve(helper.getLevel(), graph);
        StringBuilder out = new StringBuilder(" | GRAPH:");
        for (var n : graph.nodes()) {
            out.append(String.format(" [%d %s %s head=%s ceil=%s]",
                    n.index(), n.kind(), n.pos().toShortString(),
                    solution.nodeHeads().get(n.index()), solution.nodeCeilings().get(n.index())));
        }
        for (var e : graph.edges()) {
            out.append(String.format(" e%d(%d-%d len%d %s)",
                    e.index(), e.a(), e.b(), e.length(),
                    solution.edgeFlows().get(e.index()).direction()));
        }
        return out.toString();
    }

    public static BlockState pipeState(
            Block pipe, Direction... connections) {
        var state = pipe.defaultBlockState();
        for (var property : PipeBlock.PROPERTY_BY_DIRECTION.values()) {
            state = state.setValue(property, false);
        }
        for (var direction : connections) {
            state = state.setValue(
                    PipeBlock.PROPERTY_BY_DIRECTION.get(direction), true);
        }
        return state;
    }

    public static void fill(GameTestHelper helper, BlockPos relativePos, int mb) {
        fillFluid(helper, relativePos, Fluids.WATER, mb);
    }

    public static void fillFluid(GameTestHelper helper, BlockPos relativePos,
                                  Fluid fluid, int mb) {
        handler(helper, relativePos)
                .fill(new FluidStack(fluid, mb), IFluidHandler.FluidAction.EXECUTE);
    }

    public static void drain(GameTestHelper helper, BlockPos relativePos) {
        handler(helper, relativePos).drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
    }

    public static int amount(GameTestHelper helper, BlockPos relativePos) {
        return handler(helper, relativePos).getFluidInTank(0).getAmount();
    }

    public static IFluidHandler handler(GameTestHelper helper, BlockPos relativePos) {
        IFluidHandler handler = helper.getLevel().getCapability(
                Capabilities.FluidHandler.BLOCK, helper.absolutePos(relativePos), null);
        if (handler == null) helper.fail("no fluid handler at " + relativePos);
        return handler;
    }

    /**
     * Publish a pump's strength the way every pump on Create's pipe network does: inbound pressure on
     * the flank it draws from, outward pressure on the flank it pushes into. Stating it by hand is how
     * a test stands in for the power a real addon pump would need (a voltage, a circuit) — its own tick
     * restates it the tick after, so read it back in the same tick.
     */
    public static void publishPumpPressure(GameTestHelper helper, BlockPos rel, Direction push, float rpm) {
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(rel));
        PipeConnection outlet = pipe == null ? null : pipe.getConnection(push);
        PipeConnection intake = pipe == null ? null : pipe.getConnection(push.getOpposite());
        if (outlet == null || intake == null) {
            helper.fail("the pump at " + rel + " has no pipe connection on its " + push.getAxis() + " flanks");
            return;
        }
        outlet.getPressure().set(false, rpm);
        intake.getPressure().set(true, rpm);
    }

    /**
     * Swap a SMART PIPE into a rig — the stand-in for another mod's pipe device (a foreign pump, an
     * adapted turbine). It is the one Create pipe with a settable orientation and no rig of its own
     * uses it, and {@code AttachFace.WALL} makes its run VERTICAL where the rig needs a riser.
     */
    public static void placeStandInPipe(GameTestHelper helper, BlockPos rel,
                                        AttachFace face, Direction facing) {
        placeRigBlock(helper, rel, AllBlocks.SMART_FLUID_PIPE.getDefaultState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, face)
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, facing));
    }

    /**
     * Swap a block into a rig and re-shape the cells around it: {@code setBlock} only re-shapes
     * OUTWARD, so the neighbours would keep the connection state they had against whatever stood
     * here before and the run would not join up.
     */
    public static void placeRigBlock(GameTestHelper helper, BlockPos rel, BlockState state) {
        helper.setBlock(rel, state);
        for (Direction side : Direction.values()) {
            BlockPos abs = helper.absolutePos(rel).relative(side);
            helper.getLevel().setBlock(abs, Block.updateFromNeighbourShapes(
                    helper.getLevel().getBlockState(abs), helper.getLevel(), abs), 3);
        }
    }
}
