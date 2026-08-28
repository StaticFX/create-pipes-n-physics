package de.devin.pipesnphysics.compat;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

class SableCompanionImpl implements SableCompatProvider {
    private static final double NORMALIZE_EPSILON = 0.001;

    @Override
    public <T> T atOverlappingContraptions(Level level, BlockPos origin, BiFunction<Level, BlockPos, T> reader) {
        // Sable's own traversal: project `origin` out to world space, then for every OTHER contraption
        // whose bounds contain that point, inverse-project to its plot pos and hand the block to `reader`;
        // it short-circuits on the first non-null result. `origin`'s own sub-level (subA, null on the main
        // level) is passed so the traversal skips it. We reject the host-world hit (subB == null, already
        // read by the caller) so only genuine other-contraption blocks reach `reader`.
        SubLevelAccess subA = SableCompanion.INSTANCE.getContaining(level, origin);
        Vec3 center = new Vec3(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5);
        return SableCompanion.INSTANCE.runIncludingSubLevels(level, center, false, subA,
                (SubLevelAccess subB, BlockPos plotPosB) -> subB == null ? null : reader.apply(level, plotPosB));
    }

    @Override
    public SubLevelFrame clientFrame(Level level, BlockPos pos, float partialTicks) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        return sub instanceof ClientSubLevelAccess client ? frameOf(client, partialTicks) : null;
    }

    @Override
    public List<SubLevelFrame> clientFramesNear(Level level, Vec3 center, double radius,
                                                float partialTicks) {
        BoundingBox3d bounds = new BoundingBox3d(
                center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        List<SubLevelFrame> frames = new ArrayList<>();
        for (SubLevelAccess sub : SableCompanion.INSTANCE.getAllIntersecting(level, bounds)) {
            if (!(sub instanceof ClientSubLevelAccess client)) continue;
            SubLevelFrame frame = frameOf(client, partialTicks);
            if (frame != null) frames.add(frame);
        }
        return frames;
    }

    /** Sable's render pose copied into our own plain-math frame; null for a pose not yet built. */
    private static SubLevelFrame frameOf(ClientSubLevelAccess sub, float partialTicks) {
        Pose3dc pose = sub.renderPose(partialTicks);
        if (pose == null) return null;
        BoundingBox3dc bounds = sub.boundingBox();
        double dx = bounds.maxX() - bounds.minX();
        double dy = bounds.maxY() - bounds.minY();
        double dz = bounds.maxZ() - bounds.minZ();
        return new SubLevelFrame(new Vector3d(pose.position()), new Quaterniond(pose.orientation()),
                new Vector3d(pose.scale()), new Vector3d(pose.rotationPoint()),
                0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    @Override
    public String getSubLevelId(Level level, BlockPos pos) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        return sub == null ? null : sub.getUniqueId().toString();
    }

    @Override
    public boolean isOnSubLevel(Level level, BlockPos pos) {
        return SableCompanion.INSTANCE.getContaining(level, pos) != null;
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
    public double getUpProjectionY(Level level, BlockPos pos) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        if (sub == null) return 1.0;
        Pose3dc pose = sub.logicalPose();
        if (pose == null) return 1.0;
        Vector3d up = pose.transformNormal(new Vector3d(0, 1, 0), new Vector3d());
        double len = Math.sqrt(up.x * up.x + up.y * up.y + up.z * up.z);
        if (len < NORMALIZE_EPSILON) return 1.0;
        return Math.clamp(up.y / len, 0.0, 1.0); // cos(tilt): a fluid column rises along local-up, not world-up
    }

    @Override
    public double getColumnBaseY(Level level, BlockPos pos, int width, int height) {
        return getColumnBaseYAtCenter(level, pos, width / 2.0, height / 2.0, width / 2.0, height);
    }

    @Override
    public double getColumnBaseYAtCenter(Level level, BlockPos pos, double halfX, double halfY, double halfZ,
                                         int verticalBlocks) {
        // Anchor at the box's projected geometric CENTER, not the bottom corner: on a tilt the
        // corner the controller sits at is not the lowest point, so baseY = getWorldY(controller)-0.5
        // skews the surface and spills a partial tank. The center projects exactly, and the surface
        // is then center + (fillFraction - 0.5)·height·cosTilt — i.e. baseY = center − 0.5·height·cosTilt.
        Vector3d center = SableCompanion.INSTANCE.projectOutOfSubLevel(level,
                new Vector3d(pos.getX() + halfX, pos.getY() + halfY, pos.getZ() + halfZ),
                new Vector3d());
        return center.y - 0.5 * verticalBlocks * getUpProjectionY(level, pos);
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
