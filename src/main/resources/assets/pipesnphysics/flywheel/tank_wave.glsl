// The fluid's height field, as a formula.
//
// Included by BOTH the surface vertex shader and the fragment shader, and that sharing is the whole
// design: the surface mesh is displaced by this, and the wall box is CUT by it, so the volume's top edge
// meets the rippling surface exactly instead of the two being reconciled. A wave that only moved the
// surface would leave a flat waterline against the glass, which is precisely where it would be noticed.
//
// It replaces a 65x65 wave-equation grid stepped on the CPU per tank. That grid could not ride in
// instance data at any price, and it did not need to: what the player reads as sloshing is a tilt of the
// surface plus a few travelling ripples, and both are cheaper to state than to simulate.

/**
 * Height above the flat plane, at a point given in plane coordinates, for a tank whose slosh (also in
 * plane coordinates, relative to its centre), ripple amplitude, heave and extent are as given.
 *
 * @param extent how far the tank reaches from its centre, so the splash bowl fits it whatever its size.
 * @param stir 0 sitting still, 1 thrown about. Decides how much of the ripple is fine chop.
 */
float pnp_waveHeight(vec2 point, vec2 centre, vec2 slosh, float amplitude, float heave,
        float extent, float stir) {
    vec2 fromCentre = point - centre;

    // The slosh: the surface tilts about the tank's centre, which conserves its volume exactly: the
    // fluid that leaves one side arrives at the other.
    float height = dot(fromCentre, slosh);

    // The splash: water climbing the walls as the tank lands under it, and doming up as it drops away.
    // A tilt cannot express this, because motion ALONG the surface normal tilts nothing, which is why a
    // falling tank showed no slosh at all. Mean of r squared over a disc is a half, so subtracting it
    // leaves the bowl volume-neutral and the waterline does not jump.
    float r = length(fromCentre) / max(0.001, extent);
    height += heave * (min(r * r, 1.5) - 0.5);

    // Five travelling ripples: rising frequency, falling amplitude, incommensurate angles and speeds, so
    // they never visibly repeat and the fine detail rides on the swell. The octaves are where "wavy"
    // actually comes from; one or two sines read as a slow heave however tall you make them.
    //
    // The canonical model for water is Gerstner (trochoidal) waves, which bunch points toward the crests
    // to get sharp peaks and broad flat troughs. Deliberately NOT used: Gerstner displaces horizontally,
    // so the surface stops being a height field, and the height field is the whole reason the walls can be
    // cut by the same formula the surface is displaced by. Octaves are the part that carries over.
    float time = flw_renderTicks + flw_partialTick;

    // The FINE octaves are weighted by the agitation, not just the overall height. Chop is something
    // agitation MAKES: still water keeps its slow swell and little else. Scaling every octave together
    // instead just makes the chop smaller, which still reads as choppy, only further away.
    //
    // Note these weight the AMPLITUDES and never the time coefficients. Changing a rate inside sin(t * r)
    // teleports the phase, so the surface would jump every time the ship's motion changed.
    float w0 = 1.0;
    float w1 = mix(0.55, 0.72, stir);
    float w2 = mix(0.20, 0.50, stir);
    float w3 = mix(0.06, 0.31, stir);
    float w4 = mix(0.02, 0.18, stir);

    float ripple = sin(dot(fromCentre, vec2(1.0, 0.31)) * 2.7 + time * 0.31) * w0
            + sin(dot(fromCentre, vec2(-0.42, 1.0)) * 3.9 - time * 0.27) * w1
            + sin(dot(fromCentre, vec2(0.77, -0.63)) * 6.1 + time * 0.44) * w2
            + sin(dot(fromCentre, vec2(-0.85, -0.53)) * 9.7 + time * 0.61) * w3
            + sin(dot(fromCentre, vec2(0.36, 0.93)) * 11.3 - time * 0.83) * w4;

    // Normalised by the weights actually used, so the octaves sum to the height asked for whatever the
    // mix. The height itself already carries the still-to-stirred envelope, which is set in Java.
    return height + ripple * amplitude / (w0 + w1 + w2 + w3 + w4);
}

/**
 * Texture coordinates for a point given RELATIVE TO THE TANK'S CENTRE, laid out on the two axes the
 * given normal does not span.
 *
 * Tank-relative on purpose, twice over. The water is carried by the tank, so a texture laid out in the
 * world (or in the world-locked plane basis the waves use) scrolls across the surface the moment the ship
 * moves. And measuring from the centre rather than from the origin keeps it clear of the render origin,
 * which Flywheel is free to shift under us.
 */
vec2 pnp_tankLocalUv(vec3 relative, vec3 normal) {
    vec3 facing = abs(normal);
    return facing.x > 0.5 ? relative.zy : (facing.y > 0.5 ? relative.xz : relative.xy);
}
