package de.devin.pipesnphysics.physics;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * An edge in the contracted fluid network. Represents a branch — a maximal
 * run of pipe cells between two nodes. Mutable: fluid state changes each tick.
 *
 * The column model tracks ordered fluid fronts from side a toward side b.
 * When two incompatible fronts meet mid-pipe, a collision is emitted.
 */
public class SimEdge {

    private final int id;
    private final NodeId a;
    private final NodeId b;
    private final int length;
    private final int capacity;
    private final float resistance;
    private final List<BlockPos> pipePositions;
    private final List<FluidFront> column;

    private EdgePhase phase = EdgePhase.EMPTY;
    private float frontPos = 0;
    private NodeId upstreamNode = null;

    public SimEdge(int id, NodeId a, NodeId b, int length, int capacity,
                   float resistance, List<BlockPos> pipePositions) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.length = length;
        this.capacity = capacity;
        this.resistance = resistance;
        this.pipePositions = List.copyOf(pipePositions);
        this.column = new ArrayList<>();
    }

    public int id() { return id; }
    public NodeId a() { return a; }

    public EdgePhase phase() { return phase; }
    public void setPhase(EdgePhase phase) { this.phase = phase; }

    public float frontPos() { return frontPos; }
    public void setFrontPos(float frontPos) { this.frontPos = frontPos; }

    public NodeId upstreamNode() { return upstreamNode; }
    public void setUpstreamNode(NodeId upstream) { this.upstreamNode = upstream; }



    public NodeId downstreamNode() {
        if (upstreamNode == null) return null;
        return upstreamNode.equals(a) ? b : a;
    }
    public NodeId b() { return b; }
    public int length() { return length; }
    public int capacity() { return capacity; }
    public float resistance() { return resistance; }
    public List<BlockPos> pipePositions() { return pipePositions; }
    public List<FluidFront> column() { return column; }

    public int totalFill() {
        int total = 0;
        for (FluidFront front : column) total += front.amount();
        return total;
    }

    public boolean isEmpty() {
        return column.isEmpty();
    }

    public String fluidAtA() {
        return column.isEmpty() ? null : column.get(0).fluidId();
    }

    public String fluidAtB() {
        return column.isEmpty() ? null : column.get(column.size() - 1).fluidId();
    }

    /**
     * Get the primary fluid in this edge (the one with the most volume).
     * Returns null if empty.
     */
    public String primaryFluid() {
        if (column.isEmpty()) return null;
        String best = null;
        int bestAmount = 0;
        for (FluidFront front : column) {
            if (front.amount() > bestAmount) {
                bestAmount = front.amount();
                best = front.fluidId();
            }
        }
        return best;
    }

    /**
     * Push fluid onto the a-side of the column.
     */
    public void pushFromA(String fluidId, int amount) {
        if (amount <= 0) return;
        if (!column.isEmpty() && column.get(0).fluidId().equals(fluidId)) {
            column.set(0, column.get(0).withAmount(column.get(0).amount() + amount));
        } else {
            column.add(0, new FluidFront(fluidId, amount));
        }
    }

    /**
     * Push fluid onto the b-side of the column.
     */
    public void pushFromB(String fluidId, int amount) {
        if (amount <= 0) return;
        if (!column.isEmpty() && column.get(column.size() - 1).fluidId().equals(fluidId)) {
            int last = column.size() - 1;
            column.set(last, column.get(last).withAmount(column.get(last).amount() + amount));
        } else {
            column.add(new FluidFront(fluidId, amount));
        }
    }

    /**
     * Drain fluid from the a-side of the column.
     * Returns the amount actually drained.
     */
    public int drainFromA(int amount) {
        return drainFromSide(0, amount);
    }

    /**
     * Drain fluid from the b-side of the column.
     * Returns the amount actually drained.
     */
    public int drainFromB(int amount) {
        return drainFromSide(column.size() - 1, amount);
    }

    private int drainFromSide(int index, int amount) {
        if (column.isEmpty() || index < 0 || index >= column.size()) return 0;
        int drained = 0;
        while (amount > 0 && !column.isEmpty()) {
            int idx = Math.min(index, column.size() - 1);
            FluidFront front = column.get(idx);
            int take = Math.min(amount, front.amount());
            if (take >= front.amount()) {
                column.remove(idx);
            } else {
                column.set(idx, front.withAmount(front.amount() - take));
            }
            drained += take;
            amount -= take;
        }
        return drained;
    }

    /**
     * Map a fill fraction (0..1 along the edge) to a world BlockPos.
     */
    public BlockPos positionAt(float fraction) {
        if (pipePositions.isEmpty()) return BlockPos.ZERO;
        int index = Math.clamp((int) (fraction * pipePositions.size()), 0, pipePositions.size() - 1);
        return pipePositions.get(index);
    }
}
