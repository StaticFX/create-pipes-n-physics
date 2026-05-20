package de.devin.pipesnphysics.compat;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

public class SablePhysicsCompat {

    private static final Map<String, Double> lastAppliedMass = new HashMap<>();

    public static void applyFluidWeight(ServerSubLevel subLevel, BlockPos controllerPos,
                                        int width, int height, double fillFraction,
                                        double massKg, double timeStep) {
        if (massKg <= 0) return;

        if (PipesNPhysicsConfig.EXPERIMENTAL_TANK_COG.get()) {
            applyViaMassTracker(subLevel, controllerPos, fillFraction, massKg);
        } else {
            applyViaForce(subLevel, massKg);
        }
    }

    private static void applyViaForce(ServerSubLevel subLevel, double massKg) {
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(subLevel.getLevel());
        if (system == null) return;
        var pipeline = system.getPipeline();
        if (pipeline == null) return;

        Vector3d force = new Vector3d(0, -massKg, 0);
        pipeline.applyLinearAndAngularImpulse(subLevel, force, new Vector3d(0, 0, 0), true);
    }

    private static final Map<String, Vec3> lastAppliedOffset = new HashMap<>();

    private static void applyViaMassTracker(ServerSubLevel subLevel, BlockPos controllerPos, double fillFraction, double massKg) {
        MassTracker tracker = subLevel.getSelfMassTracker();
        if (tracker == null) return;

        String key = subLevel.getUniqueId() + ":" + controllerPos.toShortString();
        Vec3 offset = tiltAwareOffset(subLevel, fillFraction);

        Double prevMass = lastAppliedMass.get(key);
        if (prevMass != null && Math.abs(prevMass - massKg) < 0.001) return;

        try {
            var level = subLevel.getPlot().getEmbeddedLevelAccessor();
            BlockState state = level.getBlockState(controllerPos);

            if (prevMass != null && prevMass > 0) {
                Vec3 prevOffset = lastAppliedOffset.getOrDefault(key, offset);
                tracker.addBlockMass(level, state, controllerPos, -prevMass, prevOffset);
            }

            tracker.addBlockMass(level, state, controllerPos, massKg, offset);
            lastAppliedMass.put(key, massKg);
            lastAppliedOffset.put(key, offset);
        } catch (Exception e) {
            return;
        }
    }

    private static Vec3 tiltAwareOffset(ServerSubLevel subLevel, double fillFraction) {
        double cx = 0.5;
        double cy = fillFraction / 2.0;
        double cz = 0.5;

        Pose3dc pose = subLevel.logicalPose();
        if (pose == null) return new Vec3(cx, cy, cz);

        Vector3d localGrav = pose.transformNormalInverse(new Vector3d(0, -1, 0), new Vector3d());

        double emptyRoom = 1.0 - fillFraction;
        cx += localGrav.x * 0.5 * emptyRoom * 0.5;
        cy += localGrav.y * 0.5 * emptyRoom * 0.5;
        cz += localGrav.z * 0.5 * emptyRoom * 0.5;

        cx = Math.clamp(cx, 0.05, 0.95);
        cy = Math.clamp(cy, 0.05, 0.95);
        cz = Math.clamp(cz, 0.05, 0.95);

        return new Vec3(cx, cy, cz);
    }
}
