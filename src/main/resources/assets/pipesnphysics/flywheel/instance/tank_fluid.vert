#include "pipesnphysics:tank_wave.glsl"

// The common half of a tank's fluid, shared by both of its meshes: the surface and the box whose
// submerged part is the volume under it. Everything here is what the two agree on, the box, the plane,
// the slosh, a basis to lay texture out in, and each material vertex shader then places its OWN mesh.
//
// The varyings are declared HERE rather than in the material shaders because Flywheel concatenates the
// instance shader FIRST, and their values come from FlwInstance, which only this stage can see. The
// material shaders (which come after, in the same compilation unit) write the ones that differ per mesh
// and read the plain globals below.

flat out vec3 pnp_boxCenter;
flat out vec3 pnp_boxHalf;
flat out vec3 pnp_planeNormal;
flat out float pnp_planeOffset;
flat out vec4 pnp_sprite;
flat out float pnp_debug;
// The slosh and the tank's centre, both in plane coordinates, and the ripple height, everything
// pnp_waveHeight needs, so the fragment stage can evaluate the same surface the vertex stage displaced.
flat out vec2 pnp_slosh;
flat out vec2 pnp_centreUv;
flat out float pnp_waveAmp;
flat out float pnp_heave;
flat out float pnp_extent;
flat out float pnp_stir;
// Whether this mesh is CUT by the water or IS the water. The surface must never be water-tested: it
// interpolates linearly between displaced vertices while the test evaluates the field exactly, so across
// every trough the chord sits above the curve and the surface would be punched full of moving holes.
flat out float pnp_cutAtWater;
out vec3 pnp_worldPos;
// Where this fragment sits IN THE PLANE, for the wave field, whose basis is world-locked so the waves do
// not spin with the hull. Distinct from pnp_texUv, which is laid out in the TANK's frame: the water is
// carried by the tank, so its texture has to travel with it or it scrolls whenever the ship moves.
out vec2 pnp_planeUv;
out vec2 pnp_texUv;

// Not varyings, vertex-stage scratch the material shaders read back.
vec3 pnp_tangent;
vec3 pnp_bitangent;
float pnp_radius;

/** Where a point lands in the plane's own two axes. */
vec2 pnp_toPlane(vec3 point) {
    return vec2(dot(point, pnp_tangent), dot(point, pnp_bitangent));
}

void flw_instanceVertex(in FlwInstance i) {
    vec3 n = normalize(i.normal);

    // World X in this tank's frame, handed to us rather than picked off the normal. A basis chosen here
    // could only be built from n, and every such basis turns with the ship (so the texture and the waves
    // swim as it turns) and snaps wherever the choice of seed axis flips. The pose gives a world-locked
    // one for nothing, and world X is perpendicular to world up whatever the ship is doing.
    pnp_tangent = normalize(i.tangent);
    pnp_bitangent = cross(n, pnp_tangent);
    // The box's own diagonal is the shortest radius covering it whatever way the plane is tilted.
    pnp_radius = length(i.halfExtent);

    flw_vertexColor *= i.color;
    // Some drivers reject uint over float division, so cast explicitly, as Flywheel's own types do.
    flw_vertexLight = max(vec2(i.light) / 256.0, flw_vertexLight);

    pnp_boxCenter = i.center;
    pnp_boxHalf = i.halfExtent;
    pnp_planeNormal = n;
    pnp_planeOffset = i.planeOffset;
    pnp_sprite = i.uv;
    pnp_debug = i.debug;
    // The slosh arrives as a vector in the tank's own frame, so that Java never has to reproduce this
    // basis and the two can never disagree about which way the water is leaning.
    pnp_slosh = pnp_toPlane(i.slosh);
    pnp_centreUv = pnp_toPlane(i.center);
    pnp_waveAmp = i.waveAmp;
    pnp_heave = i.heave;
    pnp_extent = pnp_radius;
    pnp_stir = i.stir;
}
