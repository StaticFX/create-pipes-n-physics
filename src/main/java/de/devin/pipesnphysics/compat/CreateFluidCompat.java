package de.devin.pipesnphysics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Optional compatibility with Create: Fluid's centrifugal pump ({@code fluid:centrifugal_pump}).
 * Uses reflection so the mod is not a hard dependency.
 */
public final class CreateFluidCompat {
    public static final ResourceLocation CENTRIFUGAL_PUMP_ID =
            ResourceLocation.fromNamespaceAndPath("fluid", "centrifugal_pump");

    /** Create: Fluid applies 2× pressure vs Create's mechanical pump at the same RPM. */
    public static final double PERFORMANCE_MULTIPLIER = 2.0;

    private static final Provider PROVIDER;

    static {
        Provider provider;
        try {
            Class.forName("com.adonis.fluid.block.CentrifugalPump.CentrifugalPumpBlock", false,
                    CreateFluidCompat.class.getClassLoader());
            provider = new ReflectiveProvider();
        } catch (ReflectiveOperationException e) {
            provider = NoOpProvider.INSTANCE;
        }
        PROVIDER = provider;
    }

    private CreateFluidCompat() {}

    public static boolean isLoaded() {
        return PROVIDER instanceof ReflectiveProvider;
    }

    public static boolean isCentrifugalPump(BlockState state) {
        return PROVIDER.isCentrifugalPump(state);
    }

    public static boolean isCentrifugalPump(Level level, BlockPos pos) {
        return PROVIDER.isCentrifugalPump(level.getBlockState(pos));
    }

    /** Push and pull ports; null when the block is not a centrifugal pump or ports cannot resolve. */
    public static PumpPorts getPumpPorts(Level level, BlockPos pos, BlockState state) {
        return PROVIDER.getPumpPorts(level, pos, state);
    }

    public static Direction getPushSide(Level level, BlockPos pos, BlockState state) {
        PumpPorts ports = getPumpPorts(level, pos, state);
        return ports != null ? ports.push() : null;
    }

    public record PumpPorts(Direction push, Direction pull) {}

    private interface Provider {
        boolean isCentrifugalPump(BlockState state);

        PumpPorts getPumpPorts(Level level, BlockPos pos, BlockState state);
    }

    private static final class NoOpProvider implements Provider {
        static final NoOpProvider INSTANCE = new NoOpProvider();

        @Override
        public boolean isCentrifugalPump(BlockState state) {
            return false;
        }

        @Override
        public PumpPorts getPumpPorts(Level level, BlockPos pos, BlockState state) {
            return null;
        }
    }

    private static final class ReflectiveProvider implements Provider {
        private final Class<?> blockClass;
        private final Class<?> beClass;

        ReflectiveProvider() throws ReflectiveOperationException {
            blockClass = Class.forName("com.adonis.fluid.block.CentrifugalPump.CentrifugalPumpBlock");
            beClass = Class.forName("com.adonis.fluid.block.CentrifugalPump.CentrifugalPumpBlockEntity");
        }

        @Override
        public boolean isCentrifugalPump(BlockState state) {
            return blockClass.isInstance(state.getBlock());
        }

        @Override
        public PumpPorts getPumpPorts(Level level, BlockPos pos, BlockState state) {
            if (!isCentrifugalPump(state)) return null;
            try {
                Direction primary = (Direction) blockClass
                        .getMethod("getPrimaryFluidDirection", BlockState.class)
                        .invoke(null, state);
                Direction secondary = (Direction) blockClass
                        .getMethod("getSecondaryFluidDirection", BlockState.class)
                        .invoke(null, state);
                if (primary == null || secondary == null) return null;

                var be = level.getBlockEntity(pos);
                if (!beClass.isInstance(be)) return null;
                boolean pullPrimary = (boolean) beClass
                        .getMethod("isPullingOnSide", boolean.class)
                        .invoke(be, true);
                Direction push = pullPrimary ? secondary : primary;
                Direction pull = pullPrimary ? primary : secondary;
                return new PumpPorts(push, pull);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    }
}
