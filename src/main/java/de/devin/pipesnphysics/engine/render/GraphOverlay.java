package de.devin.pipesnphysics.engine.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.devin.pipesnphysics.engine.net.GraphOverlayPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side renderer for graph snapshots sent by /pipegraph.
 *
 * The snapshot is held in a tiny LRU; each entry expires after LIFETIME_TICKS.
 * Every frame we draw the active overlays as colored line segments with arrowheads:
 *   - HANDLER nodes  → green box
 *   - PUMP nodes     → orange box
 *   - JUNCTION nodes → white dot
 *   - edges          → each drawn in its own distinct color as a box-tube around the pipes;
 *                      flowing edges also get an arrowhead pointing along flow
 *
 * Edges are drawn as a rectangular tube wrapping the pipe run (not a center line),
 * so the outline sits on the outside of the pipe geometry and stays visible.
 */
@EventBusSubscriber(modid = de.devin.pipesnphysics.PipesNPhysics.ID, value = Dist.CLIENT)
public final class GraphOverlay {

    private static final int LIFETIME_TICKS = 600; // 30 seconds at 20 TPS

    /** Half-width of the edge tube; sits just outside the pipe core so the outline is visible. */
    private static final float EDGE_TUBE_RADIUS = 0.35f;

    private static final List<ActiveOverlay> ACTIVE = new ArrayList<>();

    private GraphOverlay() {}

    /** Called from the network payload handler. */
    public static void receive(GraphOverlayPayload payload) {
        ACTIVE.add(new ActiveOverlay(payload, System.currentTimeMillis()));
        // Cap memory: only keep the 4 most recent snapshots.
        while (ACTIVE.size() > 4) ACTIVE.remove(0);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (ACTIVE.isEmpty()) return;

        long now = System.currentTimeMillis();
        ACTIVE.removeIf(a -> (now - a.createdMs) > LIFETIME_TICKS * 50L);
        if (ACTIVE.isEmpty()) return;

        Camera cam = event.getCamera();
        Vector3f camOff = new Vector3f(
                (float) -cam.getPosition().x,
                (float) -cam.getPosition().y,
                (float) -cam.getPosition().z);

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(camOff.x, camOff.y, camOff.z);

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance()
                .renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        for (ActiveOverlay a : ACTIVE) {
            float fade = lifeFraction(a, now);
            drawSnapshot(pose, lines, a.payload, fade);
        }

        pose.popPose();
        buffers.endBatch(RenderType.lines());

        for (ActiveOverlay a : ACTIVE) {
            drawEdgeLabels(buffers, a.payload, lifeFraction(a, now));
        }
        buffers.endBatch();
    }

    /**
     * Floating letter above each edge's run, matching the names /pipegraph prints
     * in chat. Drawn with the vanilla debug text helper, which billboards toward
     * the camera and renders through blocks.
     */
    private static void drawEdgeLabels(MultiBufferSource buffers,
                                       GraphOverlayPayload payload, float fade) {
        List<? extends GraphOverlayPayload.EdgeEntry> edges = payload.edges();
        for (int ei = 0; ei < edges.size(); ei++) {
            List<Long> pts = edges.get(ei).points();
            if (pts.isEmpty()) continue;

            BlockPos midHigh = BlockPos.of(pts.get(pts.size() / 2));
            BlockPos midLow = BlockPos.of(pts.get(Math.max(0, (pts.size() - 1) / 2)));
            double x = (midHigh.getX() + midLow.getX()) / 2.0 + 0.5;
            double y = (midHigh.getY() + midLow.getY()) / 2.0 + 1.15;
            double z = (midHigh.getZ() + midLow.getZ()) / 2.0 + 0.5;

            int alpha = (int) (255 * Math.max(0.25f, fade));
            int color = (alpha << 24) | 0xFFFF55;
            DebugRenderer.renderFloatingText(new PoseStack(), buffers,
                    GraphOverlayPayload.edgeLetter(ei), x, y, z, color,
                    0.035f, true, 0, true);
        }
    }

    private static float lifeFraction(ActiveOverlay a, long now) {
        long age = now - a.createdMs;
        long max = LIFETIME_TICKS * 50L;
        return 1f - Math.min(1f, age / (float) max);
    }

    private static void drawSnapshot(PoseStack pose, VertexConsumer buf,
                                     GraphOverlayPayload payload, float alpha) {
        Matrix4f m = pose.last().pose();

        // Nodes — small boxes.
        for (var n : payload.nodes()) {
            int r, g, b;
            switch (n.kind()) {
                case GraphOverlayPayload.NodeEntry.KIND_HANDLER  -> { r = 64; g = 220; b = 64; }
                case GraphOverlayPayload.NodeEntry.KIND_PUMP     -> { r = 250; g = 140; b = 30; }
                case GraphOverlayPayload.NodeEntry.KIND_OPEN_END -> { r = 80; g = 180; b = 255; }
                default                                          -> { r = 255; g = 255; b = 255; }
            }
            drawBox(m, buf, n.x() + 0.5f, n.y() + 0.5f, n.z() + 0.5f, 0.25f, r, g, b, alpha);
        }

        // Edges — colored as a pressure gradient along the run when fluid can
        // reach them; dim neutral gray when dry (letters give identity). Arrowhead
        // if flowing.
        List<? extends GraphOverlayPayload.EdgeEntry> edges = payload.edges();
        for (int ei = 0; ei < edges.size(); ei++) {
            var e = edges.get(ei);
            boolean flowing = e.direction() == GraphOverlayPayload.EdgeEntry.DIR_FORWARD;
            List<Long> pts = e.points();
            List<Float> pressures = e.pressures();
            boolean gradient = pressures.size() == pts.size() && pts.size() >= 2;

            int[] fallback = DRY_EDGE_COLOR;
            for (int i = 1; i < pts.size(); i++) {
                BlockPos p0 = BlockPos.of(pts.get(i - 1));
                BlockPos p1 = BlockPos.of(pts.get(i));
                int[] c0 = gradient ? pressureColor(pressures.get(i - 1)) : fallback;
                int[] c1 = gradient ? pressureColor(pressures.get(i)) : fallback;
                tube(m, buf,
                        p0.getX() + 0.5f, p0.getY() + 0.5f, p0.getZ() + 0.5f,
                        p1.getX() + 0.5f, p1.getY() + 0.5f, p1.getZ() + 0.5f,
                        EDGE_TUBE_RADIUS, c0, c1, alpha);
            }
            if (flowing && pts.size() >= 2) {
                int[] tip = gradient ? pressureColor(pressures.get(pts.size() - 1)) : fallback;
                BlockPos last = BlockPos.of(pts.get(pts.size() - 1));
                BlockPos prev = BlockPos.of(pts.get(pts.size() - 2));
                drawArrowhead(m, buf, prev, last, tip[0], tip[1], tip[2], alpha);
            }
        }
    }

    /**
     * Maps gauge pressure to a color ramp matching the goggle readout: red under
     * suction, amber near ambient, green for a healthy column, cyan when strongly
     * pressurized. The ramp spans -8 (the suction limit) to +16 blocks of head.
     */
    private static int[] pressureColor(float pressureBlocks) {
        float t = Math.clamp((pressureBlocks + 8f) / 24f, 0f, 1f);
        return hsvToRgb(t * 0.5f, 0.85f, 1f);
    }

    /** Dry runs (no reservoir can reach them) are dim gray — no pressure to show. */
    private static final int[] DRY_EDGE_COLOR = { 150, 150, 150 };

    private static int[] hsvToRgb(float h, float s, float v) {
        float i = (float) Math.floor(h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r, g, b;
        switch (((int) i) % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return new int[] { (int) (r * 255), (int) (g * 255), (int) (b * 255) };
    }

    private static void drawArrowhead(Matrix4f m, VertexConsumer buf,
                                      BlockPos from, BlockPos to,
                                      int r, int g, int b, float a) {
        float fx = from.getX() + 0.5f, fy = from.getY() + 0.5f, fz = from.getZ() + 0.5f;
        float tx = to.getX() + 0.5f, ty = to.getY() + 0.5f, tz = to.getZ() + 0.5f;
        float dx = tx - fx, dy = ty - fy, dz = tz - fz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001f) return;
        dx /= len; dy /= len; dz /= len;
        // Two short backward-flaring segments to look like an arrowhead.
        float back = 0.35f, side = 0.2f;
        float bx = tx - dx * back, by = ty - dy * back, bz = tz - dz * back;
        // Perpendicular axis (pick world-up unless edge is vertical).
        float px, py, pz;
        if (Math.abs(dy) > 0.9f) { px = 1; py = 0; pz = 0; }
        else { px = 0; py = 1; pz = 0; }
        line(m, buf, tx, ty, tz, bx + px * side, by + py * side, bz + pz * side, r, g, b, a);
        line(m, buf, tx, ty, tz, bx - px * side, by - py * side, bz - pz * side, r, g, b, a);
    }

    private static void drawBox(Matrix4f m, VertexConsumer buf,
                                float cx, float cy, float cz, float s,
                                int r, int g, int b, float a) {
        float x0 = cx - s, x1 = cx + s;
        float y0 = cy - s, y1 = cy + s;
        float z0 = cz - s, z1 = cz + s;
        // 12 edges of a cube.
        line(m, buf, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(m, buf, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(m, buf, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(m, buf, x0, y0, z1, x0, y0, z0, r, g, b, a);
        line(m, buf, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(m, buf, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(m, buf, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(m, buf, x0, y1, z1, x0, y1, z0, r, g, b, a);
        line(m, buf, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(m, buf, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(m, buf, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(m, buf, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    /**
     * Draws a rectangular tube wrapping the segment (x0,y0,z0)->(x1,y1,z1): four longitudinal
     * edges offset radius from the axis, capped by a square ring at each end. This wraps the
     * pipe instead of running a line through its center, keeping the outline visible.
     * The longitudinal lines blend from the start color to the end color, so chained
     * segments form a continuous gradient.
     */
    private static void tube(Matrix4f m, VertexConsumer buf,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float radius, int[] colorStart, int[] colorEnd, float a) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5f) return;
        dx /= len; dy /= len; dz /= len;

        // Reference axis not parallel to the direction, so the cross-product is well-defined.
        float refx, refy, refz;
        if (Math.abs(dy) > 0.9f) { refx = 1; refy = 0; refz = 0; }
        else { refx = 0; refy = 1; refz = 0; }

        // u = dir x ref, then v = dir x u — orthonormal cross-section axes.
        float ux = dy * refz - dz * refy;
        float uy = dz * refx - dx * refz;
        float uz = dx * refy - dy * refx;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        float vx = dy * uz - dz * uy;
        float vy = dz * ux - dx * uz;
        float vz = dx * uy - dy * ux;

        // Four corner directions of the square cross-section.
        float[][] offs = {
                { ux + vx, uy + vy, uz + vz },
                { ux - vx, uy - vy, uz - vz },
                { -ux - vx, -uy - vy, -uz - vz },
                { -ux + vx, -uy + vy, -uz + vz },
        };
        for (float[] o : offs) {
            float ox = o[0] * radius, oy = o[1] * radius, oz = o[2] * radius;
            gradientLine(m, buf, x0 + ox, y0 + oy, z0 + oz, x1 + ox, y1 + oy, z1 + oz,
                    colorStart, colorEnd, a);
        }
        drawRing(m, buf, x0, y0, z0, offs, radius, colorStart[0], colorStart[1], colorStart[2], a);
        drawRing(m, buf, x1, y1, z1, offs, radius, colorEnd[0], colorEnd[1], colorEnd[2], a);
    }

    private static void drawRing(Matrix4f m, VertexConsumer buf,
                                 float cx, float cy, float cz,
                                 float[][] offs, float radius,
                                 int r, int g, int b, float a) {
        for (int i = 0; i < offs.length; i++) {
            float[] o0 = offs[i];
            float[] o1 = offs[(i + 1) % offs.length];
            line(m, buf,
                    cx + o0[0] * radius, cy + o0[1] * radius, cz + o0[2] * radius,
                    cx + o1[0] * radius, cy + o1[1] * radius, cz + o1[2] * radius,
                    r, g, b, a);
        }
    }

    private static void line(Matrix4f m, VertexConsumer buf,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             int r, int g, int b, float a) {
        gradientLine(m, buf, x0, y0, z0, x1, y1, z1,
                new int[] { r, g, b }, new int[] { r, g, b }, a);
    }

    /** A line whose vertex colors differ; the GPU interpolates the gradient between them. */
    private static void gradientLine(Matrix4f m, VertexConsumer buf,
                                     float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     int[] c0, int[] c1, float a) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5f) return;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        int alpha = (int) (255 * Math.max(0.15f, a));
        buf.addVertex(m, x0, y0, z0).setColor(c0[0], c0[1], c0[2], alpha).setNormal(nx, ny, nz);
        buf.addVertex(m, x1, y1, z1).setColor(c1[0], c1[1], c1[2], alpha).setNormal(nx, ny, nz);
    }

    private record ActiveOverlay(GraphOverlayPayload payload, long createdMs) {}
}
