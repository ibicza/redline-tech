package com.redline.worldcore.api.pos;

import net.minecraft.core.BlockPos;

/** Inclusive cube-aligned block bounds. */
public record CubeBounds(BlockPos min, BlockPos max) {
    public static CubeBounds of(CubePos cubePos) {
        return new CubeBounds(cubePos.minBlockPos(), cubePos.maxBlockPos());
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}
