package de.devin.pipesnphysics.physics;

/**
 * A fluid type in the simulation. Phase and density determine flow direction.
 *
 * @param id registry id of the fluid
 * @param phase LIQUID or GAS
 * @param density fluid density (used in potential calculation)
 */
public record SimFluid(String id, FluidPhase phase, float density) {}
