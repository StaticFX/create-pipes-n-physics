package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;

/**
 * What one tick of flow execution actually did. The per-edge amounts are written straight into
 * the {@code Solution.actualFlow} array (the same instance the graph cache serves to goggle and
 * overlay probes), so "what the player is shown" and "what really moved" are one value. The
 * per-edge {@link Solution.SettleNote}s ride the same way: what MOVED and what LOOKED are both
 * facts about the executed tick, and a zero is only readable next to the path that produced it.
 */
public final class FlowLedger {
    private final int[] edgeMovedMb;
    private final Solution.SettleNote[] settleNotes;
    private boolean movedAny;
    private boolean settling;

    public FlowLedger(int[] edgeMovedMb, Solution.SettleNote[] settleNotes) {
        this.edgeMovedMb = edgeMovedMb;
        this.settleNotes = settleNotes;
    }

    /**
     * Record a boundary movement on an edge. The array arrives zeroed (a fresh Solution per
     * solve); every boundary of a run moves the same plug one step, so per-boundary amounts are
     * parallel samples of ONE throughput — hence the max below, not a sum.
     */
    void moved(Edge edge, int amount) {
        if (amount <= 0) return;
        movedAny = true;
        if (amount > edgeMovedMb[edge.index()]) edgeMovedMb[edge.index()] = amount;
    }

    /** Idle contents are still moving toward rest — the network must stay awake. */
    void markSettling() {
        settling = true;
    }

    /**
     * Record WHICH settle path examined an edge. Exactly one path runs per edge per tick, so this
     * is written once; an entry left null means the executor never reached that edge at all (a
     * solution built for a dump and never executed reads null throughout).
     */
    void note(Edge edge, Solution.SettleNote note) {
        settleNotes[edge.index()] = note;
    }

    /**
     * Per edge, the strongest single boundary movement this tick in mB — a max, not a sum
     * (see {@link #moved}).
     */
    public int[] edgeMovedMb() { return edgeMovedMb; }

    /** Per edge, the settle path that examined it this tick; null where the executor never got to it. */
    public Solution.SettleNote[] settleNotes() { return settleNotes; }

    /** Whether any fluid moved at all this tick, brigade or settle. */
    public boolean movedAny() { return movedAny; }

    /** Whether idle contents are still moving toward rest — the network must stay awake. */
    public boolean settling() { return settling; }
}
