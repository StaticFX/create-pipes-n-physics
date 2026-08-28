package de.devin.pipesnphysics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.BiFunction;

interface SableCompatProvider {
    <T> T atOverlappingContraptions(Level level, BlockPos origin, BiFunction<Level, BlockPos, T> reader);
    SubLevelFrame clientFrame(Level level, BlockPos pos, float partialTicks);
    List<SubLevelFrame> clientFramesNear(Level level, Vec3 center, double radius, float partialTicks);
    String getSubLevelId(Level level, BlockPos pos);
    boolean isOnSubLevel(Level level, BlockPos pos);
    boolean isSubLevelReady(Level level, BlockPos pos);
    double getWorldY(Level level, BlockPos pos);
    Vec3 getWorldPos(Level level, BlockPos pos);
    double getUpProjectionY(Level level, BlockPos pos);
    double getColumnBaseY(Level level, BlockPos pos, int width, int height);
    double getColumnBaseYAtCenter(Level level, BlockPos pos, double halfX, double halfY, double halfZ,
                                  int verticalBlocks);
    float getTiltAngle(Level level, BlockPos pos);
    float getTiltAngleClient(BlockEntity be);
    float getPipeElevation(Level level, BlockPos pos, Direction dir);
    boolean isOnSubLevelClient(BlockPos pos);
    float getClientPipeElevation(BlockPos pos, Direction dir);
    boolean canFluidReachPipe(Level level, BlockPos tankPos, BlockPos pipePos, double fillFraction);
}
