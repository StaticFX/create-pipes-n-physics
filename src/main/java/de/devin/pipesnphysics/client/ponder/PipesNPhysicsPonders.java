package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.AllBlocks;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class PipesNPhysicsPonders {
    public static final Set<ResourceLocation> OVERRIDDEN_COMPONENTS = Set.of(
            AllBlocks.FLUID_PIPE.getId(),
            AllBlocks.MECHANICAL_PUMP.getId()
    );

    private PipesNPhysicsPonders() {}

    public static boolean useModPonders() {
        return PipesNPhysicsConfig.ENABLE_ENGINE.get();
    }

    public static boolean shouldHide(PonderScene scene) {
        if (!OVERRIDDEN_COMPONENTS.contains(scene.getLocation())) return false;
        if (useModPonders()) return "create".equals(scene.getNamespace());
        return PipesNPhysics.ID.equals(scene.getNamespace());
    }
}
