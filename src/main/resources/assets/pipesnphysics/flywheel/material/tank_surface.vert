// The fluid's TOP: a grid swung into the plane, blown out past the box's corners so the fragment shader
// can cut the true silhouette out of it, and displaced by the wave field. The grid exists ONLY to carry
// that displacement, the silhouette is still per-pixel, so its resolution sets how finely the water can
// ripple and has nothing to do with how cleanly it meets the glass.

void flw_materialVertex() {
    // The mesh's xz is read as a coordinate PAIR in the plane, not as a position.
    vec2 corner = flw_vertexPos.xz * pnp_radius;
    vec3 flat_pos = pnp_boxCenter + pnp_planeNormal * pnp_planeOffset
            + pnp_tangent * corner.x + pnp_bitangent * corner.y;

    vec2 planeUv = pnp_toPlane(flat_pos);
    float height = pnp_waveHeight(planeUv, pnp_centreUv, pnp_slosh, pnp_waveAmp, pnp_heave, pnp_extent, pnp_stir);
    vec3 pos = flat_pos + pnp_planeNormal * height;

    // Tilt the normal with the water so the light moves with the ripples. The gradient is taken from the
    // same field rather than the mesh, so it stays right however coarse the grid is.
    float step = 0.15;
    float dU = pnp_waveHeight(planeUv + vec2(step, 0.0), pnp_centreUv, pnp_slosh, pnp_waveAmp,
            pnp_heave, pnp_extent, pnp_stir) - height;
    float dV = pnp_waveHeight(planeUv + vec2(0.0, step), pnp_centreUv, pnp_slosh, pnp_waveAmp,
            pnp_heave, pnp_extent, pnp_stir) - height;

    flw_vertexPos = vec4(pos, 1.0);
    flw_vertexNormal = normalize(pnp_planeNormal * step - pnp_tangent * dU - pnp_bitangent * dV);
    pnp_worldPos = pos;
    pnp_cutAtWater = 0.0;
    pnp_planeUv = planeUv;
    pnp_texUv = pnp_tankLocalUv(pos - pnp_boxCenter, pnp_planeNormal);
}
