package com.redline.worldcore.api.cube;

import com.redline.worldcore.api.pos.CubeLocalPos;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface CubeAccess {
    CubePos cubePos();

    CubeStatus status();

    void setStatus(CubeStatus status);

    BlockState getBlockState(int localX, int localY, int localZ);

    void setBlockState(int localX, int localY, int localZ, BlockState state);

    default BlockState getBlockState(CubeLocalPos localPos) {
        return getBlockState(localPos.x(), localPos.y(), localPos.z());
    }

    default void setBlockState(CubeLocalPos localPos, BlockState state) {
        setBlockState(localPos.x(), localPos.y(), localPos.z(), state);
    }

    default BlockState getBlockState(BlockPos worldPos) {
        requireContains(worldPos);
        return getBlockState(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()));
    }

    default void setBlockState(BlockPos worldPos, BlockState state) {
        requireContains(worldPos);
        setBlockState(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()), state);
    }

    default void requireContains(BlockPos worldPos) {
        if (!cubePos().containsBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ())) {
            throw new IllegalArgumentException("Block " + worldPos + " is outside cube " + cubePos());
        }
    }
}
