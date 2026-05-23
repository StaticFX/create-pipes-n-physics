package de.devin.pipesnphysics.physics;

/**
 * A contiguous segment of one fluid inside an edge.
 * Edges maintain an ordered list of fronts (column model) for mid-pipe collision detection.
 *
 * @param fluidId registry id of the fluid in this segment
 * @param amount volume of fluid in this segment (mB)
 */
public record FluidFront(String fluidId, int amount) {

    public FluidFront withAmount(int newAmount) {
        return new FluidFront(fluidId, newAmount);
    }
}
