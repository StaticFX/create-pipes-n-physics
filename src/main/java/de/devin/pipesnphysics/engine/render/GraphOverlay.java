package de.devin.pipesnphysics.engine.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.client.Rgb;
import de.devin.pipesnphysics.client.RodRender;
import de.devin.pipesnphysics.client.SubLevelDraw;
import de.devin.pipesnphysics.compat.SubLevelFrame;
import de.devin.pipesnphysics.engine.net.GraphOverlayPayload;
import de.devin.pipesnphysics.engine.net.GraphOverlayRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side renderer for graph snapshots sent by /pipegraph.
 *
 * The snapshot is held in a tiny LRU; each entry expires after LIFETIME_TICKS.
 * Every frame we draw the active overlays:
 *   - HANDLER nodes  → green box
 *   - PUMP nodes     → orange box
 *   - JUNCTION nodes → white box
 *   - edges          → a thin square rod threaded down the pipe centre, colored by the pressure
 *                      gradient (gray when dry, magenta when held); flowing edges get an arrowhead.
 *
 * Edges are a ~1px extruded square drawn INSIDE the pipe rather than a tube wrapping it. The rod
 * uses the no-depth quad batch so it reads as a line through the pipe whatever the pipe's opacity;
 * the node boxes stay depth-tested lines.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class GraphOverlay {
    private static final int LIFETIME_TICKS = 600; // 30 seconds at 20 TPS

    /** How often the client re-asks the server for a fresh solve, so the overlay tracks live flow. */
    private static final long REQUEST_INTERVAL_MS = 200; // ~4 ticks, matching the server probe throttle

    /** Half-side of the edge rod's square cross-section; 1/32 block ≈ a 1px-wide extruded square. */
    private static final float ROD_HALF = 1f / 32f;

    private static RenderType rodRenderType;

    /**
     * The edge-rod render type: translucent POSITION_COLOR quads with NO depth test, so the thin
     * rod stays visible even threaded through an opaque pipe body. It does not write depth (COLOR_
     * WRITE) so the overlay never pollutes the world depth buffer.
     *
     * <p>Built on first draw, never in a static initializer: FML force-initializes every
     * {@link EventBusSubscriber} class during mod CONSTRUCTION, and touching {@code RenderType} that early
     * freezes the chunk render-type ids before mods that add their own have registered them. Render thread
     * only, so no locking.
     */
    private static RenderType rodRenderType() {
        if (rodRenderType == null) {
            rodRenderType = RenderType.create(
                    "pnp_graph_rod",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return rodRenderType;
    }

    private static final List<ActiveOverlay> ACTIVE = new ArrayList<>();

    private GraphOverlay() {}

    /** Drop all snapshots — called when the player leaves the world or changes dimension. */
    public static void clear() {
        ACTIVE.clear();
    }

    /**
     * Called from the network payload handler. A payload for a network we already show is a live
     * REFRESH — update its data in place and keep its lifetime — otherwise it is a new overlay.
     */
    public static void receive(GraphOverlayPayload payload) {
        for (ActiveOverlay overlay : ACTIVE) {
            if (overlay.payload.seed() == payload.seed()) { overlay.payload = payload; return; }
        }
        ACTIVE.add(new ActiveOverlay(payload, System.currentTimeMillis()));
        // Cap memory: only keep the 4 most recent snapshots.
        while (ACTIVE.size() > 4) ACTIVE.remove(0);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (ACTIVE.isEmpty()) return;

        long now = System.currentTimeMillis();
        ACTIVE.removeIf(overlay -> (now - overlay.firstSeenMs) > LIFETIME_TICKS * 50L);
        if (ACTIVE.isEmpty()) return;

        // Keep each overlay live: periodically ask the server to re-solve its network so a bursty
        // flow tracks (arrows blink with the bursts) instead of freezing on the command's tick.
        for (ActiveOverlay overlay : ACTIVE) {
            if (now - overlay.lastRequestMs >= REQUEST_INTERVAL_MS) {
                overlay.lastRequestMs = now;
                PacketDistributor.sendToServer(new GraphOverlayRequest(overlay.payload.seed()));
            }
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance()
                .renderBuffers().bufferSource();

        // The BufferSource shares ONE builder across render types, so requesting a second type ends
        // the first's buffer — finish (endBatch) the lines BEFORE asking for the quads buffer, or the
        // next lines.addVertex throws "Not building!".
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (ActiveOverlay overlay : ACTIVE) {
            float fade = lifeFraction(overlay, now);
            SubLevelFrame frame = frameOf(overlay);
            // Every node, edge and box is emitted RELATIVE to the seed — on a Sable plot 30M blocks
            // out, absolute coordinates in a float matrix are accurate to a few blocks at best.
            BlockPos origin = BlockPos.of(overlay.payload.seed());
            pose.pushPose();
            SubLevelDraw.cameraRelative(pose, camera, frame, origin);
            drawNodes(pose, lines, overlay.payload, fade, origin);
            drawFlag(pose, lines, overlay.payload, fade, origin);
            pose.popPose();

            // A surface marker is a WORLD elevation the engine computed, not a plot one, so it is
            // drawn in world space at the node's projected footprint rather than inside the
            // contraption's frame — on a tilt it would otherwise lean with the hull and stop
            // meaning "this is the height fluid settles to".
            pose.pushPose();
            SubLevelDraw.cameraRelative(pose, camera, null, BlockPos.ZERO);
            drawSurfaceMarkers(pose, lines, overlay.payload, fade, frame);
            pose.popPose();
        }
        buffers.endBatch(RenderType.lines());

        // Edges are thin extruded squares drawn through the pipe — the no-depth quad batch so they
        // stay visible inside opaque pipes.
        RenderType rodType = rodRenderType();
        VertexConsumer rods = buffers.getBuffer(rodType);
        for (ActiveOverlay overlay : ACTIVE) {
            BlockPos origin = BlockPos.of(overlay.payload.seed());
            pose.pushPose();
            SubLevelDraw.cameraRelative(pose, camera, frameOf(overlay), origin);
            drawEdges(pose, rods, overlay.payload, lifeFraction(overlay, now), origin);
            pose.popPose();
        }
        buffers.endBatch(rodType);

        for (ActiveOverlay overlay : ACTIVE) {
            float fade = lifeFraction(overlay, now);
            SubLevelFrame frame = frameOf(overlay);
            drawEdgeLabels(buffers, overlay.payload, fade, frame);
            drawNodeLabels(buffers, overlay.payload, fade, frame);
            drawSurfaceLabels(buffers, overlay.payload, fade, frame);
            drawFlagLabel(buffers, overlay.payload, fade, frame);
        }
        buffers.endBatch();
    }

    /**
     * The frame this overlay's network is drawn through. A network never spans two Sable
     * sub-levels — the graph BFS is single-level spatial adjacency — so the seed answers for the
     * whole snapshot, and off a contraption it answers null (a plain camera offset).
     */
    private static SubLevelFrame frameOf(ActiveOverlay overlay) {
        return SubLevelDraw.frameAt(BlockPos.of(overlay.payload.seed()));
    }

    /** The gold-flag color shared by the flagged cell's box and its floating coordinates. */
    private static final Rgb FLAG_COLOR = new Rgb(255, 210, 60);

    /**
     * A gold box around the FLAGGED cell — the crosshair position /pipegraph was run on. A
     * mid-run pipe is otherwise anonymous in the overlay (its edge rod threads straight through),
     * so this is what ties the command back to the exact pipe the player was aiming at.
     */
    private static void drawFlag(PoseStack pose, VertexConsumer buf,
                                 GraphOverlayPayload payload, float alpha, BlockPos origin) {
        BlockPos p = BlockPos.of(payload.seed());
        drawBox(pose.last().pose(), buf, p.getX() - origin.getX() + 0.5f,
                p.getY() - origin.getY() + 0.5f, p.getZ() - origin.getZ() + 0.5f,
                0.4f, FLAG_COLOR, alpha);
    }

    /**
     * The flagged cell's coordinates floating above it, SEE-THROUGH (unlike the occluded node and
     * edge labels) — the flag must be findable through walls, that is its whole job.
     */
    private static void drawFlagLabel(MultiBufferSource buffers,
                                      GraphOverlayPayload payload, float fade, SubLevelFrame frame) {
        BlockPos p = BlockPos.of(payload.seed());
        int alpha = (int) (255 * Math.max(0.25f, fade));
        Vec3 at = SubLevelDraw.project(frame, p.getX() + 0.5, p.getY() + 1.6, p.getZ() + 0.5);
        DebugRenderer.renderFloatingText(new PoseStack(), buffers, "⚑ " + p.toShortString(),
                at.x, at.y, at.z, (alpha << 24) | 0xFFD23C, 0.025f, true, 0, true);
    }

    /**
     * Floating letter above each edge's run, matching the names /pipegraph prints
     * in chat. Drawn with the vanilla debug text helper, which billboards toward
     * the camera; occluded by blocks so only labels on visible pipes show (avoids
     * the whole base's labels bleeding through terrain).
     */
    private static void drawEdgeLabels(MultiBufferSource buffers,
                                       GraphOverlayPayload payload, float fade, SubLevelFrame frame) {
        List<? extends GraphOverlayPayload.EdgeEntry> edges = payload.edges();
        for (int ei = 0; ei < edges.size(); ei++) {
            List<Long> pts = edges.get(ei).points();
            if (pts.isEmpty()) continue;

            BlockPos midHigh = BlockPos.of(pts.get(pts.size() / 2));
            BlockPos midLow = BlockPos.of(pts.get(Math.max(0, (pts.size() - 1) / 2)));
            Vec3 at = SubLevelDraw.project(frame,
                    (midHigh.getX() + midLow.getX()) / 2.0 + 0.5,
                    (midHigh.getY() + midLow.getY()) / 2.0 + 1.15,
                    (midHigh.getZ() + midLow.getZ()) / 2.0 + 0.5);

            int alpha = (int) (255 * Math.max(0.25f, fade));
            int color = (alpha << 24) | 0xFFFF55;
            DebugRenderer.renderFloatingText(new PoseStack(), buffers,
                    GraphOverlayPayload.edgeLetter(ei), at.x, at.y, at.z, color,
                    0.025f, true, 0, false);
        }
    }

    /**
     * Floating label above each source/sink node: the block it is (colored by kind),
     * then its fluid/RPM line(s) below. Junctions carry an empty label and are skipped
     * so the overlay stays readable. Mirrors {@link #drawEdgeLabels}' billboarding.
     */
    private static void drawNodeLabels(MultiBufferSource buffers,
                                       GraphOverlayPayload payload, float fade, SubLevelFrame frame) {
        for (var n : payload.nodes()) {
            if (n.label().isEmpty()) continue;
            String[] lines = n.label().split("\n");
            int alpha = (int) (255 * Math.max(0.25f, fade));
            for (int i = 0; i < lines.length; i++) {
                int rgb = i == 0 ? nodeRgb(n.kind()) : 0xD0D0D0;
                // Stack the lines in the world, not in the plot: a label column that leaned with a
                // tilted hull would read as part of the ship rather than as text above the node.
                Vec3 at = SubLevelDraw.project(frame, n.x() + 0.5, n.y() + 1.35, n.z() + 0.5);
                DebugRenderer.renderFloatingText(new PoseStack(), buffers, lines[i],
                        at.x, at.y - i * 0.19, at.z, (alpha << 24) | rgb, 0.02f, true, 0, false);
            }
        }
    }

    /** Label color per node kind, matching the box colors in {@link #drawNodes}. */
    private static int nodeRgb(byte kind) {
        return switch (kind) {
            case GraphOverlayPayload.NodeEntry.KIND_HANDLER -> 0x40DC40;
            case GraphOverlayPayload.NodeEntry.KIND_PUMP -> 0xFA8C1E;
            case GraphOverlayPayload.NodeEntry.KIND_OPEN_END -> 0x50B4FF;
            default -> 0xFFFFFF;
        };
    }

    /** An overlay's remaining life as 1→0 across the 30 s lifetime — the fade every drawer scales by. */
    private static float lifeFraction(ActiveOverlay overlay, long now) {
        long age = now - overlay.firstSeenMs;
        long lifetimeMs = LIFETIME_TICKS * 50L;
        return 1f - Math.min(1f, age / (float) lifetimeMs);
    }

    /** Cyan — the engine's computed reservoir surface, drawn distinct from every kind's box. */
    private static final Rgb SURFACE_COLOR = new Rgb(0, 230, 230);

    /**
     * A cyan horizontal square at each reservoir's ENGINE-computed surface elevation
     * ({@code baseY + fill}), spanning the block footprint. This is the height a settled pipe
     * equalizes to; drawing it in-world lets you compare it against Create's own rendered tank
     * fluid, which can sit at a different height — so a "pipe doesn't match the tank" gap is visibly
     * either the pipe (our bug) or Create's tank render being bumped off our surface.
     */
    private static void drawSurfaceMarkers(PoseStack pose, VertexConsumer buf,
                                           GraphOverlayPayload payload, float alpha,
                                           SubLevelFrame frame) {
        Matrix4f m = pose.last().pose();
        for (var n : payload.nodes()) {
            if (Float.isNaN(n.surfaceY())) continue;
            float y = n.surfaceY();
            Vec3 at = SubLevelDraw.project(frame, n.x() + 0.5, n.y() + 0.5, n.z() + 0.5);
            // Slightly overhang the block so the line clears the tank walls and stays visible.
            float x0 = (float) at.x - 0.55f, x1 = (float) at.x + 0.55f;
            float z0 = (float) at.z - 0.55f, z1 = (float) at.z + 0.55f;
            line(m, buf, x0, y, z0, x1, y, z0, SURFACE_COLOR, alpha);
            line(m, buf, x1, y, z0, x1, y, z1, SURFACE_COLOR, alpha);
            line(m, buf, x1, y, z1, x0, y, z1, SURFACE_COLOR, alpha);
            line(m, buf, x0, y, z1, x0, y, z0, SURFACE_COLOR, alpha);
        }
    }

    /** The surface elevation value floating at the marker, SEE-THROUGH so it reads against the tank. */
    private static void drawSurfaceLabels(MultiBufferSource buffers,
                                          GraphOverlayPayload payload, float fade, SubLevelFrame frame) {
        for (var n : payload.nodes()) {
            if (Float.isNaN(n.surfaceY())) continue;
            int alpha = (int) (255 * Math.max(0.25f, fade));
            Vec3 at = SubLevelDraw.project(frame, n.x() + 0.5, n.y() + 0.5, n.z() + 0.5);
            DebugRenderer.renderFloatingText(new PoseStack(), buffers,
                    String.format("surface %.2f", n.surfaceY()),
                    at.x, n.surfaceY() + 0.05, at.z,
                    (alpha << 24) | 0x00E6E6, 0.02f, true, 0, true);
        }
    }

    /** Node markers — a small wireframe box per node, colored by kind (depth-tested lines). */
    private static void drawNodes(PoseStack pose, VertexConsumer buf,
                                  GraphOverlayPayload payload, float alpha, BlockPos origin) {
        Matrix4f m = pose.last().pose();
        for (var n : payload.nodes()) {
            Rgb color = switch (n.kind()) {
                case GraphOverlayPayload.NodeEntry.KIND_HANDLER -> new Rgb(64, 220, 64);
                case GraphOverlayPayload.NodeEntry.KIND_PUMP -> new Rgb(250, 140, 30);
                case GraphOverlayPayload.NodeEntry.KIND_OPEN_END -> new Rgb(80, 180, 255);
                default -> new Rgb(255, 255, 255);
            };
            drawBox(m, buf, n.x() - origin.getX() + 0.5f, n.y() - origin.getY() + 0.5f,
                    n.z() - origin.getZ() + 0.5f, 0.25f, color, alpha);
        }
    }

    /**
     * Edge rods — a thin extruded square down each pipe's centre, colored by the pressure gradient
     * along the run when fluid can reach it, dim gray when dry, magenta when held (the letters give
     * identity). Flowing edges also get an arrowhead. All in the no-depth quad batch so the rod is
     * visible through the pipe body.
     */
    private static void drawEdges(PoseStack pose, VertexConsumer buf,
                                  GraphOverlayPayload payload, float alpha, BlockPos origin) {
        Matrix4f m = pose.last().pose();
        for (var e : payload.edges()) {
            boolean flowing = e.direction() == GraphOverlayPayload.EdgeEntry.DIR_FORWARD;
            boolean held = e.direction() == GraphOverlayPayload.EdgeEntry.DIR_HELD;
            List<Long> pts = e.points();
            List<Float> pressures = e.pressures();
            // A HELD column is drawn solid magenta (no gradient) so the stored head reads at a glance.
            boolean gradient = !held && pressures.size() == pts.size() && pts.size() >= 2;
            Rgb fallback = held ? HELD_EDGE_COLOR : DRY_EDGE_COLOR;

            for (int i = 1; i < pts.size(); i++) {
                BlockPos p0 = BlockPos.of(pts.get(i - 1));
                BlockPos p1 = BlockPos.of(pts.get(i));
                Rgb startColor = gradient ? pressureColor(pressures.get(i - 1)) : fallback;
                Rgb endColor = gradient ? pressureColor(pressures.get(i)) : fallback;
                rodSegment(m, buf,
                        p0.getX() - origin.getX() + 0.5f, p0.getY() - origin.getY() + 0.5f,
                        p0.getZ() - origin.getZ() + 0.5f,
                        p1.getX() - origin.getX() + 0.5f, p1.getY() - origin.getY() + 0.5f,
                        p1.getZ() - origin.getZ() + 0.5f,
                        startColor, endColor, alpha);
            }
            if (flowing && pts.size() >= 2) {
                Rgb tip = gradient ? pressureColor(pressures.get(pts.size() - 1)) : fallback;
                BlockPos last = BlockPos.of(pts.get(pts.size() - 1));
                BlockPos prev = BlockPos.of(pts.get(pts.size() - 2));
                drawArrowheadRod(m, buf, prev.subtract(origin), last.subtract(origin), tip, alpha);
            }
        }
    }

    /**
     * Maps gauge pressure to a color ramp matching the goggle readout: red under
     * suction, amber near ambient, green for a healthy column, cyan when strongly
     * pressurized. The ramp spans -8 (the suction limit) to +16 blocks of head.
     */
    private static Rgb pressureColor(float pressureBlocks) {
        float t = Math.clamp((pressureBlocks + 8f) / 24f, 0f, 1f);
        return hsvToRgb(t * 0.5f, 0.85f, 1f);
    }

    /** Dry runs (no reservoir can reach them) are dim gray — no pressure to show. */
    private static final Rgb DRY_EDGE_COLOR = new Rgb(150, 150, 150);

    /** A pump's HELD/stored column (dead-headed by a shut valve): magenta, distinct from the ramp. */
    private static final Rgb HELD_EDGE_COLOR = new Rgb(220, 80, 220);

    /** HSV → RGB; hue, saturation, and value each in 0..1, channels out in 0..255. */
    private static Rgb hsvToRgb(float h, float s, float v) {
        // The standard hue-sector (hexcone) HSV conversion.
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
        return new Rgb((int) (r * 255), (int) (g * 255), (int) (b * 255));
    }

    /** Two short backward-flaring rods at the run's end forming an arrowhead along the flow. */
    private static void drawArrowheadRod(Matrix4f m, VertexConsumer buf,
                                         BlockPos from, BlockPos to, Rgb color, float alpha) {
        float fx = from.getX() + 0.5f, fy = from.getY() + 0.5f, fz = from.getZ() + 0.5f;
        float tx = to.getX() + 0.5f, ty = to.getY() + 0.5f, tz = to.getZ() + 0.5f;
        float dx = tx - fx, dy = ty - fy, dz = tz - fz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001f) return;
        dx /= len; dy /= len; dz /= len;
        float back = 0.35f, side = 0.2f;
        float bx = tx - dx * back, by = ty - dy * back, bz = tz - dz * back;
        // Perpendicular axis (pick world-up unless edge is vertical).
        float px, py, pz;
        if (Math.abs(dy) > 0.9f) { px = 1; py = 0; pz = 0; }
        else { px = 0; py = 1; pz = 0; }
        rodSegment(m, buf, tx, ty, tz, bx + px * side, by + py * side, bz + pz * side, color, color, alpha);
        rodSegment(m, buf, tx, ty, tz, bx - px * side, by - py * side, bz - pz * side, color, color, alpha);
    }

    /**
     * A hairline rod down the pipe centre, vertex colors blended start→end. Emitted into the
     * no-depth quad batch so it reads as a line threaded through the pipe regardless of the pipe's
     * opacity; the geometry is the shared {@link RodRender}, which the pump reach sleeve draws with
     * too (at a half-extent that wraps the pipe instead of threading it).
     */
    private static void rodSegment(Matrix4f m, VertexConsumer buf,
                                   float x0, float y0, float z0,
                                   float x1, float y1, float z1,
                                   Rgb startColor, Rgb endColor, float alpha) {
        RodRender.segment(m, buf, x0, y0, z0, x1, y1, z1,
                startColor, endColor, ROD_HALF, (int) (255 * Math.max(0.25f, alpha)));
    }

    private static void drawBox(Matrix4f m, VertexConsumer buf,
                                float cx, float cy, float cz, float s,
                                Rgb color, float alpha) {
        float x0 = cx - s, x1 = cx + s;
        float y0 = cy - s, y1 = cy + s;
        float z0 = cz - s, z1 = cz + s;
        // 12 edges of a cube.
        line(m, buf, x0, y0, z0, x1, y0, z0, color, alpha);
        line(m, buf, x1, y0, z0, x1, y0, z1, color, alpha);
        line(m, buf, x1, y0, z1, x0, y0, z1, color, alpha);
        line(m, buf, x0, y0, z1, x0, y0, z0, color, alpha);
        line(m, buf, x0, y1, z0, x1, y1, z0, color, alpha);
        line(m, buf, x1, y1, z0, x1, y1, z1, color, alpha);
        line(m, buf, x1, y1, z1, x0, y1, z1, color, alpha);
        line(m, buf, x0, y1, z1, x0, y1, z0, color, alpha);
        line(m, buf, x0, y0, z0, x0, y1, z0, color, alpha);
        line(m, buf, x1, y0, z0, x1, y1, z0, color, alpha);
        line(m, buf, x1, y0, z1, x1, y1, z1, color, alpha);
        line(m, buf, x0, y0, z1, x0, y1, z1, color, alpha);
    }

    private static void line(Matrix4f m, VertexConsumer buf,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             Rgb color, float alpha) {
        gradientLine(m, buf, x0, y0, z0, x1, y1, z1, color, color, alpha);
    }

    /** A line whose vertex colors differ; the GPU interpolates the gradient between them. */
    private static void gradientLine(Matrix4f m, VertexConsumer buf,
                                     float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     Rgb startColor, Rgb endColor, float alpha) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5f) return;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        int alphaByte = (int) (255 * Math.max(0.15f, alpha));
        buf.addVertex(m, x0, y0, z0)
                .setColor(startColor.r(), startColor.g(), startColor.b(), alphaByte)
                .setNormal(nx, ny, nz);
        buf.addVertex(m, x1, y1, z1)
                .setColor(endColor.r(), endColor.g(), endColor.b(), alphaByte)
                .setNormal(nx, ny, nz);
    }

    /**
     * A live overlay: {@code firstSeenMs} anchors the 30s lifetime and fade (a refresh does not
     * extend it), {@code lastRequestMs} throttles the re-request, {@code payload} is swapped in place
     * on each refresh.
     */
    private static final class ActiveOverlay {
        private GraphOverlayPayload payload;
        private final long firstSeenMs;
        private long lastRequestMs;

        ActiveOverlay(GraphOverlayPayload payload, long firstSeenMs) {
            this.payload = payload;
            this.firstSeenMs = firstSeenMs;
            this.lastRequestMs = firstSeenMs;
        }
    }
}
