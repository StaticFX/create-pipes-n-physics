package de.devin.pipesnphysics.client.render.flywheel;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.util.Mth;
import org.joml.Vector3d;

/**
 * How hard a tank's water is leaning, and which way.
 *
 * This is what a 65x65 wave-equation grid stepped at 20 Hz per tank collapses to once the surface is a
 * formula rather than a mesh: the grid's whole visible output was a tilt plus some ripple, and the tilt is
 * the only part that needs to remember anything between frames. Everything else the shader states
 * directly. The vector is kept in the TANK's own frame so nothing here has to reproduce the plane basis
 * the shader builds. That basis exists in one place, and the two cannot drift.
 *
 * Fluid lags what carries it: the tank accelerates and the water piles up behind, then swings back and
 * settles. Sable exposes no client-side velocity, so that has to be read off the pose, and reading it
 * BADLY is what made the water shake while the ship moved and sit still the moment it stopped. Two rules
 * keep it steady, and both were learned the hard way:
 *
 * <ul>
 *   <li>Differentiate ONCE. Position sampled per frame and divided by a wall clock is already noisy, and
 *       differencing two such velocities squares that noise. At 60 fps it dwarfs the signal. The surge
 *       below is the departure of velocity from its own recent average, which is an acceleration in every
 *       way that matters here and needs no second division.</li>
 *   <li>Aim, do not accumulate. The lean chases a TARGET set by the surge, so it is bounded by
 *       construction and settles on its own. An accumulating impulse has to be scaled by the frame time
 *       to mean anything, and gets the timestep wrong sooner or later.</li>
 * </ul>
 *
 * OWNED by one visual and never shared. Flywheel states plainly that it is free to run beginFrame on
 * several worker threads at once, so a static map keyed by position would be read-modify-written
 * concurrently, and a HashMap resized under that hands back garbage. Per-tank state belongs to the
 * per-tank object; then there is nothing to synchronise and nothing to prune.
 */
final class TankSlosh {
    /**
     * Lean per m/s that the tank's velocity departs from its recent average. Sized against a ship pulling
     * away at 6 blocks per second squared, which tilts the surface about three quarters of the way to its
     * stop rather than pinning against it. Sized for the memory below: shorten that and this must rise.
     */
    private static final double LEAN_PER_SURGE = 0.40;
    /**
     * Heave per m/s of VERTICAL surge. A lean can only ever express motion ACROSS the surface, since a
     * push along its normal tilts nothing, so a tank dropping or landing had no effect at all until this
     * existed. It is the splash: water climbing the walls as the tank stops under it.
     *
     * Far smaller than the lean because a fall is far more violent than anything a ship does sideways: at
     * the lean's own gain a two-block drop drove this past 1.0 against a stop of 0.35, so it spent the
     * whole landing pinned there. Stepped offline against that drop, this peaks around 0.16, under half
     * the range, leaving somewhere to go for a harder one.
     */
    private static final double HEAVE_PER_SURGE = 0.031;
    /** Lean per radian/s of roll: rolling a tank drags its surface across it. */
    private static final double LEAN_PER_ROLL = 0.25;
    /**
     * Gravity, in blocks per second squared, as the sloshing period is worked out from. Picked for how it
     * looks rather than claimed to be Minecraft's: the formula it feeds is the real one, the constant is a
     * dial. Larger swings the water back and forth faster.
     */
    private static final double SLOSH_GRAVITY = 12.0;
    /**
     * Damping ratio of the sloshing mode. Well under 1 so the water visibly swings back and forth rather
     * than easing into place, which is the difference between water and treacle. Stepped offline against
     * a shove-and-release: this rings clearly and is quiet again after about 2.5 seconds in a small tank
     * and 4 in a large one. Half this and a big tank was still moving 10 seconds later, which reads as
     * jelly. Scaled up by viscosity, so lava reaches the critical 1.0 and does not ring at all.
     */
    private static final double SLOSH_DAMPING = 0.30;
    /**
     * The splash breathes far faster than the tank sloshes end to end. This is the LAG knob: a spring
     * cannot answer sooner than its own quarter period, so once the velocity memory is short this is all
     * that is left. Swept offline against a two-block drop, the splash arrives 0.20s after touchdown at
     * 2.4, 0.12s at 3.2, and 0.08s at 5. Five frames reads as immediate; anything slower reads as the
     * water waiting its turn. A splash is a quick thing and does not want the long ring the slosh has.
     */
    private static final double HEAVE_FREQUENCY_RATIO = 5.0;
    /**
     * How much of the splash answers AT ONCE rather than through the spring.
     *
     * A spring cannot respond instantly to a force, ever: it has to build rate before it has displacement,
     * so its first move is always a quarter period away. Stiffening it only shortens that quarter period,
     * which is why chasing the delay through the frequency alone bottomed out around five frames.
     * Splitting the answer fixes it properly. This share tracks the surge directly and so lands on the
     * same frame as the impact; the spring chases what is left, which is what still gives the water its
     * ring. Measured against a two-block drop: the splash shows 0.017s after touchdown here, against
     * 0.083s with the spring carrying all of it, and 0.33s where this started.
     *
     * Deliberately NOT applied to the lean, which is approved as it stands and where a tilt lagging its
     * cause slightly is the right look. The mechanism is here if that ever wants sharpening too.
     */
    private static final double HEAVE_DIRECT_SHARE = 0.6;
    /**
     * How much velocity history the surge is measured against, and so most of how late the water reacts:
     * a surge cannot be seen until the mean has had time to fall behind. Was 0.20, which put the splash a
     * third of a second behind the landing, long enough to read as a delay rather than as water taking a
     * moment to pile up. At 0.04 it is about two or three frames of averaging, which is close to the floor
     * of what is still an average rather than a difference of two frames, and the rest of the lag then
     * belongs to the springs. Note it scales every surge, so shortening it weakens the lean as much as the
     * splash, and BOTH gains above are sized for THIS value.
     */
    private static final double VELOCITY_MEMORY_SECONDS = 0.04;
    /** Beyond this the tilt reads as a tank being poured out rather than sloshing. */
    private static final double MAX_LEAN = 0.35;
    /** The lean at which the water counts as fully stirred up. */
    private static final double STIR_AT_LEAN = 0.17;

    /**
     * Frame steps outside this are not measurements. Below it, two frames landed inside one clock tick and
     * the division means nothing; above it, the game was paused or stalled and what looks like motion is a
     * teleport. Either way the frame re-seeds and drives nothing.
     */
    private static final double MIN_STEP = 0.002, MAX_STEP = 0.25;

    private final Vector3d lean = new Vector3d();
    /** How fast the lean is changing. A spring needs its velocity; a lag did not, which is why it could
     *  only ever ease toward its target and never swing past it. */
    private final Vector3d leanRate = new Vector3d();
    private double heave = 0;
    private double heaveRate = 0;
    /** What the splash is currently being driven toward. Held, because the direct share reads it too. */
    private double heaveDrive = 0;
    private final Vector3d meanVelocity = new Vector3d();
    private final Vector3d lastNormal = new Vector3d();
    private final Vector3d lastPosition = new Vector3d();
    /** Motion samples taken. A surge needs a mean to depart from, so it waits for the third frame. */
    private int samples = 0;
    private long lastNanos = 0;

    /**
     * Advances the lean from this frame's motion. Takes the surface normal (the tank's own up) and the
     * sub-level's pose; a tank standing still on the ground passes a null pose and simply quiets down.
     *
     * @param viscosityRatio 1 for water, 6 for lava. A thick fluid both leans less and settles sooner.
     * @param spanBlocks how far the fluid can travel across the tank, which sets how slowly it sloshes.
     */
    void advance(float[] normal, Pose3dc pose, float viscosityRatio, double spanBlocks) {
        // Monotonic, and fine enough to resolve a frame. currentTimeMillis is neither: it quantises a
        // 16 ms frame to the millisecond, and can step backwards when the clock is corrected.
        long now = System.nanoTime();
        double step = lastNanos == 0 ? 0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;

        Vector3d up = new Vector3d(normal[0], normal[1], normal[2]);
        Vector3d position = pose == null ? null
                : new Vector3d(pose.position().x(), pose.position().y(), pose.position().z());

        if (step >= MIN_STEP && step <= MAX_STEP && samples >= 1) {
            Vector3d target = new Vector3d();
            heaveDrive = 0;
            if (position != null && samples >= 2) {
                Vector3d velocity = new Vector3d(position).sub(lastPosition).div(step);
                Vector3d surge = new Vector3d(velocity).sub(meanVelocity);
                // Into the tank's own frame, which is where the shader reads it, and opposite the surge:
                // the water is what stays behind when the tank sets off.
                Vector3d local = pose.transformNormalInverse(surge, new Vector3d());
                target.fma(-LEAN_PER_SURGE / viscosityRatio, local);
                // The part ALONG the surface normal, which the lean is about to throw away. Positive as
                // the tank decelerates upward (a landing), which is when water climbs the walls.
                heaveDrive = HEAVE_PER_SURGE / viscosityRatio * local.dot(up);
                meanVelocity.lerp(velocity, Math.min(1.0, step / VELOCITY_MEMORY_SECONDS));
            }
            // Rolling the tank drags the surface across it, whether or not the ship is going anywhere.
            target.fma(LEAN_PER_ROLL / viscosityRatio, new Vector3d(up).sub(lastNormal).div(step));

            // A DAMPED SPRING, not a lag toward the target. Water in a tilted tank does not ease into its
            // new angle: it runs downhill, overshoots, comes back, and rings down. A first-order lag can
            // only ever approach, so there was no back and forth in it at all.
            //
            // The frequency is the first sloshing mode of a tank that long, w = sqrt(g * pi / L), so a big
            // tank swings slowly and a small one slops about. Integrated semi-implicitly (rate first, then
            // position from the NEW rate), which stays stable even on the longest frame step allowed here.
            double omega = Math.sqrt(SLOSH_GRAVITY * Math.PI / Math.max(0.5, spanBlocks));
            double damping = 2.0 * Math.min(1.0, SLOSH_DAMPING * viscosityRatio) * omega;

            leanRate.fma(omega * omega * step, new Vector3d(target).sub(lean))
                    .fma(-damping * step, new Vector3d(leanRate));
            lean.fma(step, new Vector3d(leanRate));

            double heaveOmega = omega * HEAVE_FREQUENCY_RATIO;
            // The spring only chases what the direct share does not already cover, so the two together
            // settle on the drive rather than on twice it.
            heaveRate += ((1 - HEAVE_DIRECT_SHARE) * heaveDrive - heave) * heaveOmega * heaveOmega * step
                    - heaveRate * 2.0 * Math.min(1.0, SLOSH_DAMPING * viscosityRatio) * heaveOmega * step;
            heave += heaveRate * step;
        }

        if (position != null) lastPosition.set(position);
        lastNormal.set(up);
        if (samples < 2) samples++;

        // Only the part ACROSS the surface tilts it; a push straight up or down just presses on the floor.
        // The rate is projected too, or the discarded component would keep feeding back in as the tank
        // turns and the surface swung about an axis it is not free to swing about.
        lean.fma(-lean.dot(up), up);
        leanRate.fma(-leanRate.dot(up), up);
        double magnitude = lean.length();
        if (magnitude > MAX_LEAN) {
            lean.mul(MAX_LEAN / magnitude);
            // Stop dead at the stop rather than pressing on into it, so a hard shove does not store up
            // rate that then throws the surface back across the tank.
            if (leanRate.dot(lean) > 0) leanRate.fma(-leanRate.dot(lean) / lean.lengthSquared(), lean);
        }
        if (Math.abs(heave) > MAX_LEAN) {
            heave = Math.copySign(MAX_LEAN, heave);
            heaveRate = 0;
        }
        if (!Double.isFinite(heaveDrive)) heaveDrive = 0;
        if (!Double.isFinite(lean.x + lean.y + lean.z + leanRate.lengthSquared())) {
            lean.zero();
            leanRate.zero();
        }
        if (!Double.isFinite(heave + heaveRate)) {
            heave = 0;
            heaveRate = 0;
        }
    }

    float x() {
        return (float) lean.x;
    }

    float y() {
        return (float) lean.y;
    }

    float z() {
        return (float) lean.z;
    }

    /**
     * How hard the tank is being thrown about: 0 sitting still, 1 in rough handling. Drives how much of
     * the ripple the water gets and how much of THAT is fine chop, because chop is something agitation
     * makes: still water keeps its slow swell and little else.
     */
    float stir() {
        return (float) Math.min(1.0, lean.length() / STIR_AT_LEAN);
    }

    /** How hard the surface is being pressed along its own normal: the splash of a drop or a landing. */
    float heave() {
        return (float) Mth.clamp(heave + HEAVE_DIRECT_SHARE * heaveDrive, -MAX_LEAN, MAX_LEAN);
    }

    /**
     * Ripple height in blocks for a fluid: water moves, honey does not.
     *
     * The old 0.06 came from the CPU renderer's own clamp, but that grid ADDED disturbances on top of it,
     * where here it is the whole ripple. Multiplied through by the quiet-water share it left resting water
     * moving about a tenth of a pixel, which is why it read as far too viscous to be water.
     */
    static float amplitude(float viscosityRatio) {
        return (float) (0.11f / Math.sqrt(Mth.clamp(viscosityRatio, 0.5f, 20f)));
    }
}
