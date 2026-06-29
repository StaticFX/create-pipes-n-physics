package de.devin.pipesnphysics.engine;

/**
 * Exposed by Create's fluid valve so the solver can read its scroll-set opening, and a Valve
 * Handle on its shaft can crank it. The shaft still turns the valve; the angle is its position.
 */
public interface ValveThrottle {
    /** Configured opening as a 0..1 factor on the run's conductance; 1 is fully open. */
    float pipesnphysics$valveThrottle();

    /** Wind the opening by {@code delta} degrees (clamped 0–90); a Valve Handle calls this. */
    void pipesnphysics$adjustThrottle(int delta);
}
