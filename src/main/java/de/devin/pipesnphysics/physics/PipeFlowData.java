package de.devin.pipesnphysics.physics;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Interface for accessing physics flow data stored on pipe block entities.
 * Implemented by FluidTransportBehaviour via mixin.
 */
public interface PipeFlowData {

    PressureBreakdown pipesnphysics$getBreakdown();

    void pipesnphysics$setBreakdown(PressureBreakdown breakdown);

    void pipesnphysics$setFlowOnConnection(Direction side, boolean inbound, FluidStack fluid);
}
