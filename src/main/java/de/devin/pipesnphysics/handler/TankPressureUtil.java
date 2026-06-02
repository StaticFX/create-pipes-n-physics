package de.devin.pipesnphysics.handler;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;

/**
 * Shared utility for computing tank fill pressure.
 * Centralizes the accessor pattern so the formula stays consistent.
 */
public final class TankPressureUtil {

    private TankPressureUtil() {}

    /**
     * Compute pressure from a tank's fill level: density * G * fillFraction * tankHeight.
     *
     * @param tankBE the tank block entity (must be a controller or single tank)
     * @param density fluid density (ρ), defaults to 1.0 for unknown fluids
     * @param G gravity constant from SimConfig
     * @return fill-based pressure in blocks-of-water-column units
     */
    public static float computeFillPressure(FluidTankBlockEntity tankBE, float density, float G) {
        FluidTankAccessor accessor = (FluidTankAccessor) tankBE;
        int tankHeight = accessor.pipesnphysics$getHeight();
        var inventory = accessor.pipesnphysics$getTankInventory();
        float fillFraction = inventory.getCapacity() > 0
                ? (float) inventory.getFluidAmount() / inventory.getCapacity()
                : 0;
        return density * G * fillFraction * tankHeight;
    }
}
