package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.client.DialSlot;
import de.devin.pipesnphysics.client.GoggleText;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.net.PipeStatusClient;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.turbine.HydroTurbine;
import de.devin.pipesnphysics.engine.pump.Pumps;
import de.devin.pipesnphysics.engine.turbine.PumpMode;
import de.devin.pipesnphysics.engine.turbine.PumpModeBehaviour;
import de.devin.pipesnphysics.engine.turbine.TurbineRating;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Suppresses Create's pump pressure distribution while the engine is enabled (the
 * engine routes fluid itself), wakes the network whenever the pump's facing
 * changes, and adds the pump's budget to the goggle overlay: head supplied,
 * current throughput against the pump-curve cap, and a boiler-style load bar.
 * When the engine is disabled in config, Create's logic runs untouched.
 *
 * It also makes the pump REVERSIBLE: dialed to TURBINE ({@link PumpModeBehaviour}) it stops
 * consuming rotation and starts producing it from the fluid falling through it. Create keeps that
 * machinery in {@code GeneratingKineticBlockEntity}, which the pump does not extend, so the parts
 * that matter are ported here — {@code updateGeneratedRotation}/{@code applyNewSpeed} and the
 * source re-activation — over the fields {@link KineticBlockEntity} already exposes.
 *
 * The debounce below is not politeness, it is the safety: {@code RotationPropagator} DESTROYS a
 * generator whose speed changes too often (+5 flicker per change against a decay of 1/tick, break
 * at 128), so the generated speed may only ever flip on a sustained transition — never with the
 * flow itself. What the flow scales instead is the stress CAPACITY, which Create updates without
 * touching rotation at all.
 */
@Mixin(value = PumpBlockEntity.class, remap = false)
public abstract class PumpBlockEntityMixin extends KineticBlockEntity implements HydroTurbine {
    /** Sustained samples at or above the start threshold before a turbine begins turning. */
    @Unique
    private static final int PIPESNPHYSICS$START_SAMPLES = 5;
    /** Sustained quiet samples before it stops — long enough that flicker can never accumulate. */
    @Unique
    private static final int PIPESNPHYSICS$STOP_SAMPLES = 40;
    /** How stale the last sample may get before the turbine counts as unfed (the network slept). */
    @Unique
    private static final int PIPESNPHYSICS$SAMPLE_GRACE_TICKS = 3;
    /** Capacity pushes are deadbanded to this share of the last value, or this many stress units. */
    @Unique
    private static final float PIPESNPHYSICS$CAPACITY_EPS = 0.5f;

    @Unique
    private Direction pipesnphysics$lastFacing = null;
    @Unique
    private boolean pipesnphysics$generating;
    @Unique
    private float pipesnphysics$capacityPerRpm;
    @Unique
    private int pipesnphysics$flowingSamples;
    @Unique
    private int pipesnphysics$quietSamples;
    @Unique
    private int pipesnphysics$sampledFlowMb;
    @Unique
    private long pipesnphysics$lastSampleTick = Long.MIN_VALUE;
    @Unique
    private long pipesnphysics$lastCapacityPush = Long.MIN_VALUE;
    @Unique
    private boolean pipesnphysics$reactivateSource;

    private PumpBlockEntityMixin() { super(null, null, null); }

    @Inject(method = "tick", at = @At("HEAD"))
    private void pipesnphysics$detectFlip(CallbackInfo ci) {
        Level world = level;
        if (world == null || world.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof PumpBlock)) return;
        Direction front = state.getValue(PumpBlock.FACING);
        if (pipesnphysics$lastFacing != null && pipesnphysics$lastFacing != front) {
            EngineTickHandler.markChanged(world, worldPosition);
            EngineTickHandler.markChanged(world, worldPosition.relative(front));
            EngineTickHandler.markChanged(world, worldPosition.relative(front.getOpposite()));
        }
        pipesnphysics$lastFacing = front;
    }

    /**
     * The dial, looked up rather than held in a field: a pump that REPLACES {@code addBehaviours}
     * instead of extending it (TFMG's electric pump) gets its dial from {@link PumpDialMixin}, and a
     * field set in the inject above would still read null for it — leaving the block permanently
     * unable to turbine.
     */
    @Unique
    private PumpModeBehaviour pipesnphysics$mode() {
        return ((SmartBlockEntity) (Object) this).getBehaviour(PumpModeBehaviour.TYPE);
    }

    @Unique
    private void pipesnphysics$wakeNetwork() {
        Level world = level;
        if (world == null || world.isClientSide()) return;
        EngineTickHandler.markChanged(world, worldPosition);
    }

    @Inject(method = "distributePressureTo", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$replacePressureDistribution(Direction side, CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        PumpBlockEntity self = (PumpBlockEntity) (Object) this;
        if (self.getLevel() != null && !self.getLevel().isClientSide()) {
            EngineTickHandler.markChanged(self.getLevel(), self.getBlockPos().relative(side));
        }
        ci.cancel();
    }

    // ------------------------------------------------------------------------ turbine mode

    /**
     * Whether this pump is acting as a turbine right now. AUTO — the default a freshly placed pump
     * comes up as — resolves off ONE fact: is something else turning this block. A driven pump is a
     * pump; an undriven one is a turbine, and needs no head measurement to say so, because a fall
     * short of the rating passes nothing anyway (the branch is NO_HEAD, which is the same wall an
     * unpowered pump always gave). "Driving" means a shaft OR the strength an electric pump
     * publishes — see {@code drivenWithoutAShaft}. {@code hasSource()} is false while WE are the
     * source, so a turbine that is already turning keeps its role.
     */
    @Override
    public boolean pipesnphysics$isTurbine() {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return false;
        if (!PipesNPhysicsConfig.ENABLE_HYDRO_TURBINE.get()) return false;
        PumpModeBehaviour dial = pipesnphysics$mode();
        if (dial == null) return false;
        return switch (dial.mode()) {
            case TURBINE -> true;
            case AUTO -> !hasSource() && !pipesnphysics$drivenWithoutAShaft();
            case PUMP -> false;
        };
    }

    /**
     * Whether something OTHER than a shaft is running this pump — an electric one's power, say.
     * AUTO asks "is anything driving this block", and asking it as {@code hasSource()} alone was
     * only ever right for a pump a shaft drives: an electric pump has no kinetic source EVER, so it
     * came up AUTO and resolved to TURBINE, refusing to pump however much power it had.
     *
     * Reads the pump's PUBLISHED strength (§2), never its shaft speed — a turbine we are already
     * spinning turns its own shaft, and counting that would flip it back to a pump the moment it
     * started, then back again once it stopped.
     */
    @Unique
    private boolean pipesnphysics$drivenWithoutAShaft() {
        return level != null && Pumps.publishedStrength(level, worldPosition) > 0;
    }

    @Override
    public void pipesnphysics$driveTurbine(int flowMb) {
        if (level == null || level.isClientSide()) return;
        pipesnphysics$lastSampleTick = level.getGameTime();
        pipesnphysics$sampledFlowMb = Math.max(0, flowMb);
        pipesnphysics$accountSample(pipesnphysics$sampledFlowMb);
    }

    @Override
    public double pipesnphysics$turbineStress() {
        return pipesnphysics$generating ? TurbineRating.stressUnits(pipesnphysics$sampledFlowMb) : 0;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void pipesnphysics$tickTurbine(CallbackInfo ci) {
        Level world = level;
        if (world == null || world.isClientSide()) return;
        if (pipesnphysics$reactivateSource) {
            pipesnphysics$reactivateSource = false;
            pipesnphysics$updateGeneratedRotation();
        }
        if (!pipesnphysics$isTurbine()) {
            if (pipesnphysics$generating) pipesnphysics$setGenerating(false);
            return;
        }
        // A network that stops moving fluid goes to sleep and simply stops sampling, so silence IS
        // the spin-down signal — read as quiet samples, which the stop debounce then rides out.
        if (world.getGameTime() - pipesnphysics$lastSampleTick > PIPESNPHYSICS$SAMPLE_GRACE_TICKS) {
            pipesnphysics$sampledFlowMb = 0;
            pipesnphysics$accountSample(0);
        }
    }

    /**
     * Fold one tick's throughput into the start/stop decision. The two thresholds are deliberately
     * far apart — a tenth of the rating to START, any movement at all to KEEP GOING — so the
     * decision cannot chatter around a single boundary, and each transition additionally needs a
     * run of samples agreeing on it.
     */
    @Unique
    private void pipesnphysics$accountSample(int flowMb) {
        int start = (int) Math.max(1, Math.ceil(TurbineRating.swallowMb() * 0.1));
        if (flowMb >= start) {
            pipesnphysics$flowingSamples++;
            pipesnphysics$quietSamples = 0;
        } else if (flowMb < 1) {
            pipesnphysics$quietSamples++;
            pipesnphysics$flowingSamples = 0;
        }

        if (!pipesnphysics$generating) {
            if (pipesnphysics$flowingSamples >= PIPESNPHYSICS$START_SAMPLES
                    && pipesnphysics$mayStartGenerating()) {
                pipesnphysics$setGenerating(true);
            }
        } else if (pipesnphysics$quietSamples >= PIPESNPHYSICS$STOP_SAMPLES) {
            pipesnphysics$setGenerating(false);
        }
        if (pipesnphysics$generating) pipesnphysics$pushCapacity(flowMb);
    }

    /**
     * Whether it is safe to start turning. A drivetrain already turning this block the OTHER way
     * would meet our new source head-on, and Create resolves that by destroying a block — so a
     * turbine wired backwards into a running network stays a passive member instead of exploding
     * the moment water reaches it.
     */
    @Unique
    private boolean pipesnphysics$mayStartGenerating() {
        if (!hasSource()) return true;
        float current = getTheoreticalSpeed();
        return current == 0 || Math.signum(current) == Math.signum(pipesnphysics$generatedSpeed());
    }

    @Unique
    private void pipesnphysics$setGenerating(boolean on) {
        if (pipesnphysics$generating == on) return;
        pipesnphysics$generating = on;
        pipesnphysics$flowingSamples = 0;
        pipesnphysics$quietSamples = 0;
        if (!on) pipesnphysics$capacityPerRpm = 0;
        pipesnphysics$updateGeneratedRotation();
    }

    /**
     * Feed the flow-scaled capacity to the kinetic network. This is the ONLY quantity that tracks
     * the flow, and it is safe to move freely: {@code updateCapacityFor} re-totals the network
     * without touching rotation. It is still deadbanded, because every accepted change syncs each
     * member of that network.
     */
    @Unique
    private void pipesnphysics$pushCapacity(int flowMb) {
        float capacity = TurbineRating.capacityPerRpm(flowMb);
        long now = level.getGameTime();
        float last = pipesnphysics$capacityPerRpm;
        boolean moved = Math.abs(capacity - last)
                > Math.max(PIPESNPHYSICS$CAPACITY_EPS, 0.05f * Math.abs(last));
        if (!moved && now - pipesnphysics$lastCapacityPush < 20) return;
        pipesnphysics$capacityPerRpm = capacity;
        pipesnphysics$lastCapacityPush = now;
        lastCapacityProvided = capacity;
        if (hasNetwork()) getOrCreateNetwork().updateCapacityFor(this, capacity);
    }

    /** The speed this turbine would turn at, signed by which way its pipe axis runs. */
    @Unique
    private float pipesnphysics$generatedSpeed() {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof PumpBlock)) return 0;
        float sign = state.getValue(PumpBlock.FACING).getAxisDirection()
                == Direction.AxisDirection.POSITIVE ? 1 : -1;
        return sign * (float) TurbineRating.ratedRpm();
    }

    @Override
    public float getGeneratedSpeed() {
        return pipesnphysics$generating ? pipesnphysics$generatedSpeed() : 0;
    }

    @Override
    public float calculateAddedStressCapacity() {
        if (!pipesnphysics$generating) return super.calculateAddedStressCapacity();
        lastCapacityProvided = pipesnphysics$capacityPerRpm;
        return pipesnphysics$capacityPerRpm;
    }

    @Override
    public float calculateStressApplied() {
        // A turbine is turned BY the water; it is not a machine the drivetrain has to carry, so it
        // must not also charge the pump's own impact against the power it is producing.
        if (!pipesnphysics$generating) return super.calculateStressApplied();
        lastStressApplied = 0;
        return 0;
    }

    // The generator lifecycle below is ported from Create's GeneratingKineticBlockEntity, which the
    // pump does not extend. Kept close to the original so Create's own edge cases (a stronger
    // source arriving, a network splitting) resolve identically here.

    @Override
    public void removeSource() {
        if (hasSource() && isSource()) pipesnphysics$reactivateSource = true;
        super.removeSource();
    }

    @Override
    public void setSource(BlockPos source) {
        super.setSource(source);
        if (level != null && level.getBlockEntity(source) instanceof KineticBlockEntity sourceBE
                && pipesnphysics$reactivateSource
                && Math.abs(sourceBE.getSpeed()) >= Math.abs(getGeneratedSpeed())) {
            pipesnphysics$reactivateSource = false;
        }
    }

    @Unique
    private void pipesnphysics$updateGeneratedRotation() {
        if (level == null || level.isClientSide) return;
        float generated = getGeneratedSpeed();
        float prevSpeed = this.speed;

        if (prevSpeed != generated) {
            if (!hasSource() && SpeedLevel.of(prevSpeed) != SpeedLevel.of(generated)) {
                effects.queueRotationIndicators();
            }
            pipesnphysics$applyNewSpeed(prevSpeed, generated);
        }

        if (hasNetwork() && generated != 0) {
            KineticNetwork network = getOrCreateNetwork();
            network.updateCapacityFor(this, calculateAddedStressCapacity());
            network.updateStressFor(this, calculateStressApplied());
            network.updateStress();
        }

        onSpeedChanged(prevSpeed);
        sendData();
    }

    @Unique
    private void pipesnphysics$applyNewSpeed(float prevSpeed, float newSpeed) {
        if (newSpeed == 0) {
            if (hasSource()) {
                getOrCreateNetwork().updateCapacityFor(this, 0);
                getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
                return;
            }
            detachKinetics();
            setSpeed(0);
            setNetwork(null);
            return;
        }

        if (prevSpeed == 0) {
            setSpeed(newSpeed);
            setNetwork(worldPosition.asLong());
            attachKinetics();
            return;
        }

        if (hasSource()) {
            if (Math.abs(prevSpeed) >= Math.abs(newSpeed)) {
                if (Math.signum(prevSpeed) != Math.signum(newSpeed)) {
                    level.destroyBlock(worldPosition, true);
                }
                return;
            }
            detachKinetics();
            setSpeed(newSpeed);
            source = null;
            setNetwork(worldPosition.asLong());
            attachKinetics();
            return;
        }

        detachKinetics();
        setSpeed(newSpeed);
        attachKinetics();
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        // The client needs the flag: getGeneratedSpeed() answers there too (network sync, goggles).
        compound.putBoolean("Generating", pipesnphysics$generating);
        if (!clientPacket) compound.putFloat("TurbineCapacity", pipesnphysics$capacityPerRpm);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void pipesnphysics$readTurbine(CompoundTag compound, HolderLookup.Provider registries,
                                           boolean clientPacket, CallbackInfo ci) {
        pipesnphysics$generating = compound.getBoolean("Generating");
        if (!clientPacket) pipesnphysics$capacityPerRpm = compound.getFloat("TurbineCapacity");
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean base = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!PipesNPhysicsConfig.SHOW_PIPE_GOGGLE_INFO.get()) return base;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return base;
        Level world = level;
        if (world == null || !world.isClientSide()) return base;

        boolean turbine = pipesnphysics$isTurbine();
        GoggleText.lang(turbine ? "gui.goggles.turbine_stats" : "gui.goggles.pump_stats")
                .style(ChatFormatting.WHITE)
                .forGoggles(tooltip);
        // The role ALWAYS spells itself out, exactly as the valve's direction line does: the dial on
        // the block is an icon, and AUTOMATIC in particular is a rule, not a state a player can read
        // off the world. It comes before the numbers, and it is the one thing a STOPPED pump still
        // says — "Pump only, and nothing is driving it" is the answer to why it moves nothing.
        pipesnphysics$addRoleLines(tooltip, isPlayerSneaking, turbine);

        if (turbine) {
            pipesnphysics$addTurbineInfo(tooltip, isPlayerSneaking, world);
            return true;
        }

        float speed = Math.abs(getSpeed());
        if (speed <= 0.01f) return true;

        long now = world.getGameTime();
        PipeStatusClient.requestIfStale(worldPosition, now);
        PipeStatusPayload data = PipeStatusClient.current(worldPosition, now);

        if (data != null && data.status() == PipeStatusPayload.STATUS_NO_HEAD) {
            GoggleText.lang("gui.goggles.no_head")
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip, 1);
        } else if (data != null && data.status() == PipeStatusPayload.STATUS_STALLED) {
            GoggleText.lang("gui.goggles.stalled")
                    .style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        }

        double flowCap = speed * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
        int rate = data != null ? data.mbPerTick() : 0;
        double headSupplied = speed * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();

        GoggleText.lang("gui.goggles.pumping")
                .style(ChatFormatting.GRAY)
                .add(GoggleText.text(LangNumberFormat.format(rate)).style(ChatFormatting.WHITE))
                .add(GoggleText.text(" / ").style(ChatFormatting.DARK_GRAY))
                .add(GoggleText.text(LangNumberFormat.format(flowCap)).style(ChatFormatting.GRAY))
                .add(GoggleText.lang("gui.goggles.mb_per_tick").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 1);

        // Name the binding limiter on the default view when the pump runs below cap. Three factors
        // can hold it down — lift, pipe run, and supply. The solver's hydraulic flow is
        // cap·headFactor·friction; the source/sink then throttle THAT to what actually moves, so
        // supplyFactor = actual / hydraulic. The smallest factor is the binding constraint, so a
        // starved pump reads "limited by supply" instead of being mislabelled lift/pipe-run.
        if (data != null && data.hasPumpLoad() && rate > 0 && rate < flowCap * 0.95f
                && headSupplied > 1e-6) {
            float headFactor = (float) ((headSupplied - data.pumpHeadAgainst()) / headSupplied);
            float friction = data.pumpFrictionFactor();
            float supply = pipesnphysics$supplyFactor(rate, flowCap, headFactor, friction);
            String capKey = supply <= headFactor && supply <= friction ? "gui.goggles.pump_cap_supply"
                    : headFactor < friction ? "gui.goggles.pump_cap_lift"
                    : "gui.goggles.pump_cap_friction";
            GoggleText.lang(capKey).style(ChatFormatting.DARK_GRAY).forGoggles(tooltip, 2);
        }

        GoggleText.lang("gui.goggles.head_supplied")
                .style(ChatFormatting.GRAY)
                .add(GoggleText.text(LangNumberFormat.format(headSupplied)).style(ChatFormatting.AQUA))
                .add(GoggleText.lang("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 1);

        if (isPlayerSneaking && data != null && data.hasPumpLoad() && flowCap > 0) {
            pipesnphysics$addLoadBreakdown(tooltip, data, headSupplied, rate, flowCap);
        }
        return true;
    }

    /**
     * Which role this pump is in, and — on sneak — what the three dial positions mean. The state
     * line names the role AND, for AUTOMATIC, which way it has currently resolved, because that is
     * a fact about the world (is a shaft turning this?) rather than about the setting.
     */
    @Unique
    private void pipesnphysics$addRoleLines(List<Component> tooltip, boolean isPlayerSneaking,
                                            boolean turbine) {
        PumpModeBehaviour dial = pipesnphysics$mode();
        PumpMode mode = dial == null ? PumpMode.PUMP : dial.mode();
        String roleKey = switch (mode) {
            case PUMP -> "gui.goggles.role_pump";
            case TURBINE -> "gui.goggles.role_turbine";
            case AUTO -> turbine ? "gui.goggles.role_auto_turbine" : "gui.goggles.role_auto_pump";
        };
        GoggleText.lang("gui.goggles.role")
                .style(ChatFormatting.GRAY)
                .add(GoggleText.lang(roleKey).style(ChatFormatting.WHITE))
                .forGoggles(tooltip, 1);

        if (!isPlayerSneaking) return;
        GoggleText.lang("gui.goggles.role_hint_dial")
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip, 2);
        GoggleText.lang("gui.goggles.role_hint_turbine")
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip, 2);
    }

    /**
     * The turbine's own goggle section, in place of the pump's: what it is making, what is running
     * through it, and — when it is not turning — that the fall is simply short of its rating,
     * which is the one thing a player cannot read off the block.
     */
    @Unique
    private void pipesnphysics$addTurbineInfo(List<Component> tooltip, boolean isPlayerSneaking,
                                              Level world) {
        long now = world.getGameTime();
        PipeStatusClient.requestIfStale(worldPosition, now);
        PipeStatusPayload data = PipeStatusClient.current(worldPosition, now);
        int rate = data != null ? data.mbPerTick() : 0;
        boolean turning = getGeneratedSpeed() != 0;

        if (turning) {
            GoggleText.lang("gui.goggles.turbine_output")
                    .style(ChatFormatting.GRAY)
                    .add(GoggleText.text(LangNumberFormat.format(TurbineRating.stressUnits(rate)))
                            .style(ChatFormatting.AQUA))
                    .add(GoggleText.lang("gui.goggles.su").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        } else {
            GoggleText.lang("gui.goggles.turbine_no_fall")
                    .style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        }

        GoggleText.lang("gui.goggles.turbine_passing")
                .style(ChatFormatting.GRAY)
                .add(GoggleText.text(LangNumberFormat.format(rate)).style(ChatFormatting.WHITE))
                .add(GoggleText.text(" / ").style(ChatFormatting.DARK_GRAY))
                .add(GoggleText.text(LangNumberFormat.format(TurbineRating.swallowMb()))
                        .style(ChatFormatting.GRAY))
                .add(GoggleText.lang("gui.goggles.mb_per_tick").style(ChatFormatting.DARK_GRAY))
                .forGoggles(tooltip, 1);

        if (isPlayerSneaking) {
            GoggleText.lang("gui.goggles.turbine_needs")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(GoggleText.text(LangNumberFormat.format(TurbineRating.ratedHead()))
                            .style(ChatFormatting.GRAY))
                    .add(GoggleText.lang("gui.goggles.blocks_fall").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 2);
        }
    }

    /**
     * Sneak-only load detail: first the one honest summary — throughput as a share
     * of cap, with the bar that used to sit on the default view — then the two
     * independent factors that multiply to it. {@code headFactor = (supplied −
     * against)/supplied} is the share the net lift leaves ("Lift"); {@code
     * frictionFactor} is the share the connected run's width/length passes ("Pipe
     * run"). They are shown as distinct causes, never summed, so they reconstruct
     * the bar exactly — and when gravity assists ({@code against < 0}) the lift
     * line flips to a green bonus.
     */
    @Unique
    private void pipesnphysics$addLoadBreakdown(List<Component> tooltip, PipeStatusPayload data,
                                                double headSupplied, int rate, double flowCap) {
        int filled = Math.clamp(Math.round(10 * rate / (float) flowCap), 0, 10);
        int percent = Math.clamp(Math.round(100 * rate / (float) flowCap), 0, 100);
        LangBuilder bar = GoggleText.lang("gui.goggles.load_throughput")
                .style(ChatFormatting.GRAY)
                .add(GoggleText.text(percent + "%").style(ChatFormatting.WHITE))
                .add(GoggleText.text("  ").style(ChatFormatting.DARK_GRAY));
        GoggleText.appendBars(bar, filled, 10);
        bar.forGoggles(tooltip, 2);

        if (headSupplied <= 1e-6) return;
        float friction = data.pumpFrictionFactor();
        float headFactor = (float) ((headSupplied - data.pumpHeadAgainst()) / headSupplied);

        if (headFactor < 0.985f) {
            GoggleText.lang("gui.goggles.load_backpressure")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(GoggleText.text(Math.round(headFactor * 100f) + "%").style(ChatFormatting.GOLD))
                    .add(GoggleText.text("  " + LangNumberFormat.format(data.pumpHeadAgainst())
                            + " / " + LangNumberFormat.format(headSupplied))
                            .style(ChatFormatting.DARK_GRAY))
                    .add(GoggleText.lang("gui.goggles.blocks").style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 3);
        } else if (headFactor > 1.015f) {
            GoggleText.lang("gui.goggles.load_assist")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(GoggleText.text("+" + Math.round((headFactor - 1f) * 100f) + "%")
                            .style(ChatFormatting.GREEN))
                    .forGoggles(tooltip, 3);
        }
        if (friction < 0.985f) {
            GoggleText.lang("gui.goggles.load_friction")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(GoggleText.text(Math.round(friction * 100f) + "%").style(ChatFormatting.GOLD))
                    .forGoggles(tooltip, 3);
        }
        // The third factor: how much of the pump's hydraulic flow the source/sink actually let
        // through. lift × pipe-run × supply reconstructs the throughput bar above.
        float supply = pipesnphysics$supplyFactor(rate, flowCap, headFactor, friction);
        if (supply < 0.985f) {
            GoggleText.lang("gui.goggles.load_supply")
                    .style(ChatFormatting.DARK_GRAY)
                    .add(GoggleText.text(Math.round(supply * 100f) + "%").style(ChatFormatting.GOLD))
                    .forGoggles(tooltip, 3);
        }
    }

    /**
     * The share of the pump's HYDRAULIC flow that actually reaches the far endpoint: actual moved /
     * (cap · headFactor · friction). Below 1 when the source can't supply — or the sink can't accept
     * — the rate the pump curve allows; the third factor, beside lift and pipe run, that holds
     * throughput under the cap. Gravity assist (headFactor > 1) can't push past the pump's own
     * throughput cap, so the lift share is clamped to 1 in the denominator — else assist would
     * fabricate a supply deficit. Result clamped to [0,1]; 1 when there is no hydraulic flow.
     */
    @Unique
    private static float pipesnphysics$supplyFactor(int rate, double flowCap,
                                                    float headFactor, float friction) {
        double hydraulic = flowCap * Math.clamp(headFactor, 0f, 1f) * friction;
        if (hydraulic <= 1e-6) return 1f;
        return (float) Math.clamp(rate / hydraulic, 0.0, 1.0);
    }
}
