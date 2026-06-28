package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.fluids.pipes.VanillaFluidTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import java.util.List;

/**
 * Top-level entry point for the fluid engine.
 *
 * One tick of one network:
 *
 *   1. {@link GraphBuilder#build} walks Create pipes outward from a seed position
 *      and contracts the network into nodes (tanks, pumps, junctions) and edges
 *      (pipe runs).
 *
 *   2. {@link FlowSolver#solve} reads the live tank fills and pump speeds, runs the
 *      implicit hydraulic solve (see {@link de.devin.pipesnphysics.engine.solve.NetworkSolver}),
 *      and returns a {@link Solution}: per-edge flow for visualization plus the list
 *      of endpoint-to-endpoint transfers for this tick. Solving never mutates the world.
 *
 *   3. {@link #apply} executes the transfers through {@code IFluidHandler} with the
 *      simulate-then-execute pattern, so the engine interoperates with any mod's
 *      fluid containers and can never duplicate or destroy fluid: each pair moves
 *      exactly {@code min(what the source gave, what the sink accepted)}.
 *
 * The engine is stateless — fluid lives only in the endpoint handlers, never in the
 * pipes — so saving, chunk unload, and reload need no extra persistence and always
 * resume consistently.
 */
public final class FluidEngine {
    private FluidEngine() {}

    /** Run one full tick (build, solve, apply) on the network containing seedPos. */
    public static Solution tick(ServerLevel level, BlockPos seedPos) {
        Graph graph = GraphBuilder.build(level, seedPos);
        Solution solution = FlowSolver.solve(level, graph);
        apply(level, solution);
        return solution;
    }

    /** Build a graph without solving. Used by /pipegraph and the overlay. */
    public static Graph buildGraph(ServerLevel level, BlockPos seedPos) {
        return GraphBuilder.build(level, seedPos);
    }

    /** Build and solve without applying. Used by the visualizer. */
    public static Solution simulate(ServerLevel level, BlockPos seedPos) {
        Graph graph = GraphBuilder.build(level, seedPos);
        return FlowSolver.solve(level, graph);
    }

    /**
     * Execute the planned transfers. Capabilities are looked up again here — the
     * world may have changed since the solve — and each transfer is clamped by what
     * the source can actually give and the sink can actually take, so a stale plan
     * degrades to a smaller (or zero) transfer instead of an error.
     */
    public static void apply(ServerLevel level, Solution solution) {
        apply(level, solution.transfers());
    }

    /**
     * Execute a specific set of transfers — used when the caller has held some back
     * (e.g. until the visual fluid front reaches the sink, see {@code EngineTickHandler}).
     */
    public static void apply(ServerLevel level, List<Solution.Transfer> transfers) {
        for (Solution.Transfer transfer : transfers) {
            IFluidHandler source = handlerAt(level, transfer.from());
            IFluidHandler sink = handlerAt(level, transfer.to());
            if (source == null || sink == null) continue;

            FluidStack drained = source.drain(transfer.fluid().copy(), FluidAction.SIMULATE);
            if (drained.isEmpty()) continue;
            int accepted = sink.fill(drained, FluidAction.SIMULATE);
            if (accepted <= 0) continue;

            FluidStack moved = source.drain(
                    transfer.fluid().copyWithAmount(Math.min(accepted, drained.getAmount())),
                    FluidAction.EXECUTE);
            if (moved.isEmpty()) continue;
            sink.fill(moved, FluidAction.EXECUTE);

            // A transfer INTO an open end is a spill (intake has the open end as the
            // SOURCE). Stamp it so the network won't suck a finite source back in for a
            // cooldown — the no-reclaim guard for hand-placed-source intake.
            if (BoundaryColumn.findHandler(level, transfer.to()) == null) {
                OpenEndPipes.markSpilled(level, transfer.to());
            }
        }
    }

    /**
     * Block capability, or the open-end pipe behind a world-space position. A vanilla
     * fluid target (cauldron/honey) exposes a coarse-granularity capability — NeoForge's
     * {@code CauldronWrapper} only drains in whole 1000 mB steps — but the engine treats
     * it as an OPEN_END (see {@code GraphBuilder}), so it must drain through the open-end
     * pipe (atomic drain + buffered delivery), NOT that capability. Resolving the
     * capability here would refuse every sub-1000 mB transfer and show flow while moving
     * nothing — the same hijack that misclassified the node, one layer down.
     */
    private static IFluidHandler handlerAt(ServerLevel level, BlockPos pos) {
        if (!VanillaFluidTargets.canProvideFluidWithoutCapability(level.getBlockState(pos))) {
            IFluidHandler handler = BoundaryColumn.findHandler(level, pos);
            if (handler != null) return handler;
        }
        return OpenEndPipes.existing(level, pos);
    }
}
