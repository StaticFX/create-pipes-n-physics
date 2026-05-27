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
 * Translates the physics engine's SimResult into Create's pipe rendering.
 * Sets Flow objects directly on PipeConnections — Create's tick is cancelled
 * for managed pipes (via GravityFlowMixin), so our Flow objects persist.
 * No addPressure/wipePressure interaction at all.
 */
public final class CreatePipeRendering {

    /** Max progress per pipe — leaves a 1-pixel gap at the pipe end. */
    private static final float MAX_PIPE_PROGRESS = 15f / 16f;

    private CreatePipeRendering() {}

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
            boolean isCharging = edge.phase() == EdgePhase.CHARGING;
            boolean isDraining = edge.phase() == EdgePhase.DRAINING;
            boolean flowFromA;

            if (isCharging || isDraining) {
                flowFromA = edge.upstreamNode().equals(edge.a());
            } else {
                flowFromA = rate > 0;
            }

            float frontPos = Math.clamp(edge.frontPos(), 0f, edge.length());

            boolean frontFromA = edge.upstreamNode() != null
                    && edge.upstreamNode().equals(edge.a());
            // During DRAINING, fluid recedes from the source end first (no more
            // fluid coming in), leaving the sink end last. Flip the front direction.
            if (isDraining) frontFromA = !frontFromA;

            for (int pi = 0; pi < pipeCount; pi++) {
                PipeEntry entry = edge.pipes().get(pi);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, entry.pos());
                if (pipe == null) continue;

                if (edge.isEmpty()
                        || (rate == 0 && !isCharging && !isDraining
                            && edge.phase() != EdgePhase.FLOWING)) {
                    clearPipeFlows(pipe);
                    continue;
                }

                // Compute fill progress for this pipe
                float pipeProgress;
                if (!isCharging && !isDraining) {
                    pipeProgress = MAX_PIPE_PROGRESS;
                } else {
                    float dist = frontFromA ? pi : (pipeCount - 1 - pi);
                    if (frontPos >= dist + 1) {
                        pipeProgress = MAX_PIPE_PROGRESS;
                    } else if (frontPos > dist) {
                        pipeProgress = (frontPos - dist) * MAX_PIPE_PROGRESS;
                    } else {
                        pipeProgress = 0;
                    }
                }

                if (pipeProgress <= 0) {
                    clearPipeFlows(pipe);
                    continue;
                }

                float inboundProgress = Math.min(1.0f, pipeProgress * 2f);
                float outboundProgress = Math.max(0f, (pipeProgress - 0.5f) * 2f);

                // entry.from() = face toward A, entry.to() = face toward B
                // When flowFromA: from-face is inbound (upstream), to-face is outbound
                // When !flowFromA: to-face is inbound, from-face is outbound
                Direction inboundDir = flowFromA ? entry.from() : entry.to();
                Direction outboundDir = flowFromA ? entry.to() : entry.from();

                PipeConnection inConn = pipe.getConnection(inboundDir);
                PipeConnection outConn = pipe.getConnection(outboundDir);

                if (!networkFluid.isEmpty()) {
                    boolean changed = false;
                    if (inConn != null && inboundProgress > 0) {
                        changed |= setFlow(inConn, true, networkFluid, inboundProgress);
                    }
                    if (outConn != null && outboundProgress > 0) {
                        changed |= setFlow(outConn, false, networkFluid, outboundProgress);
                    }
                    if (changed) pipe.blockEntity.notifyUpdate();
                }
            }
        }
    }

    /** Set or update Flow on a connection. Returns true if state changed. */
    private static boolean setFlow(PipeConnection conn, boolean inbound,
                                    FluidStack fluid, float progress) {
        if (!(conn instanceof PipeConnectionAccessor accessor)) return false;
        Optional<PipeConnection.Flow> current = accessor.pipesnphysics$getFlow();
        if (current.isEmpty()) {
            PipeConnection.Flow flow = conn.new Flow(inbound, fluid.copy());
            flow.progress.setValue(progress);
            flow.complete = progress >= MAX_PIPE_PROGRESS;
            accessor.pipesnphysics$setFlow(Optional.of(flow));
            return true;
        } else {
            PipeConnection.Flow flow = current.get();
            boolean changed = flow.inbound != inbound;
            flow.inbound = inbound;
            if (Math.abs(flow.progress.getValue() - progress) > 0.01f) {
                flow.progress.setValue(progress);
                flow.complete = progress >= MAX_PIPE_PROGRESS;
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
