package de.devin.pipesnphysics;

/**
 * Data records used by FluidTankRendererMixin to pass groups of related values
 * between helper methods. Kept outside the mixin package because Sponge Mixin
 * does not allow inner classes of a mixin to be loaded as standalone classes.
 */
public final class FluidRenderData {

    private FluidRenderData() {}

    /**
     * Grid dimensions for the fluid surface mesh.
     *
     * @param res    number of cells per axis (e.g. 64 → 64×64 grid)
     * @param stride vertices per row = res + 1 (used for 1D array indexing)
     * @param size   total vertex count = stride × stride
     */
    public record GridDims(int res, int stride, int size) {}

    /**
     * Axis-aligned bounding box of the tank interior (inset slightly to avoid z-fighting).
     *
     * @param mins    minimum corner {xMin, yMin, zMin}
     * @param maxs    maximum corner {xMax, yMax, zMax}
     * @param centers center point {cx, cy, cz} — used as the plane's origin
     */
    public record TankBounds(float[] mins, float[] maxs, float[] centers) {}

    /**
     * Describes the fluid surface cutting plane and which axes to use for the grid.
     *
     * <p>The plane is defined by a normal (world "up" in local space) and an offset.
     * The dominant axis is the normal's largest component — the grid is built on the
     * other two axes (axis1, axis2), and the dominant coordinate is solved from the plane equation.</p>
     *
     * @param normal      surface normal {nx, ny, nz} — world "up" transformed into tank-local space
     * @param dominant    index of the normal's largest component (0=X, 1=Y, 2=Z)
     * @param axis1       first grid axis (dominant + 1) % 3
     * @param axis2       second grid axis (dominant + 2) % 3
     * @param planeOffset signed distance offset that makes the plane cut the correct fill volume
     */
    public record SurfacePlane(float[] normal, int dominant, int axis1, int axis2, float planeOffset) {}

    /**
     * Bundles all visual properties needed to render fluid quads.
     *
     * @param nx  surface normal X — world-space "up" projected into local space, used for lighting
     * @param ny  surface normal Y
     * @param nz  surface normal Z
     * @param cr  red   component of the fluid tint color (0–1)
     * @param cg  green component of the fluid tint color (0–1)
     * @param cb  blue  component of the fluid tint color (0–1)
     * @param ca  alpha component of the fluid tint color (0–1, defaults to 0.8 if absent)
     * @param su0 minimum U coordinate from the still-fluid texture atlas sprite
     * @param su1 maximum U coordinate from the still-fluid texture atlas sprite
     * @param sv0 minimum V coordinate from the still-fluid texture atlas sprite
     * @param sv1 maximum V coordinate from the still-fluid texture atlas sprite
     */
    public record FluidStyle(float nx, float ny, float nz,
                             float cr, float cg, float cb, float ca,
                             float su0, float su1, float sv0, float sv1) {}
}
