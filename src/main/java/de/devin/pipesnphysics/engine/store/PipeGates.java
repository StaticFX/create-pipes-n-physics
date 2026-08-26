package de.devin.pipesnphysics.engine.store;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * The per-cell fluid GATES a pipe carries: a smart pipe's filter, a shut valve, any pipe with an
 * opinion of its own — all of them Create's one predicate, {@code canPullFluidFrom}.
 *
 * A cell that rejects a fluid is a WALL for it: nothing crosses that boundary in either direction,
 * exactly as a fully shut valve walls a run. Both sides of the engine ask THIS predicate — the
 * solve refuses to assemble a branch through a rejecting run ({@code FluidPass.runAcceptsFluid}),
 * and the executor refuses to move the fluid across the boundary ({@code SettlingRun},
 * {@code SettlePass}) — so they cannot disagree. They did once: a filtered smart pipe stopped the
 * SOLVE while the settle, which reads only elevations, walked the rejected fluid straight through
 * it and out of an open mouth.
 */
public final class PipeGates {
    private PipeGates() {}

    /** Whether {@code fluid} may cross between two ADJACENT cells: both have to admit it. */
    public static boolean conducts(Level level, BlockPos from, BlockPos to, FluidStack fluid) {
        return admits(level, from, to, fluid) && admits(level, to, from, fluid);
    }

    /** Whether {@code fluid} may travel the whole way along a path of adjacent cells. */
    public static boolean conductsAlong(Level level, List<BlockPos> path, FluidStack fluid) {
        for (int i = 1; i < path.size(); i++) {
            if (!conducts(level, path.get(i - 1), path.get(i), fluid)) return false;
        }
        return true;
    }

    /**
     * Whether the pipe at {@code pos} passes {@code fluid} through the face toward its neighbour —
     * the ONE-SIDED question, for a boundary whose other side is not part of the run: an endpoint
     * (a tank, a mouth, a pump) or the NODE a run ends at. A node's own gate is deliberately not
     * asked there: a fully shut valve is a node the solver already models as a dead end, and
     * walling the run into it instead would cost the pump its held column.
     */
    public static boolean admits(Level level, BlockPos pos, BlockPos neighbour, FluidStack fluid) {
        if (fluid.isEmpty()) return true;
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
        if (pipe == null) return true; // a tank, a mouth, anything that is not a pipe has no gate
        Direction face = PipeGeometry.between(pos, neighbour);
        return face == null || pipe.canPullFluidFrom(fluid, level.getBlockState(pos), face);
    }
}
