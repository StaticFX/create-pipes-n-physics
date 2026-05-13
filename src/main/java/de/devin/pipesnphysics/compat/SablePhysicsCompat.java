package de.devin.pipesnphysics.compat;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Bridge to Sable's full physics API for dynamic tank mass.
 * This class is ONLY loaded when Sable (full) is present at runtime —
 * the mixin plugin prevents the calling mixin from being applied otherwise.
 * Do NOT reference this class from any code path that runs without Sable.
 */
public class SablePhysicsCompat {

    /**
     * Apply a gravitational impulse representing the weight of fluid in a tank.
     * Applies a downward linear impulse plus angular impulse (torque) when the
     * fluid center of mass is offset from the sub-level's center of mass.
     *
     * @param subLevel     the sub-level containing the tank
     * @param controllerPos the controller block's local position
     * @param width        tank width in blocks (tanks are width × width square)
     * @param height       tank height in blocks
     * @param fillFraction 0.0–1.0 how full the tank is
     * @param massKg       total fluid mass in kilograms
     * @param timeStep     physics timestep in seconds
     */
    public static void applyFluidWeight(ServerSubLevel subLevel, BlockPos controllerPos,
                                        int width, int height, double fillFraction,
                                        double massKg, double timeStep) {
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(subLevel.getLevel());
        if (system == null) return;

        PhysicsPipeline pipeline = system.getPipeline();
        if (pipeline == null) return;

        // Linear impulse: weight = mass × g, impulse = force × dt
        Vector3d linearImpulse = new Vector3d(0, -massKg * 9.81 * timeStep, 0);

        // Compute torque from off-center fluid mass.
        // r × F where r = fluidCenter - structureCenterOfMass (in world space).
        Vector3d angularImpulse = new Vector3d(0, 0, 0);

        MassData massData = subLevel.getMassTracker();
        Vector3dc com = massData != null ? massData.getCenterOfMass() : null;
        if (com != null) {
            // Fluid center in local sub-level coordinates
            double fluidX = controllerPos.getX() + width / 2.0;
            double fluidY = controllerPos.getY() + (fillFraction * height) / 2.0;
            double fluidZ = controllerPos.getZ() + width / 2.0;

            // Transform both to world space
            Pose3dc pose = subLevel.logicalPose();
            Vector3d fluidWorld = pose.transformPosition(
                    new Vector3d(fluidX, fluidY, fluidZ), new Vector3d());
            Vector3d comWorld = pose.transformPosition(
                    new Vector3d(com), new Vector3d());

            // r = offset from center of mass to fluid center
            Vector3d r = new Vector3d(fluidWorld).sub(comWorld);
            // angular impulse = r × linearImpulse
            r.cross(linearImpulse, angularImpulse);
        }

        pipeline.applyLinearAndAngularImpulse(subLevel, linearImpulse, angularImpulse, true);
    }
}
