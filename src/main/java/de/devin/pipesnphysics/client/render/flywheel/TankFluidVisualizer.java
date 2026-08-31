package de.devin.pipesnphysics.client.render.flywheel;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.HashSet;
import java.util.Set;

/**
 * Attaches {@link TankFluidVisual} to every Create tank block entity type.
 *
 * Bound the same way the Tank Contents display source is (see {@code PnpDisplaySources}): by asking
 * each block what block entity IT declares through Create's {@code IBE}, rather than naming Create's
 * two types, so a tank DERIVATIVE (Create: Connected's fluid vessel, the block that cost issue #79
 * its goggle line and its tilted fluid) is covered without a mod id. The test needs no instance.
 */
public final class TankFluidVisualizer implements BlockEntityVisualizer<FluidTankBlockEntity> {
    private static final TankFluidVisualizer INSTANCE = new TankFluidVisualizer();

    private TankFluidVisualizer() {}

    /** Called once from client setup; the visualizer is a field on the block entity type. */
    @SuppressWarnings("unchecked")
    public static void register() {
        // Spike scaffolding: Flywheel reads this property once, when it first compiles, which is after
        // client setup. Setting it here works and, unlike a gradle run argument, survives being
        // launched from the IDE. A program only lands in run/flywheel_sources/ once something DRAWS with
        // it, which is the one question a blank screen with no shader error cannot answer. Goes with the
        // spike.
        if (PipesNPhysicsConfig.FLYWHEEL_TANK_VISUAL.get()) System.setProperty("flw.dumpShaderSource", "true");

        Set<BlockEntityType<?>> seen = new HashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof IBE<?> ibe)) continue;
            if (!FluidTankBlockEntity.class.isAssignableFrom(ibe.getBlockEntityClass())) continue;
            if (!seen.add(ibe.getBlockEntityType())) continue;
            // Safe by the assignability check above: every such type's block entity IS a tank.
            VisualizerRegistry.setVisualizer((BlockEntityType<FluidTankBlockEntity>) ibe.getBlockEntityType(), INSTANCE);
        }
        PipesNPhysics.LOGGER.info("Flywheel tank visual attached to {} tank block entity types", seen.size());
    }

    @Override
    public BlockEntityVisual<? super FluidTankBlockEntity> createVisual(VisualizationContext ctx,
                                                                       FluidTankBlockEntity blockEntity,
                                                                       float partialTick) {
        return new TankFluidVisual(ctx, blockEntity, partialTick);
    }

    /**
     * Never. The spike draws ON TOP of the tank. Taking the tank over is what a real port would do,
     * and doing it here would hide a failure as a missing tank instead of a missing cube.
     */
    @Override
    public boolean skipVanillaRender(FluidTankBlockEntity blockEntity) {
        return false;
    }
}
