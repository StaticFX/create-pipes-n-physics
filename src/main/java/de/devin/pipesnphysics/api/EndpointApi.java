package de.devin.pipesnphysics.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Decide from code, per fluid and per tick, which endpoints a network may move fluid through — for
 * a machine whose tank is only a valid target under conditions the engine cannot see. Register an
 * EndpointFilter from your mod's setup; every registered filter has to allow an endpoint before it
 * takes part, so filters compose as an AND and each one only ever narrows.
 *
 * This package depends on nothing internal and is the supported way to shape routing: the engine's
 * own classes move between packages between releases, so a mixin into them breaks on update — hard,
 * at classload, which is a crashed server rather than a missing feature.
 */
public final class EndpointApi {
    private static final List<EndpointFilter> FILTERS = new CopyOnWriteArrayList<>();

    private EndpointApi() {}

    /** Add a veto on endpoint participation; register once, from your mod's setup. */
    public static void registerFilter(EndpointFilter filter) {
        FILTERS.add(filter);
    }

    /** Remove a previously registered filter, returning whether it was registered at all. */
    public static boolean removeFilter(EndpointFilter filter) {
        return FILTERS.remove(filter);
    }

    /**
     * Whether every registered filter lets this endpoint take part in moving this fluid. The engine
     * asks this in the SOLVE (so no flow is planned through a vetoed endpoint) and again at the
     * boundary itself (so the settle phases, which deliberately move fluid the solve never planned,
     * cannot slip past it).
     */
    public static boolean allows(Level level, BlockPos endpoint, FluidStack fluid) {
        if (FILTERS.isEmpty()) return true;
        for (EndpointFilter filter : FILTERS) {
            if (!filter.allows(level, endpoint, fluid)) return false;
        }
        return true;
    }
}
