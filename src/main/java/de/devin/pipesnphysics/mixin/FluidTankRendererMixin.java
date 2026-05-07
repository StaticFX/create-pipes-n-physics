package de.devin.pipesnphysics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankRenderer;
import com.simibubi.create.content.contraptions.ContraptionWorld;
import de.devin.pipesnphysics.FluidRenderData.FluidStyle;
import de.devin.pipesnphysics.FluidRenderData.GridDims;
import de.devin.pipesnphysics.FluidRenderData.SurfacePlane;
import de.devin.pipesnphysics.FluidRenderData.TankBounds;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders fluid in Create tanks on Sable sub-levels with tilted surface,
 * wave simulation, and proper wall clipping at any rotation.
 */
@Mixin(value = FluidTankRenderer.class, remap = false)
public class FluidTankRendererMixin {

    // --- Constants ---
    @Unique private static final float INSET = 0.003f;
    @Unique private static final float CAP_HEIGHT = 1 / 4f;
    @Unique private static final float HULL_WIDTH = 1 / 16f + 1 / 128f;
    @Unique private static final float PUDDLE_HEIGHT = 1 / 16f;

    /** Box edge pairs for plane-box intersection. */
    @Unique private static final int[][] BOX_EDGES = {
            {0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}
    };

    /** Box faces: corner indices in CCW winding with outward normals.
     *  Corner index = bit0:X bit1:Y bit2:Z (0=min, 1=max) */
    @Unique private static final int[][] BOX_FACES = {
            {0,4,5,1}, {2,3,7,6}, {0,1,3,2}, {4,6,7,5}, {0,2,6,4}, {1,5,7,3}
    };
    @Unique private static final float[][] FACE_NORMALS = {
            {0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}
    };
    @Unique private static final int[][] FACE_UV_AXES = {
            {0,2},{0,2},{0,1},{0,1},{2,1},{2,1}
    };
    @Unique private static final float[][] FACE_DEBUG_COLORS = {
            {1,0.3f,0.3f},{0.3f,1,0.3f},{0.3f,0.3f,1},{1,1,0.3f},{0.3f,1,1},{1,0.3f,1}
    };

    // --- Wave simulation state per tank ---
    @Unique private static final Map<BlockPos, float[]> WAVE_CUR = new HashMap<>();
    @Unique private static final Map<BlockPos, float[]> WAVE_PREV = new HashMap<>();
    @Unique private static final Map<BlockPos, Long> WAVE_TICK = new HashMap<>();
    @Unique private static final Map<BlockPos, float[]> WAVE_LAST_UP = new HashMap<>();
    @Unique private static final Map<BlockPos, float[]> WAVE_LAST_POS = new HashMap<>();
    @Unique private static final Map<BlockPos, float[]> WAVE_LAST_VEL = new HashMap<>();
    @Unique private static final Map<BlockPos, float[]> WAVE_LAST_QUAT = new HashMap<>();
    @Unique private static final Map<BlockPos, Float> SMOOTH_LEVEL = new HashMap<>();

    // ========================================================================
    // Main render method
    // ========================================================================

    @Inject(method = "renderSafe", at = @At("HEAD"), cancellable = true)
    private void renderTiltedFluid(FluidTankBlockEntity be, float partialTicks, PoseStack ms,
                                    MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (!PipesNPhysicsConfig.FLUID_TILT_ENABLED.get()) return;

        FluidTankAccessor acc = (FluidTankAccessor) be;
        if (!be.isController() || !acc.pipesnphysics$isWindow()) return;

        // Skip tanks on Create contraptions — they use a different rendering path
        if (be.getLevel() instanceof ContraptionWorld) return;

        // --- Sable orientation ---
        // Try BlockEntity lookup first, fall back to position-based lookup.
        // The BE lookup can return null on moving physics objects / contraptions.
        ClientSubLevelAccess subLevel = SableCompanion.INSTANCE.getContainingClient(be);
        if (subLevel == null) subLevel = SableCompanion.INSTANCE.getContainingClient(be.getBlockPos());
        if (subLevel == null) return;
        Pose3dc pose = subLevel.renderPose(partialTicks);
        if (pose == null) return;

        Vector3d localUp = pose.transformNormalInverse(new Vector3d(0, 1, 0), new Vector3d());
        double len = localUp.length();
        if (len < 0.001) return;
        localUp.div(len);

        // --- Fluid data ---
        FluidTank tank = acc.pipesnphysics$getTankInventory();
        FluidStack fluidStack = tank.getFluid();
        if (fluidStack.isEmpty()) return;

        int width = acc.pipesnphysics$getWidth();
        int height = acc.pipesnphysics$getHeight();
        float totalHeight = height - 2 * CAP_HEIGHT - PUDDLE_HEIGHT;

        // Read actual fill fraction directly from the tank inventory.
        // Create's LerpedFloat may not tick on Sable sub-levels, causing laggy animation.
        // We handle our own smoothing instead.
        int capacity = tank.getCapacity();
        if (capacity <= 0) return;
        float targetLevel = (float) tank.getFluidAmount() / capacity;
        if (targetLevel < 0.001f) {
            SMOOTH_LEVEL.remove(be.getBlockPos());
            return;
        }

        // Smooth interpolation: 20% per frame → ~90% in 10 frames (~0.17s at 60fps)
        BlockPos levelKey = be.getBlockPos();
        Float prevSmooth = SMOOTH_LEVEL.get(levelKey);
        float level;
        if (prevSmooth == null) {
            level = targetLevel;
        } else {
            level = prevSmooth + (targetLevel - prevSmooth) * 0.2f;
            if (Math.abs(level - targetLevel) < 0.002f) level = targetLevel;
        }
        SMOOTH_LEVEL.put(levelKey, level);

        float clampedLevel = Mth.clamp(level * totalHeight, 0, totalHeight);

        // --- Tank bounds (inset to prevent z-fighting with tank glass) ---
        float xMin = HULL_WIDTH + INSET, xMax = HULL_WIDTH + width - 2 * HULL_WIDTH - INSET;
        float yMin = CAP_HEIGHT + INSET, yMax = CAP_HEIGHT + PUDDLE_HEIGHT + totalHeight - INSET;
        float zMin = HULL_WIDTH + INSET, zMax = HULL_WIDTH + width - 2 * HULL_WIDTH - INSET;
        float[] mins = {xMin, yMin, zMin}, maxs = {xMax, yMax, zMax};
        float cx = (xMin + xMax) / 2f, cy = (yMin + yMax) / 2f, cz = (zMin + zMax) / 2f;
        TankBounds bounds = new TankBounds(mins, maxs, new float[]{cx, cy, cz});

        // --- Surface plane: find where the fluid surface cuts through the tank box ---
        float nxf = (float) localUp.x, nyf = (float) localUp.y, nzf = (float) localUp.z;
        float[] normal = {nxf, nyf, nzf};
        float[][] corners = pipesnphysics$buildCorners(mins, maxs);
        float[] dist = pipesnphysics$computeCornerDistances(corners, localUp, cx, cy, cz);
        float planeOffset = pipesnphysics$findVolumeCorrectOffset(dist, clampedLevel / totalHeight);
        for (int i = 0; i < 8; i++) dist[i] -= planeOffset;

        // If the plane doesn't intersect at least 3 edges, there's no visible surface to render
        List<float[]> intersections = pipesnphysics$findPlaneEdgeIntersections(corners, dist);
        ci.cancel();
        if (intersections.size() < 3) return;

        // --- Grid setup: choose which axes to build the mesh on ---
        int dominant = pipesnphysics$dominantAxis(nxf, nyf, nzf);
        int axis1 = (dominant + 1) % 3, axis2 = (dominant + 2) % 3;
        int gridRes = pipesnphysics$alignedGridRes(mins, maxs, axis1, axis2);
        SurfacePlane plane = new SurfacePlane(normal, dominant, axis1, axis2, planeOffset);
        GridDims grid = new GridDims(gridRes, gridRes + 1, (gridRes + 1) * (gridRes + 1));

        // --- Wave simulation: 2D wave equation for fluid sloshing ---
        boolean wavesEnabled = PipesNPhysicsConfig.FLUID_WAVE_MESH.get();
        // Sub-level world position for inertia sloshing (impact detection)
        float[] subLevelPos = {
                (float) pose.position().x(),
                (float) pose.position().y(),
                (float) pose.position().z()
        };
        float[] waveCur = pipesnphysics$stepWaveSimulation(
                be.getBlockPos().immutable(), grid, wavesEnabled, fluidStack, normal, subLevelPos, pose);

        // --- Build grid: create vertex positions for the surface mesh ---
        float[][] gridRaw = new float[grid.size()][3];
        boolean[] gridOutside = new boolean[grid.size()];
        pipesnphysics$buildGrid(grid, bounds, plane, wavesEnabled, waveCur, gridRaw, gridOutside);

        // --- Fluid appearance ---
        IClientFluidTypeExtensions fluidExt = IClientFluidTypeExtensions.of(fluidStack.getFluid());
        TextureAtlasSprite still = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(fluidExt.getStillTexture(fluidStack));
        int color = fluidExt.getTintColor(fluidStack);
        float cr = ((color >> 16) & 0xFF) / 255f, cg = ((color >> 8) & 0xFF) / 255f;
        float cb = (color & 0xFF) / 255f, ca = ((color >> 24) & 0xFF) / 255f;
        if (ca == 0) ca = 0.8f;
        FluidStyle style = new FluidStyle(nxf, nyf, nzf, cr, cg, cb, ca,
                still.getU0(), still.getU1(), still.getV0(), still.getV1());

        Matrix4f mat = ms.last().pose();

        VertexConsumer qvc = buffer.getBuffer(RenderType.cutoutMipped());

        boolean hideTexture = PipesNPhysicsConfig.FLUID_HIDE_TEXTURE.get();
        if (!hideTexture) {
            // --- Render the fluid surface as a grid mesh ---
            pipesnphysics$renderSurface(qvc, mat, grid, gridRaw, gridOutside, bounds, plane, style);

            // --- Render walls ---
            // Side walls use skirts (follow wave displacement) with vertex clamping.
            // Bottom face uses plane clipping (always flat on the tank floor).
            pipesnphysics$renderWallSkirts(qvc, mat, grid, gridRaw, gridOutside,
                    bounds, plane, style, light);
            pipesnphysics$renderWallFaces(qvc, mat, corners, dist, bounds, plane.dominant(), style, light);
        }

        // --- Level debug: show target vs smoothed level as colored lines ---
        if (PipesNPhysicsConfig.FLUID_DEBUG_RENDER.get()) {
            VertexConsumer dlvc = buffer.getBuffer(RenderType.LINES);
            float targetY = yMin + targetLevel * totalHeight;
            float smoothY = yMin + level * totalHeight;
            // Red line = target level (what the tank actually has)
            dlvc.addVertex(mat, xMin, targetY, zMin).setColor(255, 0, 0, 255).setNormal(0, 1, 0);
            dlvc.addVertex(mat, xMax, targetY, zMax).setColor(255, 0, 0, 255).setNormal(0, 1, 0);
            // Green line = smoothed level (what we render)
            dlvc.addVertex(mat, xMin, smoothY, zMin).setColor(0, 255, 0, 255).setNormal(0, 1, 0);
            dlvc.addVertex(mat, xMax, smoothY, zMax).setColor(0, 255, 0, 255).setNormal(0, 1, 0);
            // Blue line = plane offset position (where binary search puts the surface)
            float planeY = (Math.abs(nyf) > 0.01f) ? cy + planeOffset / nyf : cy;
            dlvc.addVertex(mat, xMin, planeY, zMin).setColor(0, 100, 255, 255).setNormal(0, 1, 0);
            dlvc.addVertex(mat, xMax, planeY, zMax).setColor(0, 100, 255, 255).setNormal(0, 1, 0);
        }

        // --- Debug overlay ---
        if (PipesNPhysicsConfig.FLUID_DEBUG_RENDER.get()) {
            VertexConsumer lvc = buffer.getBuffer(RenderType.LINES);

            if (hideTexture) {
                // Full wireframe mode: surface grid + wall skirts + bottom face edges
                pipesnphysics$renderDebugWireframe(lvc, mat, grid, gridRaw, gridOutside,
                        bounds, plane, corners, dist);
            } else {
                // Surface grid overlay only
                float[][] gridClamped = new float[grid.size()][3];
                for (int i = 0; i < grid.size(); i++) {
                    gridClamped[i] = new float[]{
                        Mth.clamp(gridRaw[i][0], xMin, xMax),
                        Mth.clamp(gridRaw[i][1], yMin, yMax),
                        Mth.clamp(gridRaw[i][2], zMin, zMax)
                    };
                }
                pipesnphysics$renderDebug(lvc, mat, grid, gridClamped, gridOutside, corners, dist);
            }
        }
    }

    // Geometry helpers

    /**
     * Builds the 8 corners of the tank interior box.
     * Corner index uses bit encoding: bit0 = X (0=min,1=max), bit1 = Y, bit2 = Z.
     * So corner 0 = (minX,minY,minZ), corner 5 = (maxX,minY,maxZ), corner 7 = (maxX,maxY,maxZ), etc.
     */
    @Unique
    private static float[][] pipesnphysics$buildCorners(float[] mins, float[] maxs) {
        return new float[][] {
                {mins[0], mins[1], mins[2]},  // 0: ---
                {maxs[0], mins[1], mins[2]},  // 1: X--
                {mins[0], maxs[1], mins[2]},  // 2: -Y-
                {maxs[0], maxs[1], mins[2]},  // 3: XY-
                {mins[0], mins[1], maxs[2]},  // 4: --Z
                {maxs[0], mins[1], maxs[2]},  // 5: X-Z
                {mins[0], maxs[1], maxs[2]},  // 6: -YZ
                {maxs[0], maxs[1], maxs[2]}   // 7: XYZ
        };
    }

    /**
     * Computes the signed distance from each box corner to the fluid surface plane.
     * The plane passes through the tank center with normal = localUp.
     * Positive = above the surface (air), negative = below (submerged).
     */
    @Unique
    private static float[] pipesnphysics$computeCornerDistances(float[][] corners, Vector3d localUp,
                                                                 float cx, float cy, float cz) {
        float[] dist = new float[8];
        for (int i = 0; i < 8; i++) {
            // dot(normal, corner - center) = signed distance from center plane
            dist[i] = (float) (localUp.x * (corners[i][0] - cx)
                    + localUp.y * (corners[i][1] - cy)
                    + localUp.z * (corners[i][2] - cz));
        }
        return dist;
    }

    /**
     * Binary-searches for the plane offset that makes the submerged volume match the fill fraction.
     *
     * <p>Uses a continuous volume estimation: instead of counting discrete samples above/below
     * the plane (which creates ~1.5% steps), each sample contributes a smooth 0–1 value based
     * on its distance from the plane. This makes the volume function continuous, so the binary
     * search can converge to any fill fraction without snapping.</p>
     *
     * @param dist         signed distances at the 8 box corners (from computeCornerDistances)
     * @param fillFraction how full the tank is (0 = empty, 1 = full)
     * @return plane offset that produces the correct fill volume
     */
    /**
     * Finds the plane offset for a given fill fraction using sorted corner distances.
     *
     * <p>The volume below a plane sweeping through a box is a monotonically increasing
     * function of the offset. By sorting the 8 corner distances, we get the breakpoints
     * where corners transition from above to below the plane. Between breakpoints,
     * the volume is approximated as linear, giving a piecewise-linear volume curve
     * with 8 segments — smooth enough for any tilt angle including the "pyramid" case.</p>
     *
     * <p>For upright tanks (2 distinct corner distances), this reduces to exact linear
     * interpolation. For tilted tanks (up to 8 distinct values), the piecewise curve
     * correctly handles the non-linear volume growth near corners.</p>
     */
    /**
     * Finds the plane offset for a given fill fraction.
     * Uses direct linear interpolation between min and max corner distances.
     * This is exact for upright tanks and smooth for all orientations.
     * For tilted tanks the volume mapping is approximate but visually smooth,
     * which matters more than mathematical precision.
     */
    @Unique
    private static float pipesnphysics$findVolumeCorrectOffset(float[] dist, float fillFraction) {
        float minDist = Float.MAX_VALUE, maxDist = -Float.MAX_VALUE;
        for (float d : dist) { minDist = Math.min(minDist, d); maxDist = Math.max(maxDist, d); }

        if (fillFraction <= 0.001f) return minDist;
        if (fillFraction >= 0.999f) return maxDist;

        return minDist + fillFraction * (maxDist - minDist);
    }

    /**
     * Finds where the fluid surface plane intersects the 12 edges of the tank box.
     * An edge is intersected when one endpoint is above the plane (dist > 0) and the other
     * is below (dist < 0). The intersection point is found by linear interpolation.
     *
     * @return list of 3D intersection points (need at least 3 to form a visible surface)
     */
    @Unique
    private static List<float[]> pipesnphysics$findPlaneEdgeIntersections(float[][] corners, float[] dist) {
        List<float[]> result = new ArrayList<>();
        for (int[] edge : BOX_EDGES) {
            float d0 = dist[edge[0]], d1 = dist[edge[1]];
            // Edge crosses the plane when endpoints have opposite signs
            if ((d0 < 0) != (d1 < 0)) {
                float t = d0 / (d0 - d1); // interpolation factor (0 = at corner0, 1 = at corner1)
                float[] c0 = corners[edge[0]], c1 = corners[edge[1]];
                result.add(new float[]{
                        c0[0] + t * (c1[0] - c0[0]),
                        c0[1] + t * (c1[1] - c0[1]),
                        c0[2] + t * (c1[2] - c0[2])
                });
            }
        }
        return result;
    }

    /**
     * Returns which axis (0=X, 1=Y, 2=Z) the surface normal points along most strongly.
     * The grid is built on the other two axes, and this axis is solved from the plane equation.
     * For a nearly-horizontal surface, dominant = Y, and the grid spans X/Z.
     */
    @Unique
    private static int pipesnphysics$dominantAxis(float nx, float ny, float nz) {
        if (Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz)) return 0;
        return Math.abs(ny) >= Math.abs(nz) ? 1 : 2;
    }

    /**
     * Computes grid resolution aligned to block boundaries to prevent UV tiling seams.
     * The resolution is a multiple of the tank's block size, so grid lines land exactly
     * on block edges (e.g. a 3-wide tank at configRes=64 → 63 cells, rounded to 3×21=63).
     */
    @Unique
    private static int pipesnphysics$alignedGridRes(float[] mins, float[] maxs, int axis1, int axis2) {
        int configRes = PipesNPhysicsConfig.FLUID_SURFACE_RESOLUTION.get();
        int blocks1 = Math.max(1, Math.round(maxs[axis1] - mins[axis1]));
        int blocks2 = Math.max(1, Math.round(maxs[axis2] - mins[axis2]));
        int tankBlocks = Math.max(blocks1, blocks2);
        return tankBlocks * Math.max(2, configRes / tankBlocks);
    }

    /**
     * Clips a box face polygon to the submerged region (where dist <= 0).
     * Uses the Sutherland–Hodgman algorithm with the fluid plane as the clip edge.
     * Walks the face vertices in order; for each edge that crosses the plane,
     * inserts an interpolated vertex at the crossing point.
     *
     * @param corners the 8 box corner positions
     * @param face    4 corner indices defining this face (CCW winding)
     * @param dist    signed distance at each corner (negative = submerged)
     * @return clipped polygon vertices (may be 3–6 points, or fewer if fully above)
     */
    @Unique
    private static List<float[]> pipesnphysics$clipFace(float[][] corners, int[] face, float[] dist) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < face.length; i++) {
            int cur = face[i], next = face[(i + 1) % face.length];

            // Keep vertices that are on or below the surface
            if (dist[cur] <= 0) out.add(corners[cur]);

            // If this edge crosses the plane, add the intersection point
            if ((dist[cur] < 0) != (dist[next] < 0)) {
                float t = dist[cur] / (dist[cur] - dist[next]);
                float[] cc = corners[cur], cn = corners[next];
                out.add(new float[]{
                        cc[0] + t * (cn[0] - cc[0]),
                        cc[1] + t * (cn[1] - cc[1]),
                        cc[2] + t * (cn[2] - cc[2])
                });
            }
        }
        return out;
    }

    // ========================================================================
    // Wave simulation
    // ========================================================================

    /**
     * Advances the 2D wave simulation by one timestep (rate-limited to 20 Hz).
     *
     * <p>Uses the discrete wave equation: next = (2·cur - prev + c²·laplacian) × damping.
     * The wave speed and damping are derived from the fluid's viscosity (lava = slow/heavy,
     * water = fast/light). Three sources of disturbance drive the waves:</p>
     * <ul>
     *   <li><b>Rotation change</b> — when the tank tilts, a directional impulse pushes the
     *       surface in the tilt direction (like sloshing in a moving container)</li>
     *   <li><b>Impact / deceleration</b> — when the sub-level suddenly slows down (lands,
     *       collides), a strong impulse in the velocity-change direction creates a big slosh</li>
     *   <li><b>Ambient ripple</b> — a tiny random poke that keeps the surface alive even
     *       when stationary</li>
     * </ul>
     *
     * @param key         block entity position, used as cache key for per-tank wave state
     * @param grid        grid dimensions (res, stride, size)
     * @param enabled     false → skip simulation, return current state
     * @param fluidStack  the fluid being rendered (viscosity affects wave speed/damping)
     * @param normal      surface normal {nx, ny, nz} — compared to last frame to detect rotation
     * @param worldPos    sub-level world position {x, y, z} — tracked to detect velocity changes
     * @param pose        sub-level pose — used to transform world-space velocity into local space
     * @return the current wave height at each grid vertex (displacement along the normal)
     */
    @Unique
    private static float[] pipesnphysics$stepWaveSimulation(BlockPos key, GridDims grid,
                                                             boolean enabled, FluidStack fluidStack,
                                                             float[] normal, float[] worldPos,
                                                             Pose3dc pose) {
        float[] waveCur = WAVE_CUR.get(key);
        float[] wavePrev = WAVE_PREV.get(key);
        if (waveCur == null || waveCur.length != grid.size()) {
            waveCur = new float[grid.size()];
            wavePrev = new float[grid.size()];
            WAVE_CUR.put(key, waveCur);
            WAVE_PREV.put(key, wavePrev);
        }

        // Rate-limit to 20 Hz (50ms between steps)
        long now = System.currentTimeMillis();
        Long lastTick = WAVE_TICK.get(key);
        if (!enabled || (lastTick != null && now - lastTick < 50)) return waveCur;
        WAVE_TICK.put(key, now);

        // Derive wave parameters from fluid viscosity (water=1000, lava=6000)
        int viscosity = fluidStack.getFluid().getFluidType().getViscosity();
        float viscRatio = viscosity / 1000f;   // 1.0 for water, 6.0 for lava
        float speed = 0.4f / viscRatio;         // wave propagation speed
        float damp = Mth.clamp(1.0f - 0.06f / viscRatio, 0.85f, 0.99f); // energy loss per step
        float c2 = speed * speed;               // squared speed for wave equation

        // Detect how much the tank orientation changed since last frame
        float[] lastUp = WAVE_LAST_UP.get(key);
        float disturbance = 0;
        if (lastUp != null) {
            float dx = normal[0] - lastUp[0], dy = normal[1] - lastUp[1], dz = normal[2] - lastUp[2];
            disturbance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        WAVE_LAST_UP.put(key, new float[]{normal[0], normal[1], normal[2]});

        // --- Track sub-level velocity and acceleration ---
        // Acceleration = velocity change per frame. Fluid reacts opposite to acceleration:
        // speeding up → fluid pushes back, braking → fluid pushes forward, landing → splash up.
        float[] lastPos = WAVE_LAST_POS.get(key);
        float[] lastVel = WAVE_LAST_VEL.get(key);
        float[] curVel = {0, 0, 0};
        float[] accel = {0, 0, 0}; // world-space acceleration (velocity delta)
        if (lastPos != null) {
            curVel[0] = worldPos[0] - lastPos[0];
            curVel[1] = worldPos[1] - lastPos[1];
            curVel[2] = worldPos[2] - lastPos[2];
        }
        if (lastVel != null) {
            accel[0] = curVel[0] - lastVel[0];
            accel[1] = curVel[1] - lastVel[1];
            accel[2] = curVel[2] - lastVel[2];
        }
        WAVE_LAST_POS.put(key, new float[]{worldPos[0], worldPos[1], worldPos[2]});
        WAVE_LAST_VEL.put(key, new float[]{curVel[0], curVel[1], curVel[2]});

        // --- Wave equation step ---
        // For each vertex: next = (2·current - previous + c²·(avg_neighbors - current)) × damping
        // Boundary vertices use their own value as the neighbor (reflective boundary)
        float[] waveNext = new float[grid.size()];
        for (int g1 = 0; g1 <= grid.res(); g1++) {
            for (int g2 = 0; g2 <= grid.res(); g2++) {
                int idx = g1 * grid.stride() + g2;
                float cur = waveCur[idx];
                float left  = (g1 > 0)        ? waveCur[(g1 - 1) * grid.stride() + g2] : cur;
                float right = (g1 < grid.res())  ? waveCur[(g1 + 1) * grid.stride() + g2] : cur;
                float down  = (g2 > 0)         ? waveCur[g1 * grid.stride() + g2 - 1]   : cur;
                float up    = (g2 < grid.res())   ? waveCur[g1 * grid.stride() + g2 + 1]   : cur;
                float laplacian = (left + right + down + up) / 4f - cur;
                waveNext[idx] = (2 * cur - wavePrev[idx] + c2 * laplacian) * damp;
            }
        }

        // --- Sloshing impulse from rotation ---
        // When the tank tilts, push the surface proportional to each vertex's position
        // along the tilt direction (vertices on the "downhill" side get pushed down)
        if (disturbance > 0.001f && lastUp != null) {
            float str = Math.min(0.02f, disturbance * 1.0f) / viscRatio;
            float dx = normal[0] - lastUp[0], dz = normal[2] - lastUp[2];
            for (int g1 = 0; g1 <= grid.res(); g1++) {
                for (int g2 = 0; g2 <= grid.res(); g2++) {
                    float fx = (g1 / (float) grid.res() - 0.5f) * 2;
                    float fz = (g2 / (float) grid.res() - 0.5f) * 2;
                    waveNext[g1 * grid.stride() + g2] += (fx * dx + fz * dz) * str;
                }
            }
        }

        // --- Inertia sloshing from acceleration ---
        // Fluid reacts opposite to any acceleration (Newton's first law):
        // - Contraption speeds up → fluid pushes backward
        // - Contraption brakes → fluid pushes forward
        // - Contraption lands (vertical decel) → fluid splashes upward
        // Transform world-space acceleration into tank-local space, then apply as
        // a directional impulse. Vertical impacts get amplified (landing is dramatic).
        float accelMag = (float) Math.sqrt(accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]);
        if (accelMag > 0.005f) {
            // Negative sign: fluid moves OPPOSITE to the acceleration direction
            Vector3d localAccel = pose.transformNormalInverse(
                    new Vector3d(-accel[0], -accel[1], -accel[2]), new Vector3d());
            float lax = (float) localAccel.x, lay = (float) localAccel.y, laz = (float) localAccel.z;

            // Vertical component (along dominant axis) creates a uniform push (splash)
            // Horizontal components create directional lean
            float horizontalMag = (float) Math.sqrt(lax * lax + laz * laz);

            // Directional lean from horizontal acceleration
            if (horizontalMag > 0.003f) {
                float leanStr = Math.min(0.02f, horizontalMag * 1.2f) / viscRatio;
                float ndx = lax / horizontalMag, ndz = laz / horizontalMag;
                for (int g1 = 0; g1 <= grid.res(); g1++) {
                    for (int g2 = 0; g2 <= grid.res(); g2++) {
                        float fx = (g1 / (float) grid.res() - 0.5f) * 2;
                        float fz = (g2 / (float) grid.res() - 0.5f) * 2;
                        waveNext[g1 * grid.stride() + g2] += (fx * ndx + fz * ndz) * leanStr;
                    }
                }
            }

            // Vertical splash from impacts (landing, sudden drops)
            // Amplified 2x because vertical impacts feel much stronger
            float verticalAccel = Math.abs(lay);
            if (verticalAccel > 0.005f) {
                float splashStr = Math.min(0.03f, verticalAccel * 2.0f) / viscRatio;
                // Radial splash: push outward from center (edges go up, center goes down)
                for (int g1 = 0; g1 <= grid.res(); g1++) {
                    for (int g2 = 0; g2 <= grid.res(); g2++) {
                        float fx = (g1 / (float) grid.res() - 0.5f) * 2;
                        float fz = (g2 / (float) grid.res() - 0.5f) * 2;
                        float dist = (float) Math.sqrt(fx * fx + fz * fz);
                        // Edges go up (+), center goes down (-): creates a bowl/splash shape
                        float radial = (dist - 0.5f) * 2;
                        waveNext[g1 * grid.stride() + g2] += radial * splashStr * Math.signum(lay);
                    }
                }
            }
        }

        // --- Centrifugal force from spinning ---
        // When the sub-level rotates, fluid is pushed outward from the spin axis.
        // Computed from angular velocity (quaternion delta between frames).
        float[] lastQuat = WAVE_LAST_QUAT.get(key);
        float qx = (float) pose.orientation().x(), qy = (float) pose.orientation().y();
        float qz = (float) pose.orientation().z(), qw = (float) pose.orientation().w();
        // Normalize quaternion sign (q and -q are the same rotation, pick consistent hemisphere)
        if (qw < 0) { qx = -qx; qy = -qy; qz = -qz; qw = -qw; }
        float[] curQuat = {qx, qy, qz, qw};
        WAVE_LAST_QUAT.put(key, curQuat);

        if (lastQuat != null) {
            // Quaternion delta: q_delta = q_cur × q_prev⁻¹ (conjugate for unit quaternions)
            float pxI = -lastQuat[0], pyI = -lastQuat[1], pzI = -lastQuat[2], pwI = lastQuat[3];
            float dw = qw * pwI - qx * pxI - qy * pyI - qz * pzI;
            float dx = qw * pxI + qx * pwI + qy * pzI - qz * pyI;
            float dy = qw * pyI - qx * pzI + qy * pwI + qz * pxI;
            float dz = qw * pzI + qx * pyI - qy * pxI + qz * pwI;

            // Extract angle from delta quaternion: angle = 2 × acos(|w|)
            float angle = 2.0f * (float) Math.acos(Mth.clamp(Math.abs(dw), 0, 1));

            if (angle > 0.005f) { // ~0.3 degrees minimum to avoid noise
                // Angular velocity axis (world space) = normalized (dx, dy, dz)
                float sinHalf = (float) Math.sin(angle / 2);
                if (Math.abs(sinHalf) > 0.0001f) {
                    float axWx = dx / sinHalf, axWy = dy / sinHalf, axWz = dz / sinHalf;

                    // Transform spin axis to local space
                    Vector3d localAxis = pose.transformNormalInverse(
                            new Vector3d(axWx, axWy, axWz), new Vector3d());
                    float lax = (float) localAxis.x, lay2 = (float) localAxis.y, laz = (float) localAxis.z;
                    float axLen = (float) Math.sqrt(lax * lax + lay2 * lay2 + laz * laz);
                    if (axLen > 0.001f) {
                        lax /= axLen; lay2 /= axLen; laz /= axLen;
                    }

                    // ω² — centrifugal force scales with square of angular velocity
                    float omega = angle; // angle per frame ≈ angular velocity (at 20Hz tick rate)
                    float omegaSq = omega * omega;
                    float centStr = Math.min(0.02f, omegaSq * 8.0f) / viscRatio;

                    if (centStr > 0.001f) {
                        // For each grid vertex, compute distance from spin axis and push outward
                        for (int g1 = 0; g1 <= grid.res(); g1++) {
                            for (int g2 = 0; g2 <= grid.res(); g2++) {
                                // Vertex position relative to grid center (-1 to +1)
                                float fx = (g1 / (float) grid.res() - 0.5f) * 2;
                                float fz = (g2 / (float) grid.res() - 0.5f) * 2;

                                // Project vertex onto the plane perpendicular to the spin axis
                                // dot = projection of (fx,0,fz) onto the spin axis
                                float dot = fx * lax + fz * laz;
                                // Perpendicular component = vertex pos minus projection onto axis
                                float perpX = fx - dot * lax;
                                float perpZ = fz - dot * laz;
                                float perpDist = (float) Math.sqrt(perpX * perpX + perpZ * perpZ);

                                // Centrifugal force pushes outward: farther from axis = more displacement
                                waveNext[g1 * grid.stride() + g2] += perpDist * centStr;
                            }
                        }
                    }
                }
            }
        }

        // --- Ambient ripple: tiny poke at a slowly-moving grid point ---
        float ambStr = 0.001f / (float) Math.sqrt(viscRatio);
        int rg1 = (int) ((now / 17) % (grid.res() + 1));
        int rg2 = (int) ((now / 31) % (grid.res() + 1));
        waveNext[rg1 * grid.stride() + rg2] += ambStr * ((now % 2 == 0) ? 1 : -1);

        // --- Hard clamp: viscous fluids can't reach the same amplitude as water ---
        // Water (viscRatio=1) → 0.06, Lava (viscRatio=6) → 0.025, Honey → even less
        float maxAmplitude = 0.06f / (float) Math.sqrt(viscRatio);
        for (int i = 0; i < waveNext.length; i++) {
            waveNext[i] = Mth.clamp(waveNext[i], -maxAmplitude, maxAmplitude);
        }

        WAVE_PREV.put(key, waveCur);
        WAVE_CUR.put(key, waveNext);
        return waveNext;
    }

    // ========================================================================
    // Grid building
    // ========================================================================

    /**
     * Builds vertex positions for the fluid surface mesh.
     *
     * <p>The grid spans axis1 × axis2 (the two non-dominant axes). For each vertex,
     * the dominant coordinate is solved from the plane equation:
     * {@code p[dominant] = center + (offset - dot(normal, p - center)) / normal[dominant]}.
     * This places every vertex exactly on the tilted fluid surface.</p>
     *
     * <p>Vertices outside the tank box are flagged so the renderer can clip or skip them.</p>
     *
     * @param grid         grid dimensions
     * @param bounds       tank interior bounding box
     * @param plane        fluid surface plane definition
     * @param wavesEnabled whether to apply wave displacement
     * @param waveCur      wave height per vertex (displacement along the surface normal)
     * @param gridRaw      output: 3D position of each vertex (may extend outside bounds due to tilt)
     * @param gridOutside  output: true if the flat (pre-wave) position is outside the tank box
     */
    @Unique
    private static void pipesnphysics$buildGrid(GridDims grid, TankBounds bounds, SurfacePlane plane,
                                                 boolean wavesEnabled, float[] waveCur,
                                                 float[][] gridRaw, boolean[] gridOutside) {
        float[] mins = bounds.mins(), maxs = bounds.maxs(), centers = bounds.centers();
        float[] normal = plane.normal();
        int dominant = plane.dominant(), axis1 = plane.axis1(), axis2 = plane.axis2();

        for (int g1 = 0; g1 <= grid.res(); g1++) {
            for (int g2 = 0; g2 <= grid.res(); g2++) {
                float[] p = new float[3];

                // Place vertex on the grid (spans axis1 × axis2)
                p[axis1] = mins[axis1] + (g1 / (float) grid.res()) * (maxs[axis1] - mins[axis1]);
                p[axis2] = mins[axis2] + (g2 / (float) grid.res()) * (maxs[axis2] - mins[axis2]);

                // Solve the plane equation for the dominant axis:
                // normal · (p - center) = planeOffset
                // → p[dominant] = center[dominant] + (offset - contribution_from_other_axes) / normal[dominant]
                float otherDot = normal[axis1] * (p[axis1] - centers[axis1])
                        + normal[axis2] * (p[axis2] - centers[axis2]);
                p[dominant] = centers[dominant] + (plane.planeOffset() - otherDot) / normal[dominant];

                // Flag if outside tank box (checked BEFORE wave displacement)
                boolean outside = p[0] < mins[0] || p[0] > maxs[0]
                        || p[1] < mins[1] || p[1] > maxs[1]
                        || p[2] < mins[2] || p[2] > maxs[2];

                // Wave displacement: push vertex along surface normal by wave height
                if (wavesEnabled) {
                    float w = waveCur[g1 * grid.stride() + g2];
                    p[0] += normal[0] * w;
                    p[1] += normal[1] * w;
                    p[2] += normal[2] * w;
                }

                int idx = g1 * grid.stride() + g2;
                gridOutside[idx] = outside;
                gridRaw[idx] = p;
            }
        }
    }

    // ========================================================================
    // Surface rendering
    // ========================================================================

    /**
     * Renders the fluid surface as a grid of quads.
     *
     * <p>Each grid cell is a quad defined by 4 neighboring vertices. Cells fully inside
     * the tank box are emitted directly. Cells at the boundary (where the tilted surface
     * extends beyond the tank walls) are clipped to the tank edge.</p>
     *
     * <p>UV coordinates use per-block tiling: the texture repeats every 1 block, aligned
     * to block boundaries so there are no seams in multi-block tanks.</p>
     */
    @Unique
    private static void pipesnphysics$renderSurface(VertexConsumer vc, Matrix4f mat,
                                                     GridDims grid, float[][] gridRaw, boolean[] gridOutside,
                                                     TankBounds bounds, SurfacePlane plane, FluidStyle s) {
        float[] mins = bounds.mins(), maxs = bounds.maxs();
        float uSize = maxs[plane.axis1()] - mins[plane.axis1()];
        float vSize = maxs[plane.axis2()] - mins[plane.axis2()];

        // If the normal's dominant component is negative (tank flipped), reverse the winding
        // so the surface faces the correct direction. Single-sided to avoid translucent overlap.
        boolean flip = plane.normal()[plane.dominant()] < 0;

        for (int g1 = 0; g1 < grid.res(); g1++) {
            for (int g2 = 0; g2 < grid.res(); g2++) {
                // Look up the 4 corners of this grid cell
                boolean o00 = gridOutside[g1 * grid.stride() + g2];
                boolean o10 = gridOutside[(g1 + 1) * grid.stride() + g2];
                boolean o11 = gridOutside[(g1 + 1) * grid.stride() + g2 + 1];
                boolean o01 = gridOutside[g1 * grid.stride() + g2 + 1];
                int outsideCount = (o00 ? 1 : 0) + (o10 ? 1 : 0) + (o11 ? 1 : 0) + (o01 ? 1 : 0);
                if (outsideCount == 4) continue;

                float[] r00 = gridRaw[g1 * grid.stride() + g2];
                float[] r10 = gridRaw[(g1 + 1) * grid.stride() + g2];
                float[] r11 = gridRaw[(g1 + 1) * grid.stride() + g2 + 1];
                float[] r01 = gridRaw[g1 * grid.stride() + g2 + 1];

                // Per-block UV tiling
                float u0f = ((g1 / (float) grid.res()) * uSize) % 1.0f;
                float u1f = (((g1 + 1) / (float) grid.res()) * uSize) % 1.0f;
                float v0f = ((g2 / (float) grid.res()) * vSize) % 1.0f;
                float v1f = (((g2 + 1) / (float) grid.res()) * vSize) % 1.0f;
                if (u1f < u0f) u1f = 1.0f;
                if (v1f < v0f) v1f = 1.0f;

                float qu0 = Mth.lerp(u0f, s.su0(), s.su1()), qu1 = Mth.lerp(u1f, s.su0(), s.su1());
                float qv0 = Mth.lerp(v0f, s.sv0(), s.sv1()), qv1 = Mth.lerp(v1f, s.sv0(), s.sv1());

                if (outsideCount == 0) {
                    if (flip) {
                        pipesnphysics$emitQuad(vc, mat, r01, r11, r10, r00, qu0, qu1, qv1, qv0, s);
                    } else {
                        pipesnphysics$emitQuad(vc, mat, r00, r10, r11, r01, qu0, qu1, qv0, qv1, s);
                    }
                } else {
                    // Boundary cell — clip polygon to tank bounds for smooth diagonal edge
                    pipesnphysics$emitClippedCell(vc, mat,
                            new float[][]{r00, r10, r11, r01},
                            new boolean[]{o00, o10, o11, o01},
                            new float[][]{{qu0, qv0}, {qu1, qv0}, {qu1, qv1}, {qu0, qv1}},
                            bounds, plane.dominant(), s, flip);
                }
            }
        }
    }

    /** Clamps a 3D point to the tank bounding box. */
    @Unique
    private static float[] pipesnphysics$clampToBox(float[] p, float[] mins, float[] maxs) {
        return new float[]{
                Mth.clamp(p[0], mins[0], maxs[0]),
                Mth.clamp(p[1], mins[1], maxs[1]),
                Mth.clamp(p[2], mins[2], maxs[2])
        };
    }

    /** Emits a single-sided quad (4 vertices). Winding is caller's responsibility. */
    @Unique
    private static void pipesnphysics$emitQuad(VertexConsumer vc, Matrix4f mat,
                                                float[] p00, float[] p10, float[] p11, float[] p01,
                                                float qu0, float qu1, float qv0, float qv1,
                                                FluidStyle s) {
        vc.addVertex(mat, p00[0], p00[1], p00[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                .setUv(qu0, qv0).setOverlay(0).setLight(15728880).setNormal(s.nx(), s.ny(), s.nz());
        vc.addVertex(mat, p10[0], p10[1], p10[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                .setUv(qu1, qv0).setOverlay(0).setLight(15728880).setNormal(s.nx(), s.ny(), s.nz());
        vc.addVertex(mat, p11[0], p11[1], p11[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                .setUv(qu1, qv1).setOverlay(0).setLight(15728880).setNormal(s.nx(), s.ny(), s.nz());
        vc.addVertex(mat, p01[0], p01[1], p01[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                .setUv(qu0, qv1).setOverlay(0).setLight(15728880).setNormal(s.nx(), s.ny(), s.nz());
    }

    /**
     * Clips a grid cell (quad) to the tank bounding box along the dominant axis, then emits
     * the resulting polygon as a triangle fan.
     *
     * <p>When the fluid surface is tilted, some grid cells extend beyond the tank walls.
     * This method walks the 4 edges of the quad, keeps vertices that are inside, and
     * interpolates new vertices where edges cross the tank boundary. The clipped polygon
     * (3–5 vertices) is then emitted as a fan from the first vertex.</p>
     *
     * @param rawC     the 4 corner positions of this grid cell
     * @param outs     which of the 4 corners are outside the tank box
     * @param uvs      UV coordinates for each of the 4 corners
     * @param bounds   tank interior bounding box (used for clip boundaries)
     * @param dominant the axis to clip against (the surface normal's strongest component)
     * @param flip     if true, reverse the winding order (for flipped tanks)
     */
    @Unique
    private static void pipesnphysics$emitClippedCell(VertexConsumer vc, Matrix4f mat,
                                                       float[][] rawC, boolean[] outs, float[][] uvs,
                                                       TankBounds bounds, int dominant, FluidStyle s,
                                                       boolean flip) {
        float[] mins = bounds.mins(), maxs = bounds.maxs();
        List<float[]> clippedVerts = new ArrayList<>();
        List<float[]> clippedUVs = new ArrayList<>();

        // Walk each edge of the quad; keep inside vertices, interpolate at boundary crossings
        for (int i = 0; i < 4; i++) {
            int ni = (i + 1) % 4;

            // Keep this vertex if it's inside the tank
            if (!outs[i]) {
                clippedVerts.add(rawC[i]);
                clippedUVs.add(uvs[i]);
            }

            // If this edge crosses the boundary (one in, one out), add the intersection
            if (outs[i] != outs[ni]) {
                float[] pIn  = outs[i] ? rawC[ni] : rawC[i];
                float[] pOut = outs[i] ? rawC[i]  : rawC[ni];

                // Which boundary did the outside vertex cross? (min or max along dominant axis)
                float boundary = (pOut[dominant] > maxs[dominant]) ? maxs[dominant] : mins[dominant];

                // Interpolation factor: how far along the edge does the boundary lie?
                float den = pOut[dominant] - pIn[dominant];
                float t = (Math.abs(den) > 0.0001f) ? (boundary - pIn[dominant]) / den : 0.5f;
                t = Mth.clamp(t, 0, 1);

                // Interpolate position
                clippedVerts.add(new float[]{
                        pIn[0] + t * (pOut[0] - pIn[0]),
                        pIn[1] + t * (pOut[1] - pIn[1]),
                        pIn[2] + t * (pOut[2] - pIn[2])
                });

                // Interpolate UVs to match
                float[] uvIn  = outs[i] ? uvs[ni] : uvs[i];
                float[] uvOut = outs[i] ? uvs[i]  : uvs[ni];
                clippedUVs.add(new float[]{
                        uvIn[0] + t * (uvOut[0] - uvIn[0]),
                        uvIn[1] + t * (uvOut[1] - uvIn[1])
                });
            }
        }

        if (clippedVerts.size() < 3) return;

        // Emit as a single-sided triangle fan. If flipped, reverse winding (b before a).
        float[] first = clippedVerts.get(0), firstUV = clippedUVs.get(0);
        for (int ti = 1; ti < clippedVerts.size() - 1; ti++) {
            float[] a = clippedVerts.get(ti),    b = clippedVerts.get(ti + 1);
            float[] aUV = clippedUVs.get(ti), bUV = clippedUVs.get(ti + 1);
            float[] v1 = flip ? b : a,    v2 = flip ? a : b;
            float[] uv1 = flip ? bUV : aUV, uv2 = flip ? aUV : bUV;
            vc.addVertex(mat, first[0], first[1], first[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                    .setUv(firstUV[0], firstUV[1]).setOverlay(0).setLight(15728880).setNormal(s.nx(), s.ny(), s.nz());
            vc.addVertex(mat, v1[0], v1[1], v1[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                    .setUv(uv1[0], uv1[1]).setOverlay(0).setLight(15728880).setNormal(s.nx(), s.ny(), s.nz());
            vc.addVertex(mat, v2[0], v2[1], v2[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                    .setUv(uv2[0], uv2[1]).setOverlay(0).setLight(15728880).setNormal(s.nx(), s.ny(), s.nz());
            vc.addVertex(mat, v2[0], v2[1], v2[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                    .setUv(uv2[0], uv2[1]).setOverlay(0).setLight(15728880).setNormal(s.nx(), s.ny(), s.nz());
        }
    }

    // ========================================================================
    // Wall rendering
    // ========================================================================

    /**
     * Renders wall skirts — quad strips from surface boundary to tank floor.
     * Single-sided with computed winding: uses cross product to determine which
     * way the quad faces, and flips if it doesn't match the expected outward direction.
     */
    @Unique
    private static void pipesnphysics$renderWallSkirts(VertexConsumer vc, Matrix4f mat,
                                                        GridDims grid, float[][] gridRaw, boolean[] gridOutside,
                                                        TankBounds bounds, SurfacePlane plane,
                                                        FluidStyle s, int light) {
        float[] mins = bounds.mins(), maxs = bounds.maxs();
        int res = grid.res(), stride = grid.stride();
        int dom = plane.dominant();
        float floorVal = (plane.normal()[dom] > 0) ? mins[dom] : maxs[dom];

        // {fixedG1, fixedG2, stepG1, stepG2, outwardAxis, outwardSign}
        // outwardAxis/Sign define which direction is "outside" for this wall
        int ax1 = plane.axis1(), ax2 = plane.axis2();
        int[][] edges = {
                {0,   -1, 0, 1, ax1, -1},  // g1=0: outward is -axis1
                {res,  -1, 0, 1, ax1,  1},  // g1=res: outward is +axis1
                {-1,   0, 1, 0, ax2, -1},   // g2=0: outward is -axis2
                {-1, res, 1, 0, ax2,  1},   // g2=res: outward is +axis2
        };

        for (int[] edge : edges) {
            boolean walkG1 = edge[2] == 1;
            int fixedVal = walkG1 ? edge[1] : edge[0];
            int outAxis = edge[4], outSign = edge[5];
            int uAxis = walkG1 ? ax1 : ax2;
            float uRange = Math.max(0.001f, maxs[uAxis] - mins[uAxis]);
            float domRange = Math.max(0.001f, maxs[dom] - mins[dom]);

            for (int i = 0; i < res; i++) {
                int g1a, g2a, g1b, g2b;
                if (walkG1) {
                    g1a = i; g2a = fixedVal; g1b = i + 1; g2b = fixedVal;
                } else {
                    g1a = fixedVal; g2a = i; g1b = fixedVal; g2b = i + 1;
                }

                int idxA = g1a * stride + g2a, idxB = g1b * stride + g2b;
                // Don't skip — clamped vertices at the boundary fill the wall gap

                float[] sA = gridRaw[idxA], sB = gridRaw[idxB];
                float[] tA = {Mth.clamp(sA[0], mins[0], maxs[0]),
                              Mth.clamp(sA[1], mins[1], maxs[1]),
                              Mth.clamp(sA[2], mins[2], maxs[2])};
                float[] tB = {Mth.clamp(sB[0], mins[0], maxs[0]),
                              Mth.clamp(sB[1], mins[1], maxs[1]),
                              Mth.clamp(sB[2], mins[2], maxs[2])};
                float[] bA = {tA[0], tA[1], tA[2]};
                float[] bB = {tB[0], tB[1], tB[2]};
                bA[dom] = floorVal;
                bB[dom] = floorVal;

                if (Math.abs(tA[dom] - floorVal) < 0.001f && Math.abs(tB[dom] - floorVal) < 0.001f) continue;

                // Compute face normal via cross product to determine correct winding
                float e1x = bA[0]-tA[0], e1y = bA[1]-tA[1], e1z = bA[2]-tA[2];
                float e2x = tB[0]-tA[0], e2y = tB[1]-tA[1], e2z = tB[2]-tA[2];
                float fnx = e1y*e2z - e1z*e2y;
                float fny = e1z*e2x - e1x*e2z;
                float fnz = e1x*e2y - e1y*e2x;
                // Does the face normal agree with the expected outward direction?
                float outDot = (outAxis == 0 ? fnx : outAxis == 1 ? fny : fnz) * outSign;
                boolean needFlip = outDot < 0;

                // UVs
                float uA = Mth.clamp((tA[uAxis] - mins[uAxis]) / uRange, 0, 1);
                float uB = Mth.clamp((tB[uAxis] - mins[uAxis]) / uRange, 0, 1);
                float vTA = Mth.clamp((tA[dom] - mins[dom]) / domRange, 0, 1);
                float vTB = Mth.clamp((tB[dom] - mins[dom]) / domRange, 0, 1);
                float vBot = Mth.clamp((floorVal - mins[dom]) / domRange, 0, 1);
                float quA = Mth.lerp(uA, s.su0(), s.su1()), quB = Mth.lerp(uB, s.su0(), s.su1());
                float qvTA = Mth.lerp(vTA, s.sv0(), s.sv1()), qvTB = Mth.lerp(vTB, s.sv0(), s.sv1());
                float qvBot = Mth.lerp(vBot, s.sv0(), s.sv1());

                // Single-sided quad with correct outward winding
                float[][] verts, uvs;
                if (needFlip) {
                    verts = new float[][]{tB, bB, bA, tA};
                    uvs = new float[][]{{quB, qvTB}, {quB, qvBot}, {quA, qvBot}, {quA, qvTA}};
                } else {
                    verts = new float[][]{tA, bA, bB, tB};
                    uvs = new float[][]{{quA, qvTA}, {quA, qvBot}, {quB, qvBot}, {quB, qvTB}};
                }
                for (int vi = 0; vi < 4; vi++) {
                    vc.addVertex(mat, verts[vi][0], verts[vi][1], verts[vi][2])
                            .setColor(s.cr(), s.cg(), s.cb(), s.ca())
                            .setUv(uvs[vi][0], uvs[vi][1]).setOverlay(0).setLight(light).setNormal(0, 1, 0);
                }
            }
        }
    }

    /**
     * Renders only the floor/ceiling faces of the fluid body (perpendicular to the dominant axis).
     * Side walls are handled by wall skirts. Single-sided with computed outward winding.
     */
    @Unique
    private static void pipesnphysics$renderWallFaces(VertexConsumer vc, Matrix4f mat,
                                                       float[][] corners, float[] dist,
                                                       TankBounds bounds, int dominant,
                                                       FluidStyle s, int light) {
        float[] mins = bounds.mins(), maxs = bounds.maxs();

        for (int f = 0; f < 6; f++) {
            // Only render faces whose normal is along the dominant axis (floor/ceiling).
            // Side faces (normal perpendicular to dominant) are covered by wall skirts.
            float[] fn = FACE_NORMALS[f];
            if (fn[dominant] == 0) continue;

            int[] face = BOX_FACES[f];
            boolean anyBelow = false;
            for (int fi : face) {
                if (dist[fi] <= 0) { anyBelow = true; break; }
            }
            if (!anyBelow) continue;

            List<float[]> clipped = pipesnphysics$clipFace(corners, face, dist);
            if (clipped.size() < 3) continue;

            int uAx = FACE_UV_AXES[f][0], vAx = FACE_UV_AXES[f][1];
            float uRange = Math.max(0.001f, maxs[uAx] - mins[uAx]);
            float vRange = Math.max(0.001f, maxs[vAx] - mins[vAx]);

            // Use the first triangle to check if clipFace winding matches the face normal
            if (clipped.size() >= 3) {
                float[] a = clipped.get(0), b = clipped.get(1), c = clipped.get(2);
                float ex1 = b[0]-a[0], ey1 = b[1]-a[1], ez1 = b[2]-a[2];
                float ex2 = c[0]-a[0], ey2 = c[1]-a[1], ez2 = c[2]-a[2];
                float cx1 = ey1*ez2 - ez1*ey2, cy1 = ez1*ex2 - ex1*ez2, cz1 = ex1*ey2 - ey1*ex2;
                float dot = cx1*fn[0] + cy1*fn[1] + cz1*fn[2];
                // If dot > 0, clipFace winding matches outward normal → use as-is
                // If dot < 0, winding is inward → reverse (p2, p1)
                boolean inward = dot < 0;

                float[] first = clipped.get(0);
                for (int i = 1; i < clipped.size() - 1; i++) {
                    float[] p1 = clipped.get(i), p2 = clipped.get(i + 1);
                    float[] va = inward ? p2 : p1, vb = inward ? p1 : p2;
                    for (float[] p : new float[][]{first, va, vb, vb}) {
                        float u = Mth.lerp(Mth.clamp((p[uAx] - mins[uAx]) / uRange, 0, 1), s.su0(), s.su1());
                        float v = Mth.lerp(Mth.clamp((p[vAx] - mins[vAx]) / vRange, 0, 1), s.sv0(), s.sv1());
                        vc.addVertex(mat, p[0], p[1], p[2]).setColor(s.cr(), s.cg(), s.cb(), s.ca())
                                .setUv(u, v).setOverlay(0).setLight(light).setNormal(0, 1, 0);
                    }
                }
            }
        }
    }

    // ========================================================================
    // Debug rendering
    // ========================================================================

    /**
     * Renders debug visualization: grid wireframe and box corner markers.
     *
     * <p>Grid lines are drawn in two colors (green for axis1, cyan for axis2) so you can
     * see the mesh structure. Box corners are marked with small 3-axis crosses — yellow
     * if the corner is above the fluid surface (air), red if below (submerged).</p>
     */
    @Unique
    private static void pipesnphysics$renderDebug(VertexConsumer lvc, Matrix4f mat,
                                                    GridDims grid, float[][] gridPos, boolean[] gridOutside,
                                                    float[][] corners, float[] dist) {
        // Grid wireframe along axis2 (green lines)
        for (int g1 = 0; g1 <= grid.res(); g1++) {
            for (int g2 = 0; g2 < grid.res(); g2++) {
                if (gridOutside[g1 * grid.stride() + g2] || gridOutside[g1 * grid.stride() + g2 + 1]) continue;
                float[] a = gridPos[g1 * grid.stride() + g2], b = gridPos[g1 * grid.stride() + g2 + 1];
                lvc.addVertex(mat, a[0], a[1], a[2]).setColor(0, 255, 100, 255).setNormal(0, 1, 0);
                lvc.addVertex(mat, b[0], b[1], b[2]).setColor(0, 255, 100, 255).setNormal(0, 1, 0);
            }
        }
        // Grid wireframe along axis1 (cyan lines)
        for (int g2 = 0; g2 <= grid.res(); g2++) {
            for (int g1 = 0; g1 < grid.res(); g1++) {
                if (gridOutside[g1 * grid.stride() + g2] || gridOutside[(g1 + 1) * grid.stride() + g2]) continue;
                float[] a = gridPos[g1 * grid.stride() + g2], b = gridPos[(g1 + 1) * grid.stride() + g2];
                lvc.addVertex(mat, a[0], a[1], a[2]).setColor(0, 200, 255, 255).setNormal(0, 1, 0);
                lvc.addVertex(mat, b[0], b[1], b[2]).setColor(0, 200, 255, 255).setNormal(0, 1, 0);
            }
        }

        // Corner markers: small 3-axis crosses at each box corner
        // Yellow = above surface (air), Red = below surface (submerged)
        for (int i = 0; i < 8; i++) {
            float[] c = corners[i];
            int blue = dist[i] > 0 ? 255 : 0; // yellow (255,255,255) vs red (255,255,0)
            float halfSize = 0.15f;
            lvc.addVertex(mat, c[0] - halfSize, c[1], c[2]).setColor(255, 255, blue, 255).setNormal(0, 1, 0);
            lvc.addVertex(mat, c[0] + halfSize, c[1], c[2]).setColor(255, 255, blue, 255).setNormal(0, 1, 0);
            lvc.addVertex(mat, c[0], c[1] - halfSize, c[2]).setColor(255, 255, blue, 255).setNormal(0, 1, 0);
            lvc.addVertex(mat, c[0], c[1] + halfSize, c[2]).setColor(255, 255, blue, 255).setNormal(0, 1, 0);
            lvc.addVertex(mat, c[0], c[1], c[2] - halfSize).setColor(255, 255, blue, 255).setNormal(0, 1, 0);
            lvc.addVertex(mat, c[0], c[1], c[2] + halfSize).setColor(255, 255, blue, 255).setNormal(0, 1, 0);
        }
    }

    /**
     * Full wireframe mode: renders the entire fluid body as a mesh without textures.
     * Shows the surface grid (green), wall skirt edges (orange), and bottom face outline (red).
     */
    @Unique
    private static void pipesnphysics$renderDebugWireframe(VertexConsumer lvc, Matrix4f mat,
                                                             GridDims grid, float[][] gridRaw, boolean[] gridOutside,
                                                             TankBounds bounds, SurfacePlane plane,
                                                             float[][] corners, float[] dist) {
        float[] mins = bounds.mins(), maxs = bounds.maxs();
        int res = grid.res(), stride = grid.stride();
        int dom = plane.dominant();
        float floorVal = (plane.normal()[dom] > 0) ? mins[dom] : maxs[dom];

        // --- Surface grid (green) ---
        for (int g1 = 0; g1 <= res; g1++) {
            for (int g2 = 0; g2 < res; g2++) {
                if (gridOutside[g1 * stride + g2] || gridOutside[g1 * stride + g2 + 1]) continue;
                float[] a = gridRaw[g1 * stride + g2], b = gridRaw[g1 * stride + g2 + 1];
                lvc.addVertex(mat, a[0], a[1], a[2]).setColor(0, 255, 100, 255).setNormal(0, 1, 0);
                lvc.addVertex(mat, b[0], b[1], b[2]).setColor(0, 255, 100, 255).setNormal(0, 1, 0);
            }
        }
        for (int g2 = 0; g2 <= res; g2++) {
            for (int g1 = 0; g1 < res; g1++) {
                if (gridOutside[g1 * stride + g2] || gridOutside[(g1 + 1) * stride + g2]) continue;
                float[] a = gridRaw[g1 * stride + g2], b = gridRaw[(g1 + 1) * stride + g2];
                lvc.addVertex(mat, a[0], a[1], a[2]).setColor(0, 255, 100, 255).setNormal(0, 1, 0);
                lvc.addVertex(mat, b[0], b[1], b[2]).setColor(0, 255, 100, 255).setNormal(0, 1, 0);
            }
        }

        // --- Wall skirt edges (orange): vertical lines from surface boundary to floor ---
        int[][] edgeDefs = {{0, -1, 0, 1}, {res, -1, 0, 1}, {-1, 0, 1, 0}, {-1, res, 1, 0}};
        for (int[] ed : edgeDefs) {
            boolean walkG1 = ed[2] == 1;
            int fixedVal = walkG1 ? ed[1] : ed[0];
            for (int i = 0; i <= res; i++) {
                int g1 = walkG1 ? i : fixedVal, g2 = walkG1 ? fixedVal : i;
                int idx = g1 * stride + g2;
                if (gridOutside[idx]) continue;
                float[] surf = gridRaw[idx];
                float[] floor = {surf[0], surf[1], surf[2]};
                floor[dom] = floorVal;
                // Vertical drop line (orange)
                lvc.addVertex(mat, surf[0], surf[1], surf[2]).setColor(255, 160, 0, 255).setNormal(0, 1, 0);
                lvc.addVertex(mat, floor[0], floor[1], floor[2]).setColor(255, 160, 0, 255).setNormal(0, 1, 0);
            }
            // Horizontal bottom edge (orange)
            for (int i = 0; i < res; i++) {
                int g1a = walkG1 ? i : fixedVal, g2a = walkG1 ? fixedVal : i;
                int g1b = walkG1 ? i + 1 : fixedVal, g2b = walkG1 ? fixedVal : i + 1;
                int idxA = g1a * stride + g2a, idxB = g1b * stride + g2b;
                // Don't skip — clamped vertices at the boundary fill the wall gap
                float[] sA = gridRaw[idxA], sB = gridRaw[idxB];
                float[] fA = {sA[0], sA[1], sA[2]};
                float[] fB = {sB[0], sB[1], sB[2]};
                fA[dom] = floorVal;
                fB[dom] = floorVal;
                lvc.addVertex(mat, fA[0], fA[1], fA[2]).setColor(255, 160, 0, 255).setNormal(0, 1, 0);
                lvc.addVertex(mat, fB[0], fB[1], fB[2]).setColor(255, 160, 0, 255).setNormal(0, 1, 0);
            }
        }

        // --- Bottom face outline (red) ---
        float[] bMins = {mins[0], mins[1], mins[2]};
        float[] bMaxs = {maxs[0], maxs[1], maxs[2]};
        bMins[dom] = floorVal;
        bMaxs[dom] = floorVal;
        float[][] fc = {
                {bMins[0], bMins[1], bMins[2]}, {bMaxs[0], bMins[1], bMins[2]},
                {bMaxs[0], bMaxs[1], bMaxs[2]}, {bMins[0], bMaxs[1], bMaxs[2]}
        };
        for (int i = 0; i < 4; i++) {
            float[] a = fc[i], b = fc[(i + 1) % 4];
            lvc.addVertex(mat, a[0], a[1], a[2]).setColor(255, 50, 50, 255).setNormal(0, 1, 0);
            lvc.addVertex(mat, b[0], b[1], b[2]).setColor(255, 50, 50, 255).setNormal(0, 1, 0);
        }
        // Diagonals
        lvc.addVertex(mat, fc[0][0], fc[0][1], fc[0][2]).setColor(255, 50, 50, 255).setNormal(0, 1, 0);
        lvc.addVertex(mat, fc[2][0], fc[2][1], fc[2][2]).setColor(255, 50, 50, 255).setNormal(0, 1, 0);
        lvc.addVertex(mat, fc[1][0], fc[1][1], fc[1][2]).setColor(255, 50, 50, 255).setNormal(0, 1, 0);
        lvc.addVertex(mat, fc[3][0], fc[3][1], fc[3][2]).setColor(255, 50, 50, 255).setNormal(0, 1, 0);
    }
}
