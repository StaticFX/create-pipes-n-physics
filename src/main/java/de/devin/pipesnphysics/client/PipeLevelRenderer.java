package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
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
 * SPIKE renderer for {@code EXPERIMENTAL_PIPE_LEVEL_RENDER}: draws resting fluid inside a straight
 * pipe at its actual WATERLINE — a partial fill at the solved surface — instead of Create's binary
 * full-or-empty fill. The waterline is encoded server-side onto the pipe flow (see
 * {@link CreatePipeRendering}); the two pipe-render mixins hide a marked flow from Create so this
 * renderer owns it.
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
     * Scroll speed (blocks/sec) per unit of flow pressure — Create's {@code flowPressure} is ∝ mB/t.
     * {@link #MAX_SCROLL} bounds it so a brisk flow (e.g. a pump pulling from an infinite source) does
     * not scroll frantically fast; the fill still SPEEDS UP with flow, it just stops climbing past the cap.
     */
    private static final float SCROLL_PER_PRESSURE = 0.08f;
    private static final float MIN_SCROLL = 0.4f;
    private static final float MAX_SCROLL = 4f;

    /**
     * How many chunks out from the camera to scan for pipes. Bounded for the spike; the mixins hide
     * marked flows at any distance, so a marked pipe farther than this shows no fluid (a known spike
     * limit — tune later / distance-gate the mixin for a real rollout).
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

    private PipeLevelRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!PipesNPhysicsConfig.EXPERIMENTAL_PIPE_LEVEL_RENDER.get()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            FADES.clear();
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

        // Drop fade records for cells that left the scan radius (a fading cell keeps refreshing lastSeen
        // until its recede finishes and removes it explicitly).
        FADES.entrySet().removeIf(e -> now - e.getValue().lastSeen > FADE_TICKS + 4f);

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

        // Level + direction come from the dedicated synced field; the fluid TYPE from any flow (which
        // Create still syncs — we only hid it from Create's own draw).
        int data = holder.pipesnphysics$getLevelData();
        FluidStack fluid = data != 0 ? anyFluid(pipe) : null;

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

        float lo = 0.5f - PIPE_RADIUS;
        float hi = 0.5f + PIPE_RADIUS;
        float x0, y0, z0, x1, y1, z1;
        switch (state.getValue(AxisPipeBlock.AXIS)) {
            case Y -> {
                // Vertical tube: fills the full block height up to the waterline fraction.
                x0 = lo; z0 = lo; x1 = hi; z1 = hi;
                y0 = 0f; y1 = Math.min(frac, 1f);
            }
            case X -> {
                // Horizontal tube along X: a horizontal surface partway up the tube cross-section.
                float top = horizontalTop(frac, lo, hi, flowDir);
                if (top <= lo) return false;
                x0 = 0f; x1 = 1f;
                y0 = lo; y1 = top;
                z0 = lo; z1 = hi;
            }
            default -> {
                float top = horizontalTop(frac, lo, hi, flowDir);
                if (top <= lo) return false;
                z0 = 0f; z1 = 1f;
                y0 = lo; y1 = top;
                x0 = lo; x1 = hi;
            }
        }

        int light = LevelRenderer.getLightColor(level, cellPos);

        // Advance the travelling front: clip the cell's fluid along the flow axis by how far it has
        // filled (the connection fill progress chargeEdge animates), from the inbound face. So a
        // filling pipe shows fluid moving IN cell-by-cell instead of each cell popping full at once.
        if (flowDir != null) {
            float front = frontProgress(pipe, flowDir, AnimationTickHolder.getPartialTicks());
            if (front < 1f) {
                boolean positive = flowDir.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                switch (flowDir.getAxis()) {
                    case X -> { if (positive) x1 = x0 + front * (x1 - x0); else x0 = x1 - front * (x1 - x0); }
                    case Y -> { if (positive) y1 = y0 + front * (y1 - y0); else y0 = y1 - front * (y1 - y0); }
                    default -> { if (positive) z1 = z0 + front * (z1 - z0); else z0 = z1 - front * (z1 - z0); }
                }
            }
        }

        poseStack.pushPose();
        poseStack.translate(cellPos.getX() - camera.x, cellPos.getY() - camera.y, cellPos.getZ() - camera.z);
        if (flowDir == null) {
            // Resting: the proven catnip box (still texture, non-directional).
            NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(
                    fluid, x0, y0, z0, x1, y1, z1, OWN_BUFFER, poseStack, light, true, true);
        } else {
            renderFlowingBox(fluid, x0, y0, z0, x1, y1, z1, light, flowDir, maxPressure(pipe), poseStack);
        }
        poseStack.popPose();
        return true;
    }

    /**
     * The rendered surface height for a HORIZONTAL cell. The waterline is clamped into the tube band
     * [lo, hi]: below lo the tube is empty. A cell whose waterline sits below the tube would draw
     * nothing — correct at REST (the fluid is genuinely below the pipe) but wrong while FLOWING (a
     * pipe actively carrying fluid is full of it), so a flowing cell fills the tube instead of
     * vanishing. This mirrors {@code stampWaterlines}, which only declines to stamp cells ABOVE the
     * waterline; a stamped-but-below-tube cell must therefore be a low resting surface (return lo, i.e.
     * empty) or a flowing pipe (return hi, i.e. full).
     */
    private static float horizontalTop(float frac, float lo, float hi, Direction flowDir) {
        float top = Math.clamp(frac, lo, hi);
        return top <= lo && flowDir != null ? hi : top;
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
                                         float pressure, PoseStack ms) {
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(stack.getFluid());
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ext.getStillTexture(stack));
        int color = ext.getTintColor(stack);
        int luminosity = Math.max((light >> 4) & 0xF, stack.getFluid().getFluidType().getLightLevel(stack));
        int lightOut = (light & 0xF00000) | luminosity << 4;
        VertexConsumer builder = FluidRenderHelper.getFluidBuilder(OWN_BUFFER);

        // Scroll speed tracks the flow: Create's flowPressure (∝ mB/t) drives blocks/sec, scaled by
        // the client's flow-speed setting.
        float speed = Math.clamp(pressure * SCROLL_PER_PRESSURE, MIN_SCROLL, MAX_SCROLL)
                * PipesNPhysicsConfig.EXPERIMENTAL_PIPE_LEVEL_FLOW_SPEED.get().floatValue();
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
     * How far the travelling front has filled this cell, 0..1 along the flow: the average of the
     * inbound (upstream) and outbound (downstream) connection fill progress, which {@code chargeEdge}
     * advances cell-by-cell (the inbound half fills, then the outbound). 1 once the front has passed.
     */
    private static float frontProgress(FluidTransportBehaviour pipe, Direction flowDir, float partial) {
        return 0.5f * (connProgress(pipe.getFlow(flowDir.getOpposite()), partial)
                + connProgress(pipe.getFlow(flowDir), partial));
    }

    private static float connProgress(PipeConnection.Flow flow, float partial) {
        if (flow == null) return 0f;
        if (flow.complete || flow.progress == null) return 1f;
        return flow.progress.getValue(partial);
    }

    /**
     * The largest flow pressure across this cell's connections — Create's {@code flowPressure}, which
     * {@code chargeEdge} set proportional to mB/t. Drives the scroll speed so it tracks the flow rate.
     */
    private static float maxPressure(FluidTransportBehaviour pipe) {
        float max = 0f;
        for (Direction dir : DIRECTIONS) {
            PipeConnection conn = pipe.getConnection(dir);
            if (conn == null) continue;
            max = Math.max(max, Math.max(conn.getPressure().getFirst(), conn.getPressure().getSecond()));
        }
        return max;
    }

    /** Any non-empty flow's fluid on this pipe (for its TYPE/colour/texture), or null if dry. */
    private static FluidStack anyFluid(FluidTransportBehaviour pipe) {
        for (Direction dir : DIRECTIONS) {
            PipeConnection.Flow flow = pipe.getFlow(dir);
            if (flow != null && !flow.fluid.isEmpty()) return flow.fluid;
        }
        return null;
    }
}
