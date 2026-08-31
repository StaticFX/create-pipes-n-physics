// Only the indirect backend runs this. Instancing (which is all macOS can offer, GL 4.1 having no
// compute shaders) culls on the CPU. The quad is grown to the box's diagonal, so a sphere around the
// box at that radius contains every vertex the instance shader can produce.

void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    center = i.center;
    radius = length(i.halfExtent);
}
