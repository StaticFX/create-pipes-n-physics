package de.devin.pipesnphysics.display;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.HashSet;
import java.util.Set;

/**
 * Registers the pipe, pump, and tank display-link sources into Create's
 * {@code display_source} registry and, once the block-entity registry is frozen,
 * associates them with Create's block-entity types. The mod owns none of those blocks,
 * so association happens in setup rather than through Registrate's {@code associate}
 * (which would need the types resolved at builder time, before Create registers them).
 */
public final class PnpDisplaySources {
    private static final DeferredRegister<DisplaySource> SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, PipesNPhysics.ID);

    public static final DeferredHolder<DisplaySource, PipeNetworkDisplaySource> PIPE =
            SOURCES.register("pipe", () ->
                    new PipeNetworkDisplaySource(PipeDisplayMetric.PIPE_METRICS, "display_source.pipe_metric", false));
    public static final DeferredHolder<DisplaySource, PipeNetworkDisplaySource> PUMP =
            SOURCES.register("pump", () ->
                    new PipeNetworkDisplaySource(PipeDisplayMetric.PUMP_METRICS, "display_source.pump_metric", true));
    public static final DeferredHolder<DisplaySource, TankContentsDisplaySource> TANK =
            SOURCES.register("tank", TankContentsDisplaySource::new);

    public static void register(IEventBus modBus) {
        SOURCES.register(modBus);
    }

    /** Wire the sources onto Create's pipe/pump/tank BE types; call after the BE registry is frozen. */
    public static void associate() {
        DisplaySource pipe = PIPE.get();
        associate(pipe, AllBlockEntityTypes.FLUID_PIPE.get());
        associate(pipe, AllBlockEntityTypes.ENCASED_FLUID_PIPE.get());
        associate(pipe, AllBlockEntityTypes.GLASS_FLUID_PIPE.get());
        associate(pipe, AllBlockEntityTypes.SMART_FLUID_PIPE.get());
        associate(PUMP.get(), AllBlockEntityTypes.MECHANICAL_PUMP.get());
        associateTanks(TANK.get());
    }

    /**
     * Tank Contents goes on EVERY vessel built on Create's tank block entity, not just Create's own
     * two: a derivative — Create: Connected's fluid vessel — reads through the same handler and holds
     * its fluid the same way, so naming the types by hand simply left it without the source. The
     * block states its own block entity through Create's {@code IBE}, so the test needs no instance
     * and no mod id.
     */
    private static void associateTanks(DisplaySource tank) {
        Set<BlockEntityType<?>> seen = new HashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof IBE<?> ibe)) continue;
            if (!FluidTankBlockEntity.class.isAssignableFrom(ibe.getBlockEntityClass())) continue;
            if (seen.add(ibe.getBlockEntityType())) associate(tank, ibe.getBlockEntityType());
        }
    }

    private static void associate(DisplaySource source, BlockEntityType<?> type) {
        DisplaySource.BY_BLOCK_ENTITY.add(type, source);
    }

    private PnpDisplaySources() {}
}
