package de.devin.pipesnphysics.compat;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class SableCompanionImpl implements SableCompatProvider {

    private static final double NORMALIZE_EPSILON = 0.001;
    private final Map<UUID, float[]> lastOrientations = new ConcurrentHashMap<>();

    @Override
    public void clearCaches() {
        lastOrientations.clear();
    }

    @Override
    public boolean isSubLevelReady(Level level, BlockPos pos) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        if (sub == null) return true;
        return sub.logicalPose() != null;
    }

    @Override
    public double getWorldY(Level level, BlockPos pos) {
        Vector3d result = SableCompanion.INSTANCE.projectOutOfSubLevel(level,
                new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), new Vector3d());
        return result.y;
    }

    @Override
    public Vec3 getWorldPos(Level level, BlockPos pos) {
        Vector3d result = SableCompanion.INSTANCE.projectOutOfSubLevel(level,
                new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), new Vector3d());
        return new Vec3(result.x, result.y, result.z);
    }

    @Override
    public float getTiltAngle(Level level, BlockPos pos) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        if (sub == null) return 0;
        return angleFromPose(sub.logicalPose());
    }

    @Override
    public float getTiltAngleClient(BlockEntity be) {
        ClientSubLevelAccess sub = SableCompanion.INSTANCE.getContainingClient(be);
        if (sub == null) sub = SableCompanion.INSTANCE.getContainingClient(be.getBlockPos());
        if (sub == null && be.getLevel() != null) {
            SubLevelAccess sub2 = SableCompanion.INSTANCE.getContaining(be.getLevel(), be.getBlockPos());
            if (sub2 instanceof ClientSubLevelAccess csa) sub = csa;
        }
        if (sub == null) return -1;
        Pose3dc pose = sub.renderPose();
        if (pose == null) pose = sub.logicalPose();
        return angleFromPose(pose);
    }

    @Override
    public float getPipeElevation(Level level, BlockPos pos, Direction dir) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        if (sub != null) {
            Pose3dc pose = sub.logicalPose();
            if (pose != null) {
                Vector3d worldDir = pose.transformNormal(
                        new Vector3d(dir.getStepX(), dir.getStepY(), dir.getStepZ()),
                        new Vector3d());
                double len = Math.sqrt(worldDir.x * worldDir.x + worldDir.y * worldDir.y + worldDir.z * worldDir.z);
                if (len > NORMALIZE_EPSILON) {
                    return (float) Math.toDegrees(Math.asin(Math.min(1, Math.max(-1, Math.abs(worldDir.y) / len))));
                }
            }
        }
        return (float) Math.toDegrees(Math.asin(Math.abs(dir.getStepY())));
    }

    @Override
    public boolean hasSubLevelRotated(Level level, BlockPos pos) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        if (sub == null) return false;

        Pose3dc pose = sub.logicalPose();
        if (pose == null) return false;

        Quaterniondc q = pose.orientation();
        UUID id = sub.getUniqueId();
        float[] current = {(float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()};
        float[] last = lastOrientations.get(id);

        if (last == null) {
            lastOrientations.put(id, current);
            return false;
        }

        float dot = last[0] * current[0] + last[1] * current[1] + last[2] * current[2] + last[3] * current[3];
        float threshold = PipesNPhysicsConfig.GRAVITY_ROTATION_THRESHOLD.get().floatValue();
        if (Math.abs(dot) < threshold) {
            lastOrientations.put(id, current);
            return true;
        }

        return false;
    }

    @Override
    public boolean isOnSubLevelClient(BlockPos pos) {
        return SableCompanion.INSTANCE.getContainingClient(pos) != null;
    }

    @Override
    public float getClientPipeElevation(BlockPos pos, Direction dir) {
        ClientSubLevelAccess sub = SableCompanion.INSTANCE.getContainingClient(pos);
        if (sub == null) return -1;

        Pose3dc pose = sub.renderPose();
        if (pose == null) return -1;

        Vector3d worldDir = pose.transformNormal(
                new Vector3d(dir.getStepX(), dir.getStepY(), dir.getStepZ()),
                new Vector3d());
        double len = Math.sqrt(worldDir.x * worldDir.x + worldDir.y * worldDir.y + worldDir.z * worldDir.z);
        if (len <= NORMALIZE_EPSILON) return -1;

        return (float) Math.toDegrees(Math.asin(Math.min(1, Math.max(-1, Math.abs(worldDir.y) / len))));
    }

    @Override
    public boolean canFluidReachPipe(Level level, BlockPos tankPos, BlockPos pipePos, double fillFraction) {
        return true;
    }

    private static float angleFromPose(Pose3dc pose) {
        if (pose == null) return 0;
        Vector3d localUp = pose.transformNormalInverse(new Vector3d(0, 1, 0), new Vector3d());
        double len = Math.sqrt(localUp.x * localUp.x + localUp.y * localUp.y + localUp.z * localUp.z);
        if (len < NORMALIZE_EPSILON) return 0;
        localUp.div(len);
        return (float) Math.toDegrees(Math.acos(Math.min(1, Math.max(-1, localUp.y))));
    }
}
