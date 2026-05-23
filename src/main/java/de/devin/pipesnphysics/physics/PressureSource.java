package de.devin.pipesnphysics.physics;

/**
 * A source of pressure in a pipe network.
 * Can be a gravity source (tank above pipes), pump push, or pump pull.
 *
 * @param node the node where this source injects pressure
 * @param kind what type of source this is
 * @param basePressure initial pressure provided by this source
 * @param worldY world-space Y of the source (tank surface or pump position)
 */
public record PressureSource(NodeId node, Kind kind, float basePressure, double worldY) {

    public enum Kind {
        GRAVITY,
        PUMP_PUSH,
        PUMP_PULL
    }
}
