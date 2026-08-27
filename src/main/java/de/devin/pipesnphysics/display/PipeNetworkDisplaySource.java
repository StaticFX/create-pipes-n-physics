package de.devin.pipesnphysics.display;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.display.PipeDisplayMetric.Readout;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.pump.Pumps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Makes a Create display link read a live pipe-network cell: it solves the network
 * at the source block (via the read-only {@link PipeProbe}) and writes one selected
 * metric per tick. The pipe and pump variants are the same source configured with a
 * different metric list — the pump one also reads the pump's RPM to derive its curve
 * cap and lift, which the pipe metrics never touch.
 */
public class PipeNetworkDisplaySource extends MetricDisplaySource {
    private final List<PipeDisplayMetric> metrics;
    private final boolean pump;

    public PipeNetworkDisplaySource(List<PipeDisplayMetric> metrics, String optionPrefix, boolean pump) {
        super(optionPrefix, metrics.stream().map(PipeDisplayMetric::key).toList());
        this.metrics = metrics;
        this.pump = pump;
    }

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.level() instanceof ServerLevel level)) return EMPTY_LINE;
        BlockPos pos = context.getSourcePos();
        PipeStatusPayload data = PipeProbe.probe(level, pos);
        if (data.status() == PipeStatusPayload.STATUS_NOT_CONNECTED) return DisplayLine.dash();

        double cap = 0, canLift = 0;
        if (pump) {
            double rpm = Pumps.strength(level, pos);
            cap = rpm * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
            canLift = rpm * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();
        }
        return metrics.get(metricIndex(context)).format(new Readout(data, cap, canLift));
    }
}
