package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class PipesNPhysicsScenes {

    private static void fillTank(SceneBuilder scene, BlockPos pos) {
        scene.world().modifyBlockEntity(pos, FluidTankBlockEntity.class, tank -> {
            var inv = tank.getTankInventory();
            inv.fill(new FluidStack(Fluids.WATER.builtInRegistryHolder(), inv.getCapacity()),
                    IFluidHandler.FluidAction.EXECUTE);
        });
    }

    /**
     * Pump Basics: How a pump transfers fluids.
     * pump.nbt (5×4×5):
     *   Z=1: Basin(4,1,1) → pipes → Tank(0,1,1)
     *   Z=4: Tank(0,1-2,4) → pipes → Tank(4,1-2,4)
     *   Pump at (2,1,2) connecting via pipe(2,1,3)
     */
    public static void pumpBasics(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pump", "Mechanical Pump");
        scene.scaleSceneView(0.7f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        // Show base
        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        // Show the source tank (Z=4 left side, tall)
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(0, 1, 4, 0, 2, 4), Direction.DOWN);
        fillTank(scene, new BlockPos(0, 1, 4));
        scene.idle(15);

        scene.overlay().showText(80)
                .text("A fluid tank filled with water")
                .pointAt(util.vector().centerOf(0, 1, 4))
                .placeNearTarget();
        scene.idle(100);

        // Show the destination tank (Z=1 left side)
        scene.world().showSection(util.select().position(0, 1, 1), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(80)
                .text("We want to move the fluid to this empty tank")
                .pointAt(util.vector().centerOf(0, 1, 1))
                .placeNearTarget();
        scene.idle(100);

        // Show the pipes connecting source to dest
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(1, 1, 4, 3, 1, 4), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(1, 1, 1, 3, 1, 1), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(80)
                .text("Connect them with fluid pipes")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(80)
                .text("Fluid won't move on its own through horizontal pipes. It needs a pump!")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(100);

        // Show the pump
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 2, 2, 1, 3), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.GREEN, "pump",
                util.select().position(2, 1, 2), 100);
        scene.overlay().showText(80)
                .text("A Mechanical Pump creates pressure to push fluids through pipes")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget();
        scene.idle(100);

        // Power the pump and propagate
        scene.addKeyframe();
        createScene.world().setKineticSpeed(util.select().everywhere(), 32);
        scene.idle(10);
        createScene.world().propagatePipeChange(new BlockPos(2, 1, 2));
        scene.idle(10);

        scene.overlay().showText(100)
                .text("Give it rotational power and the pump starts pushing fluid through the network")
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget();
        scene.idle(60);

        // Let fluid flow visibly
        scene.idle(80);

        // Show remaining parts (basin, other tanks)
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(4, 1, 1, 4, 1, 1), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(4, 1, 4, 4, 2, 4), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(100)
                .text("The pump pushes fluid to all connected pipes in its network")
                .pointAt(util.vector().centerOf(0, 1, 1))
                .placeNearTarget();
        scene.idle(120);

        scene.overlay().showOutline(PonderPalette.GREEN, "pumpdir",
                util.select().position(2, 1, 2), 80);
        scene.overlay().showText(80)
                .text("The pump's facing direction determines which way it pushes")
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget();
        scene.idle(100);

        scene.markAsFinished();
    }

    /**
     * Scene 1: What is Friction?
     * friction_new.nbt (5×4×5):
     *   Z=0: Tall tank(0,1-3,0) → pipe(1,1,0) → pipe(2,1,0)
     *   Z=3: Tank(0,1,3) → pipe(1,1,3) → pipe(2,1,3) → pipe(3,1,3) → Tank(4,1,3)
     *   Pump at (2,1,1) connecting via pipe(2,1,2)
     */
    public static void frictionIntro(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("friction_new", "What is Friction?");
        scene.scaleSceneView(0.7f);
        scene.idle(5);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        // Show the horizontal pipe run (Z=3)
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(0, 1, 3, 4, 1, 3), Direction.DOWN);
        fillTank(scene, new BlockPos(0, 1, 3));
        scene.idle(20);

        scene.overlay().showText(100)
                .text("Pushing fluid through pipes creates friction")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.overlay().showOutline(PonderPalette.RED, "horizpipes",
                util.select().fromTo(1, 1, 3, 3, 1, 3), 100);
        scene.overlay().showText(100)
                .text("Each horizontal pipe segment costs friction, reducing range and flow")
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget();
        scene.idle(120);

        // Show the tall tank + vertical drop side (Z=0)
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(0, 1, 0, 2, 3, 0), Direction.DOWN);
        fillTank(scene, new BlockPos(0, 1, 0));
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.GREEN, "vertpipes",
                util.select().fromTo(1, 1, 0, 2, 1, 0), 100);
        scene.overlay().showText(100)
                .text("Vertical pipes have zero friction. Gravity does the work for free!")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(1, 1, 0))
                .placeNearTarget();
        scene.idle(120);

        // Show the pump connecting the two
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 1, 2, 1, 2), Direction.DOWN);
        scene.idle(10);

        var createScene = new CreateSceneBuilder(scene);
        createScene.world().setKineticSpeed(util.select().everywhere(), 32);
        scene.idle(20);

        scene.overlay().showText(100)
                .text("Each horizontal pipe segment has a friction value (configurable in settings)")
                .placeNearTarget();
        scene.idle(110);

        scene.markAsFinished();
    }

    /**
     * Scene 2: What is Pressure?
     * pressure_2.nbt (6×7×6)
     */
    public static void pressureIntro(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pressure_2", "What is Pressure?");
        scene.scaleSceneView(0.55f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        // Show gravity drop: tall tank → pipes down → tank
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 4, 2, 6, 4), Direction.DOWN);
        fillTank(scene, new BlockPos(2, 6, 4));
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.BLUE, "drop",
                util.select().fromTo(2, 2, 4, 2, 5, 4), 120);
        scene.overlay().showText(100)
                .text("A vertical drop creates pressure. Each block of height adds to it")
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().centerOf(2, 4, 4))
                .placeNearTarget();
        scene.idle(120);

        scene.overlay().showText(100)
                .text("Pressure is what pushes fluid through pipes, overcoming friction")
                .pointAt(util.vector().centerOf(2, 2, 4))
                .placeNearTarget();
        scene.idle(120);

        // Show pump side
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(1, 1, 0, 4, 1, 4), Direction.DOWN);
        fillTank(scene, new BlockPos(1, 1, 0));
        scene.idle(15);

        createScene.world().setKineticSpeed(util.select().everywhere(), 64);
        createScene.world().propagatePipeChange(new BlockPos(3, 1, 2));
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.GREEN, "pump",
                util.select().position(3, 1, 2), 120);
        scene.overlay().showText(100)
                .text("A mechanical pump also creates pressure, driven by RPM from your kinetic network")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(3, 1, 2))
                .placeNearTarget();
        scene.idle(120);

        scene.addKeyframe();
        scene.overlay().showText(80)
                .text("As long as pressure exceeds friction, fluid flows. Use goggles to see the breakdown!")
                .pointAt(util.vector().centerOf(4, 1, 4))
                .placeNearTarget();
        scene.idle(100);

        scene.markAsFinished();
    }

    /**
     * Scene 3: Pressure vs Friction.
     * l_shape_drop.nbt (6×7×6)
     */
    public static void lShapeExample(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("l_shape_drop", "Pressure vs Friction");
        scene.scaleSceneView(0.55f);
        scene.idle(5);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        // Show top tank
        scene.addKeyframe();
        scene.world().showSection(util.select().position(4, 6, 2), Direction.DOWN);
        fillTank(scene, new BlockPos(4, 6, 2));
        scene.idle(20);

        scene.overlay().showText(100)
                .text("This tank sits high up, building pressure from the vertical drop")
                .pointAt(util.vector().centerOf(4, 6, 2))
                .placeNearTarget();
        scene.idle(120);

        // Reveal vertical pipes one by one
        scene.addKeyframe();
        for (int y = 5; y >= 1; y--) {
            scene.world().showSection(util.select().position(4, y, 2), Direction.DOWN);
            scene.idle(10);
        }
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.GREEN, "vert",
                util.select().fromTo(4, 2, 2, 4, 5, 2), 120);
        scene.overlay().showText(100)
                .text("The vertical pipes add zero friction, all the pressure is preserved")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(4, 4, 2))
                .placeNearTarget();
        scene.idle(120);

        // Reveal horizontal pipes + dest tank
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(1, 1, 2, 3, 1, 2), Direction.EAST);
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.RED, "horiz",
                util.select().fromTo(2, 1, 2, 4, 1, 2), 120);
        scene.overlay().showText(100)
                .text("The horizontal pipes spend that pressure on friction, one segment at a time")
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(3, 1, 2))
                .placeNearTarget();
        scene.idle(120);

        scene.effects().indicateSuccess(new BlockPos(1, 1, 2));
        scene.overlay().showText(80)
                .text("A taller drop means more pressure, letting fluid push further horizontally")
                .placeNearTarget();
        scene.idle(100);

        scene.markAsFinished();
    }

    /**
     * Scene 4: Pumping Uphill.
     * uphill.nbt (6×6×6)
     */
    public static void pumpUphill(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("uphill", "Pumping Uphill");
        scene.scaleSceneView(0.6f);
        scene.idle(5);

        var createScene = new CreateSceneBuilder(scene);

        scene.world().showSection(util.select().layer(0), Direction.DOWN);
        scene.idle(15);

        // Show source tank
        scene.addKeyframe();
        scene.world().showSection(util.select().position(1, 1, 3), Direction.DOWN);
        fillTank(scene, new BlockPos(1, 1, 3));
        scene.idle(15);

        scene.overlay().showText(80)
                .text("A tank filled with fluid at ground level")
                .pointAt(util.vector().centerOf(1, 1, 3))
                .placeNearTarget();
        scene.idle(100);

        // Show pipes and pump
        scene.addKeyframe();
        scene.world().showSection(util.select().fromTo(2, 1, 3, 4, 5, 3), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.GREEN, "pump",
                util.select().position(3, 1, 3), 100);
        scene.overlay().showText(100)
                .text("This pump needs to push fluid upward through vertical pipes")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget();
        scene.idle(120);

        // Power up and propagate
        scene.addKeyframe();
        createScene.world().setKineticSpeed(util.select().everywhere(), 64);
        createScene.world().propagatePipeChange(new BlockPos(3, 1, 3));
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.RED, "upward",
                util.select().fromTo(4, 2, 3, 4, 4, 3), 120);
        scene.overlay().showText(100)
                .text("Each block of height costs pressure, gravity works against the pump")
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(4, 3, 3))
                .placeNearTarget();
        scene.idle(60);

        // Let fluid flow visibly
        scene.idle(80);

        scene.addKeyframe();
        scene.effects().indicateSuccess(new BlockPos(4, 5, 3));
        scene.overlay().showText(100)
                .text("A faster pump (more RPM) can push fluid higher")
                .pointAt(util.vector().centerOf(4, 5, 3))
                .placeNearTarget();
        scene.idle(120);

        scene.overlay().showText(80)
                .text("Pushing downhill is free, gravity assists instead of fighting")
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget();
        scene.idle(100);

        scene.markAsFinished();
    }
}
