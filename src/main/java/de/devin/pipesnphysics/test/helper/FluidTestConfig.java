package de.devin.pipesnphysics.test.helper;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Simple testing setup, requires a powered pipe network or gravity falls (Bill?).
 *
 * @param source
 * @param sink
 * @param ticksToWait
 */
public record FluidTestConfig(
        BlockPos source,
        BlockPos sink,
        int ticksToWait,
        @Nullable PipeConfig pipeConfig
) {
}


