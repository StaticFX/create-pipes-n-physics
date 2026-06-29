package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
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
                .addStoryBoard("friction_new", PipesNPhysicsPonderScenes::frictionIntro, tag)
                .addStoryBoard("pressure_2", PipesNPhysicsPonderScenes::pressureIntro, tag)
                .addStoryBoard("l_shape_drop", PipesNPhysicsPonderScenes::lShapeExample, tag)
                .addStoryBoard("uphill", PipesNPhysicsPonderScenes::pumpUphill, tag)
                .addStoryBoard("vertical_drop", PipesNPhysicsPonderScenes::verticalDrop, tag)
                .addStoryBoard("siphon", PipesNPhysicsPonderScenes::siphonLimit, tag);

        helper.forComponents(AllBlocks.MECHANICAL_PUMP.getId())
                .addStoryBoard("pump", PipesNPhysicsPonderScenes::pumpBasics,
                        AllCreatePonderTags.KINETIC_APPLIANCES, tag)
                .addStoryBoard("pressure_2", PipesNPhysicsPonderScenes::pressureIntro, tag)
                .addStoryBoard("uphill", PipesNPhysicsPonderScenes::pumpUphill, tag)
                .addStoryBoard("siphon", PipesNPhysicsPonderScenes::siphonLimit, tag);

        helper.forComponents(AllItems.GOGGLES.getId())
                .addStoryBoard("pressure_2", PipesNPhysicsPonderScenes::gogglesHint, tag);
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

    public static void pumpBasics(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pump", "Mechanical Pump");
        scene.scaleSceneView(0.7f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(0, 1, 4, 0, 2, 4), Direction.DOWN);
        fillTank(scene, new BlockPos(0, 1, 4));
        scene.idle(15);

        scene.overlay().showText(80)
                .text("text_1")
                .pointAt(util.vector().centerOf(0, 1, 4))
                .placeNearTarget();
        scene.idle(100);

        scene.world().showSection(util.select().position(0, 1, 1), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(80)
                .text("text_2")
                .pointAt(util.vector().centerOf(0, 1, 1))
                .placeNearTarget();
        scene.idle(100);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(1, 1, 4, 3, 1, 4), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(1, 1, 1, 3, 1, 1), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(80)
                .text("text_3")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(80)
                .text("text_4")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(100);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 2, 2, 2, 3), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.GREEN, "pump",
                util.select().position(2, 1, 2), 100);
        scene.overlay().showText(80)
                .text("text_5")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget();
        scene.idle(100);

        scene.addKeyframe();
        createScene.world().setKineticSpeed(util.select().everywhere(), 32);
        scene.idle(10);

        // Animate the tanks to show the pump in action
        for (int i = 0; i < 80; i++) {
            float progress = i / 80f;
            setTankFill(scene, new BlockPos(0, 1, 4), 1f - progress);
            setTankFill(scene, new BlockPos(0, 1, 1), progress);
            showFlow(scene, new BlockPos(2, 1, 2));
            scene.idle(1);
        }

        scene.overlay().showText(100)
                .text("text_6")
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget();
        scene.idle(60);

        scene.addKeyframe();
        scene.world().showSection(util.select().position(4, 1, 1), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(4, 1, 4, 4, 2, 4), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(100)
                .text("text_7")
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget();
        scene.idle(120);

        scene.markAsFinished();
    }

    public static void frictionIntro(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("friction", "Pipe Run Friction");
        scene.scaleSceneView(0.7f);
        scene.idle(5);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(0, 1, 3, 4, 1, 3), Direction.DOWN);
        fillTank(scene, new BlockPos(0, 1, 3));
        scene.idle(20);

        scene.overlay().showText(100)
                .text("text_1")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(60);
        scene.overlay().showText(100)
                .text("text_2")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.overlay().showOutline(PonderPalette.RED, "horizpipes",
                util.select().fromTo(1, 1, 3, 3, 1, 3), 100);
        scene.overlay().showText(100)
                .text("text_3")
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(0, 1, 0, 2, 3, 0), Direction.DOWN);
        fillTank(scene, new BlockPos(0, 1, 0));
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.GREEN, "vertpipes",
                util.select().fromTo(1, 1, 0, 2, 1, 0), 100);
        scene.overlay().showText(100)
                .text("text_4")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(1, 1, 0))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 1, 2, 2, 2), Direction.DOWN);
        scene.idle(10);

        var createScene = new CreateSceneBuilder(scene);
        createScene.world().setKineticSpeed(util.select().everywhere(), 32);
        scene.idle(10);

        // Show flow and animate tanks to demonstrate friction vs gravity
        for (int i = 0; i < 100; i++) {
            float progress = i / 100f;
            // Horizontal run (slow)
            setTankFill(scene, new BlockPos(0, 1, 3), 1f - 0.2f * progress);

            // Vertical drop (fast)
            setTankFill(scene, new BlockPos(0, 1, 0), 1f - 0.8f * progress);

            showFlow(scene, new BlockPos(2, 1, 3));
            showFlow(scene, new BlockPos(1, 1, 0));
            scene.idle(1);
        }

        scene.overlay().showText(100)
                .text("text_5")
                .placeNearTarget();
        scene.idle(110);

        scene.markAsFinished();
    }

    public static void pressureIntro(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pressure", "Head and Pressure");
        scene.scaleSceneView(0.55f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 4, 2, 6, 4), Direction.DOWN);
        fillTank(scene, new BlockPos(2, 6, 4));
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.BLUE, "drop",
                util.select().fromTo(2, 2, 4, 2, 5, 4), 120);
        scene.overlay().showText(100)
                .text("text_1")
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().centerOf(2, 4, 4))
                .placeNearTarget();
        scene.idle(120);

        scene.overlay().showText(100)
                .text("text_2")
                .pointAt(util.vector().centerOf(2, 2, 4))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(1, 1, 0, 4, 1, 4), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(100)
                .text("text_3")
                .pointAt(util.vector().centerOf(2, 2, 4))
                .placeNearTarget();

        for (int i = 0; i < 40; i++) {
            float progress = i / 40f;
            setTankFill(scene, new BlockPos(2, 6, 4), 1f - 0.5f * progress);
            setTankFill(scene, new BlockPos(1, 1, 0), 0.5f * progress);
            showFlow(scene, new BlockPos(2, 4, 4));
            scene.idle(1);
        }
        scene.idle(60);

        scene.addKeyframe();
        createScene.world().setKineticSpeed(util.select().everywhere(), 64);
        showFlow(scene, new BlockPos(3, 1, 2));
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.GREEN, "pump",
                util.select().position(3, 1, 2), 120);
        scene.overlay().showText(100)
                .text("text_4")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(3, 1, 2))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("text_5")
                .pointAt(util.vector().centerOf(4, 1, 4))
                .placeNearTarget();
        scene.idle(100);

        scene.markAsFinished();
    }

    public static void lShapeExample(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("l_shape", "Pressure vs Friction");
        scene.scaleSceneView(0.55f);
        scene.idle(5);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.world().showSection(util.select().position(4, 6, 2), Direction.DOWN);
        fillTank(scene, new BlockPos(4, 6, 2));
        scene.idle(20);

        scene.overlay().showText(100)
                .text("text_1")
                .pointAt(util.vector().centerOf(4, 6, 2))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        for (int y = 5; y >= 1; y--) {
            scene.world().showSection(util.select().position(4, y, 2), Direction.DOWN);
            scene.idle(10);
        }
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.GREEN, "vert",
                util.select().fromTo(4, 2, 2, 4, 5, 2), 120);
        scene.overlay().showText(100)
                .text("text_2")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(4, 4, 2))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(1, 1, 2, 3, 1, 2), Direction.EAST);
        scene.idle(10);
        showFlow(scene, new BlockPos(4, 3, 2));
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.RED, "horiz",
                util.select().fromTo(2, 1, 2, 4, 1, 2), 120);
        scene.overlay().showText(100)
                .text("text_3")
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(3, 1, 2))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.effects().indicateSuccess(new BlockPos(1, 1, 2));
        scene.overlay().showText(80)
                .text("text_4")
                .placeNearTarget();
        scene.idle(100);

        scene.markAsFinished();
    }

    public static void pumpUphill(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("uphill", "Pumping Uphill");
        scene.scaleSceneView(0.6f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.world().showSection(util.select().position(1, 1, 3), Direction.DOWN);
        fillTank(scene, new BlockPos(1, 1, 3));
        scene.idle(15);

        scene.overlay().showText(80)
                .text("text_1")
                .pointAt(util.vector().centerOf(1, 1, 3))
                .placeNearTarget();
        scene.idle(100);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 3, 4, 5, 3), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.GREEN, "pump",
                util.select().position(3, 1, 3), 100);
        scene.overlay().showText(100)
                .text("text_2")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        // Start with low RPM - not enough to reach the top
        createScene.world().setKineticSpeed(util.select().everywhere(), 8);
        showFlow(scene, new BlockPos(3, 1, 3));
        scene.idle(60);

        scene.overlay().showOutline(PonderPalette.RED, "upward",
                util.select().fromTo(4, 2, 3, 4, 4, 3), 120);
        scene.overlay().showText(100)
                .text("text_3")
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(4, 3, 3))
                .placeNearTarget();
        scene.idle(140);

        scene.addKeyframe();
        // Increase RPM to reach the top
        createScene.world().setKineticSpeed(util.select().everywhere(), 64);

        // Animate the tank draining and the top tank filling
        for (int i = 0; i < 60; i++) {
            float progress = i / 60f;
            setTankFill(scene, new BlockPos(1, 1, 3), 1f - progress);
            setTankFill(scene, new BlockPos(4, 5, 3), progress);
            showFlow(scene, new BlockPos(3, 1, 3));
            scene.idle(1);
        }

        scene.effects().indicateSuccess(new BlockPos(4, 5, 3));
        scene.overlay().showText(100)
                .text("text_4")
                .pointAt(util.vector().centerOf(4, 5, 3))
                .placeNearTarget();
        scene.idle(120);

        scene.markAsFinished();
    }

    public static void verticalDrop(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("vertical_drop", "Gravity Equalization");
        scene.scaleSceneView(0.6f);
        scene.idle(5);

        BlockPos highTank = new BlockPos(2, 5, 4);
        BlockPos lowTank = new BlockPos(2, 1, 4);
        BlockPos seedPipe = new BlockPos(2, 3, 4);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 4, 2, 5, 4), Direction.DOWN);
        fillTank(scene, highTank);
        scene.idle(10);
        showFlow(scene, seedPipe);
        scene.idle(10);

        scene.overlay().showText(90)
                .text("text_1")
                .pointAt(util.vector().centerOf(highTank))
                .placeNearTarget();
        scene.idle(100);

        scene.addKeyframe();
        scene.overlay().showText(90)
                .text("text_2")
                .pointAt(util.vector().centerOf(lowTank))
                .placeNearTarget();

        for (int i = 0; i < 40; i++) {
            float progress = i / 40f;
            setTankFill(scene, highTank, 1f - 0.5f * progress);
            setTankFill(scene, lowTank, 0.5f * progress);
            showFlow(scene, seedPipe);
            scene.idle(1);
        }

        scene.idle(60);
        scene.markAsFinished();
    }

    public static void siphonLimit(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("siphon", "Siphon Limits");
        scene.scaleSceneView(0.6f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        BlockPos sourceTank = new BlockPos(0, 4, 0);
        BlockPos pump = new BlockPos(0, 4, 1);
        BlockPos crest = new BlockPos(0, 4, 2);
        BlockPos destTank = new BlockPos(4, 4, 2);
        BlockPos seedPipe = new BlockPos(2, 1, 2);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(0, 1, 2, 4, 1, 2), Direction.EAST);
        scene.world().showSection(util.select().fromTo(0, 2, 2, 0, 3, 2), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(4, 2, 2, 4, 3, 2), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(0, 4, 0, 0, 4, 2), Direction.DOWN);
        scene.world().showSection(util.select().position(4, 4, 2), Direction.DOWN);
        fillTank(scene, sourceTank);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.GREEN, "pump",
                util.select().position(0, 4, 1), 100);
        scene.overlay().showText(80)
                .colored(PonderPalette.GREEN)
                .text("text_1")
                .pointAt(util.vector().centerOf(pump))
                .placeNearTarget();
        scene.idle(90);

        scene.addKeyframe();
        createScene.world().setKineticSpeed(util.select().everywhere(), 64);
        showFlow(scene, seedPipe);
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.RED, "crest",
                util.select().position(0, 4, 2), 100);
        scene.overlay().showText(90)
                .text("text_2")
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(crest))
                .placeNearTarget();
        scene.idle(110);

        scene.addKeyframe();
        scene.overlay().showText(90)
                .text("text_3")
                .pointAt(util.vector().centerOf(crest))
                .placeNearTarget();

        for (int i = 0; i < 50; i++) {
            float progress = i / 50f;
            setTankFill(scene, sourceTank, 1f - progress);
            setTankFill(scene, destTank, progress);
            if (progress < 0.7f) {
                showFlow(scene, seedPipe);
            }
            scene.idle(1);
        }

        scene.idle(60);
        scene.markAsFinished();
    }

    public static void gogglesHint(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("goggles", "Engineer's Goggles");
        scene.scaleSceneView(0.55f);
        scene.idle(5);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(1, 1, 0, 4, 1, 4), Direction.DOWN);
        scene.idle(15);

        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("text_1")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(3, 1, 2));
        scene.idle(90);

        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("text_2")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(3, 1, 2));
        scene.idle(90);

        scene.markAsFinished();
    }
}
