package de.devin.pipesnphysics.physics;

/**
 * Live flow state at a pipe node.
 * Populated by the solver after computing gravity or pump flow.
 *
 * @param flowRateMbPerTick transfer rate in millibuckets per tick (pressure / 2)
 * @param inflowFace        face index where fluid enters this node (-1 if none)
 * @param outflowFace       face index where fluid exits this node (-1 if none)
 */
public record FlowState(float flowRateMbPerTick, int inflowFace, int outflowFace) {

    public static final FlowState NONE = new FlowState(0, -1, -1);
}
