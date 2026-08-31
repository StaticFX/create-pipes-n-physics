package de.devin.pipesnphysics.client.render.flywheel;

import de.devin.pipesnphysics.PipesNPhysics;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.lib.instance.ColoredLitInstance;
import dev.engine_room.flywheel.lib.instance.SimpleInstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

/**
 * One tank's fluid, as the GPU needs it: the interior BOX and the world-level PLANE cutting through
 * it. The mesh is a bare quad: the vertex shader puts it in the plane and the fragment shader
 * discards it outside the box, so the whole tilted surface is these few numbers, re-sent per frame,
 * in place of the ~4,400 quads {@code TiltedTankFluid} re-tessellates on the CPU.
 *
 * Every field is in the tank's own block-local frame, pre-embedding: on a Sable contraption Flywheel
 * multiplies by the sub-level's model matrix after our shader runs, which is why {@code normal} is
 * world-up transformed INTO that frame rather than plain +Y.
 */
public class TankFluidInstance extends ColoredLitInstance {
    /** Box centre, relative to the visual's render origin. */
    public float centerX, centerY, centerZ;
    /** Half the box's size on each axis. */
    public float halfX, halfY, halfZ;
    /** Fluid surface normal, world up in this tank's frame, negated for a buoyant gas. */
    public float normalX = 0, normalY = 1, normalZ = 0;
    /**
     * World X in this tank's frame, which the shader uses as the plane's first axis. Passed rather than
     * derived from the normal: any basis a shader picks off the normal alone turns with the ship, so the
     * texture and the waves swim as it turns, and it snaps outright wherever the choice of seed axis
     * flips. Taken from the pose it is world-locked and continuous, which is how water behaves.
     */
    public float tangentX = 1, tangentY = 0, tangentZ = 0;
    /** Signed distance from the box centre to the surface plane, along the normal. */
    public float planeOffset;
    /** The still sprite's atlas bounds: u0, u1, v0, v1. */
    public float u0, u1, v0, v1;
    /**
     * Non-zero paints the diagnosis instead of the fluid: the whole quad red where it would normally be
     * discarded, green where it would be kept. A blank screen is otherwise three failures wearing one
     * face (geometry that never landed, a box test that rejected everything, or a discard that ate it),
     * and this tells them apart in a single run. Driven by {@code fluidDebugRender}.
     */
    public float debug;
    /** How hard the water is leaning, and which way, in the tank's OWN frame. The shader projects it. */
    public float sloshX, sloshY, sloshZ;
    /** Ripple height in blocks, from the fluid's viscosity: honey barely moves, water does. */
    public float waveAmp;
    /** The splash: how hard the surface is pressed along its own normal by a drop or a landing. */
    public float heave;
    /** Agitation, 0 still to 1 thrown about, which decides how much of the ripple is fine chop. */
    public float stir;

    public TankFluidInstance(InstanceType<? extends TankFluidInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public static final InstanceType<TankFluidInstance> TYPE = SimpleInstanceType.builder(TankFluidInstance::new)
            .layout(LayoutBuilder.create()
                    .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                    .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                    .vector("center", FloatRepr.FLOAT, 3)
                    .vector("halfExtent", FloatRepr.FLOAT, 3)
                    .vector("normal", FloatRepr.FLOAT, 3)
                    .vector("tangent", FloatRepr.FLOAT, 3)
                    .scalar("planeOffset", FloatRepr.FLOAT)
                    .vector("uv", FloatRepr.FLOAT, 4)
                    .scalar("debug", FloatRepr.FLOAT)
                    .vector("slosh", FloatRepr.FLOAT, 3)
                    .scalar("waveAmp", FloatRepr.FLOAT)
                    .scalar("heave", FloatRepr.FLOAT)
                    .scalar("stir", FloatRepr.FLOAT)
                    .build())
            // Offsets are tight and follow the layout's declaration order, as Flywheel's own types do.
            .writer((ptr, i) -> {
                MemoryUtil.memPutByte(ptr, i.red);
                MemoryUtil.memPutByte(ptr + 1, i.green);
                MemoryUtil.memPutByte(ptr + 2, i.blue);
                MemoryUtil.memPutByte(ptr + 3, i.alpha);
                ExtraMemoryOps.put2x16(ptr + 4, i.light);
                MemoryUtil.memPutFloat(ptr + 8, i.centerX);
                MemoryUtil.memPutFloat(ptr + 12, i.centerY);
                MemoryUtil.memPutFloat(ptr + 16, i.centerZ);
                MemoryUtil.memPutFloat(ptr + 20, i.halfX);
                MemoryUtil.memPutFloat(ptr + 24, i.halfY);
                MemoryUtil.memPutFloat(ptr + 28, i.halfZ);
                MemoryUtil.memPutFloat(ptr + 32, i.normalX);
                MemoryUtil.memPutFloat(ptr + 36, i.normalY);
                MemoryUtil.memPutFloat(ptr + 40, i.normalZ);
                MemoryUtil.memPutFloat(ptr + 44, i.tangentX);
                MemoryUtil.memPutFloat(ptr + 48, i.tangentY);
                MemoryUtil.memPutFloat(ptr + 52, i.tangentZ);
                MemoryUtil.memPutFloat(ptr + 56, i.planeOffset);
                MemoryUtil.memPutFloat(ptr + 60, i.u0);
                MemoryUtil.memPutFloat(ptr + 64, i.u1);
                MemoryUtil.memPutFloat(ptr + 68, i.v0);
                MemoryUtil.memPutFloat(ptr + 72, i.v1);
                MemoryUtil.memPutFloat(ptr + 76, i.debug);
                MemoryUtil.memPutFloat(ptr + 80, i.sloshX);
                MemoryUtil.memPutFloat(ptr + 84, i.sloshY);
                MemoryUtil.memPutFloat(ptr + 88, i.sloshZ);
                MemoryUtil.memPutFloat(ptr + 92, i.waveAmp);
                MemoryUtil.memPutFloat(ptr + 96, i.heave);
                MemoryUtil.memPutFloat(ptr + 100, i.stir);
            })
            .vertexShader(rl("instance/tank_fluid.vert"))
            .cullShader(rl("instance/cull/tank_fluid.glsl"))
            .build();

    /** Flywheel prepends {@code flywheel/} itself, so these live in {@code assets/pipesnphysics/flywheel/}. */
    static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(PipesNPhysics.ID, path);
    }

    /** The box, as its centre and half-extents, from the two corners the surface was resolved against. */
    public TankFluidInstance box(float[] mins, float[] maxs) {
        centerX = (mins[0] + maxs[0]) / 2f;
        centerY = (mins[1] + maxs[1]) / 2f;
        centerZ = (mins[2] + maxs[2]) / 2f;
        halfX = (maxs[0] - mins[0]) / 2f;
        halfY = (maxs[1] - mins[1]) / 2f;
        halfZ = (maxs[2] - mins[2]) / 2f;
        return this;
    }

    public TankFluidInstance plane(float[] normal, double[] tangent, float offset) {
        normalX = normal[0];
        normalY = normal[1];
        normalZ = normal[2];
        tangentX = (float) tangent[0];
        tangentY = (float) tangent[1];
        tangentZ = (float) tangent[2];
        planeOffset = offset;
        return this;
    }

    /**
     * @param amplitude the ripple height ALREADY scaled for how stirred up the water is. The envelope is
     *   applied on this side because how lively still water should look is a config knob and a matter of
     *   taste, and the shader has no business holding either.
     */
    public TankFluidInstance waves(TankSlosh slosh, float amplitude) {
        sloshX = slosh.x();
        sloshY = slosh.y();
        sloshZ = slosh.z();
        waveAmp = amplitude;
        stir = slosh.stir();
        heave = slosh.heave() * (amplitude > 0 ? 1f : 0f);
        return this;
    }

    public TankFluidInstance debug(boolean on) {
        debug = on ? 1f : 0f;
        return this;
    }

    public TankFluidInstance sprite(float u0, float u1, float v0, float v1) {
        this.u0 = u0;
        this.u1 = u1;
        this.v0 = v0;
        this.v1 = v1;
        return this;
    }

    /** Moves the box out of block-local space into the frame the visual's instances are drawn in. */
    public TankFluidInstance translate(float x, float y, float z) {
        centerX += x;
        centerY += y;
        centerZ += z;
        return this;
    }

    /** A zero box draws nothing: the plane test can never pass, so every fragment is discarded. */
    public TankFluidInstance hide() {
        halfX = 0;
        halfY = 0;
        halfZ = 0;
        return this;
    }
}
