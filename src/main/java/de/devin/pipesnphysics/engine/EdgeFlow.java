package de.devin.pipesnphysics.engine;

/**
 * Computed flow state for one edge of a {@link Graph} for one tick.
 *
 * Direction is signed relative to the edge's stored (a, b) order:
 *   A_TO_B  — fluid moves from node a toward node b,
 *   B_TO_A  — fluid moves from node b toward node a,
 *   NONE    — no flow this tick (no driving force, or path blocked).
 *
 * Rate is the absolute flow in millibuckets per tick. NONE flows report rate 0.
 */
public record EdgeFlow(int edgeIndex, Direction direction, int mbPerTick) {
    public enum Direction { A_TO_B, B_TO_A, NONE }

    public static EdgeFlow none(int edgeIndex) {
        return new EdgeFlow(edgeIndex, Direction.NONE, 0);
    }
}
