package de.devin.pipesnphysics.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * A veto on which endpoints a given fluid may move through — the per-fluid, per-tick counterpart of
 * the static block roles in FluidHandlerRole. Answer false and the engine treats that endpoint as if
 * it were not on the network for that fluid: it joins no solve, and nothing drains out of or fills
 * into it. Register with EndpointApi.registerFilter.
 *
 * Asked often (per endpoint, per fluid, per tick) and on the server thread, so keep it cheap and
 * side-effect free — never move fluid or mutate the world from here.
 */
@FunctionalInterface
public interface EndpointFilter {
    /**
     * Whether the endpoint at this position may take part in moving this fluid. The position is the
     * endpoint's identity: a multiblock tank's controller, a machine's own block, or the space block
     * an open pipe mouth faces.
     */
    boolean allows(Level level, BlockPos endpoint, FluidStack fluid);
}
