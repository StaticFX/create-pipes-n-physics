package de.devin.pipesnphysics.physics;

import net.minecraft.core.BlockPos;

/**
 * Two incompatible fluids met, either at a junction or mid-pipe.
 *
 * @param position world position where the collision occurred
 * @param fluidA first fluid id
 * @param fluidB second fluid id
 */
public record CollisionEvent(BlockPos position, String fluidA, String fluidB) {}
