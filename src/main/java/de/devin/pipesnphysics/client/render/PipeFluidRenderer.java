package de.devin.pipesnphysics.client.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.AxisPipeBlock;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.SubLevelDraw;
import de.devin.pipesnphysics.compat.SubLevelFrame;
import de.devin.pipesnphysics.engine.store.PipeFluidCell;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.createmod.catnip.animation.AnimationTickHolder;
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
 * Draws the fluid inside straight (glass) pipes from the pipes' REAL synced contents
 * ({@link PipeFluidCell}): the fill fraction is the cell's stored mB over the per-cell capacity,
 * so what is on screen is exactly the fluid that exists — no reconstruction from solver flags.
 * All smoothing of the 20 tps sync (integrated scroll phase, eased speed, held flow direction,
 * front episodes, fade-out) lives in one {@link CellAnim} per cell.
 *
 * A travelling plug is clipped along the flow axis: a fill front grows out of the inbound side, a
 * draining full cell recedes onto the outbound side. Everything else is a waterline — a liquid
 * pools at the bottom, a gas mirrors it and hangs from the top. Every cell renders through the
 * SAME scrolled-box path (a resting cell simply has a static phase), so a flow starting or
 * stopping never switches renderers and never snaps the texture. Create's own pipe-fluid
 * rendering is suppressed while the engine runs (the two pipe render mixins).
 *
 * Backend-agnostic by design: it draws from {@link RenderLevelStageEvent} rather than a BER,
 * because under Flywheel Create's pipe BER is suppressed — an event hook always runs. It is a
 * deliberately simple per-frame scan of nearby {@link StraightPipeBlockEntity}s — complete,
 * because only straight/glass pipes have a window (opaque pipes hide their contents). A Sable
 * contraption's pipes are NOT in the chunks around the camera, so each nearby contraption's own
 * plot is swept as well and its cells drawn through its frame ({@link SubLevelDraw}). The
 * animation cache is cleared by {@code ClientCleanupHandler} on logout/dimension change, which
 * is what prevents ghost fluid from a previous world.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class PipeFluidRenderer {
    /** Fluid-column half-width inside the pipe (matches Create's stream radius, {@code 3/16}). */
    private static final float PIPE_RADIUS = 3f / 16f;
    /**
     * Pull the fluid back from a NON-CONTINUING axis end (an open mouth, a dead end, a reservoir
     * face) so its end quad doesn't sit flush with the pipe opening and z-fight the rim.
     */
    private static final float OPEN_END_INSET = 0.0625f;

    /** How many chunks out from the camera to scan for pipes. */
    private static final int SCAN_RADIUS_CHUNKS = 6;

    /** Cached to avoid {@code Direction.values()} cloning a 6-element array on every hot-path call. */
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final MultiBufferSource.BufferSource OWN_BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(2048));

    /** Per-cell animation state ({@link CellAnim}) — client-render bookkeeping only. */
    private static final Map<BlockPos, CellAnim> ANIMS = new HashMap<>();

    private PipeFluidRenderer() {}

    /** Drop all animation records — called when the player leaves the world or changes dimension. */
    public static void clear() {
        ANIMS.clear();
    }

    /**
     * Whether Create's own pipe renderers should skip a pipe's fluid entirely: the engine owns
     * in-pipe fluid whenever it is enabled (virtual/ponder blocks keep Create's animation).
     * Shared by both pipe-render mixins.
     */
    public static boolean hidesFromCreate(FluidTransportBehaviour pipe) {
        return PipesNPhysicsConfig.ENABLE_ENGINE.get() && !pipe.blockEntity.isVirtual();
    }

    /**
     * Draw one PONDER pipe cell's stored fluid straight into the scene buffer at the block-entity
     * renderer's (block-local) pose. The main-world path is camera-relative and its {@link CellAnim}
     * cache is keyed by BlockPos — which would collide with real pipes at the same coords — so ponder
     * gets its own cache-free waterline (no scroll/plug smoothing; the engine already updates the
     * content every scene tick). Called from the pipe BER mixin, which runs during ponder's render.
     */
    public static void drawPonderCell(StraightPipeBlockEntity be, PoseStack ms, MultiBufferSource buffer, int light) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get() || !PipesNPhysicsConfig.ENABLE_PONDER_ENGINE.get()) return;
        int capacity = PipeStore.capacityMb();
        if (capacity <= 0) return;
        BlockState state = be.getBlockState();
        if (!state.hasProperty(AxisPipeBlock.AXIS)) return;
        FluidTransportBehaviour pipe = be.getBehaviour(FluidTransportBehaviour.TYPE);
        if (!(pipe instanceof PipeFluidCell holder)) return;
        FluidStack content = holder.pipesnphysics$content();
        if (content.isEmpty()) return;

        float frac = Math.min((float) content.getAmount() / capacity, 1f);
        boolean gas = content.getFluid().getFluidType().isLighterThanAir();
        Direction.Axis axis = state.getValue(AxisPipeBlock.AXIS);
        float lo = 0.5f - PIPE_RADIUS;
        float hi = 0.5f + PIPE_RADIUS;
        float x0, y0, z0, x1, y1, z1;
        switch (axis) {
            case Y -> {
                x0 = lo; z0 = lo; x1 = hi; z1 = hi;
                if (gas) { y0 = 1f - frac; y1 = 1f; } else { y0 = 0f; y1 = frac; }
            }
            case X -> {
                x0 = 0f; x1 = 1f; z0 = lo; z1 = hi;
                float bore = hi - lo;
                if (gas) { y0 = hi - frac * bore; y1 = hi; } else { y0 = lo; y1 = lo + frac * bore; }
            }
            default -> {
                z0 = 0f; z1 = 1f; x0 = lo; x1 = hi;
                float bore = hi - lo;
                if (gas) { y0 = hi - frac * bore; y1 = hi; } else { y0 = lo; y1 = lo + frac * bore; }
            }
        }
        if (y1 <= y0) return;
        renderBox(content, x0, y0, z0, x1, y1, z1, light, axis, 0f, ms,
                FluidRenderHelper.getFluidBuilder(buffer));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!PipesNPhysicsConfig.PIPE_LEVEL_RENDER.get()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        int capacity = PipeStore.capacityMb();
        if (capacity <= 0) return;

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

        drewAny = scanAround(level, camera, SCAN_RADIUS_CHUNKS, null, capacity, camera, poseStack, now);
        // A contraption's pipes are NOT in the chunks around the camera — they live at plot
        // coordinates far outside the world and are drawn through the contraption's pose. Sweep each
        // nearby contraption's own plot instead, centred on where the camera stands inside it and
        // only as wide as the contraption really is (a ship is a few chunks; sweeping the full view
        // distance per ship would cost more than every ship in sight is worth).
        for (SubLevelFrame frame : SubLevelDraw.framesNear(camera, SCAN_RADIUS_CHUNKS * 16.0)) {
            int radius = Math.min(SCAN_RADIUS_CHUNKS, frame.chunkRadius());
            drewAny |= scanAround(level, frame.unproject(camera), radius, frame, capacity, camera,
                    poseStack, now);
        }

        ANIMS.entrySet().removeIf(e -> now - e.getValue().lastSeen > CellAnim.FADE_TICKS + 4f);

        if (drewAny) OWN_BUFFER.endBatch();
    }

    /** Draw every straight pipe in the chunks around {@code center}, returning whether any emitted. */
    private static boolean scanAround(ClientLevel level, Vec3 center, int radiusChunks,
                                      SubLevelFrame frame, int capacity,
                                      Vec3 camera, PoseStack poseStack, float now) {
        boolean drewAny = false;
        int centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(center.x));
        int centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(center.z));
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (!(level.getChunk(centerChunkX + dx, centerChunkZ + dz, ChunkStatus.FULL, false)
                        instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof StraightPipeBlockEntity pipe) {
                        drewAny |= renderCell(level, pipe, capacity, camera, frame, poseStack, now);
                    }
                }
            }
        }
        return drewAny;
    }

    /** Draw one pipe cell's stored fluid, returning whether anything was emitted. */
    private static boolean renderCell(ClientLevel level, StraightPipeBlockEntity be, int capacity,
                                      Vec3 camera, SubLevelFrame frame, PoseStack poseStack, float now) {
        BlockState state = be.getBlockState();
        if (!state.hasProperty(AxisPipeBlock.AXIS)) return false;

        FluidTransportBehaviour pipe = be.getBehaviour(FluidTransportBehaviour.TYPE);
        if (!(pipe instanceof PipeFluidCell holder)) return false;
        BlockPos cellPos = be.getBlockPos();

        FluidStack content = holder.pipesnphysics$content();
        CellAnim anim = ANIMS.get(cellPos);
        if (anim == null) {
            if (content.isEmpty()) return false;
            anim = new CellAnim();
            ANIMS.put(cellPos.immutable(), anim);
        }
        anim.advance(content, holder.pipesnphysics$flowData(), capacity, now);
        if (!anim.visible()) {
            if (anim.idle()) ANIMS.remove(cellPos);
            return false;
        }

        FluidStack fluid = anim.fluid();
        float frac = anim.frac();
        boolean gas = fluid.getFluid().getFluidType().isLighterThanAir();
        Direction flowDir = anim.dir();
        boolean front = anim.frontClip();

        // A partially-filled cell draws one of two ways:
        //   PLUG      — a travelling front episode: a full-bore box clipped along the flow axis,
        //               gas and liquid alike (see CellAnim for when an episode starts and ends).
        //   WATERLINE — everything else, flowing or not: the level the content actually stands at
        //               (a liquid bottom-up, a gas hanging from the top), so standing fluid never
        //               replays a fill animation when a flow starts through it — it just rises.
        float lo = 0.5f - PIPE_RADIUS;
        float hi = 0.5f + PIPE_RADIUS;
        float x0, y0, z0, x1, y1, z1;
        Direction.Axis axis = state.getValue(AxisPipeBlock.AXIS);
        switch (axis) {
            case Y -> {
                x0 = lo; z0 = lo; x1 = hi; z1 = hi;
                if (front) { y0 = 0f; y1 = 1f; }
                else if (gas) { y0 = 1f - Math.min(frac, 1f); y1 = 1f; }
                else { y0 = 0f; y1 = Math.min(frac, 1f); }
            }
            case X -> {
                x0 = 0f; x1 = 1f;
                z0 = lo; z1 = hi;
                float bore = hi - lo;
                if (front) { y0 = lo; y1 = hi; }
                else if (gas) { y0 = hi - frac * bore; y1 = hi; }
                else { y0 = lo; y1 = lo + frac * bore; }
            }
            default -> {
                z0 = 0f; z1 = 1f;
                x0 = lo; x1 = hi;
                float bore = hi - lo;
                if (front) { y0 = lo; y1 = hi; }
                else if (gas) { y0 = hi - frac * bore; y1 = hi; }
                else { y0 = lo; y1 = lo + frac * bore; }
            }
        }
        if (y1 <= y0) return false;

        // Clip a travelling plug along the flow axis: a fill front grows out of the inbound side,
        // a draining one recedes onto the outbound side (the gap opens where the fluid left from).
        if (front) {
            boolean keepHigh = (flowDir.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                    == anim.anchorsDownstream();
            switch (flowDir.getAxis()) {
                case X -> { if (keepHigh) x0 = x1 - frac * (x1 - x0); else x1 = x0 + frac * (x1 - x0); }
                case Y -> { if (keepHigh) y0 = y1 - frac * (y1 - y0); else y1 = y0 + frac * (y1 - y0); }
                default -> { if (keepHigh) z0 = z1 - frac * (z1 - z0); else z1 = z0 + frac * (z1 - z0); }
            }
        }

        // Inset axis ends that do NOT continue into another pipe, so the end quad doesn't sit
        // coplanar with the pipe opening (rim z-fight).
        boolean openNeg = FluidPropagator.getPipe(level,
                cellPos.relative(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE))) == null;
        boolean openPos = FluidPropagator.getPipe(level,
                cellPos.relative(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE))) == null;
        switch (axis) {
            case X -> { if (openNeg) x0 = Math.min(x0 + OPEN_END_INSET, x1); if (openPos) x1 = Math.max(x1 - OPEN_END_INSET, x0); }
            case Y -> { if (openNeg) y0 = Math.min(y0 + OPEN_END_INSET, y1); if (openPos) y1 = Math.max(y1 - OPEN_END_INSET, y0); }
            default -> { if (openNeg) z0 = Math.min(z0 + OPEN_END_INSET, z1); if (openPos) z1 = Math.max(z1 - OPEN_END_INSET, z0); }
        }

        int light = LevelRenderer.getLightColor(level, cellPos);
        poseStack.pushPose();
        // The box below is block-local (0..1), so the cell IS the origin — nothing large ever
        // reaches the matrix (SubLevelDraw).
        SubLevelDraw.cameraRelative(poseStack, camera, frame, cellPos);
        renderBox(fluid, x0, y0, z0, x1, y1, z1, light, axis, anim.phase(), poseStack,
                FluidRenderHelper.getFluidBuilder(OWN_BUFFER));
        poseStack.popPose();
        return true;
    }

    /**
     * Draw a cell's box with the still texture, its grain scrolled along the pipe axis by the
     * cell's integrated phase — the ONE path for flowing, resting, and fading cells, so a flow
     * starting or stopping keeps its texture offset instead of snapping to a differently-drawn
     * box. Each face is a small custom quad ({@link #pipeFace}) because catnip's tiled-face
     * helper can't scroll; offsets the UV per block tile so the scroll wraps.
     */
    private static void renderBox(FluidStack stack, float x0, float y0, float z0,
                                  float x1, float y1, float z1, int light, Direction.Axis grainAxis,
                                  float scroll, PoseStack ms, VertexConsumer builder) {
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(stack.getFluid());
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ext.getStillTexture(stack));
        int color = ext.getTintColor(stack);
        int luminosity = Math.max((light >> 4) & 0xF, stack.getFluid().getFluidType().getLightLevel(stack));
        int lightOut = (light & 0xF00000) | luminosity << 4;

        int fAxis = grainAxis.ordinal();
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
            // Grain (texture V) runs along the pipe axis when it lies in this face; a
            // pipe-perpendicular cap gets no scroll.
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
     * Emit one box face, tiling the sprite one tile per block along both in-plane axes and
     * scrolling the grain (V) by {@code scroll} along the flow axis — splitting at tile boundaries
     * so the scroll wraps seamlessly. Winding is derived from the in-plane basis vs the outward
     * normal, so the face is not back-face culled.
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
}
