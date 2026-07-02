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

    int getBlockLight(int localX, int localY, int localZ);

    void setBlockLight(int localX, int localY, int localZ, int level);

    byte[] copyBlockLight();

    void replaceBlockLight(byte[] light);

    void clearBlockLight();

    int getSkyLight(int localX, int localY, int localZ);

    void setSkyLight(int localX, int localY, int localZ, int level);

    byte[] copySkyLight();

    void replaceSkyLight(byte[] light);

    void clearSkyLight();

    default BlockState getBlockState(CubeLocalPos localPos) {
        return getBlockState(localPos.x(), localPos.y(), localPos.z());
    }

    default void setBlockState(CubeLocalPos localPos, BlockState state) {
        setBlockState(localPos.x(), localPos.y(), localPos.z(), state);
    }

    default int getBlockLight(CubeLocalPos localPos) {
        return getBlockLight(localPos.x(), localPos.y(), localPos.z());
    }

    default void setBlockLight(CubeLocalPos localPos, int level) {
        setBlockLight(localPos.x(), localPos.y(), localPos.z(), level);
    }

    default int getSkyLight(CubeLocalPos localPos) {
        return getSkyLight(localPos.x(), localPos.y(), localPos.z());
    }

    default void setSkyLight(CubeLocalPos localPos, int level) {
        setSkyLight(localPos.x(), localPos.y(), localPos.z(), level);
    }

    default BlockState getBlockState(BlockPos worldPos) {
        requireContains(worldPos);
        return getBlockState(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()));
    }

    default void setBlockState(BlockPos worldPos, BlockState state) {
        requireContains(worldPos);
        setBlockState(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()), state);
    }

    default int getBlockLight(BlockPos worldPos) {
        requireContains(worldPos);
        return getBlockLight(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()));
    }

    default void setBlockLight(BlockPos worldPos, int level) {
        requireContains(worldPos);
        setBlockLight(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()), level);
    }

    default int getSkyLight(BlockPos worldPos) {
        requireContains(worldPos);
        return getSkyLight(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()));
    }

    default void setSkyLight(BlockPos worldPos, int level) {
        requireContains(worldPos);
        setSkyLight(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()), level);
    }

    default void requireContains(BlockPos worldPos) {
        if (!cubePos().containsBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ())) {
            throw new IllegalArgumentException("Block " + worldPos + " is outside cube " + cubePos());
        }
    }
}
