package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Graph;
import de.devin.pipesnphysics.engine.GraphBuilder;
import de.devin.pipesnphysics.engine.Solution;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public final class PipesNPhysicsPonderScenes {
    private PipesNPhysicsPonderScenes() {}

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation tag = PipesNPhysicsPonderTags.PIPE_PHYSICS;

        helper.forComponents(AllBlocks.FLUID_PIPE.getId())
                .addStoryBoard("uphill", PipesNPhysicsPonderScenes::fluidDynamics, tag)
                .addStoryBoard("siphon", PipesNPhysicsPonderScenes::siphonsAndSuction, tag);

        helper.forComponents(AllBlocks.MECHANICAL_PUMP.getId())
                .addStoryBoard("uphill", PipesNPhysicsPonderScenes::fluidDynamics,
                        AllCreatePonderTags.KINETIC_APPLIANCES, tag)
                .addStoryBoard("siphon", PipesNPhysicsPonderScenes::siphonsAndSuction, tag);
    }

    private static void fillTank(SceneBuilder scene, BlockPos pos) {
        scene.world().modifyBlockEntity(pos, FluidTankBlockEntity.class, tank -> {
            var inv = tank.getTankInventory();
            inv.fill(new FluidStack(Fluids.WATER, inv.getCapacity()), FluidAction.EXECUTE);
        });
    }

    private static void setTankFill(SceneBuilder scene, BlockPos pos, float fillPercentage) {
        scene.world().modifyBlockEntity(pos, FluidTankBlockEntity.class, tank -> {
            var inv = tank.getTankInventory();
            inv.drain(inv.getCapacity(), FluidAction.EXECUTE);
            inv.fill(new FluidStack(Fluids.WATER, (int) (inv.getCapacity() * fillPercentage)), FluidAction.EXECUTE);
        });
    }

    private static void showFlow(SceneBuilder scene, BlockPos seedPos) {
        scene.world().modifyBlockEntity(seedPos, SmartBlockEntity.class, be -> {
            Level level = be.getLevel();
            if (level == null) return;
            Graph graph = GraphBuilder.build(level, seedPos);
            Solution solution = FlowSolver.solve(level, graph);
            CreatePipeRendering.apply(level, graph, solution);
        });
    }

    public static void fluidDynamics(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("fluid_dynamics", "Fluid Dynamics");
        scene.scaleSceneView(0.6f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        // Show base and tanks
        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(1, 1, 3), Direction.DOWN); // Bottom tank
        scene.world().showSection(util.select().position(4, 5, 3), Direction.DOWN); // Top tank
        scene.idle(10);

        // 1. Gravity Flow
        scene.addKeyframe();
        fillTank(scene, new BlockPos(4, 5, 3));
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(1, 1, 3, 4, 5, 3).substract(util.select().position(3, 1, 3)), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(80)
                .text("text_1")
                .pointAt(util.vector().centerOf(4, 5, 3))
                .placeNearTarget();
        scene.idle(20);

        scene.overlay().showText(80)
                .text("text_2")
                .pointAt(util.vector().centerOf(4, 3, 3))
                .placeNearTarget();
        scene.idle(20);

        // Animate gravity flow
        for (int i = 0; i < 40; i++) {
            float progress = i / 40f;
            setTankFill(scene, new BlockPos(4, 5, 3), 1f - 0.5f * progress);
            setTankFill(scene, new BlockPos(1, 1, 3), 0.5f * progress);
            showFlow(scene, new BlockPos(4, 3, 3));
            scene.idle(1);
        }
        scene.idle(20);

        scene.overlay().showText(80)
                .text("text_3")
                .pointAt(util.vector().centerOf(1, 1, 3))
                .placeNearTarget();
        scene.idle(90);

        // 2. Friction
        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("text_4")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(90);

        // 3. Pumping Uphill
        scene.addKeyframe();
        scene.world().showSection(util.select().position(3, 1, 3), Direction.DOWN); // Show pump
        scene.idle(15);

        scene.overlay().showText(80)
                .text("text_5")
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget();
        scene.idle(90);

        // Start with low RPM - not enough to reach the top
        scene.addKeyframe();
        createScene.world().setKineticSpeed(util.select().everywhere(), 8);
        showFlow(scene, new BlockPos(3, 1, 3));
        
        scene.overlay().showText(80)
                .text("text_6")
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget();
        scene.idle(90);

        // Increase RPM to reach the top
        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("text_7")
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget();
        scene.idle(20);
        
        createScene.world().setKineticSpeed(util.select().everywhere(), 64);

        // Animate the tank draining and the top tank filling
        for (int i = 0; i < 60; i++) {
            float progress = i / 60f;
            setTankFill(scene, new BlockPos(1, 1, 3), 0.5f - 0.5f * progress);
            setTankFill(scene, new BlockPos(4, 5, 3), 0.5f + 0.5f * progress);
            showFlow(scene, new BlockPos(3, 1, 3));
            scene.idle(1);
        }

        scene.effects().indicateSuccess(new BlockPos(4, 5, 3));
        scene.idle(60);

        scene.markAsFinished();
    }

    public static void siphonsAndSuction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("siphons", "Siphons & Suction");
        scene.scaleSceneView(0.6f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        BlockPos sourceTank = new BlockPos(0, 4, 0);
        BlockPos pump = new BlockPos(0, 4, 1);
        BlockPos crest = new BlockPos(0, 4, 2);
        BlockPos destTank = new BlockPos(4, 4, 2);
        BlockPos seedPipe = new BlockPos(2, 1, 2);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(0, 4, 0, 0, 4, 1), Direction.DOWN); // Source and pump
        fillTank(scene, sourceTank);
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(0, 1, 2, 4, 4, 2), Direction.DOWN); // Crest and dest
        scene.idle(15);

        // 1. Suction
        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("text_1")
                .pointAt(util.vector().centerOf(pump))
                .placeNearTarget();
        scene.idle(90);

        // 2. Siphoning
        scene.addKeyframe();
        createScene.world().setKineticSpeed(util.select().everywhere(), 64);
        showFlow(scene, seedPipe);
        
        scene.overlay().showText(80)
                .text("text_2")
                .pointAt(util.vector().centerOf(crest))
                .placeNearTarget();
        scene.idle(90);

        // 3. Air Break
        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("text_3")
                .pointAt(util.vector().centerOf(crest))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showOutline(PonderPalette.RED, "crest", util.select().position(crest), 80);
        scene.overlay().showText(80)
                .colored(PonderPalette.RED)
                .text("text_4")
                .pointAt(util.vector().centerOf(crest))
                .placeNearTarget();
        scene.idle(90);

        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("text_5")
                .placeNearTarget();
        
        // Animate the siphon working then breaking
        for (int i = 0; i < 50; i++) {
            float progress = i / 50f;
            setTankFill(scene, sourceTank, 1f - progress);
            setTankFill(scene, destTank, progress);
            if (progress < 0.7f) {
                showFlow(scene, seedPipe);
            } else {
                // Siphon breaks
                scene.effects().indicateRedstone(crest);
            }
            scene.idle(1);
        }

        scene.idle(60);
        scene.markAsFinished();
    }
}
