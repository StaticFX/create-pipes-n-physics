package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;
import com.tterrag.registrate.util.entry.RegistryEntry;
import de.devin.pipesnphysics.PipesNPhysics;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public final class PipesNPhysicsPonderTags {
    public static final ResourceLocation PIPE_PHYSICS = PipesNPhysics.asResource("pipe_physics");

    private PipesNPhysicsPonderTags() {}

    public static void register(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PonderTagRegistrationHelper<RegistryEntry> entries =
                helper.withKeyFunction(RegistryEntry::getId);

        helper.registerTag(PIPE_PHYSICS)
                .addToIndex()
                .item(AllBlocks.MECHANICAL_PUMP, true, false)
                .title("Pipe Physics")
                .description("Realistic fluid pressure, lift limits, and flow for Create pipe networks")
                .register();

        entries.addToTag(PIPE_PHYSICS)
                .add(AllBlocks.FLUID_PIPE)
                .add(AllBlocks.MECHANICAL_PUMP)
                .add(AllBlocks.FLUID_TANK)
                .add(AllItems.GOGGLES);

        entries.addToTag(AllCreatePonderTags.FLUIDS)
                .add(AllBlocks.FLUID_PIPE)
                .add(AllBlocks.MECHANICAL_PUMP)
                .add(AllBlocks.FLUID_TANK);
    }
}
