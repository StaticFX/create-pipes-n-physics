#include "pipesnphysics:tank_wave.glsl"

// ONE fragment shader for both meshes, because both are the same two questions asked per PIXEL: is this
// point inside the tank's box, and is it under the water. The surface lies exactly ON the wave field and
// overhangs the box, so the box test cuts it; the wall box lies exactly INSIDE the box and stands through
// the water, so the wave test cuts it. Neither needs geometry the other doesn't, which is why the CPU
// renderer's wall skirts and clipped cells have no counterpart here.

flat in vec3 pnp_boxCenter;
flat in vec3 pnp_boxHalf;
flat in vec3 pnp_planeNormal;
flat in float pnp_planeOffset;
flat in vec4 pnp_sprite;
flat in float pnp_debug;
flat in vec2 pnp_slosh;
flat in vec2 pnp_centreUv;
flat in float pnp_waveAmp;
flat in float pnp_heave;
flat in float pnp_extent;
flat in float pnp_stir;
flat in float pnp_cutAtWater;
in vec3 pnp_worldPos;
in vec2 pnp_planeUv;
in vec2 pnp_texUv;

void flw_materialFragment() {
    vec3 local = pnp_worldPos - pnp_boxCenter;
    bool outsideBox = any(greaterThan(abs(local), pnp_boxHalf));

    // Walls are cut by the SAME height field the surface mesh was displaced by, so a wall ends exactly
    // where the water it holds actually sits, a flat cut here would show as a straight waterline against
    // the glass with a rippling surface floating above it.
    //
    // The surface itself is exempt, and that is not an optimisation. It approximates the field with flat
    // quads between displaced vertices, so across a trough its chord rises ABOVE the curve it is meant to
    // BE, by about twice any slack worth calling slack, and testing it against the exact field punches
    // holes through every trough that crawl about as the waves move.
    bool aboveWater = false;
    if (pnp_cutAtWater > 0.5) {
        float waterline = pnp_planeOffset
                + pnp_waveHeight(pnp_planeUv, pnp_centreUv, pnp_slosh, pnp_waveAmp, pnp_heave, pnp_extent, pnp_stir);
        aboveWater = dot(pnp_planeNormal, local) - waterline > 0.0;
    }
    bool outside = outsideBox || aboveWater;

    // Diagnosis mode paints the test instead of obeying it, so the ways this can come out blank stop
    // looking alike: RED means the geometry reached the screen and was rejected, GREEN means it was kept,
    // and nothing at all means no geometry ever landed, a different problem entirely.
    if (pnp_debug > 0.5) {
        flw_fragColor = outside ? vec4(1.0, 0.0, 0.0, 0.4) : vec4(0.0, 1.0, 0.0, 0.9);
        return;
    }

    // Zero alpha is the discard: the material runs CutoutShaders.EPSILON, which drops it for us.
    if (outside) {
        flw_fragColor.a = 0.0;
        return;
    }

    // Tile the sprite once per block. The wrap has to happen here rather than at the vertices, or the seam
    // between repeats would smear across a whole face instead of landing on a texel boundary.
    vec2 spriteSize = vec2(pnp_sprite.y - pnp_sprite.x, pnp_sprite.w - pnp_sprite.z);
    vec2 uv = mix(pnp_sprite.xz, pnp_sprite.yw, fract(pnp_texUv));
    // Derivatives taken from the UNWRAPPED coordinate: fract() jumps by a whole texture at the wrap, and
    // reading the gradient there would pick the smallest mip and draw a seam of mush.
    vec2 dUvDx = dFdx(pnp_texUv) * spriteSize;
    vec2 dUvDy = dFdy(pnp_texUv) * spriteSize;

    flw_fragColor = flw_vertexColor * textureGrad(flw_diffuseTex, uv, dUvDx, dUvDy);
}
