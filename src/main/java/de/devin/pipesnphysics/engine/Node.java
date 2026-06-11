package de.devin.pipesnphysics.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A single node in a {@link Graph}.
 *
 * Nodes are the boundary entities of the contracted graph. They are either:
 *   HANDLER  — a block with an IFluidHandler capability (tank, basin, drain, etc.),
 *   PUMP     — a Create pump (carries a facing for push/pull side),
 *   JUNCTION — a pipe cell whose connection count is not exactly 2 (split, dead-end),
 *   OPEN_END — the world-space block an open pipe end faces (air, fluid, cauldron);
 *              pos is the space block, openFace points from it back to its pipe.
 *
 * Pipes with exactly two connections are pass-through and become part of an {@link Edge}
 * rather than a Node.
 */
public record Node(int index, BlockPos pos, Kind kind, double worldY,
                   Direction pumpFacing, Direction openFace) {

    public enum Kind { HANDLER, PUMP, JUNCTION, OPEN_END }

    public boolean isHandler() { return kind == Kind.HANDLER; }
    public boolean isPump() { return kind == Kind.PUMP; }
    public boolean isJunction() { return kind == Kind.JUNCTION; }
    public boolean isOpenEnd() { return kind == Kind.OPEN_END; }
}
