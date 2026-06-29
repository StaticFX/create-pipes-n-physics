package de.devin.pipesnphysics.mixin;

/** Shared tuning for the pipe-render mixins. */
final class CreatePipeRenderConstants {
    /**
     * How far (in blocks) a fluid front is pulled back from an open/dead pipe mouth, replacing
     * Create's hairline {@code 1e-6}. Half a pipe-cell maps the surface to {@code 1 − INSET·0.5}
     * of the cell, so {@code 0.125} leaves a ~1px gap off the rim — enough to kill the z-fight
     * without the fluid visibly receding.
     */
    static final float OPEN_END_INSET = 0.125f;

    private CreatePipeRenderConstants() {}
}
