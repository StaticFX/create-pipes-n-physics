// The fluid's SIDES and FLOOR: the tank's whole interior box, which the fragment shader then cuts off at
// the waterline. Drawing the box and discarding whatever floats above the water is the same trick as the
// surface in reverse, and it is why there are no wall skirts here, the volume needs no seam with the
// surface, because both are the same wave field on the same numbers.

void flw_materialVertex() {
    // The mesh is a unit box over [-1, 1], so the half-extents scale it to this tank.
    vec3 pos = pnp_boxCenter + flw_vertexPos.xyz * pnp_boxHalf;

    flw_vertexPos = vec4(pos, 1.0);
    pnp_worldPos = pos;
    pnp_cutAtWater = 1.0;
    pnp_planeUv = pnp_toPlane(pos);
    // Tile off the two axes this face actually spans, which is what keeps a wall's texture square instead
    // of smeared along whichever axis it happens to be flat in. The face normal is constant across a box
    // face, so choosing per vertex and choosing per pixel come to the same thing.
    pnp_texUv = pnp_tankLocalUv(pos - pnp_boxCenter, flw_vertexNormal);
}
