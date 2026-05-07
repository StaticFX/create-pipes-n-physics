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
    /**
     * Angle-based strategy with pressure propagation.
     *
     * <p>Downward/angled pipes generate pressure from sin(elevation) × maxPressure.
     * Horizontal pipes don't generate pressure themselves, but CARRY accumulated
     * pressure from upstream drops, losing some to friction per segment.</p>
     *
     * <p>This models real fluid physics: a 10-block vertical drop builds up pressure,
     * and that pressure pushes fluid into horizontal branches at the bottom.
     * Friction eventually stops the flow after enough horizontal distance.</p>
     */
    /**
     * Angle-based strategy with separate flow rate and range caps.
     *
     * <p>Vertical/angled pipes always flow at sin(elevation) × maxPressure / 2 mB/t.
     * A 1-block vertical drop gives 10 mB/t (full flow). Horizontal branches receive
     * carried pressure from upstream drops, capped at rangeCap, and lose friction per block.
     * This separates "how fast" (maxPressure) from "how far" (rangeCap).</p>
     */
    class AngleBased implements GravityFlowStrategy {
        private final float gravityPerBlock;
        private final float frictionPerBlock;
        private final float maxPressure;  // caps flow rate (20 → 10 mB/t)
        private final float rangeCap;     // caps horizontal propagation (maxRange × friction)
        private final float deadZone;

        public AngleBased(float gravityPerBlock, float frictionPerBlock, float maxPressure,
                          float rangeCap, float deadZone) {
            this.gravityPerBlock = gravityPerBlock;
            this.frictionPerBlock = frictionPerBlock;
            this.maxPressure = maxPressure;
            this.rangeCap = rangeCap;
            this.deadZone = deadZone;
        }

        @Override
        public float computeSinkPressure(Level level, double sourceWorldY, BlockPos sinkPipePos,
                                          int pathLength, Direction parentDir) {
            float elevation = SableCompat.getPipeElevation(level, sinkPipePos, parentDir);
            if (elevation >= deadZone) {
                // Angled sink: full pressure from angle
                return (float) Math.sin(Math.toRadians(elevation)) * maxPressure;
            }
            // Horizontal sink: use height-based check with range cap
            double sinkWorldY = SableCompat.getWorldY(level, sinkPipePos);
            float heightDiff = (float) (sourceWorldY - sinkWorldY);
            if (heightDiff < deadZone) return 0;
            return Math.min(heightDiff * gravityPerBlock, rangeCap) - pathLength * frictionPerBlock;
        }

        @Override
        public float computeNodePressure(Level level, double sourceWorldY, BlockPos nodePos,
                                          int pathLength, Direction parentDir, float carriedPressure) {
            float elevation = SableCompat.getPipeElevation(level, nodePos, parentDir);

            if (elevation >= deadZone) {
                // Flow rate limited by BOTH the pipe's own angle and available pressure.
                // A 45° pipe can't flow as fast as a 90° pipe even with max pressure available.
                float anglePressure = (float) Math.sin(Math.toRadians(elevation)) * maxPressure;
                return Math.min(anglePressure, carriedPressure);
            }

            // Horizontal pipe: carries pressure from upstream, friction reduces it per block.
            // No artificial cap here — friction naturally limits range.
            if (carriedPressure > frictionPerBlock) {
                return carriedPressure - frictionPerBlock;
            }
            return 0;
        }

        @Override
        public float computeSegmentContribution(Level level, BlockPos fromPos, BlockPos toPos,
                                                 Direction direction, float parentPressure) {
            float elevation = SableCompat.getPipeElevation(level, fromPos, direction);

            if (elevation >= deadZone) {
                // Downward segment: accumulate pressure from the drop
                float gain = (float) Math.sin(Math.toRadians(elevation)) * gravityPerBlock;
                return Math.min(parentPressure + gain, maxPressure);
            }

            // Horizontal/upward: pass through (friction applied in computeNodePressure)
            return parentPressure;
        }
    }
}
