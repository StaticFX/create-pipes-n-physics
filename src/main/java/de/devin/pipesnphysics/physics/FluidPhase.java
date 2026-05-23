package de.devin.pipesnphysics.physics;

/**
 * Phase of a fluid. Determines gravity direction.
 * Liquids flow down (positive potential at height), gases flow up (negative).
 */
public enum FluidPhase {
    LIQUID(1),
    GAS(-1);

    private final int sign;

    FluidPhase(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }
}
