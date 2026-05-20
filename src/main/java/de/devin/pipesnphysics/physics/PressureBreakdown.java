package de.devin.pipesnphysics.physics;

public record PressureBreakdown(
        float head,
        float friction,
        float net,
        boolean capped,
        float localPressure
) {
    public PressureBreakdown(float head, float friction, float net, boolean capped) {
        this(head, friction, net, capped, net);
    }
}
