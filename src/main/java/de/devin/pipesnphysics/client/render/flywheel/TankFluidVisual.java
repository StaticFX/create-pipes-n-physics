package de.devin.pipesnphysics.client.render.flywheel;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.render.TiltedTankFluid;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.FrustumIntersection;
import org.joml.Vector3d;

import java.util.function.Consumer;

/**
 * Draws a Sable contraption tank's fluid on the GPU: ONE instance carrying the tank's box, the
 * world-level plane through it, and how hard its water is leaning, against {@link TiltedTankFluid}'s
 * per-frame CPU tessellation of the same fluid.
 *
 * The two share their geometry through {@link TiltedTankFluid#resolve} deliberately, where the water
 * stands is one question with one answer, and a second copy of that maths would be free to drift from
 * the first. What differs is only how it is DRAWN.
 */
public class TankFluidVisual extends AbstractBlockEntityVisual<FluidTankBlockEntity>
        implements SimpleDynamicVisual {
    private static boolean loggedResolved = false;
    private static boolean loggedUnresolved = false;

    /**
     * The opaque and translucent surfaces, both alive, with whichever the player did not pick hidden.
     * Holding both beats rebuilding one on the toggle: a material is fixed once made, so switching means a
     * new instance, and a new instance comes up unlit until Flywheel next has a light update to give it,
     * which on a still contraption may be never. A hidden one has a zero box, so its geometry collapses to
     * a point and rasterises nothing.
     */
    private final TankFluidInstance[] surfaces = new TankFluidInstance[2];
    /** This tank's own slosh. Per-visual, because beginFrame may run on several threads at once. */
    private final TankSlosh slosh = new TankSlosh();

    /**
     * The A/B control: a stock {@code TransformedInstance} of a plain block model, drawn small at the
     * tank's corner. It is the one thing here already PROVEN to reach the screen on a contraption, so
     * it separates the two ways the surface can fail to appear, marker but no water means the custom
     * instance type or its shaders are at fault, neither means the visual is not running at all.
     * Delete it with the spike.
     */
    private final TransformedInstance marker;

    public TankFluidVisual(VisualizationContext ctx, FluidTankBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);
        for (int i = 0; i < surfaces.length; i++) {
            surfaces[i] = ctx.instancerProvider()
                    .instancer(TankFluidInstance.TYPE, TankFluidModel.get(i == 1))
                    .createInstance();
        }
        marker = ctx.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.block(Blocks.GLOWSTONE.defaultBlockState()))
                .createInstance();
        syncSurface(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        syncSurface(ctx.partialTick());
    }

    /**
     * Always visible. The default test puts a sphere at {@link #getVisualPosition()}, which on a Sable
     * sub-level is a plot coordinate tens of millions of blocks out and would cull every frame; the
     * contraption's real position is known only to Sable's embedding, downstream of us.
     */
    @Override
    public boolean isVisible(FrustumIntersection frustum) {
        return true;
    }

    /**
     * Light is taken HERE and not while placing the surface, though both run per frame. beginFrame runs on
     * Flywheel's worker threads, and reading the level's light engine off the render thread is exactly the
     * kind of thing that works until it doesn't; this hook exists so a visual never has to. It is also
     * less work: light changes far more rarely than the water moves.
     */
    @Override
    public void updateLight(float partialTick) {
        relight(surfaces[0], surfaces[1], marker);
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(surfaces[0]);
        consumer.accept(surfaces[1]);
    }

    @Override
    protected void _delete() {
        for (TankFluidInstance surface : surfaces) surface.delete();
        marker.delete();
    }

    private void syncSurface(float partialTick) {
        boolean on = PipesNPhysicsConfig.FLYWHEEL_TANK_VISUAL.get();

        TiltedTankFluid.Surface resolved = on ? TiltedTankFluid.resolve(blockEntity, partialTick) : null;
        // The marker tracks the SURFACE, not the tank: drawn beside every tank it said nothing, since a
        // tank we do not own (any tank off a contraption) is bare for a perfectly good reason and looks
        // exactly like a broken one. A marker means a surface is expected right there. It rides the debug
        // flag with the shader's diagnosis paint, so the two appear and vanish together.
        placeMarker(resolved != null && PipesNPhysicsConfig.FLUID_DEBUG_RENDER.get());
        if (resolved == null) {
            report(false, null);
            for (TankFluidInstance hidden : surfaces) hidden.hide().setChanged();
            return;
        }
        report(true, resolved);

        FluidStack fluid = resolved.fluid();
        TextureAtlasSprite still = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(IClientFluidTypeExtensions.of(fluid.getFluid()).getStillTexture(fluid));
        int tint = IClientFluidTypeExtensions.of(fluid.getFluid()).getTintColor(fluid);
        // A fluid that declares no alpha is drawn at Create's own 0.8, not invisible.
        if ((tint >>> 24) == 0) tint |= 0xCC000000;

        float viscosity = Math.max(0.5f, fluid.getFluid().getFluidType().getViscosity() / 1000f);
        slosh.advance(resolved.normal(), resolved.pose(), viscosity, sloshSpan(resolved));

        TankFluidInstance shown = surfaces[PipesNPhysicsConfig.FLUID_OPAQUE.get() ? 1 : 0];
        surfaces[shown == surfaces[0] ? 1 : 0].hide()
                .setChanged();

        shown.box(resolved.bounds().mins(), resolved.bounds().maxs())
                .plane(resolved.normal(), worldXInTankFrame(resolved), resolved.planeOffset())
                .sprite(still.getU0(), still.getU1(), still.getV0(), still.getV1())
                .waves(slosh, rippleHeight(viscosity))
                .debug(PipesNPhysicsConfig.FLUID_DEBUG_RENDER.get())
                .translate(visualPos.getX(), visualPos.getY(), visualPos.getZ())
                .colorArgb(tint)
                .setChanged();
    }

    /**
     * How far the fluid can travel ACROSS its tank, which is what sets how slowly it sloshes: a long tank
     * swings lazily, a small one slops about. Measured perpendicular to the surface, so a tank lying on
     * its side is read along the axes the water is actually free to run down, not the ones it is capped by.
     */
    private static float sloshSpan(TiltedTankFluid.Surface resolved) {
        float[] mins = resolved.bounds().mins(), maxs = resolved.bounds().maxs(), n = resolved.normal();
        double squared = 0;
        for (int axis = 0; axis < 3; axis++) {
            double half = (maxs[axis] - mins[axis]) / 2.0;
            squared += half * half * (1 - n[axis] * n[axis]);
        }
        return (float) (2 * Math.sqrt(squared));
    }

    /** The fluid's ripple height, faded toward the configured resting share as the water settles. */
    private float rippleHeight(float viscosity) {
        if (!PipesNPhysicsConfig.FLUID_WAVE_MESH.get()) return 0f;
        float resting = PipesNPhysicsConfig.FLUID_RESTING_WAVES.get().floatValue();
        return TankSlosh.amplitude(viscosity) * Mth.lerp(slosh.stir(), resting, 1f);
    }

    /**
     * World X expressed in this tank's frame, the plane's first axis. Off a contraption there is no pose
     * and the tank's frame IS the world's, so plain world X is the answer.
     */
    private static double[] worldXInTankFrame(TiltedTankFluid.Surface resolved) {
        if (resolved.pose() == null) return new double[]{1, 0, 0};
        Vector3d x = resolved.pose().transformNormalInverse(new Vector3d(1, 0, 0), new Vector3d());
        return x.lengthSquared() < 1.0e-6 ? new double[]{1, 0, 0} : new double[]{x.x, x.y, x.z};
    }

    private void placeMarker(boolean on) {
        if (!on) {
            marker.setZeroTransform()
                    .setChanged();
            return;
        }
        marker.setIdentityTransform()
                .translate(visualPos)
                .scale(0.15f)
                .setChanged();
    }

    /**
     * Says ONCE per outcome what the visual is being handed, so a blank screen can be read as either
     * "never ran", "ran but resolved nothing to draw", or "wrote a surface the GPU then did not show".
     */
    private void report(boolean resolved, TiltedTankFluid.Surface s) {
        if (resolved ? loggedResolved : loggedUnresolved) return;
        if (resolved) loggedResolved = true;
        else loggedUnresolved = true;

        if (!resolved) {
            PipesNPhysics.LOGGER.info("Tank fluid visual at {} resolved NOTHING to draw (flag {}, backend {})",
                    pos, PipesNPhysicsConfig.FLYWHEEL_TANK_VISUAL.get(), BackendManager.currentBackend());
            return;
        }
        PipesNPhysics.LOGGER.info("Tank fluid visual at {} wrote box {}..{} normal [{}, {}, {}] offset {} (visualPos {})",
                pos, java.util.Arrays.toString(s.bounds().mins()), java.util.Arrays.toString(s.bounds().maxs()),
                s.normal()[0], s.normal()[1], s.normal()[2], s.planeOffset(), visualPos);
    }
}
