package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.AxisPipeBlock;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import de.devin.pipesnphysics.compat.PipeLevelData;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.platform.NeoForgeCatnipServices;
import net.createmod.catnip.render.FluidRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.HashMap;
import java.util.Map;

/**
 * In-pipe LEVEL renderer for {@code PIPE_LEVEL_RENDER}: draws fluid inside a straight pipe as a
 * partial fill instead of Create's binary full-or-empty — a RESTING cell at its solved waterline, and
 * (when {@code PIPE_FLOW_PARTIAL_FILL} is on) a FLOWING horizontal cell at its head
 * waterline too, so the flowing fill rises with the head. Everything it draws comes from the dedicated synced {@link PipeLevelData}
 * fields stamped server-side (see {@link CreatePipeRendering}): the waterline + direction, the
 * engine-owned travelling-front fill, and the fluid — it reads none of Create's Flow state. The
 * two pipe-render mixins hide a stamped cell from Create so this renderer owns it.
 *
 * Backend-agnostic by design: it draws from {@link RenderLevelStageEvent} rather than a BER, because
 * under Flywheel Create's pipe BER is suppressed (the visual handles it) — an event hook always runs.
 * It is a deliberately simple per-frame scan of nearby {@link StraightPipeBlockEntity}s; instancing
 * (Flywheel) would be the path to scale this to large networks. Resting/straight pipes only.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class PipeLevelRenderer {
    /** Fluid-column half-width inside the pipe (matches Create's stream radius, {@code 3/16}). */
    private static final float PIPE_RADIUS = 3f / 16f;
    /**
     * Pull the fluid back from a NON-CONTINUING axis end (an open mouth, a dead end, a reservoir
     * face) so its end quad doesn't sit flush with the pipe opening and z-fight the rim — the level
     * renderer's own version of the {@code OPEN_END_INSET} the Create-pipe mixins apply. Create's
     * visible gap is {@code 0.125·0.5}; matched here.
     */
    private static final float OPEN_END_INSET = 0.0625f;

    /**
     * Scroll speed bounds (blocks/sec). The scroll runs at the fluid's real speed — the synced
     * front advance rate (cells/tick · 20) — so texture and fill front move together.
     * {@link #MAX_SCROLL} bounds it so a brisk flow (e.g. a pump pulling from an infinite source)
     * does not scroll frantically fast; the speed still climbs with flow, it just caps.
     */
    private static final float MIN_SCROLL = 0.4f;
    private static final float MAX_SCROLL = 4f;

    /**
     * How many chunks out from the camera to scan for pipes. Bounded for cost; the mixins hide
     * marked flows at any distance, so a marked pipe farther than this shows no fluid (a known
     * limit — tune later / distance-gate the mixin for a wider scan).
     */
    private static final int SCAN_RADIUS_CHUNKS = 6;

    /** Cached to avoid {@code Direction.values()} cloning a 6-element array on every hot-path call. */
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final MultiBufferSource.BufferSource OWN_BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(2048));

    /** Ticks over which a just-emptied cell's waterline recedes to zero instead of popping. */
    private static final float FADE_TICKS = 6f;

    /**
     * Last-rendered waterline per pipe cell, so a cell that loses its solved fill RECEDES to empty over
     * {@link #FADE_TICKS} rather than vanishing in a frame (the "just despawns when empty" report). A
     * live cell refreshes its entry every frame; once the solve stops stamping it, the entry drains and
     * is dropped. Stale entries (a cell that left the scan radius) are pruned by {@code lastSeen}.
     * Client-render bookkeeping only; keyed by cell, cleared when there is no level.
     */
    private static final Map<BlockPos, Fade> FADES = new HashMap<>();

    /** Mutable per-cell fade record (see {@link #FADES}). */
    private static final class Fade {
        int data;
        FluidStack fluid;
        float fadeStart = -1f; // tick the recede began, -1 while the cell is still solved/full
        float lastSeen;
    }

    /**
     * Per-cell front interpolation state: when the synced front value last CHANGED, so the fill can
     * be extrapolated by the synced advance rate between server stamps — the server integrates the
     * same rate per tick, so the extrapolation meets the next stamp and the front advances smoothly
     * instead of stepping at 20 tps. (This replaced Create's {@code LerpedFloat} sub-tick smoothing,
     * which came for free while the front rode {@code Flow.progress}.)
     */
    private static final Map<BlockPos, FrontAnim> FRONTS = new HashMap<>();

    /** Mutable per-cell front record (see {@link #FRONTS}). */
    private static final class FrontAnim {
        int data;
        float syncTick;
        float lastSeen;
    }

    private PipeLevelRenderer() {}

    /** Drop all animation records — called when the player leaves the world or changes dimension. */
    public static void clear() {
        FADES.clear();
        FRONTS.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!PipesNPhysicsConfig.PIPE_LEVEL_RENDER.get()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        float now = AnimationTickHolder.getTicks() + AnimationTickHolder.getPartialTicks();
        boolean drewAny = false;

        int camChunkX = SectionPos.blockToSectionCoord(Mth.floor(camera.x));
        int camChunkZ = SectionPos.blockToSectionCoord(Mth.floor(camera.z));
        for (int dx = -SCAN_RADIUS_CHUNKS; dx <= SCAN_RADIUS_CHUNKS; dx++) {
            for (int dz = -SCAN_RADIUS_CHUNKS; dz <= SCAN_RADIUS_CHUNKS; dz++) {
                if (!(level.getChunk(camChunkX + dx, camChunkZ + dz, ChunkStatus.FULL, false)
                        instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof StraightPipeBlockEntity pipe) {
                        drewAny |= renderCell(level, pipe, camera, poseStack, now);
                    }
                }
            }
        }

        // Drop animation records for cells that left the scan radius (a fading cell keeps refreshing
        // lastSeen until its recede finishes and removes it explicitly).
        FADES.entrySet().removeIf(e -> now - e.getValue().lastSeen > FADE_TICKS + 4f);
        FRONTS.entrySet().removeIf(e -> now - e.getValue().lastSeen > FADE_TICKS + 4f);

        if (drewAny) OWN_BUFFER.endBatch();
    }

    /** Draw one pipe cell's waterline, returning whether anything was emitted. */
    private static boolean renderCell(ClientLevel level, StraightPipeBlockEntity be,
                                      Vec3 camera, PoseStack poseStack, float now) {
        BlockState state = be.getBlockState();
        if (!state.hasProperty(AxisPipeBlock.AXIS)) return false;

        FluidTransportBehaviour pipe = be.getBehaviour(FluidTransportBehaviour.TYPE);
        if (!(pipe instanceof PipeLevelData holder)) return false;
        BlockPos cellPos = be.getBlockPos();

        // Level + direction + front + fluid all come from the dedicated synced fields — the renderer
        // no longer reads Create's Flow objects at all.
        int data = holder.pipesnphysics$getLevelData();
        FluidStack fluid = data != 0 && !holder.pipesnphysics$getRenderFluid().isEmpty()
                ? holder.pipesnphysics$getRenderFluid() : null;

        boolean fading = false;
        float fadeScale = 1f;
        if (data != 0 && fluid != null) {
            // Live cell: remember it so a later drain can recede from this fill instead of popping.
            Fade f = FADES.computeIfAbsent(cellPos.immutable(), k -> new Fade());
            f.data = data;
            f.fluid = fluid;
            f.fadeStart = -1f;
            f.lastSeen = now;
        } else {
            // No solved fill this frame: recede a just-emptied cell to zero over FADE_TICKS.
            Fade f = FADES.get(cellPos);
            if (f == null) return false;
            if (f.fadeStart < 0f) f.fadeStart = now;
            float elapsed = now - f.fadeStart;
            if (elapsed >= FADE_TICKS) {
                FADES.remove(cellPos);
                return false;
            }
            fading = true;
            fadeScale = 1f - elapsed / FADE_TICKS;
            data = f.data;
            fluid = f.fluid;
            f.lastSeen = now;
        }

        float frac = CreatePipeRendering.levelFraction(data) * fadeScale;
        if (frac <= 0) return false;

        // A draining (fading) cell renders STILL and recedes purely by frac — no scroll, no travelling
        // front (its flow is gone). Only a live flowing cell keeps its direction/front.
        int dirIndex = CreatePipeRendering.levelFlowDir(data);
        Direction flowDir = fading || dirIndex < 0 ? null : Direction.from3DDataValue(dirIndex);

        // A flowing (flowDir set) and a resting cell both carry a true head-surface elevation as their
        // fraction; a flowing horizontal cell only differs in that it never drops below a visible floor
        // (never blank while carrying flow — {@link #flowingTop}), and a flowing vertical bore fills.
        // Whether a flowing run is drawn partial or full is decided server-side (it stamps a full
        // fraction when the partial-fill toggle is off), so this reads no config.
        boolean flowing = flowDir != null;

        float lo = 0.5f - PIPE_RADIUS;
        float hi = 0.5f + PIPE_RADIUS;
        float x0, y0, z0, x1, y1, z1;
        switch (state.getValue(AxisPipeBlock.AXIS)) {
            case Y -> {
                // Vertical tube: a flowing bore fills fully; a resting surface rises to its waterline.
                x0 = lo; z0 = lo; x1 = hi; z1 = hi;
                y0 = 0f; y1 = flowing ? 1f : Math.min(frac, 1f);
            }
            case X -> {
                // Horizontal tube along X: a horizontal surface partway up the tube cross-section.
                float top = flowing ? flowingTop(frac, lo, hi) : horizontalTop(frac, lo, hi);
                if (top <= lo) return false;
                x0 = 0f; x1 = 1f;
                y0 = lo; y1 = top;
                z0 = lo; z1 = hi;
            }
            default -> {
                float top = flowing ? flowingTop(frac, lo, hi) : horizontalTop(frac, lo, hi);
                if (top <= lo) return false;
                z0 = 0f; z1 = 1f;
                y0 = lo; y1 = top;
                x0 = lo; x1 = hi;
            }
        }

        int light = LevelRenderer.getLightColor(level, cellPos);

        // The travelling front: clip the cell's fluid along the flow axis by the engine-owned front
        // fraction (integrated server-side from the solved flow rate into the synced field), from
        // the inbound face — so a filling pipe shows fluid moving IN cell-by-cell instead of each
        // cell popping full at once. Extrapolated by the synced rate between stamps for smoothness.
        if (flowDir != null) {
            float front = displayedFront(cellPos, holder.pipesnphysics$getFrontData(), now);
            if (front < 1f) {
                boolean positive = flowDir.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                switch (flowDir.getAxis()) {
                    case X -> { if (positive) x1 = x0 + front * (x1 - x0); else x0 = x1 - front * (x1 - x0); }
                    case Y -> { if (positive) y1 = y0 + front * (y1 - y0); else y0 = y1 - front * (y1 - y0); }
                    default -> { if (positive) z1 = z0 + front * (z1 - z0); else z0 = z1 - front * (z1 - z0); }
                }
            }
        }

        // Inset each axis end that does NOT continue into another fluid pipe (an open mouth, a dead
        // end, or a reservoir face): its end quad would otherwise be coplanar with the pipe opening
        // and z-fight the rim (the open-end flicker). An interior joint between two cells has a pipe
        // on both sides, so it stays seamless. Applied after the front clip so the mouth end is inset
        // whatever the fill; guarded so the box never inverts.
        Direction.Axis axis = state.getValue(AxisPipeBlock.AXIS);
        boolean openNeg = FluidPropagator.getPipe(level,
                cellPos.relative(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE))) == null;
        boolean openPos = FluidPropagator.getPipe(level,
                cellPos.relative(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE))) == null;
        switch (axis) {
            case X -> { if (openNeg) x0 = Math.min(x0 + OPEN_END_INSET, x1); if (openPos) x1 = Math.max(x1 - OPEN_END_INSET, x0); }
            case Y -> { if (openNeg) y0 = Math.min(y0 + OPEN_END_INSET, y1); if (openPos) y1 = Math.max(y1 - OPEN_END_INSET, y0); }
            default -> { if (openNeg) z0 = Math.min(z0 + OPEN_END_INSET, z1); if (openPos) z1 = Math.max(z1 - OPEN_END_INSET, z0); }
        }

        poseStack.pushPose();
        poseStack.translate(cellPos.getX() - camera.x, cellPos.getY() - camera.y, cellPos.getZ() - camera.z);
        if (flowDir == null) {
            // Resting: the proven catnip box (still texture, non-directional).
            NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(
                    fluid, x0, y0, z0, x1, y1, z1, OWN_BUFFER, poseStack, light, true, true);
        } else {
            // Scroll at the fluid's real speed: the synced front rate (cells/tick → blocks/sec).
            // Rate 0 is a HELD front (chained edge waiting at its upstream node): draw it truly
            // still rather than flooring to MIN_SCROLL — nothing is moving yet.
            float rate = CreatePipeRendering.frontRate(holder.pipesnphysics$getFrontData());
            float speed = rate <= 0f ? 0f
                    : Math.clamp(rate * 20f, MIN_SCROLL, MAX_SCROLL)
                            * PipesNPhysicsConfig.PIPE_LEVEL_FLOW_SPEED.get().floatValue();
            renderFlowingBox(fluid, x0, y0, z0, x1, y1, z1, light, flowDir, speed, poseStack);
        }
        poseStack.popPose();
        return true;
    }

    /** Thinnest a flowing horizontal channel draws (fraction of the tube band) so it never blanks while carrying flow. */
    private static final float FLOW_MIN_FILL = 0.15f;

    /**
     * The rendered surface height for a RESTING horizontal cell: its fraction is a true surface
     * elevation, clamped into the tube band [lo, hi]. A fraction below the tube is a genuinely low
     * resting surface and the cell draws nothing ({@link #renderCell} bails on {@code top <= lo}). A
     * backed-up cell arrives at frac 1 (stamped FULL upstream) and fills the tube.
     */
    private static float horizontalTop(float frac, float lo, float hi) {
        return Math.clamp(frac, lo, hi);
    }

    /**
     * The surface height for a FLOWING horizontal cell: its fraction is the head waterline (same true
     * elevation as a resting cell, flattened per run server-side so it tracks the head without stepping),
     * clamped into the tube band but never below a visible floor — so a flowing pipe always shows at
     * least a sliver and fills to full as the head rises above the bore.
     */
    private static float flowingTop(float frac, float lo, float hi) {
        return Math.clamp(frac, lo + FLOW_MIN_FILL * (hi - lo), hi);
    }

    /**
     * Draw a flowing cell's box with the SAME still texture as a resting cell — only SCROLLED along
     * the flow axis over time, so the surface looks identical whether moving or not and simply slides
     * when there is flow (switching to the ripply animated flowing texture read as a jarring swap).
     * Each face is a small custom quad ({@link #pipeFace}) because catnip's tiled-face helper can't
     * scroll, and offsets the UV per block tile so the scroll wraps seamlessly.
     */
    private static void renderFlowingBox(FluidStack stack, float x0, float y0, float z0,
                                         float x1, float y1, float z1, int light, Direction flowDir,
                                         float speed, PoseStack ms) {
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(stack.getFluid());
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ext.getStillTexture(stack));
        int color = ext.getTintColor(stack);
        int luminosity = Math.max((light >> 4) & 0xF, stack.getFluid().getFluidType().getLightLevel(stack));
        int lightOut = (light & 0xF00000) | luminosity << 4;
        VertexConsumer builder = FluidRenderHelper.getFluidBuilder(OWN_BUFFER);

        float t = (AnimationTickHolder.getTicks() + AnimationTickHolder.getPartialTicks()) / 20f;
        float scroll = (t * speed) % 1f;
        // Move the texture WITH the fluid (toward the downstream face). Flip this sign to reverse.
        if (flowDir.getAxisDirection() == Direction.AxisDirection.POSITIVE) scroll = -scroll;
        int fAxis = flowDir.getAxis().ordinal();

        float[] min = {x0, y0, z0};
        float[] max = {x1, y1, z1};
        PoseStack.Pose peek = ms.last();
        for (Direction side : DIRECTIONS) {
            int nAxis = side.getAxis().ordinal();
            float depth = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max[nAxis] : min[nAxis];
            int inA = -1, inB = -1;
            for (int ax = 0; ax < 3; ax++) {
                if (ax == nAxis) continue;
                if (inA < 0) inA = ax; else inB = ax;
            }
            // Grain (texture V) runs along the flow axis when it lies in this face; the other in-plane
            // axis is the cross (U). A flow-perpendicular cap (flow == normal) gets no scroll.
            int vAxis, uAxis;
            float faceScroll;
            if (fAxis != nAxis) {
                vAxis = fAxis;
                uAxis = fAxis == inA ? inB : inA;
                faceScroll = scroll;
            } else {
                uAxis = inA;
                vAxis = inB;
                faceScroll = 0f;
            }
            pipeFace(builder, peek, sprite, color, lightOut, nAxis, depth,
                    side.getAxisDirection() == Direction.AxisDirection.POSITIVE,
                    uAxis, min[uAxis], max[uAxis], vAxis, min[vAxis], max[vAxis], faceScroll);
        }
    }

    /**
     * Emit one box face, tiling the sprite one tile per block along both in-plane axes and scrolling
     * the grain (V) by {@code scroll} along the flow axis — splitting at tile boundaries so the scroll
     * wraps seamlessly. Winding is derived from the in-plane basis vs the outward normal, so the face
     * is not back-face culled.
     */
    private static void pipeFace(VertexConsumer buf, PoseStack.Pose peek, TextureAtlasSprite sprite,
                                 int color, int light, int nAxis, float depth, boolean positive,
                                 int uAxis, float uMin, float uMax, int vAxis, float vMin, float vMax,
                                 float scroll) {
        float[] normal = {0f, 0f, 0f};
        normal[nAxis] = positive ? 1f : -1f;
        float[] eu = {0f, 0f, 0f};
        eu[uAxis] = 1f;
        float[] ev = {0f, 0f, 0f};
        ev[vAxis] = 1f;
        float cx = eu[1] * ev[2] - eu[2] * ev[1];
        float cy = eu[2] * ev[0] - eu[0] * ev[2];
        float cz = eu[0] * ev[1] - eu[1] * ev[0];
        boolean rightHanded = cx * normal[0] + cy * normal[1] + cz * normal[2] > 0;

        int a = color >> 24 & 0xff;
        if (a == 0) a = 255;
        int r = color >> 16 & 0xff;
        int g = color >> 8 & 0xff;
        int b = color & 0xff;

        // Nudge UVs off the sprite edge toward its centre (catnip's renderTiledFace does the same),
        // so a tile seam doesn't bleed into a neighbouring atlas sprite under mipmapping.
        float shrink = sprite.uvShrinkRatio() * 0.25f;
        float centerU = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * 0.5f;
        float centerV = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * 0.5f;

        for (float v = vMin; v < vMax - 1e-5f; ) {
            float shifted = v + scroll;
            float v2 = Math.min((float) Math.floor(shifted) + 1f - scroll, vMax);
            float frac = shifted - (float) Math.floor(shifted);
            float sv0 = Mth.lerp(shrink, sprite.getV(frac), centerV);
            float sv1 = Mth.lerp(shrink, sprite.getV(frac + (v2 - v)), centerV);
            for (float u = uMin; u < uMax - 1e-5f; ) {
                float u2 = Math.min((float) Math.floor(u) + 1f, uMax);
                float uf = u - (float) Math.floor(u);
                float su0 = Mth.lerp(shrink, sprite.getU(uf), centerU);
                float su1 = Mth.lerp(shrink, sprite.getU(uf + (u2 - u)), centerU);
                addQuad(buf, peek, nAxis, depth, uAxis, u, u2, vAxis, v, v2,
                        su0, su1, sv0, sv1, r, g, b, a, light, normal, rightHanded);
                u = u2;
            }
            v = v2;
        }
    }

    private static void addQuad(VertexConsumer buf, PoseStack.Pose peek, int nAxis, float depth,
                                int uAxis, float u0, float u1, int vAxis, float v0, float v1,
                                float su0, float su1, float sv0, float sv1,
                                int r, int g, int b, int a, int light, float[] normal, boolean rightHanded) {
        if (rightHanded) {
            vertex(buf, peek, nAxis, depth, uAxis, u0, vAxis, v0, su0, sv0, r, g, b, a, light, normal);
            vertex(buf, peek, nAxis, depth, uAxis, u1, vAxis, v0, su1, sv0, r, g, b, a, light, normal);
            vertex(buf, peek, nAxis, depth, uAxis, u1, vAxis, v1, su1, sv1, r, g, b, a, light, normal);
            vertex(buf, peek, nAxis, depth, uAxis, u0, vAxis, v1, su0, sv1, r, g, b, a, light, normal);
        } else {
            vertex(buf, peek, nAxis, depth, uAxis, u0, vAxis, v0, su0, sv0, r, g, b, a, light, normal);
            vertex(buf, peek, nAxis, depth, uAxis, u0, vAxis, v1, su0, sv1, r, g, b, a, light, normal);
            vertex(buf, peek, nAxis, depth, uAxis, u1, vAxis, v1, su1, sv1, r, g, b, a, light, normal);
            vertex(buf, peek, nAxis, depth, uAxis, u1, vAxis, v0, su1, sv0, r, g, b, a, light, normal);
        }
    }

    private static void vertex(VertexConsumer buf, PoseStack.Pose peek, int nAxis, float depth,
                               int uAxis, float u, int vAxis, float v, float texU, float texV,
                               int r, int g, int b, int a, int light, float[] normal) {
        float[] p = {0f, 0f, 0f};
        p[nAxis] = depth;
        p[uAxis] = u;
        p[vAxis] = v;
        buf.addVertex(peek.pose(), p[0], p[1], p[2])
                .setColor(r, g, b, a)
                .setUv(texU, texV)
                .setLight(light)
                .setNormal(peek, normal[0], normal[1], normal[2]);
    }

    /**
     * The 0..1 front fill of a cell as displayed THIS frame: the synced fraction plus the synced
     * advance rate times the time since that value arrived, clamped to 1. The server integrates the
     * same rate each tick, so the extrapolation meets the next stamp and the fill advances smoothly
     * between server updates instead of stepping at 20 tps. An untracked cell (no front data)
     * renders unclipped — full.
     */
    private static float displayedFront(BlockPos cell, int frontData, float now) {
        if (frontData == 0) return 1f;
        FrontAnim anim = FRONTS.computeIfAbsent(cell.immutable(), k -> new FrontAnim());
        if (anim.data != frontData) {
            anim.data = frontData;
            anim.syncTick = now;
        }
        anim.lastSeen = now;
        return Math.min(1f, CreatePipeRendering.frontFraction(frontData)
                + CreatePipeRendering.frontRate(frontData) * (now - anim.syncTick));
    }
}
