package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.PipeConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(value = PipeConnection.class, remap = false)
public interface PipeConnectionAccessor {
    @Accessor("flow")
    Optional<PipeConnection.Flow> pipesnphysics$getFlow();

    @Accessor("flow")
    void pipesnphysics$setFlow(Optional<PipeConnection.Flow> flow);
}
