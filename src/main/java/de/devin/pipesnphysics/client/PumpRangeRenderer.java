package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.foundation.model.BakedQuadHelper;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.net.PumpRangePayload;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelDataManager;

import java.util.Arrays;
import java.util.List;

/**
 * Colors the pipes a pump can reach, while a goggle-wearing player looks at it (and for a
 * configurable grace window afterwards). The paths come from the server
 * ({@link PumpRangeClient}); a cell is painted when its reach MARGIN is still positive — the
 * push ceiling above the pump, the drawable floor below it.
 *
 * The paint spans what the pump's head is PAYING FOR: everything above the supply surface, up to
 * the limit. So the painted EXTENT is the answer to "how far does this pump go" (owner's call,
 * 2026-07-31): where the green stops is the limit. That limit is an ELEVATION and almost never
 * lands on a block boundary, so where the run CLIMBS through it the paint is CUT part-way through
 * a pipe rather than rounded to whole blocks. Along a LEVEL stretch there is nothing to cut — one
 * elevation, so the limit clears every cell of it or none — and each cell is painted whole or not
 * at all ({@link #climbsThrough}).
 *
 * The supply surface underneath keeps PUSH runs honest — horizontal distance costs no head at
 * all, so against the ceiling ALONE every pipe at or below the waterline was reachable and a
 * whole ground-level network lit up green along its entire length, saying nothing — but it is a
 * WHOLE-CELL test, never a second cut: a pipe standing entirely below the surface is gravity's
 * work and stays bare, while one the surface merely passes THROUGH is painted whole. Cutting
 * there answered no question asked at a pump, and left every run sitting at its supply's own
 * level painted along its top half, cell after cell — which reads as a broken overlay rather
 * than an answer.
 *
 * PULLING is bounded by the drawable floor ALONE. The supply surface has no business on that
 * side: how deep a pump can draw is a question about pipe BELOW the surface, so testing against
 * it hides the very answer — and where the solve has no supply to anchor at it self-anchors the
 * field at the node's own centre (§6), a fiction that sits at the pump and capped every suction
 * run at one block however far the pump could really reach.
 *
 * The paint is each pipe's OWN baked model re-drawn a hair larger and tinted, rather than a
 * shell built around it — so elbows, connection stubs and encasing are all followed exactly,
 * which no hand-built geometry can manage. BOTH sides ramp green→amber→red toward their limit
 * ({@link #rampColor}), one colour per cell taken from that cell's own reported margin. That ramp
 * measures the reach LEFT — still the "how far does this pump go" question, read at every pipe
 * instead of only where the paint stops. It is not the encoding
 * reverted on 2026-07-31: those ramped by how hard the pump was WORKING, competed with the
 * in-pipe fluid, and read as a promise about throughput.
 *
 * Reading those models is expensive and is therefore done ONCE PER ANSWER ({@link #bake}), not
 * once per frame: a Create pipe caches no model data, so every {@code getModelData} rebuilds its
 * rim attachments from the neighbourhood and every {@code getQuads} copies the model into a fresh
 * list — seven of those per cell, plus a cut and four {@code Vec3}s per quad. At 60 fps over a
 * large network that is millions of allocations a second, which is the lag reported in issue #81
 * ("look at a pump with a lot of pipes connected and the game becomes laggy"). The answer only
 * changes every ~10 ticks, so the geometry is baked flat then ({@link ReachMesh}) and each frame
 * only streams vertices. The bake also paints each POSITION once: the walk hands every branch its
 * own copy of the shared trunk, so a manifold's header arrived once per branch and was rebuilt,
 * re-cut and re-drawn that many times over — and being translucent, double-painted too.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class PumpRangeRenderer {
    /** Strong enough to read at a glance, light enough that the pipe still shows through it. */
    private static final float TINT_ALPHA = 0.55f;

    /** At the pump, with the whole reach still ahead. */
    private static final Rgb REACH_COLOR = new Rgb(60, 255, 90);
    /** Halfway through it: warm enough to read as "using it up", not as alarm. */
    private static final Rgb HALFWAY_COLOR = new Rgb(255, 200, 50);
    /** At the limit — the ceiling it can push to, or the floor it can draw from. */
    private static final Rgb LIMIT_COLOR = new Rgb(255, 70, 55);

    private static final MultiBufferSource.BufferSource OWN_BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(2048));

    /** The answer {@link #mesh} was baked from — by IDENTITY, since each answer is a fresh payload. */
    private static PumpRangePayload bakedFrom;
    private static ReachMesh mesh;

    private PumpRangeRenderer() {}

    /** Drop the baked geometry — it holds the whole overlay's vertices (world change, logout). */
    public static void clear() {
        bakedFrom = null;
        mesh = null;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_PARTICLES (not AFTER_TRANSLUCENT_BLOCKS) so the tint draws over the in-pipe
        // fluid's own translucency instead of fighting it for sort order.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PipesNPhysicsConfig.SHOW_PUMP_REACH_OVERLAY.get()) return;
        if (!GogglesItem.isWearingGoggles(mc.player)) return;

        long now = mc.level.getGameTime();
        if (mc.hitResult instanceof BlockHitResult blockHit
                && mc.hitResult.getType() == HitResult.Type.BLOCK
                && mc.level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof PumpBlock) {
            PumpRangeClient.looking(blockHit.getBlockPos(), now);
        }

        boolean preserve = PipesNPhysicsConfig.PRESERVE_PUMP_RANGE.get();
        int preserveTicks = PipesNPhysicsConfig.PUMP_RANGE_PRESERVE_SECONDS.get() * 20;
        PumpRangePayload payload = PumpRangeClient.active(now, preserve, preserveTicks);
        if (payload == null || payload.paths().isEmpty()) return;

        float fade = PumpRangeClient.preserveFraction(now, preserveTicks);
        paintReach(event.getPoseStack(), mc, payload, Math.max(0.15f, fade));
    }

    /**
     * One frame: stream the baked vertices, applying only the camera offset and the fade's alpha
     * (which is why alpha is the one thing NOT baked into the mesh).
     */
    private static void paintReach(PoseStack poseStack, Minecraft mc,
                                   PumpRangePayload payload, float fade) {
        ReachMesh baked = meshFor(mc, payload);
        if (baked.colors().length == 0) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float alpha = TINT_ALPHA * fade;

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer buf = OWN_BUFFER.getBuffer(PnpRenderTypes.REACH_TINT);
        PoseStack.Pose pose = poseStack.last();
        float[] vertices = baked.vertices();
        for (int i = 0; i < baked.colors().length; i++) {
            int at = i * ReachMesh.STRIDE;
            int color = baked.colors()[i];
            buf.addVertex(pose, vertices[at], vertices[at + 1], vertices[at + 2])
                    .setColor(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f,
                            (color & 0xFF) / 255f, alpha)
                    .setUv(vertices[at + 3], vertices[at + 4])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(ArrowRender.FULL_BRIGHTNESS)
                    .setNormal(pose, vertices[at + 5], vertices[at + 6], vertices[at + 7]);
        }
        OWN_BUFFER.endBatch();
        poseStack.popPose();
    }

    /**
     * One answer's tint geometry, baked flat: eight interleaved floats per vertex — world position,
     * texture, normal — plus its packed colour. Alpha stays out of it: the preserve window fades it
     * per frame while the geometry stands still.
     */
    private record ReachMesh(float[] vertices, int[] colors) {
        static final int STRIDE = 8;
    }

    /** The mesh for this answer, baking it the first frame the answer is seen. */
    private static ReachMesh meshFor(Minecraft mc, PumpRangePayload payload) {
        if (payload == bakedFrom && mesh != null) return mesh;
        bakedFrom = payload;
        mesh = bake(mc, payload);
        return mesh;
    }

    /**
     * Read every reachable pipe's model once, cut it to its band, shade it off its ramp, and write
     * the result out flat. Each POSITION is baked at most once — the walk gives every branch its
     * own copy of the trunk they share, and painting a translucent tint twice both costs the work
     * twice and comes out darker; the first path to reach a cell decides its cut and colour.
     */
    private static ReachMesh bake(Minecraft mc, PumpRangePayload payload) {
        Bake bake = new Bake();
        for (PumpRangePayload.RangePath path : payload.paths()) {
            // Both quantities arrive as ELEVATIONS measured from the cell's own centre, so they
            // read as block-local heights around 0.5.
            boolean push = !path.pull();
            List<PumpRangePayload.RangeCell> cells = path.cells();
            // The margin at the pump — the path's first entry — is this run's WHOLE reach, and so
            // the span the colour ramp normalizes over (see rampColor).
            float reach = cells.get(0).margin();
            for (int i = 0; i < cells.size(); i++) {
                PumpRangePayload.RangeCell cell = cells.get(i);
                // The pump itself and a tank or open end at the far end of a run are graph nodes,
                // not pipes — painting those would color a block that is not part of the run.
                if (!cell.pipe()) continue;

                // PUSHING, a cell standing wholly below the supply surface is gravity's work and
                // stays bare. PULLING, that same surface would hide the answer — how deep the
                // pump can draw is a question about pipe BELOW it — so the floor alone bounds
                // that side. (The surface only ever decides WHETHER a cell is painted either
                // way, never where its paint starts; see the class comment.)
                if (push && 0.5f - cell.aboveSupply() >= 1) continue;

                // The limit — the ceiling overhead when pushing, the drawable floor below when
                // pulling — cuts the pipe only where the run CLIMBS through it. On a level
                // stretch it is the cell's own margin that answers the question, whole cell at a
                // time; see climbsThrough.
                float limit = push ? 0.5f + cell.margin() : 0.5f - cell.margin();
                float low = Float.NEGATIVE_INFINITY;
                float high = Float.POSITIVE_INFINITY;
                if (climbsThrough(cells, i)) {
                    low = push ? Float.NEGATIVE_INFINITY : limit;
                    high = push ? limit : Float.POSITIVE_INFINITY;
                    if (low >= 1 || high <= 0) continue; // the band misses this cell entirely
                } else if (cell.margin() < 0) {
                    continue; // level stretch, past the limit: out of reach, all of it
                }
                if (!bake.first(cell.pos())) continue;
                bakeCell(bake, mc, BlockPos.of(cell.pos()), low, high,
                        rampColor(cell.margin(), reach));
            }
        }
        return bake.done();
    }

    /** The growable vertex sink a bake writes into, and the set of positions it has already done. */
    private static final class Bake {
        private final FloatArrayList vertices = new FloatArrayList();
        private final IntArrayList colors = new IntArrayList();
        private final LongOpenHashSet painted = new LongOpenHashSet();

        /** Whether this position is being painted for the first time this bake. */
        boolean first(long pos) {
            return painted.add(pos);
        }

        /** One vertex: model-local geometry lifted into world space, its texture, normal and colour. */
        void vertex(Vec3 at, BlockPos cell, float u, float v, Direction facing, Rgb color) {
            vertices.add((float) at.x + cell.getX());
            vertices.add((float) at.y + cell.getY());
            vertices.add((float) at.z + cell.getZ());
            vertices.add(u);
            vertices.add(v);
            vertices.add(facing.getStepX());
            vertices.add(facing.getStepY());
            vertices.add(facing.getStepZ());
            colors.add(color.r() << 16 | color.g() << 8 | color.b());
        }

        ReachMesh done() {
            return new ReachMesh(vertices.toFloatArray(), colors.toIntArray());
        }
    }

    /**
     * One cell's colour: green where the pump stands, warming through amber to red at the limit —
     * how much of this run's reach is spent by the time fluid is up (or down) HERE. {@code margin}
     * is the blocks of reach left at the cell, {@code reach} the margin at the pump, which is the
     * path's first entry and so this run's whole reach.
     *
     * Normalizing over that, never a fixed band, is the trap the first ramp fell into (2026-07-31):
     * every cell more than the band's width clear of the limit clamped to full saturation, the
     * overlay read as one flat colour, and it silently swallowed a correct fix to the pull-side
     * quantity ("it looks exactly the same").
     *
     * A cell is ONE colour, taken from the very margin the walk reported for it — the number
     * {@code /pipegraph} prints, so the tint and the dump can never say different things. Shading
     * WITHIN a cell instead asks the ramp about the pipe's MODEL rather than about the run: a
     * pipe's connection stubs span the whole block, so wherever the reach is SHORT one cell covers
     * the entire green-to-red sweep. A dry suction line is exactly that — a pump establishes
     * through one on a tenth of its head, half a block at 16 RPM — and a riser came out SAWTOOTHED,
     * every pipe red at its foot and green at its head (2026-08-26, after a first pass that
     * unified only LEVEL cells and left the climbing ones striped). The cost is that a long climb
     * steps once per pipe instead of shading continuously; at any reach worth painting that is a
     * fraction of the ramp per step and still reads as a gradient.
     */
    private static Rgb rampColor(float margin, float reach) {
        if (reach <= 1e-3) return LIMIT_COLOR;
        double spent = 1 - Math.clamp(margin / reach, 0, 1);
        return spent < 0.5
                ? mix(REACH_COLOR, HALFWAY_COLOR, spent * 2)
                : mix(HALFWAY_COLOR, LIMIT_COLOR, (spent - 0.5) * 2);
    }

    /** Two colours blended {@code t} of the way from one to the other, per channel. */
    private static Rgb mix(Rgb from, Rgb to, double t) {
        return new Rgb((int) Mth.lerp(t, from.r(), to.r()),
                (int) Mth.lerp(t, from.g(), to.g()),
                (int) Mth.lerp(t, from.b(), to.b()));
    }

    /**
     * Whether the run CLIMBS through this cell — a neighbour along the path stands at a different
     * elevation. Only there does cutting the cell at the limit answer anything: the run passes
     * through the limit INSIDE that block, and the green stops exactly where it does, which is
     * the whole reason the paint is cut rather than rounded to whole blocks.
     *
     * Along a LEVEL stretch every cell shares one elevation, so the limit either clears all of
     * them or none. A plane through such a cell is an artifact of the pipe being 8/16 thick, not
     * of the run's path, and cutting there paints the identical partial slice on cell after cell
     * down the whole stretch — which reads as a broken overlay rather than as an answer (reported
     * twice: the supply surface slicing at the midline, then the drawable floor at the pipe lip).
     */
    private static boolean climbsThrough(List<PumpRangePayload.RangeCell> cells, int index) {
        int y = BlockPos.of(cells.get(index).pos()).getY();
        return (index > 0 && BlockPos.of(cells.get(index - 1).pos()).getY() != y)
                || (index + 1 < cells.size() && BlockPos.of(cells.get(index + 1).pos()).getY() != y);
    }

    /** Bakes one block's model, tinted and cut down to the band between two planes. */
    private static void bakeCell(Bake bake, Minecraft mc, BlockPos pos,
                                 float low, float high, Rgb color) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        // A Create pipe's rim ATTACHMENTS, bracket and casing are not in its blockstate model at
        // all: PipeAttachmentModel builds them in getModelData and appends them in getQuads. So
        // the model has to be asked to ENRICH the block entity's data the way the chunk renderer
        // does — handing it the raw ModelDataManager entry leaves its PIPE_PROPERTY absent and
        // renders the bare core, tinting every pipe's middle and leaving its joints copper.
        // A null render type likewise takes ALL of the model's layers, not just one.
        ModelData data = model.getModelData(mc.level, pos, state, blockEntityData(mc, pos));

        // Seeded exactly as the block itself renders, so a multi-variant model tints the variant
        // actually on screen rather than a different roll of the same model.
        RandomSource random = RandomSource.create();
        for (Direction face : Direction.values()) {
            random.setSeed(state.getSeed(pos));
            bakeQuads(bake, pos, model.getQuads(state, face, random, data, null), low, high, color);
        }
        random.setSeed(state.getSeed(pos));
        bakeQuads(bake, pos, model.getQuads(state, null, random, data, null), low, high, color);
    }

    /** The block entity's own model data — the raw input a model enriches into its real data. */
    private static ModelData blockEntityData(Minecraft mc, BlockPos pos) {
        ModelDataManager manager = mc.level.getModelDataManager();
        if (manager == null) return ModelData.EMPTY;
        ModelData data = manager.getAt(pos);
        return data == null ? ModelData.EMPTY : data;
    }

    /**
     * Bakes the quads of one cell, each cut to its band and carrying the cell's own colour. Written
     * vertex by vertex rather than through {@code putBulkData} (which takes one colour per quad and
     * cannot feed a flat mesh); the geometry, UVs and face normal are the model's own, only the
     * colour is ours.
     */
    private static void bakeQuads(Bake bake, BlockPos pos, List<BakedQuad> quads,
                                  float low, float high, Rgb color) {
        for (BakedQuad quad : quads) {
            BakedQuad shown = cutAt(quad, low, false);
            if (shown != null) shown = cutAt(shown, high, true);
            if (shown == null) continue;
            int[] vertices = shown.getVertices();
            Direction facing = shown.getDirection();
            for (int i = 0; i < 4; i++) {
                Vec3 at = BakedQuadHelper.getXYZ(vertices, i);
                bake.vertex(at, pos, BakedQuadHelper.getU(vertices, i),
                        BakedQuadHelper.getV(vertices, i), facing, color);
            }
        }
    }

    /**
     * The quad cut down to the painted side of a horizontal plane at block-local {@code plane}:
     * the quad itself when it lies wholly inside, null when wholly outside, and a cut copy when
     * it straddles — each vertex on the wrong side slides along its OWN edge to the plane and
     * carries its texture coordinate with it, so the cut face keeps the pipe's texture instead of
     * stretching it.
     *
     * This is what lets the paint stop part-way THROUGH a pipe: reach is an elevation, and it
     * almost never lands on a block boundary, so whole-block tinting quantized the answer to the
     * nearest metre and hid a limit that fell just short of the next pipe entirely.
     */
    private static BakedQuad cutAt(BakedQuad quad, float plane, boolean keepBelow) {
        int[] source = quad.getVertices();
        boolean[] outside = new boolean[4];
        int cut = 0;
        for (int i = 0; i < 4; i++) {
            double y = BakedQuadHelper.getXYZ(source, i).y;
            outside[i] = keepBelow ? y > plane : y < plane;
            if (outside[i]) cut++;
        }
        if (cut == 0) return quad;
        if (cut == 4) return null;

        int[] data = Arrays.copyOf(source, source.length);
        for (int i = 0; i < 4; i++) {
            if (!outside[i]) continue;
            int before = (i + 3) % 4;
            int after = (i + 1) % 4;
            int anchor = !outside[before] ? before : (!outside[after] ? after : -1);
            Vec3 from = BakedQuadHelper.getXYZ(source, i);
            if (anchor < 0) {
                // No neighbour left to slide along (never happens on the cuboid faces a pipe is
                // built from); pin the height so the quad at least stops at the plane.
                BakedQuadHelper.setXYZ(data, i, new Vec3(from.x, plane, from.z));
                continue;
            }
            Vec3 to = BakedQuadHelper.getXYZ(source, anchor);
            double rise = to.y - from.y;
            if (Math.abs(rise) < 1e-6) { // coincident after an earlier cut; nothing to slide along
                BakedQuadHelper.setXYZ(data, i, new Vec3(from.x, plane, from.z));
                continue;
            }
            double t = (plane - from.y) / rise;
            BakedQuadHelper.setXYZ(data, i, from.add(to.subtract(from).scale(t)));
            BakedQuadHelper.setU(data, i, (float) Mth.lerp(t,
                    BakedQuadHelper.getU(source, i), BakedQuadHelper.getU(source, anchor)));
            BakedQuadHelper.setV(data, i, (float) Mth.lerp(t,
                    BakedQuadHelper.getV(source, i), BakedQuadHelper.getV(source, anchor)));
        }
        return BakedQuadHelper.cloneWithCustomGeometry(quad, data);
    }
}
