package de.devin.pipesnphysics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Interface for Sable Companion compatibility.
 * Implemented by SableCompanionImpl (real) and NoOpProvider (fallback).
 */
interface SableCompatProvider {
    void clearCaches();
    boolean isSubLevelReady(Level level, BlockPos pos);
    double getWorldY(Level level, BlockPos pos);
    Vec3 getWorldPos(Level level, BlockPos pos);
    float getTiltAngle(Level level, BlockPos pos);
    float getTiltAngleClient(BlockEntity be);
    float getPipeElevation(Level level, BlockPos pos, Direction dir);
    boolean hasSubLevelRotated(Level level, BlockPos pos);
    boolean isOnSubLevelClient(BlockPos pos);
    float getClientPipeElevation(BlockPos pos, Direction dir);

    /**
     * Check if fluid in a tilted tank can reach a pipe at the given face.
     * On a tilted sub-level, fluid pools to the low side. If the pipe is on the
     * high side and the tank is nearly empty, fluid can't reach.
     *
     * @return true if fluid reaches the pipe face (always true if not on a sub-level)
     */
    boolean canFluidReachPipe(Level level, BlockPos tankPos, BlockPos pipePos, double fillFraction);

}
