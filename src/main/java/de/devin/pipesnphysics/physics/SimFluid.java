package de.devin.pipesnphysics.physics;

/**
 * A fluid type in the simulation.
 *
 * @param id registry id of the fluid
 * @param phase LIQUID or GAS
 * @param density fluid density (used in potential calculation)
 * @param viscosity fluid viscosity (drives front speed and steady flow rate)
 */
public record SimFluid(String id, FluidPhase phase, float density, float viscosity) {}
