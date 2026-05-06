package de.devin.pipesnphysics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankRenderer;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.createmod.catnip.animation.LerpedFloat;
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
 * Renders fluid surface debug lines with spring-damped sloshing.
 * Uses plane-box intersection — works at any rotation.
 */
@Mixin(value = FluidTankRenderer.class, remap = false)
public class FluidTankRendererMixin {

    @Unique private static final int[][] EDGES = {
            {0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}
    };

    @Inject(method = "renderSafe", at = @At("HEAD"), cancellable = true)
    private void renderTiltedFluid(FluidTankBlockEntity be, float partialTicks, PoseStack ms,
                                    MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (PipesNPhysicsConfig.FLUID_TILT_MODE.get() == 0) return;
        FluidTankAccessor acc = (FluidTankAccessor) be;
        if (!be.isController() || !acc.pipesnphysics$isWindow()) return;

        ClientSubLevelAccess subLevel = SableCompanion.INSTANCE.getContainingClient(be);
        if (subLevel == null) return;
        Pose3dc pose = subLevel.renderPose(partialTicks);
        if (pose == null) return;

        // Compute localUp directly from Sable's orientation (no sloshing)
        Vector3d localUp = pose.transformNormalInverse(new Vector3d(0, 1, 0), new Vector3d());
        double len = Math.sqrt(localUp.x*localUp.x + localUp.y*localUp.y + localUp.z*localUp.z);
        if (len < 0.001) return;
        localUp.div(len);

        FluidTank tank = acc.pipesnphysics$getTankInventory();
        FluidStack fluidStack = tank.getFluid();
        if (fluidStack.isEmpty()) return;

        // Tank geometry
        int width = acc.pipesnphysics$getWidth();
        int height = acc.pipesnphysics$getHeight();
        float capHeight = 1/4f, hullWidth = 1/16f + 1/128f, puddle = 1/16f;
        float totalHeight = height - 2*capHeight - puddle;
        LerpedFloat fluidLevel = be.getFluidLevel();
        if (fluidLevel == null) return;
        float level = fluidLevel.getValue(partialTicks);
        if (level < 0.01f) return;
        float clampedLevel = Mth.clamp(level * totalHeight, 0, totalHeight);

        // Inset slightly to prevent z-fighting with tank walls
        float inset = 0.003f;
        float xMin = hullWidth + inset, xMax = hullWidth + width - 2*hullWidth - inset;
        float yMin = capHeight + inset, yMax = capHeight + puddle + totalHeight - inset;
        float zMin = hullWidth + inset, zMax = hullWidth + width - 2*hullWidth - inset;
        float cx = (xMin+xMax)/2f, cy = (yMin+yMax)/2f, cz = (zMin+zMax)/2f;

        // Plane offset
        float[][] corners = {
                {xMin,yMin,zMin},{xMax,yMin,zMin},{xMin,yMax,zMin},{xMax,yMax,zMin},
                {xMin,yMin,zMax},{xMax,yMin,zMax},{xMin,yMax,zMax},{xMax,yMax,zMax}
        };
        float[] dist = new float[8];
        float minDist = Float.MAX_VALUE, maxDist = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            dist[i] = (float)(localUp.x*(corners[i][0]-cx)+localUp.y*(corners[i][1]-cy)+localUp.z*(corners[i][2]-cz));
            minDist = Math.min(minDist, dist[i]); maxDist = Math.max(maxDist, dist[i]);
        }
        // Binary search for volume-correct plane offset (linear mapping is wrong when tilted)
        float fillFraction = clampedLevel / totalHeight;
        float planeOffset;
        if (fillFraction <= 0.001f) { planeOffset = minDist; }
        else if (fillFraction >= 0.999f) { planeOffset = maxDist; }
        else {
            float lo = minDist, hi = maxDist;
            for (int iter = 0; iter < 12; iter++) {
                float mid = (lo + hi) / 2f;
                // Count submerged volume using 4x4x4 trilinear sampling
                int below = 0;
                for (int ix=0;ix<4;ix++) for (int iy=0;iy<4;iy++) for (int iz=0;iz<4;iz++) {
                    float fx=(ix+0.5f)/4f, fy=(iy+0.5f)/4f, fz=(iz+0.5f)/4f;
                    // Trilinear interpolation of corner distances
                    float d00=dist[0]*(1-fx)+dist[1]*fx, d10=dist[2]*(1-fx)+dist[3]*fx;
                    float d01=dist[4]*(1-fx)+dist[5]*fx, d11=dist[6]*(1-fx)+dist[7]*fx;
                    float d0=d00*(1-fy)+d10*fy, d1=d01*(1-fy)+d11*fy;
                    float d=d0*(1-fz)+d1*fz;
                    if (d < mid) below++;
                }
                if ((float)below/64f < fillFraction) lo = mid; else hi = mid;
            }
            planeOffset = (lo + hi) / 2f;
        }
        for (int i = 0; i < 8; i++) dist[i] -= planeOffset;

        // Find plane-edge intersections
        List<float[]> intersections = new ArrayList<>();
        for (int[] edge : EDGES) {
            float d0 = dist[edge[0]], d1 = dist[edge[1]];
            if ((d0 < 0) != (d1 < 0)) {
                float t = d0 / (d0 - d1);
                float[] c0 = corners[edge[0]], c1 = corners[edge[1]];
                intersections.add(new float[]{
                        c0[0]+t*(c1[0]-c0[0]), c0[1]+t*(c1[1]-c0[1]), c0[2]+t*(c1[2]-c0[2])
                });
            }
        }
        // Cancel Create's renderer — we render everything from here
        ci.cancel();

        float nxf = (float)localUp.x, nyf = (float)localUp.y, nzf = (float)localUp.z;
        Matrix4f mat = ms.last().pose();
        float[] mins = {xMin, yMin, zMin}, maxs = {xMax, yMax, zMax};

        // Fluid texture
        IClientFluidTypeExtensions fluidExt = IClientFluidTypeExtensions.of(fluidStack.getFluid());
        TextureAtlasSprite still = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(fluidExt.getStillTexture(fluidStack));
        int color = fluidExt.getTintColor(fluidStack);
        float cr = ((color>>16)&0xFF)/255f, cg = ((color>>8)&0xFF)/255f;
        float cb = (color&0xFF)/255f, ca = ((color>>24)&0xFF)/255f;
        if (ca == 0) ca = 0.8f;
        float su0 = still.getU0(), su1 = still.getU1(), sv0 = still.getV0(), sv1 = still.getV1();

        VertexConsumer qvc = buffer.getBuffer(RenderType.translucent());

        boolean hasIntersections = intersections.size() >= 3;
        if (!hasIntersections) return;

        int dominant;
        if (Math.abs(nxf)>=Math.abs(nyf)&&Math.abs(nxf)>=Math.abs(nzf)) dominant=0;
        else if (Math.abs(nyf)>=Math.abs(nzf)) dominant=1;
        else dominant=2;
        float[] centers = {cx, cy, cz};
        float[] normal = {nxf, nyf, nzf};
        int axis1 = (dominant+1)%3, axis2 = (dominant+2)%3;

        // Align grid resolution with block boundaries to prevent UV tiling seams
        int configRes = PipesNPhysicsConfig.FLUID_SURFACE_RESOLUTION.get();
        int tankBlocksA1 = Math.max(1, Math.round(maxs[axis1]-mins[axis1]));
        int tankBlocksA2 = Math.max(1, Math.round(maxs[axis2]-mins[axis2]));
        int tankBlocks = Math.max(tankBlocksA1, tankBlocksA2);
        int cellsPerBlock = Math.max(2, configRes / tankBlocks);
        int gridRes = tankBlocks * cellsPerBlock;
        int stride = gridRes + 1;
        int gridSize = stride * stride;

        // Build grid
        float[][] gridPos = new float[gridSize][3];
        float[][] gridRaw = new float[gridSize][3];
        boolean[] gridOutside = new boolean[gridSize];
        for (int g1 = 0; g1 <= gridRes; g1++) {
            for (int g2 = 0; g2 <= gridRes; g2++) {
                float[] p = new float[3];
                p[axis1] = mins[axis1]+(g1/(float)gridRes)*(maxs[axis1]-mins[axis1]);
                p[axis2] = mins[axis2]+(g2/(float)gridRes)*(maxs[axis2]-mins[axis2]);
                float otherDot = normal[axis1]*(p[axis1]-centers[axis1])+normal[axis2]*(p[axis2]-centers[axis2]);
                p[dominant] = centers[dominant]+(planeOffset-otherDot)/normal[dominant];
                boolean outside = p[0]<xMin||p[0]>xMax||p[1]<yMin||p[1]>yMax||p[2]<zMin||p[2]>zMax;
                gridOutside[g1*stride+g2] = outside;
                gridRaw[g1*stride+g2] = new float[]{p[0], p[1], p[2]};
                gridPos[g1*stride+g2] = new float[]{
                    Mth.clamp(p[0],xMin,xMax), Mth.clamp(p[1],yMin,yMax), Mth.clamp(p[2],zMin,zMax)
                };
            }
        }

        // Surface mesh — clip boundary cells to exact tank edge
        for (int g1=0;g1<gridRes;g1++) { for (int g2=0;g2<gridRes;g2++) {
            boolean o00=gridOutside[g1*stride+g2], o10=gridOutside[(g1+1)*stride+g2];
            boolean o11=gridOutside[(g1+1)*stride+g2+1], o01=gridOutside[g1*stride+g2+1];
            int outsideCount = (o00?1:0)+(o10?1:0)+(o11?1:0)+(o01?1:0);
            if (outsideCount==4) continue;

            float[] r00=gridRaw[g1*stride+g2], r10=gridRaw[(g1+1)*stride+g2];
            float[] r11=gridRaw[(g1+1)*stride+g2+1], r01=gridRaw[g1*stride+g2+1];
            // Per-block UV tiling (grid aligned to block boundaries — no seam artifacts)
            float uSize = maxs[axis1]-mins[axis1], vSize = maxs[axis2]-mins[axis2];
            float u0f = ((g1/(float)gridRes)*uSize) % 1.0f, u1f = (((g1+1)/(float)gridRes)*uSize) % 1.0f;
            float v0f = ((g2/(float)gridRes)*vSize) % 1.0f, v1f = (((g2+1)/(float)gridRes)*vSize) % 1.0f;
            // Fix wrap: if u1f < u0f, we crossed a boundary — shouldn't happen with aligned grid
            if (u1f < u0f) u1f = 1.0f;
            if (v1f < v0f) v1f = 1.0f;
            float qu0 = Mth.lerp(u0f, su0, su1), qu1 = Mth.lerp(u1f, su0, su1);
            float qv0 = Mth.lerp(v0f, sv0, sv1), qv1 = Mth.lerp(v1f, sv0, sv1);

            if (outsideCount==0) {
                // All inside — single-sided quad (reversed winding → faces localUp direction)
                qvc.addVertex(mat,r01[0],r01[1],r01[2]).setColor(cr,cg,cb,ca).setUv(qu0,qv1).setOverlay(0).setLight(light).setNormal(0,1,0);
                qvc.addVertex(mat,r11[0],r11[1],r11[2]).setColor(cr,cg,cb,ca).setUv(qu1,qv1).setOverlay(0).setLight(light).setNormal(0,1,0);
                qvc.addVertex(mat,r10[0],r10[1],r10[2]).setColor(cr,cg,cb,ca).setUv(qu1,qv0).setOverlay(0).setLight(light).setNormal(0,1,0);
                qvc.addVertex(mat,r00[0],r00[1],r00[2]).setColor(cr,cg,cb,ca).setUv(qu0,qv0).setOverlay(0).setLight(light).setNormal(0,1,0);
            } else {
                // Mixed — clip to tank boundary
                float[][] rawC = {r00, r10, r11, r01};
                boolean[] outs = {o00, o10, o11, o01};
                float[][] uvs = {{qu0,qv0},{qu1,qv0},{qu1,qv1},{qu0,qv1}};
                List<float[]> cv = new ArrayList<>();
                List<float[]> cuv = new ArrayList<>();
                for (int ci2=0; ci2<4; ci2++) {
                    int ni=(ci2+1)%4;
                    if (!outs[ci2]) { cv.add(rawC[ci2]); cuv.add(uvs[ci2]); }
                    if (outs[ci2]!=outs[ni]) {
                        float[] pIn=outs[ci2]?rawC[ni]:rawC[ci2], pOut=outs[ci2]?rawC[ci2]:rawC[ni];
                        float bnd=(pOut[dominant]>maxs[dominant])?maxs[dominant]:mins[dominant];
                        float den=pOut[dominant]-pIn[dominant];
                        float t=(Math.abs(den)>0.0001f)?(bnd-pIn[dominant])/den:0.5f;
                        t=Mth.clamp(t,0,1);
                        cv.add(new float[]{pIn[0]+t*(pOut[0]-pIn[0]),pIn[1]+t*(pOut[1]-pIn[1]),pIn[2]+t*(pOut[2]-pIn[2])});
                        float[] uvI=outs[ci2]?uvs[ni]:uvs[ci2], uvO=outs[ci2]?uvs[ci2]:uvs[ni];
                        cuv.add(new float[]{uvI[0]+t*(uvO[0]-uvI[0]),uvI[1]+t*(uvO[1]-uvI[1])});
                    }
                }
                if (cv.size()>=3) {
                    float[] f=cv.get(0); float[] fuv=cuv.get(0);
                    // Single-sided triangle fan (reversed winding → faces localUp)
                    for (int ti=1;ti<cv.size()-1;ti++) {
                        float[] a=cv.get(ti),b=cv.get(ti+1);
                        float[] auv=cuv.get(ti),buv=cuv.get(ti+1);
                        qvc.addVertex(mat,b[0],b[1],b[2]).setColor(cr,cg,cb,ca).setUv(buv[0],buv[1]).setOverlay(0).setLight(light).setNormal(0,1,0);
                        qvc.addVertex(mat,a[0],a[1],a[2]).setColor(cr,cg,cb,ca).setUv(auv[0],auv[1]).setOverlay(0).setLight(light).setNormal(0,1,0);
                        qvc.addVertex(mat,f[0],f[1],f[2]).setColor(cr,cg,cb,ca).setUv(fuv[0],fuv[1]).setOverlay(0).setLight(light).setNormal(0,1,0);
                        qvc.addVertex(mat,f[0],f[1],f[2]).setColor(cr,cg,cb,ca).setUv(fuv[0],fuv[1]).setOverlay(0).setLight(light).setNormal(0,1,0);
                    }
                }
            }
        }}

        // === WALL FACES: only partially-clipped faces (where surface crosses the wall) ===
        // Debug colors: -Y=red, +Y=green, -Z=blue, +Z=yellow, -X=cyan, +X=magenta
        boolean debugWalls = PipesNPhysicsConfig.FLUID_DEBUG_RENDER.get();
        float[][] wallDbgColors = {{1,0.3f,0.3f},{0.3f,1,0.3f},{0.3f,0.3f,1},{1,1,0.3f},{0.3f,1,1},{1,0.3f,1}};
        int[][] wallFaces = {
                {0,4,5,1}, {2,3,7,6}, {0,1,3,2}, {4,6,7,5}, {0,2,6,4}, {1,5,7,3}
        };
        float[][] wallNormals = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
        int[][] wallUvAxes = {{0,2},{0,2},{0,1},{0,1},{2,1},{2,1}};
        for (int f = 0; f < 6; f++) {
            int[] face = wallFaces[f];
            boolean anyAbove = false, anyBelow = false;
            for (int fi : face) { if (dist[fi] > 0) anyAbove = true; if (dist[fi] <= 0) anyBelow = true; }
            if (!anyBelow) continue; // fully above surface — skip

            List<float[]> clipped = pipesnphysics$clipFace(corners, face, dist);
            boolean fullySubmerged = !anyAbove; // all corners below surface
            if (clipped.size() < 3) continue;
            float[] fn = wallNormals[f];
            int uAx = wallUvAxes[f][0], vAx = wallUvAxes[f][1];
            float uRange = maxs[uAx]-mins[uAx], vRange = maxs[vAx]-mins[vAx];
            if (uRange < 0.001f) uRange = 1; if (vRange < 0.001f) vRange = 1;
            float wcr = debugWalls ? wallDbgColors[f][0] : cr;
            float wcg = debugWalls ? wallDbgColors[f][1] : cg;
            float wcb = debugWalls ? wallDbgColors[f][2] : cb;
            float[] first = clipped.get(0);
            for (int i = 1; i < clipped.size() - 1; i++) {
                float[] p1 = clipped.get(i), p2 = clipped.get(i+1);
                // Double-sided with uniform normal (0,1,0) — no sphere, visible from all angles
                for (float[] p : new float[][]{first, p1, p2, p2}) {
                    float u = Mth.lerp(Mth.clamp((p[uAx]-mins[uAx])/uRange, 0, 1), su0, su1);
                    float v = Mth.lerp(Mth.clamp((p[vAx]-mins[vAx])/vRange, 0, 1), sv0, sv1);
                    qvc.addVertex(mat,p[0],p[1],p[2]).setColor(wcr,wcg,wcb,ca)
                            .setUv(u,v).setOverlay(0).setLight(light).setNormal(0,1,0);
                }
                for (float[] p : new float[][]{first, p2, p1, p1}) {
                    float u = Mth.lerp(Mth.clamp((p[uAx]-mins[uAx])/uRange, 0, 1), su0, su1);
                    float v = Mth.lerp(Mth.clamp((p[vAx]-mins[vAx])/vRange, 0, 1), sv0, sv1);
                    qvc.addVertex(mat,p[0],p[1],p[2]).setColor(wcr,wcg,wcb,ca)
                            .setUv(u,v).setOverlay(0).setLight(light).setNormal(0,1,0);
                }
            }
        }

        if (!PipesNPhysicsConfig.FLUID_DEBUG_RENDER.get()) return;

        // Debug wireframe (skip outside vertices)
        VertexConsumer lvc = buffer.getBuffer(RenderType.LINES);
        for (int g1=0;g1<=gridRes;g1++) for (int g2=0;g2<gridRes;g2++) {
            if (gridOutside[g1*stride+g2]||gridOutside[g1*stride+g2+1]) continue;
            float[] a=gridPos[g1*stride+g2], b=gridPos[g1*stride+g2+1];
            lvc.addVertex(mat,a[0],a[1],a[2]).setColor(0,255,100,255).setNormal(0,1,0);
            lvc.addVertex(mat,b[0],b[1],b[2]).setColor(0,255,100,255).setNormal(0,1,0);
        }
        for (int g2=0;g2<=gridRes;g2++) for (int g1=0;g1<gridRes;g1++) {
            if (gridOutside[g1*stride+g2]||gridOutside[(g1+1)*stride+g2]) continue;
            float[] a=gridPos[g1*stride+g2], b=gridPos[(g1+1)*stride+g2];
            lvc.addVertex(mat,a[0],a[1],a[2]).setColor(0,200,255,255).setNormal(0,1,0);
            lvc.addVertex(mat,b[0],b[1],b[2]).setColor(0,200,255,255).setNormal(0,1,0);
        }
        // Debug corner dots (white = above surface, yellow = submerged)
        for (int i = 0; i < 8; i++) {
            float[] c = corners[i];
            int dc = dist[i] > 0 ? 255 : 0;
            float s = 0.15f;
            lvc.addVertex(mat,c[0]-s,c[1],c[2]).setColor(255,255,dc,255).setNormal(0,1,0);
            lvc.addVertex(mat,c[0]+s,c[1],c[2]).setColor(255,255,dc,255).setNormal(0,1,0);
            lvc.addVertex(mat,c[0],c[1]-s,c[2]).setColor(255,255,dc,255).setNormal(0,1,0);
            lvc.addVertex(mat,c[0],c[1]+s,c[2]).setColor(255,255,dc,255).setNormal(0,1,0);
            lvc.addVertex(mat,c[0],c[1],c[2]-s).setColor(255,255,dc,255).setNormal(0,1,0);
            lvc.addVertex(mat,c[0],c[1],c[2]+s).setColor(255,255,dc,255).setNormal(0,1,0);
        }
    }

    /** Project a surface vertex along -localUp to the nearest tank wall. */
    @Unique
    private static float[] pipesnphysics$dropToFloor(float[] p, float nxf, float nyf, float nzf,
                                                      float[] mins, float[] maxs) {
        // Find how far along -localUp we can go before exiting the box
        float tMin = Float.MAX_VALUE;
        float[] dir = {-nxf, -nyf, -nzf}; // world-down in local space
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 0.0001f) continue;
            float t;
            if (dir[i] > 0) t = (maxs[i] - p[i]) / dir[i];
            else t = (mins[i] - p[i]) / dir[i];
            if (t > 0 && t < tMin) tMin = t;
        }
        if (tMin == Float.MAX_VALUE) tMin = 0;
        return new float[]{p[0]+dir[0]*tMin, p[1]+dir[1]*tMin, p[2]+dir[2]*tMin};
    }

    /** Clip a box face to the submerged region (dist < 0). */
    @Unique
    private static List<float[]> pipesnphysics$clipFace(float[][] corners, int[] face, float[] dist) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < face.length; i++) {
            int c = face[i], n = face[(i+1) % face.length];
            if (dist[c] <= 0) out.add(corners[c]);
            if ((dist[c] < 0) != (dist[n] < 0)) {
                float t = dist[c] / (dist[c] - dist[n]);
                float[] cc = corners[c], cn = corners[n];
                out.add(new float[]{cc[0]+t*(cn[0]-cc[0]), cc[1]+t*(cn[1]-cc[1]), cc[2]+t*(cn[2]-cc[2])});
            }
        }
        return out;
    }

    @Unique
    private static void pipesnphysics$sortConvex(List<float[]> verts, Vector3d normal) {
        if (verts.size() < 3) return;
        float cx=0,cy=0,cz=0;
        for (float[] v : verts) { cx+=v[0]; cy+=v[1]; cz+=v[2]; }
        cx/=verts.size(); cy/=verts.size(); cz/=verts.size();
        float nx=(float)normal.x,ny=(float)normal.y,nz=(float)normal.z;
        float ax,ay,az;
        if (Math.abs(nx)<0.9f){ax=0;ay=-nz;az=ny;}else{ax=nz;ay=0;az=-nx;}
        float l=(float)Math.sqrt(ax*ax+ay*ay+az*az); ax/=l; ay/=l; az/=l;
        float bx=ny*az-nz*ay,by=nz*ax-nx*az,bz=nx*ay-ny*ax;
        final float fcx=cx,fcy=cy,fcz=cz,fax=ax,fay=ay,faz=az,fbx=bx,fby=by,fbz=bz;
        verts.sort((p,q)->Float.compare(
                (float)Math.atan2((p[0]-fcx)*fbx+(p[1]-fcy)*fby+(p[2]-fcz)*fbz,
                        (p[0]-fcx)*fax+(p[1]-fcy)*fay+(p[2]-fcz)*faz),
                (float)Math.atan2((q[0]-fcx)*fbx+(q[1]-fcy)*fby+(q[2]-fcz)*fbz,
                        (q[0]-fcx)*fax+(q[1]-fcy)*fay+(q[2]-fcz)*faz)));
    }
}
