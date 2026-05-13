package de.devin.pipesnphysics.physics;

/**
 * Pure math for computing fluid tank mass and gravitational impulses.
 * Used by the Sable dynamic tank mass feature.
 */
public final class TankMassFormulas {

    private TankMassFormulas() {}

    /**
     * Compute fluid mass in kilograms from fluid amount, density, and config.
     *
     * @param fluidAmountMb  fluid amount in millibuckets
     * @param fluidDensity   fluid density (water = 1000, lava = 3000)
     * @param massPerBucket  config: kg per bucket of water-density fluid
     * @return mass in kg, scaled by density ratio
     */
    public static double fluidMassKg(int fluidAmountMb, int fluidDensity, double massPerBucket) {
        double densityRatio = fluidDensity / 1000.0;
        double buckets = fluidAmountMb / 1000.0;
        return buckets * massPerBucket * densityRatio;
    }

    /**
     * Compute the downward gravitational impulse for a fluid mass.
     *
     * @param massKg          fluid mass in kg
     * @param timestepSeconds physics timestep duration
     * @return impulse Y component (negative = downward)
     */
    public static double gravityImpulseY(double massKg, double timestepSeconds) {
        return -massKg * 9.81 * timestepSeconds;
    }

    /**
     * Compute fill fraction from fluid amount and tank capacity.
     *
     * @return 0.0–1.0 fill level, or 0 if capacity is zero
     */
    public static double fillFraction(int fluidAmount, int capacity) {
        return capacity > 0 ? (double) fluidAmount / capacity : 0.0;
    }
}
