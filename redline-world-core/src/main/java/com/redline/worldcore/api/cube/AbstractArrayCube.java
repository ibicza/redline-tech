package com.redline.worldcore.api.cube;

import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Objects;

abstract class AbstractArrayCube implements CubeAccess {
    private final CubePos cubePos;
    private final BlockState[] states = new BlockState[CubePos.BLOCK_COUNT];
    private CubeStatus status;

    protected AbstractArrayCube(CubePos cubePos, CubeStatus status) {
        this.cubePos = Objects.requireNonNull(cubePos, "cubePos");
        this.status = Objects.requireNonNull(status, "status");
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
    }

    @Override
    public CubePos cubePos() {
        return cubePos;
    }

    @Override
    public CubeStatus status() {
        return status;
    }

    @Override
    public void setStatus(CubeStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    @Override
    public BlockState getBlockState(int localX, int localY, int localZ) {
        return states[CubePos.localIndex(localX, localY, localZ)];
    }

    @Override
    public void setBlockState(int localX, int localY, int localZ, BlockState state) {
        states[CubePos.localIndex(localX, localY, localZ)] = Objects.requireNonNull(state, "state");
    }

    public void fill(BlockState state) {
        Arrays.fill(states, Objects.requireNonNull(state, "state"));
    }
}
