package de.devin.pipesnphysics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

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
    boolean canFluidReachPipe(Level level, BlockPos tankPos, BlockPos pipePos, double fillFraction);
}
