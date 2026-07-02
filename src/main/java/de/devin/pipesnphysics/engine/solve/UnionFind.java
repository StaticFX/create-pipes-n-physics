package de.devin.pipesnphysics.engine.solve;

/**
 * Path-halving disjoint-set over {@code n} integer nodes. Minecraft-free, so both the pure solver
 * (capacitance-free component pruning) and transfer planning (hydraulic islands) share one copy.
 */
public final class UnionFind {
    private final int[] parent;

    public UnionFind(int n) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
    }

    public int find(int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    public void union(int a, int b) {
        parent[find(a)] = find(b);
    }

    /** Component root per node, path-compressed — the id array transfer planning keys islands by. */
    public int[] roots() {
        int[] roots = new int[parent.length];
        for (int i = 0; i < parent.length; i++) roots[i] = find(i);
        return roots;
    }
}
