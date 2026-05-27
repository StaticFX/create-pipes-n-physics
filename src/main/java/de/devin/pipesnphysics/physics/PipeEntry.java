package de.devin.pipesnphysics.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A single pipe block in an edge, with its connection directions.
 * {@code from} is the face toward node A, {@code to} is the face toward node B.
 * When fluid flows A→B: from=inflow face, to=outflow face.
 * When fluid flows B→A: to=inflow face, from=outflow face.
 */
public record PipeEntry(BlockPos pos, Direction from, Direction to) {}
