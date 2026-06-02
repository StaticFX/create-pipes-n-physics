package de.devin.pipesnphysics.compat;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import de.devin.pipesnphysics.handler.PipeGraphBuilder;
import de.devin.pipesnphysics.handler.PhysicsConfigFactory;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import de.devin.pipesnphysics.physics.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * Translates the physics engine's SimResult into Create's pipe rendering.
 * Sets Flow objects directly on PipeConnections — Create's tick is cancelled
 * for managed pipes (via GravityFlowMixin), so our Flow objects persist.
 * No addPressure/wipePressure interaction at all.
 */
public final class CreatePipeRendering {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Max progress per pipe — leaves a 1-pixel gap at the pipe end. */
    private static final float MAX_PIPE_PROGRESS = 15f / 16f;

    private CreatePipeRendering() {}

    // ---- Per-pipe progress helpers ----

    /**
     * Compute visual fill progress for a single pipe based on the fluid front
     * position. Pipes behind the front are fully filled, the pipe AT the front
     * is partially filled, and pipes ahead of the front are empty.
     *
     * @param frontPos   how far the front has advanced (0..edgeLength)
     * @param pipeIndex  this pipe's index in the edge (0-based, ordered A→B)
     * @param pipeCount  total number of pipes in the edge
     * @param frontFromA true if the front advances from node A toward node B
     * @return fill progress in the range 0..MAX_PIPE_PROGRESS
     */
    static float pipeProgressFromFront(float frontPos, int pipeIndex,
                                       int pipeCount, boolean frontFromA) {
        float dist = frontFromA ? pipeIndex : (pipeCount - 1 - pipeIndex);
        if (frontPos >= dist + 1) return MAX_PIPE_PROGRESS;
        if (frontPos > dist) return (frontPos - dist) * MAX_PIPE_PROGRESS;
        return 0;
    }

    /**
     * Source-side connection progress. Fills first: 0→1 covers face→center.
     */
    static float sourceSideProgress(float pipeProgress) {
        return Math.min(1.0f, pipeProgress * 2f);
    }

    /**
     * Exit-side connection progress. Fills second (after source reaches center).
     */
    static float exitSideProgress(float pipeProgress) {
        return Math.max(0f, (pipeProgress - 0.5f) * 2f);
    }

    // ---- Main visual application ----

    /**
     * Set Flow objects on pipes based on simulation state.
     * Create's tick is cancelled for these pipes, so flows persist until we clear them.
     */
    public static void applyVisuals(Level level, FluidNetwork network, SimResult result,
                                     FluidStack networkFluid) {
        for (int i = 0; i < network.edges().size(); i++) {
            SimEdge edge = network.edges().get(i);
            float rate = result.flowRates()[i];
            int pipeCount = edge.pipePositions().size();

            // Determine flow direction
            boolean hasFront = edge.phase().hasFront();
            boolean isDraining = edge.phase() == EdgePhase.DRAINING;
            boolean flowFromA;

            if (hasFront) {
                flowFromA = edge.upstreamNode() != null && edge.upstreamNode().equals(edge.a());
            } else {
                flowFromA = rate > 0;
            }

            // Use visualFrontPos for rendering — it only advances during CHARGING
            // (no cycling reset), so pipes stay filled once the front passes them.
            float frontPos = Math.clamp(edge.visualFrontPos(), 0f, edge.length());

            boolean frontFromA = edge.upstreamNode() != null
                    && edge.upstreamNode().equals(edge.a());
            if (isDraining) {
                // Flip within-pipe direction so fluid drains forward (out the exit)
                flowFromA = !flowFromA;
                // Flip pipe order so source-end pipes empty first
                frontFromA = !frontFromA;
            }

            for (int pi = 0; pi < pipeCount; pi++) {
                PipeEntry entry = edge.pipes().get(pi);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, entry.pos());
                if (pipe == null) continue;

                if (edge.isEmpty()
                        || (rate == 0 && !hasFront
                            && edge.phase() != EdgePhase.FLOWING)) {
                    clearPipeFlows(pipe);
                    continue;
                }

                // Compute per-pipe fill progress from the front position.
                // hasFront phases: front tracks which pipe the fluid is in.
                // FLOWING (fallback): all pipes fully filled.
                float pipeProgress;
                if (hasFront) {
                    pipeProgress = pipeProgressFromFront(frontPos, pi, pipeCount, frontFromA);
                } else {
                    pipeProgress = MAX_PIPE_PROGRESS;
                }

                if (pipeProgress <= 0) {
                    clearPipeFlows(pipe);
                    continue;
                }

                float srcProgress = sourceSideProgress(pipeProgress);
                float exitProgress = exitSideProgress(pipeProgress);

                Direction srcDir = flowFromA ? entry.from() : entry.to();
                Direction exitDir = flowFromA ? entry.to() : entry.from();

                PipeConnection srcConn = pipe.getConnection(srcDir);
                PipeConnection exitConn = pipe.getConnection(exitDir);

                if (level.getGameTime() % 40 == 0) {
                    LOGGER.info("[SRV] pipe={} pipeProgress={} src={} srcDir={} srcConn={} exit={} exitDir={} exitConn={}",
                            entry.pos(), pipeProgress, srcProgress, srcDir, srcConn != null,
                            exitProgress, exitDir, exitConn != null);
                }

                if (!networkFluid.isEmpty()) {
                    boolean changed = false;
                    if (srcConn != null) {
                        if (srcProgress > 0) {
                            changed |= setFlow(srcConn, true, networkFluid, srcProgress);
                        } else {
                            changed |= clearFlow(srcConn);
                        }
                    }
                    if (exitConn != null) {
                        if (exitProgress > 0) {
                            changed |= setFlow(exitConn, false, networkFluid, exitProgress);
                        } else {
                            changed |= clearFlow(exitConn);
                        }
                    }
                    if (changed) pipe.blockEntity.notifyUpdate();
                }
            }
        }
    }

    // ---- Flow object management ----

    /** Set or update Flow on a connection. Returns true if state changed. */
    private static boolean setFlow(PipeConnection conn, boolean inbound,
                                    FluidStack fluid, float progress) {
        if (!(conn instanceof PipeConnectionAccessor accessor)) return false;
        Optional<PipeConnection.Flow> current = accessor.pipesnphysics$getFlow();
        if (current.isEmpty()) {
            PipeConnection.Flow flow = conn.new Flow(inbound, fluid.copy());
            // startWithValue sets BOTH previous and current — no interpolation
            // artifacts on the first frame, and correct NBT serialization.
            flow.progress.startWithValue(progress);
            flow.complete = progress >= MAX_PIPE_PROGRESS;
            accessor.pipesnphysics$setFlow(Optional.of(flow));
            return true;
        } else {
            PipeConnection.Flow flow = current.get();
            boolean changed = flow.inbound != inbound;
            flow.inbound = inbound;
            float old = (float) flow.progress.getValue();
            flow.progress.startWithValue(progress);
            flow.complete = progress >= MAX_PIPE_PROGRESS;
            if (Math.abs(old - progress) > 0.001f) {
                changed = true;
            }
            return changed;
        }
    }

    /** Clear Flow from a connection. Returns true if something was cleared. */
    private static boolean clearFlow(PipeConnection conn) {
        if (conn instanceof PipeConnectionAccessor accessor) {
            if (accessor.pipesnphysics$getFlow().isPresent()) {
                accessor.pipesnphysics$setFlow(Optional.empty());
                return true;
            }
        }
        return false;
    }

    /** Clear all flows from a pipe, syncing to client if anything changed. */
    private static void clearPipeFlows(FluidTransportBehaviour pipe) {
        boolean changed = false;
        for (Direction dir : Direction.values()) {
            PipeConnection conn = pipe.getConnection(dir);
            if (conn != null) changed |= clearFlow(conn);
        }
        if (changed) pipe.blockEntity.notifyUpdate();
    }

    /**
     * Store diagnostic pressure breakdowns on pipe block entities for goggle display.
     * Includes phase, front progress, ΔΦ, and head remaining for phase-aware tooltips.
     */
    public static void storeBreakdowns(Level level, FluidNetwork network, SimResult result) {
        float totalPumpHead = 0;
        for (SimNode n : network.nodes().values()) {
            if (n.kind() == SimNodeKind.PUMP) totalPumpHead += n.staticPressure();
        }

        SimConfig config = PhysicsConfigFactory.simConfig();

        for (int i = 0; i < network.edges().size(); i++) {
            SimEdge edge = network.edges().get(i);
            float rate = Math.abs(result.flowRates()[i]);

            SimNode nodeA = network.node(edge.a());
            SimNode nodeB = network.node(edge.b());
            float gravityHead = 0;
            if (nodeA != null && nodeB != null) {
                float deltaY = (float) Math.abs(nodeA.elevation() - nodeB.elevation());
                gravityHead = config.G() * deltaY;
            }

            // Compute ΔΦ from potentials (stored per node in SimResult)
            float deltaPhi = 0;
            if (result.potentials() != null) {
                float phiA = result.potentials().getOrDefault(edge.a(), 0f);
                float phiB = result.potentials().getOrDefault(edge.b(), 0f);
                deltaPhi = phiA - phiB;
            }

            // Head at upstream (entry) and downstream (exit) nodes
            NodeId upstream = edge.upstreamNode();
            NodeId downstream = edge.downstreamNode();
            float headAtUpstream = upstream != null ? network.headAt(upstream) : 0;
            float headRemaining = downstream != null ? network.headAt(downstream) : 0;

            // Front progress as fraction 0..1
            float frontProgress = edge.length() > 0
                    ? Math.clamp(edge.frontPos() / edge.length(), 0f, 1f)
                    : 0;

            // Derive display phase: the sim keeps edges in CHARGING even after
            // the front has traversed the full length and flow is active (primed).
            // CHARGING + non-zero flow rate = effectively FLOWING for display.
            EdgePhase displayPhase = edge.phase();
            if (displayPhase == EdgePhase.CHARGING && rate > 0) {
                displayPhase = EdgePhase.FLOWING;
            }

            PressureBreakdown breakdown = new PressureBreakdown(
                    gravityHead,
                    totalPumpHead,
                    0, 0,
                    edge.resistance(),
                    rate,
                    rate > config.maxFlow() * 0.95f,
                    false,
                    displayPhase,
                    frontProgress,
                    deltaPhi,
                    headRemaining,
                    edge.length(),
                    headAtUpstream);

            for (BlockPos pos : edge.pipePositions()) {
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe instanceof PipeFlowData data) {
                    data.pipesnphysics$setBreakdown(breakdown);
                    pipe.blockEntity.notifyUpdate();
                }
            }
        }
    }
}
