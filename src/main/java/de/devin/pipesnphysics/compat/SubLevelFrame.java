package de.devin.pipesnphysics.compat;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * How one sub-level's PLOT coordinates map onto the screen for a single frame, flattened to plain
 * math so render code can place plot-coordinate geometry where the contraption is actually drawn
 * without touching Sable's own types. Mirrors Sable's pose transform exactly:
 * {@code world = position + orientation · (scale · (plot − rotationPoint))}.
 */
public record SubLevelFrame(Vector3d position, Quaterniond orientation, Vector3d scale,
                            Vector3d rotationPoint, double extentBlocks) {
    /**
     * How far a sweep has to look around a point inside this contraption to have covered it: half
     * the diagonal of its bounds. A renderer scanning chunks pays per chunk it asks for, and a
     * contraption is a few chunks across — sweeping the world's whole view distance around each one
     * would cost far more than the ships are worth.
     */
    public int chunkRadius() {
        return (int) Math.ceil(extentBlocks / 16.0);
    }

    /** Cosine of the frame's tilt — 1 while the contraption's own up still points at world up. */
    public double upProjection() {
        return orientation.transform(new Vector3d(0, 1, 0)).y;
    }

    /**
     * Whether plot Y is still a world elevation. It is not on a tilted contraption, and anything
     * that reasons about height in raw block coordinates (a cut plane, a climb test) is meaningless
     * there — the caller must fall back to whole-cell work instead.
     */
    public boolean upright() {
        return upProjection() > 0.9999;
    }

    /** The world position a plot position is drawn at. */
    public Vec3 project(double x, double y, double z) {
        Vector3d local = new Vector3d(x, y, z).sub(rotationPoint).mul(scale);
        Vector3d world = orientation.transform(local).add(position);
        return new Vec3(world.x, world.y, world.z);
    }

    /** The plot position a world position falls on — the inverse of {@link #project}. */
    public Vec3 unproject(Vec3 world) {
        Vector3d local = new Vector3d(world.x, world.y, world.z).sub(position);
        orientation.transformInverse(local)
                .div(scale)
                .add(rotationPoint);
        return new Vec3(local.x, local.y, local.z);
    }
}
