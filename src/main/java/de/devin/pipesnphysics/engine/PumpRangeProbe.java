package de.devin.pipesnphysics.engine;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.CreateFluidCompat;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.net.PumpRangePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes how far a pump can reach for the in-world range indicator.
 *
 * Reach in this engine is governed by elevation, not distance: a pump can push
 * fluid up to {@code supplyHead + pumpHead} blocks high (with the suction limit
 * as a soft allowance for cresting over), and pull from no deeper than the
 * suction limit below its supply level. Horizontal runs only slow flow down.
 *
 * The walk starts at the pump, branches through junctions, and stops at other
 * pumps and at endpoints. Each visited cell is flagged reachable or starved so
 * the client can paint the boundary; the walk ends entirely once a cell exceeds
 * even the suction allowance (no liquid column can exist beyond it).
 */
public final class PumpRangeProbe {
    private static final double REACH_TOLERANCE = 0.25;
    private static final int MAX_CELLS = 512;

    private PumpRangeProbe() {}

    public static PumpRangePayload probe(ServerLevel level, BlockPos pumpPos) {
        Graph graph = GraphBuilder.build(level, pumpPos);
        Node pump = graph.nodeAt(pumpPos);
        if (pump == null || !pump.isPump() || pump.pumpFacing() == null) {
            return new PumpRangePayload(pumpPos, List.of());
        }

        float speed = level.getBlockEntity(pumpPos) instanceof KineticBlockEntity kinetic
                ? kinetic.getSpeed() : 0;
        if (Math.abs(speed) < 0.01f) return new PumpRangePayload(pumpPos, List.of());
        double mult = CreateFluidCompat.isCentrifugalPump(level, pumpPos)
                ? CreateFluidCompat.PERFORMANCE_MULTIPLIER : 1.0;
        double pumpHead = Math.abs(speed) * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get() * mult;
        double suction = PipesNPhysicsConfig.SUCTION_LIMIT.get();

        Solution solution = FlowSolver.solve(level, graph);
        Double known = solution.nodeHeads().get(pump.index());
        double supplyHead = known != null ? known : pump.worldY();

        Walker walker = new Walker(level, graph,
                supplyHead + pumpHead, supplyHead - suction, suction);
        for (Edge edge : graph.edgesOf(pump.index())) {
            BlockPos toward = adjacentCell(graph, edge, pump.index());
            boolean push = toward.equals(pumpPos.relative(pump.pumpFacing()));
            boolean pull = toward.equals(pumpPos.relative(pump.effectivePullSide()));
            if (!push && !pull) continue;

            List<BlockPos> seed = new ArrayList<>();
            List<Boolean> seedFlags = new ArrayList<>();
            seed.add(pumpPos);
            seedFlags.add(true);
            walker.walk(edge, pump.index(), seed, seedFlags, pull);
        }
        return new PumpRangePayload(pumpPos, walker.paths);
    }

    private static final class Walker {
        final ServerLevel level;
        final Graph graph;
        final double pushCeiling;
        final double pullFloor;
        final double suction;
        final Set<Integer> visited = new HashSet<>();
        final List<PumpRangePayload.RangePath> paths = new ArrayList<>();
        int cells;

        Walker(ServerLevel level, Graph graph, double pushCeiling, double pullFloor, double suction) {
            this.level = level;
            this.graph = graph;
            this.pushCeiling = pushCeiling;
            this.pullFloor = pullFloor;
            this.suction = suction;
        }

        void walk(Edge edge, int fromNode, List<BlockPos> path, List<Boolean> flags, boolean pull) {
            List<BlockPos> ordered = fromNode == edge.a()
                    ? edge.pipes()
                    : edge.pipes().reversed();
            for (BlockPos cell : ordered) {
                if (cells++ > MAX_CELLS) {
                    emit(path, flags, pull);
                    return;
                }
                double y = SableCompat.getWorldY(level, cell);
                path.add(cell);
                flags.add(reachable(y, pull));
                if (beyondAllowance(y, pull)) {
                    emit(path, flags, pull);
                    return;
                }
            }

            int farIndex = edge.other(fromNode);
            Node far = graph.node(farIndex);
            double farY = far.worldY();
            path.add(far.pos());
            flags.add(reachable(farY, pull));

            if (far.isJunction() && !beyondAllowance(farY, pull) && visited.add(farIndex)) {
                boolean branched = false;
                for (Edge next : graph.edgesOf(farIndex)) {
                    if (next.index() == edge.index()) continue;
                    walk(next, farIndex, new ArrayList<>(path), new ArrayList<>(flags), pull);
                    branched = true;
                }
                if (branched) return;
            }
            emit(path, flags, pull);
        }

        boolean reachable(double y, boolean pull) {
            return pull ? y >= pullFloor - REACH_TOLERANCE : y <= pushCeiling + REACH_TOLERANCE;
        }

        boolean beyondAllowance(double y, boolean pull) {
            return pull ? y < pullFloor - REACH_TOLERANCE
                    : y > pushCeiling + suction + REACH_TOLERANCE;
        }

        void emit(List<BlockPos> path, List<Boolean> flags, boolean pull) {
            if (path.size() < 2) return;
            List<Long> packed = new ArrayList<>(path.size());
            for (BlockPos pos : path) packed.add(pos.asLong());
            paths.add(new PumpRangePayload.RangePath(packed, List.copyOf(flags), pull));
        }
    }

    private static BlockPos adjacentCell(Graph graph, Edge edge, int nodeIndex) {
        if (edge.pipes().isEmpty()) return graph.node(edge.other(nodeIndex)).pos();
        return nodeIndex == edge.a()
                ? edge.pipes().get(0)
                : edge.pipes().get(edge.pipes().size() - 1);
    }
}
