package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.engine.flow.BrigadePass;
import de.devin.pipesnphysics.engine.flow.FlowLedger;
import de.devin.pipesnphysics.engine.flow.FlowNetwork;
import de.devin.pipesnphysics.engine.flow.SettlePass;
import de.devin.pipesnphysics.engine.graph.Graph;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * Executes one solved tick as CONSERVED plug flow through the pipes' own stored volume
 * ({@link PipeFluidCell}) — the replacement for the old endpoint-to-endpoint transfers.
 *
 * The tick runs four phases (the objects are in {@code engine.flow}): first a {@link FlowNetwork}
 * resolves the network fresh — every pipe cell as a store, every endpoint as a {@code Reservoir}
 * carrying the per-tick give/take budgets and lip caps. Then one {@link BrigadePass} per solved
 * fluid pass — every flowing edge becomes a {@code FlowingRun} that advances its contents
 * downstream by at most the solved rate, consumers-first so a chain moves one step everywhere in
 * the same tick; sources drain into the pipes, sinks fill only from fluid that actually exits
 * them. The plug gates require the run's {@linkplain FlowNetwork#flowDepthMb flow depth}, not a
 * full cell, so a trickle streams shallow while pressure or back-up still packs full-bore. Then
 * one {@link SettlePass} over everything that did not flow — each idle edge is a
 * {@code SettlingRun} moving toward its hydrostatic resting profile (humps recede, submerged
 * cells fill, broken siphons hold barometric legs, held pump lines pack fill-only, headless runs
 * gravity-pool). Finally the flush: one sync per changed cell.
 *
 * Every move is integer mB and strictly paired (cell↔cell, or handler↔cell via
 * SIMULATE-then-EXECUTE), so fluid is neither minted nor lost — EXCEPT where two different fluids
 * are driven together in one pipe cell, which reacts like Create ({@code FluidNetwork} never mixes):
 * the pipe breaks and its contents are consumed, water+lava leaving cobblestone
 * ({@link FlowNetwork#reactToCollisions}). With {@code PIPE_VOLUME_PER_CELL = 0} every run
 * degenerates to a wire and this reproduces the old instant transfers.
 */
public final class PipeFlowExecutor {
    /**
     * What one apply actually did: the strongest per-edge boundary movement (the goggle/overlay
     * "actual mB/t", written into {@code Solution.actualFlow} in place), whether anything moved
     * at all, and {@code settling} — idle contents still moving toward rest, so the network must
     * stay awake even without solved flow.
     */
    public record Actuals(int[] edgeMovedMb, boolean movedAny, boolean settling) {}

    private PipeFlowExecutor() {}

    public static Actuals run(Level level, Graph graph, Solution solution) {
        FlowNetwork network = new FlowNetwork(level, graph);
        FlowLedger ledger = new FlowLedger(solution.actualFlow(), solution.settleNotes());

        Set<Integer> flowed = new HashSet<>();
        for (Solution.FlowPass pass : solution.passes()) {
            BrigadePass brigade = new BrigadePass(network, ledger, pass);
            flowed.addAll(brigade.flowingEdges());
            brigade.execute();
        }
        new SettlePass(network, ledger, solution).execute(flowed);
        // A pump owns no cell, so a delivery straight across a zero-cell edge would leave the
        // client no stamp to draw a pour from; give the pump one for what it actually moved.
        network.stampPumps(ledger);
        network.flush();
        // Any cell where two fluids were driven together this tick reacts LAST — Create's
        // crossing-the-streams (pipe breaks, water+lava leaves cobblestone). Deferred past the
        // flush so no pipe is broken while the passes are still reading its stored volume.
        network.reactToCollisions();

        return new Actuals(ledger.edgeMovedMb(), ledger.movedAny(), ledger.settling());
    }
}
