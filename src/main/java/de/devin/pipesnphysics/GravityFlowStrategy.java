package de.devin.pipesnphysics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Strategy for computing gravity-driven fluid pressure in pipe networks.
 */
public interface GravityFlowStrategy {

    /** Compute pressure at a sink pipe to check if flow is possible. */
    float computeSinkPressure(Level level, double sourceWorldY, BlockPos sinkPipePos,
                              int pathLength, Direction parentDir);

    /** Compute effective pressure at a pipe node during BFS propagation. */
    float computeNodePressure(Level level, double sourceWorldY, BlockPos nodePos,
                              int pathLength, Direction parentDir, float carriedPressure);

    /** Compute the pressure contribution when propagating from parent to child. */
    float computeSegmentContribution(Level level, BlockPos fromPos, BlockPos toPos,
                                      Direction direction, float parentPressure);

    /**
     * Height-based strategy: uses world-space Y difference.
     * Works for all pipes, doesn't need Sable.
     */
    class HeightBased implements GravityFlowStrategy {
        private final float gravityPerBlock;
        private final float frictionPerBlock;
        private final float maxPressure;
        private final float deadZone;

        public HeightBased(float gravityPerBlock, float frictionPerBlock, float maxPressure, float deadZone) {
            this.gravityPerBlock = gravityPerBlock;
            this.frictionPerBlock = frictionPerBlock;
            this.maxPressure = maxPressure;
            this.deadZone = deadZone;
        }

        @Override
        public float computeSinkPressure(Level level, double sourceWorldY, BlockPos sinkPipePos,
                                          int pathLength, Direction parentDir) {
            double pipeWorldY = SableCompat.getWorldY(level, sinkPipePos);
            float heightDiff = (float) (sourceWorldY - pipeWorldY);
            if (Math.abs(heightDiff) < deadZone) return 0;
            float localHead = heightDiff * gravityPerBlock;
            return localHead - pathLength * frictionPerBlock;
        }

        @Override
        public float computeNodePressure(Level level, double sourceWorldY, BlockPos nodePos,
                                          int pathLength, Direction parentDir, float carriedPressure) {
            double nodeWorldY = SableCompat.getWorldY(level, nodePos);
            float localHead = (float) (sourceWorldY - nodeWorldY) * gravityPerBlock
                    - pathLength * frictionPerBlock;
            return Math.min(Math.min(localHead, carriedPressure), maxPressure);
        }

        @Override
        public float computeSegmentContribution(Level level, BlockPos fromPos, BlockPos toPos,
                                                 Direction direction, float parentPressure) {
            return parentPressure; // height-based doesn't modify per-segment
        }
    }

    /**
     * Angle-based strategy: pressure = sin(pipe elevation) × maxPressure.
     * For Sable sub-levels where pipes rotate with the structure.
     * Each pipe gets pressure directly from its own angle — no accumulation needed.
     * 0° = 0 flow, 90° = max flow.
     */
    class AngleBased implements GravityFlowStrategy {
        private final float maxPressure;
        private final float deadZone;

        public AngleBased(float gravityPerBlock, float frictionPerBlock, float maxPressure, float deadZone) {
            this.maxPressure = maxPressure;
            this.deadZone = deadZone;
        }

        @Override
        public float computeSinkPressure(Level level, double sourceWorldY, BlockPos sinkPipePos,
                                          int pathLength, Direction parentDir) {
            float elevation = SableCompat.getPipeElevation(level, sinkPipePos, parentDir);
            if (elevation < deadZone) return 0; // dead zone matches computeNodePressure
            return (float) Math.sin(Math.toRadians(elevation)) * maxPressure;
        }

        @Override
        public float computeNodePressure(Level level, double sourceWorldY, BlockPos nodePos,
                                          int pathLength, Direction parentDir, float carriedPressure) {
            // Pressure = sin(this pipe's elevation) × maxPressure
            float elevation = SableCompat.getPipeElevation(level, nodePos, parentDir);
            // Dead zone: no flow below 1 degree (prevents phantom flow on flat pipes)
            if (elevation < deadZone) return 0;
            float pressure = (float) Math.sin(Math.toRadians(elevation)) * maxPressure;
            // Still respect branch splitting from carried pressure
            return Math.min(pressure, carriedPressure);
        }

        @Override
        public float computeSegmentContribution(Level level, BlockPos fromPos, BlockPos toPos,
                                                 Direction direction, float parentPressure) {
            // Pass through — no per-segment accumulation
            return parentPressure;
        }
    }
}
