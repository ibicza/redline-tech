package com.redline.worldcore.api.pos;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** X/Z column index. Columns store vertical indexes only; they do not own blocks. */
public record ColumnPos(int x, int z) {
    public static ColumnPos fromBlock(BlockPos pos) {
        return fromBlock(pos.getX(), pos.getZ());
    }

    public static ColumnPos fromBlock(int blockX, int blockZ) {
        return new ColumnPos(CubePos.blockToCube(blockX), CubePos.blockToCube(blockZ));
    }

    public static ColumnPos fromChunkPos(ChunkPos chunkPos) {
        return new ColumnPos(chunkPos.x(), chunkPos.z());
    }

    public int minBlockX() {
        return x << CubePos.SIZE_BITS;
    }

    public int minBlockZ() {
        return z << CubePos.SIZE_BITS;
    }

    public int maxBlockX() {
        return minBlockX() + CubePos.MASK;
    }

    public int maxBlockZ() {
        return minBlockZ() + CubePos.MASK;
    }

    public ChunkPos asChunkPosForCompatibility() {
        return new ChunkPos(x, z);
    }
}
