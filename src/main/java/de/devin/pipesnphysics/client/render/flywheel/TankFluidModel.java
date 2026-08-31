package de.devin.pipesnphysics.client.render.flywheel;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.material.SimpleMaterialShaders;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.List;

/**
 * The whole geometry of a tilted tank fluid: one grid and one box, shared by every tank in the world.
 *
 * Neither carries a shape of its own. The grid is swung into the fluid plane, blown out past the tank's
 * corners and displaced by the wave field; the box is the tank's interior. The fragment shader then cuts
 * BOTH with the same two per-pixel tests: inside the box, under the water. The grid is trimmed by the
 * first and the box by the second, so the surface and the volume under it meet exactly by construction,
 * with no seam to reconcile and no wall skirts to mitre. That is why this is resolution-free where the
 * CPU renderer needs a grid: the waterline lands where the maths puts it, not at the nearest grid line.
 *
 * One model serves every fluid: the texture is the block atlas either way, and WHICH sprite rides
 * per-instance, so a world of tanks holding different fluids is still two draws.
 */
public final class TankFluidModel {
    /** Built per transparency, since a Material is fixed once made and this one is the player's choice. */
    private static final Model OPAQUE = model(Transparency.OPAQUE);
    private static final Model TRANSLUCENT = model(Transparency.TRANSLUCENT);

    private TankFluidModel() {}

    public static Model get(boolean opaque) {
        return opaque ? OPAQUE : TRANSLUCENT;
    }

    private static Model model(Transparency transparency) {
        return new SimpleModel(List.of(
                // Walls cull their back faces, so a tank shows the near side of its fluid column and not
                // the inside of the far one: the same single-sided, outward-wound volume the CPU renderer
                // builds out of skirts.
                new Model.ConfiguredMesh(material("tank_walls.vert", true, transparency), new UnitBox()),
                new Model.ConfiguredMesh(material("tank_surface.vert", false, transparency), new PlaneGrid())));
    }

    private static Material material(String vertexShader, boolean cullBackfaces, Transparency transparency) {
        return SimpleMaterial.builder()
                .shaders(new SimpleMaterialShaders(
                        TankFluidInstance.rl("material/" + vertexShader),
                        TankFluidInstance.rl("material/tank_fluid.frag")))
                .texture(InventoryMenu.BLOCK_ATLAS)
                // OPAQUE or TRANSLUCENT, never ORDER_INDEPENDENT, though Create's own fluid uses that and
                // it is in principle the right answer here. InstancedDrawManager sorts draws into two lists
                // by exactly this field, and ORDER_INDEPENDENT routes into a whole second pipeline (an OIT
                // framebuffer with multiple render targets, sampler2DArray coefficients, blue noise) that
                // draws NOTHING on GL 4.1 under a mod which warns it replaces Flywheel's framebuffers and
                // light storage wholesale. It cost a silent screen with no shader error to find.
                //
                // Between the two that are left it is only taste, so it is the player's: TRANSLUCENT shows
                // the far wall through the near one, OPAQUE reads solid like Create's own tank fluid. The
                // one thing to know is that a blended material still writes depth with nothing sorting its
                // quads, so a wavy surface can blend against itself at grazing angles.
                .transparency(transparency)
                // The fragment shader zeroes alpha outside the fluid, and EPSILON turns that into a discard.
                .cutout(CutoutShaders.EPSILON)
                .backfaceCulling(cullBackfaces)
                // A fluid's normal swings with the ship, which is exactly what cardinal lighting cannot
                // express; and the surface is seen from underneath as often as not.
                .cardinalLightingMode(CardinalLightingMode.OFF)
                .build();
    }

    /** Fills in the vertex attributes every mesh here shares. The shaders derive the rest. */
    private static void writeCommon(MutableVertexList vertexList, int i) {
        vertexList.r(i, 1);
        vertexList.g(i, 1);
        vertexList.b(i, 1);
        vertexList.a(i, 1);
        vertexList.u(i, 0);
        vertexList.v(i, 0);
        vertexList.light(i, 0);
        vertexList.overlay(i, OverlayTexture.NO_OVERLAY);
    }

    /**
     * A grid on XZ over [-1, 1], read by the surface shader as coordinate PAIRS in the fluid plane rather
     * than as positions: its extent here is arbitrary and only its corners' signs matter.
     *
     * It is subdivided for ONE reason: to carry the wave displacement, which is a per-vertex thing. The
     * silhouette is still cut per pixel, so unlike the CPU renderer's grid this resolution has nothing to
     * do with how cleanly the water meets the glass. It only sets how finely the water can ripple. And
     * it is one shared mesh for every tank in the world, uploaded once, where the CPU rebuilt its grid
     * per tank per frame.
     */
    private record PlaneGrid() implements QuadMesh {
        /**
         * Enough to carry the wave field's finest octave without aliasing it: that octave's wavelength is
         * about 0.55 blocks, and this puts four or five vertices across it on a tank of ordinary size. A
         * very large tank stretches the same grid further and will start to alias the top octave, which is
         * the smallest of the five and the least missed.
         */
        private static final int CELLS = 32;

        @Override
        public int vertexCount() {
            return CELLS * CELLS * 4;
        }

        @Override
        public void write(MutableVertexList vertexList) {
            int vertex = 0;
            for (int gx = 0; gx < CELLS; gx++) {
                for (int gz = 0; gz < CELLS; gz++) {
                    float x0 = gx * 2f / CELLS - 1f, x1 = (gx + 1) * 2f / CELLS - 1f;
                    float z0 = gz * 2f / CELLS - 1f, z1 = (gz + 1) * 2f / CELLS - 1f;
                    float[] xs = {x0, x0, x1, x1};
                    float[] zs = {z0, z1, z1, z0};
                    for (int corner = 0; corner < 4; corner++) {
                        vertexList.x(vertex, xs[corner]);
                        vertexList.y(vertex, 0);
                        vertexList.z(vertex, zs[corner]);
                        vertexList.normalX(vertex, 0);
                        vertexList.normalY(vertex, 1);
                        vertexList.normalZ(vertex, 0);
                        writeCommon(vertexList, vertex);
                        vertex++;
                    }
                }
            }
        }

        @Override
        public Vector4fc boundingSphere() {
            return new Vector4f(0, 0, 0, 1);
        }
    }

    /**
     * A unit box over [-1, 1] with outward normals, scaled to the tank by the wall shader. Every face is
     * wound counter-clockwise seen from OUTSIDE, which is what lets backface culling keep the near wall
     * of the fluid column and drop the far one.
     */
    private record UnitBox() implements QuadMesh {
        /** Per face: outward normal, then the two in-plane axes u and v, chosen so u cross v is that normal. */
        private static final float[][][] FACES = {
                {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
                {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
                {{0, 1, 0}, {0, 0, 1}, {1, 0, 0}},
                {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}},
                {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}},
                {{0, 0, -1}, {0, 1, 0}, {1, 0, 0}}
        };

        @Override
        public int vertexCount() {
            return 6 * 4;
        }

        @Override
        public void write(MutableVertexList vertexList) {
            // Corner signs in (u, v), counter-clockwise starting bottom-left as seen from outside.
            int[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            int vertex = 0;
            for (float[][] face : FACES) {
                float[] n = face[0], u = face[1], v = face[2];
                for (int[] sign : signs) {
                    for (int axis = 0; axis < 3; axis++) {
                        float p = n[axis] + sign[0] * u[axis] + sign[1] * v[axis];
                        if (axis == 0) vertexList.x(vertex, p);
                        else if (axis == 1) vertexList.y(vertex, p);
                        else vertexList.z(vertex, p);
                    }
                    vertexList.normalX(vertex, n[0]);
                    vertexList.normalY(vertex, n[1]);
                    vertexList.normalZ(vertex, n[2]);
                    writeCommon(vertexList, vertex);
                    vertex++;
                }
            }
        }

        @Override
        public Vector4fc boundingSphere() {
            return new Vector4f(0, 0, 0, (float) Math.sqrt(3));
        }
    }
}
