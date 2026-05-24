package de.devin.pipesnphysics.test.helper;

import net.minecraft.core.BlockPos;

/**
 * Holds the pipes for a test.
 * @param pipes
 */
public record PipeConfig(
        BlockPos[] pipes
) {}
