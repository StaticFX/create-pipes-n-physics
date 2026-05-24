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

import java.util.Optional;

/**
 * Translates the physics engine's SimResult into Create's pipe rendering model.
 * This is the only place that touches Create's FluidTransportBehaviour visual API
 * (addPressure, wipePressure). The physics engine itself never references these.
 */
public final class CreatePipeRendering {

    /**
     * Flag to prevent recursion: wipePressure → GravityFlowMixin.onWipePressure
     * → scheduleCheck → applyVisuals → wipePressure.
     */
    public static volatile boolean suppressWipeReschedule = false;

    /** Max progress per pipe — leaves a 1-pixel gap at the pipe end (15/16 of the block). */
    private static final float MAX_PIPE_PROGRESS = 15f / 16f;

    private CreatePipeRendering() {}

    /**
     * Apply visual pressure to Create's pipe rendering based on sim flow rates.
     * During CHARGING, fluid smoothly advances through each pipe block using
     * sub-pipe fractional progress. FLOWING edges are fully filled.
     */
    public static void applyVisuals(Level level, FluidNetwork network, SimResult result,
                                     FluidStack networkFluid) {
        // Only use addPressure when all edges are FLOWING (circuit fully primed).
        // While any edge is still CHARGING, addPressure on FLOWING edges triggers
        // Create's wipe cycle which resets the CHARGING edges.
        boolean allFlowing = network.edges().stream()
                .allMatch(e -> e.phase() == EdgePhase.FLOWING);

        suppressWipeReschedule = true;
        try {
            for (int i = 0; i < network.edges().size(); i++) {
                SimEdge edge = network.edges().get(i);
                float rate = result.flowRates()[i];
                int pipeCount = edge.pipePositions().size();

                // Determine flow direction (which end is upstream).
                // During CHARGING rate=0, so derive from upstreamNode instead.
                boolean isCharging = edge.phase() == EdgePhase.CHARGING;
                boolean flowFromA;
                if (isCharging && edge.upstreamNode() != null) {
                    flowFromA = edge.upstreamNode().equals(edge.a());
                } else {
                    flowFromA = rate > 0;
                }

                // frontPos ranges 0..length (length == pipeCount).
                // Each unit of frontPos corresponds to one pipe block.
                float frontPos = Math.clamp(edge.frontPos(), 0f, edge.length());
                boolean frontFromA = edge.upstreamNode() != null
                        && edge.upstreamNode().equals(edge.a());

                for (int pi = 0; pi < pipeCount; pi++) {
                    BlockPos pos = edge.pipePositions().get(pi);
                    FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                    if (pipe == null) continue;

                    if (edge.isEmpty()
                            || (rate == 0 && edge.phase() != EdgePhase.FLOWING
                                         && edge.phase() != EdgePhase.CHARGING)) {
                        clearPipeVisuals(pipe);
                        continue;
                    }

                    // Compute fill progress for this pipe (0 = empty, MAX = full)
                    float pipeProgress;
                    if (!isCharging) {
                        // FLOWING/STALLED: all pipes fully filled
                        pipeProgress = MAX_PIPE_PROGRESS;
                    } else {
                        // CHARGING: smooth per-pipe progress based on frontPos
                        // "distance from upstream" for this pipe index
                        float dist = frontFromA ? pi : (pipeCount - 1 - pi);
                        if (frontPos >= dist + 1) {
                            // Fully behind the front
                            pipeProgress = MAX_PIPE_PROGRESS;
                        } else if (frontPos > dist) {
                            // Leading pipe — fractional fill
                            pipeProgress = (frontPos - dist) * MAX_PIPE_PROGRESS;
                        } else {
                            // Ahead of front — empty
                            pipeProgress = 0;
                        }
                    }

                    if (pipeProgress <= 0) {
                        clearPipeVisuals(pipe);
                        continue;
                    }

                    // Apply pressure and flow on connected faces
                    float pressure = Math.max(1, Math.abs(rate));
                    boolean needsPressure = !pipe.hasAnyPressure();

                    BlockPos prevPos = (pi > 0)
                            ? edge.pipePositions().get(pi - 1)
                            : PipeGraphBuilder.posOf(edge.a());
                    BlockPos nextPos = (pi < pipeCount - 1)
                            ? edge.pipePositions().get(pi + 1)
                            : PipeGraphBuilder.posOf(edge.b());

                    // Create renders each pipe as two halves: inbound (face→center)
                    // and outbound (center→face). To show smooth end-to-end fill,
                    // fill the inbound half first, then the outbound half.
                    float inboundProgress = Math.min(1.0f, pipeProgress * 2f);
                    float outboundProgress = Math.max(0f, (pipeProgress - 0.5f) * 2f);

                    for (Direction dir : Direction.values()) {
                        PipeConnection conn = pipe.getConnection(dir);
                        if (conn == null) continue;
                        BlockPos neighbor = pos.relative(dir);

                        boolean isUpstreamFace;
                        if (neighbor.equals(prevPos)) {
                            isUpstreamFace = flowFromA;
                        } else if (neighbor.equals(nextPos)) {
                            isUpstreamFace = !flowFromA;
                        } else {
                            continue;
                        }

                        // Only addPressure when the entire network is FLOWING.
                        // Any addPressure while edges are still CHARGING triggers
                        // Create's wipe cycle that resets charging progress.
                        if (needsPressure && isUpstreamFace && allFlowing) {
                            pipe.addPressure(dir, true, pressure);
                        }

                        if (!networkFluid.isEmpty()) {
                            // Upstream face: inbound flow (renders face→center)
                            // Downstream face: outbound flow (renders center→face)
                            float faceProgress = isUpstreamFace ? inboundProgress : outboundProgress;
                            if (faceProgress > 0) {
                                setFlowOnConnection(conn, isUpstreamFace, networkFluid, faceProgress);
                            }
                        }
                    }
                }
            }
        } finally {
            suppressWipeReschedule = false;
        }
    }

    /**
     * Clear pressure from a pipe so it renders as empty.
     */
    private static void clearPipeVisuals(FluidTransportBehaviour pipe) {
        if (pipe.hasAnyPressure()) pipe.wipePressure();
    }

    /**
     * Set or update the Flow object on a pipe connection.
     * Progress controls how far the fluid extends into this pipe (0..MAX_PIPE_PROGRESS).
     * Always corrects the inbound direction (addPressure may have set it wrong).
     */
    private static void setFlowOnConnection(PipeConnection conn, boolean inbound,
                                             FluidStack fluid, float progress) {
        if (!(conn instanceof PipeConnectionAccessor accessor)) return;
        Optional<PipeConnection.Flow> current = accessor.pipesnphysics$getFlow();
        if (current.isEmpty()) {
            PipeConnection.Flow flow = conn.new Flow(inbound, fluid.copy());
            flow.progress.setValue(progress);
            flow.complete = progress >= MAX_PIPE_PROGRESS;
            accessor.pipesnphysics$setFlow(Optional.of(flow));
        } else {
            PipeConnection.Flow flow = current.get();
            // Always correct direction (addPressure uses uniform inbound, we fix it here)
            flow.inbound = inbound;
            if (Math.abs(flow.progress.getValue() - progress) > 0.01f) {
                flow.progress.setValue(progress);
                flow.complete = progress >= MAX_PIPE_PROGRESS;
            }
        }
    }

    /**
     * Store diagnostic pressure breakdowns on pipe block entities for goggle display.
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

            PressureBreakdown breakdown = new PressureBreakdown(
                    gravityHead,
                    totalPumpHead,
                    0, 0,
                    edge.resistance(),
                    rate,
                    rate > config.maxFlow() * 0.95f,
                    false);

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
