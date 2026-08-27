package de.devin.pipesnphysics.engine.turbine;

import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Hands each turbine on a network the fluid that really fell through it this tick, once the
 * executor has moved it. The block entity turns that stream of samples into rotation.
 *
 * The figure is the ACTUAL movement on the turbine's outlet flank, not the solved hydraulic flow —
 * a run the source can't keep up with must not spin a turbine at full rating, for the same reason
 * the pump goggle stopped reading its own capability instead of its throughput.
 */
public final class TurbineDrive {
    private TurbineDrive() {}

    /** Sample every turbine on {@code graph}; call once per solve, after the transfers execute. */
    public static void drive(Level level, Graph graph, Solution solution) {
        for (Node pump : graph.pumps()) {
            if (!Turbines.isTurbine(level, pump.pos())) continue;
            Edge outlet = outletEdge(graph, pump);
            int moved = outlet == null ? 0 : PipeProbe.actualEdgeFlow(graph, solution, outlet);
            Turbines.drive(level, pump.pos(), moved);
        }
    }

    /** The edge this pump discharges into (its FACING flank), or null when it has none. */
    private static Edge outletEdge(Graph graph, Node pump) {
        BlockPos push = pump.pushCell();
        if (push == null) return null;
        for (Edge edge : graph.edgesOf(pump.index())) {
            if (push.equals(PipeGeometry.adjacentCell(graph, edge, pump.index()))) return edge;
        }
        return null;
    }
}
