package de.devin.pipesnphysics.compat;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Per-pipe-cell render metadata for the in-pipe LEVEL renderer, carried on the pipe's
 * {@code FluidTransportBehaviour} (see {@code FluidTransportBehaviourMixin}). Three fields, all
 * synced to clients (they ride the behaviour's client packet) but deliberately NOT saved to disk —
 * they are re-derived from the solve every tick:
 *
 * <ul>
 *   <li><b>level data</b> — one packed int encoding the solved waterline fraction + flow direction
 *       ({@link CreatePipeRendering#encodeLevel}), or {@code 0} when the cell is not level-rendered.
 *   <li><b>front data</b> — one packed int encoding the engine-owned travelling-front state
 *       ({@link CreatePipeRendering#encodeFront}: how far fluid has advanced through the cell along
 *       the flow axis, the advance direction, and the advance rate for client interpolation), or
 *       {@code 0} when untracked. This replaced reading Create's {@code Flow.progress}, so the
 *       level renderer's fill animation no longer depends on Create's transport cosmetics.
 *   <li><b>render fluid</b> — the fluid the level renderer draws (type/texture/tint), so it no
 *       longer reads Create's synced {@code Flow.fluid}.
 * </ul>
 *
 * This is DEDICATED render metadata — it replaced an earlier hack that smuggled the waterline into
 * the flow's {@code FluidStack} amount, which risked stock Create reading it as a real fluid volume.
 */
public interface PipeLevelData {
    int pipesnphysics$getLevelData();

    void pipesnphysics$setLevelData(int data);

    int pipesnphysics$getFrontData();

    void pipesnphysics$setFrontData(int data);

    FluidStack pipesnphysics$getRenderFluid();

    void pipesnphysics$setRenderFluid(FluidStack fluid);
}
