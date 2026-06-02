package de.devin.pipesnphysics.physics;

import net.minecraft.core.BlockPos;

/**
 * Identity for a node in a pipe network graph, backed by an immutable {@link BlockPos}.
 */
public record NodeId(BlockPos pos) {}
