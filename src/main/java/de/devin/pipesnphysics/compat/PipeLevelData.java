package de.devin.pipesnphysics.compat;

/**
 * Per-pipe-cell render metadata for the in-pipe LEVEL renderer, carried on the pipe's
 * {@code FluidTransportBehaviour} (see {@code FluidTransportBehaviourMixin}). Holds one packed int
 * encoding the solved waterline fraction + flow direction ({@link CreatePipeRendering#encodeLevel}),
 * or {@code 0} when the cell is not level-rendered.
 *
 * This is DEDICATED render metadata — it replaced an earlier hack that smuggled the waterline into
 * the flow's {@code FluidStack} amount, which risked stock Create reading it as a real fluid volume.
 * It is synced to clients (rides the behaviour's client packet) but deliberately NOT saved to disk:
 * it is re-derived from the solve every tick.
 */
public interface PipeLevelData {
    int pipesnphysics$getLevelData();

    void pipesnphysics$setLevelData(int data);
}
