package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.compat.SubLevelFrame;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.List;

/**
 * Puts world-space overlays on Sable contraptions.
 *
 * A contraption's blocks live at PLOT coordinates ~30M blocks outside the world and Sable draws
 * them through the contraption's pose, so an overlay that only offsets by the camera — what every
 * renderer here used to do — lands where the plot is, not where the ship is, and is simply never
 * seen. Anything drawing at block coordinates goes through {@link #cameraRelative} instead: a
 * plain camera offset on the main level, the contraption's own frame on a sub-level.
 *
 * The one rule a caller must follow is that it emits geometry RELATIVE to the origin it passes in,
 * never raw plot coordinates — see {@link #cameraRelative} for why a float matrix cannot be handed
 * that job.
 */
public final class SubLevelDraw {
    private SubLevelDraw() {}

    /** The frame a block position is drawn through this render frame, or null when it is not on one. */
    public static SubLevelFrame frameAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return SableCompat.clientFrame(mc.level, pos, AnimationTickHolder.getPartialTicks());
    }

    /** The frame of every contraption within {@code radius} of a world point. */
    public static List<SubLevelFrame> framesNear(Vec3 center, double radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return List.of();
        return SableCompat.clientFramesNear(mc.level, center, radius,
                AnimationTickHolder.getPartialTicks());
    }

    /**
     * The camera-relative pose for geometry emitted RELATIVE to {@code origin} — a block position
     * on the run being drawn. Callers push and pop their own pose.
     *
     * The relative part is not a convenience, it is the whole reason this works: a plot sits about
     * 30 MILLION blocks out, a `PoseStack` matrix is FLOAT, and one ulp out there is over three
     * blocks. Translating the pose by −plotOrigin and then feeding it +plotPosition vertices — the
     * obvious "just emit the block positions you already have" shape — subtracts two ~3·10⁷ floats
     * and keeps the rounding error: every cell collapses into junk around the pose origin, which
     * for a player standing on the contraption is a cloud of geometry floating beside them. So the
     * big subtraction is done HERE, in double, and only small numbers ever reach the matrix.
     */
    public static void cameraRelative(PoseStack ps, Vec3 camera, SubLevelFrame frame, BlockPos origin) {
        if (frame == null) {
            ps.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
            return;
        }
        Vec3 at = frame.project(origin.getX(), origin.getY(), origin.getZ());
        Vector3d scale = frame.scale();
        ps.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ps.mulPose(new Quaternionf(frame.orientation()));
        ps.scale((float) scale.x, (float) scale.y, (float) scale.z);
    }

    /** Where a raw block position ends up on screen — for billboarded labels and distance gates. */
    public static Vec3 project(SubLevelFrame frame, double x, double y, double z) {
        return frame == null ? new Vec3(x, y, z) : frame.project(x, y, z);
    }
}
