package com.redline.worldcore.api.pos;

import net.minecraft.core.BlockPos;

/**
 * Cube-first world coordinate.
 *
 * <p>One cube owns exactly 16x16x16 blocks. Negative coordinates intentionally
 * use {@link Math#floorDiv(int, int)} so block -1 belongs to cube -1, not cube 0.</p>
 */
public record CubePos(int x, int y, int z) {
    public static final int SIZE_BITS = 4;
    public static final int SIZE = 1 << SIZE_BITS;
    public static final int MASK = SIZE - 1;
    public static final int BLOCK_COUNT = SIZE * SIZE * SIZE;

    public static CubePos fromBlock(BlockPos pos) {
        return fromBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    public static CubePos fromBlock(int blockX, int blockY, int blockZ) {
        return new CubePos(blockToCube(blockX), blockToCube(blockY), blockToCube(blockZ));
    }

    public static int blockToCube(int blockCoord) {
        return Math.floorDiv(blockCoord, SIZE);
    }

    public static int local(int blockCoord) {
        return blockCoord & MASK;
    }

    public static int localIndex(int localX, int localY, int localZ) {
        checkLocal(localX, "localX");
        checkLocal(localY, "localY");
        checkLocal(localZ, "localZ");
        return localX | (localZ << SIZE_BITS) | (localY << (SIZE_BITS * 2));
    }

    public static boolean isLocal(int value) {
        return value >= 0 && value < SIZE;
    }

    public static int localIndexFromBlock(int blockX, int blockY, int blockZ) {
        return localIndex(local(blockX), local(blockY), local(blockZ));
    }

    public int minBlockX() {
        return x << SIZE_BITS;
    }

    public int minBlockY() {
        return y << SIZE_BITS;
    }

    public int minBlockZ() {
        return z << SIZE_BITS;
    }

    public int maxBlockX() {
        return minBlockX() + MASK;
    }

    public int maxBlockY() {
        return minBlockY() + MASK;
    }

    public int maxBlockZ() {
        return minBlockZ() + MASK;
    }

    public BlockPos minBlockPos() {
        return new BlockPos(minBlockX(), minBlockY(), minBlockZ());
    }

    public BlockPos maxBlockPos() {
        return new BlockPos(maxBlockX(), maxBlockY(), maxBlockZ());
    }

    public ColumnPos columnPos() {
        return new ColumnPos(x, z);
    }

    public Region3DPos regionPos() {
        return Region3DPos.fromCube(this);
    }

    public CubePos offset(int dx, int dy, int dz) {
        if (dx == 0 && dy == 0 && dz == 0) {
            return this;
        }
        return new CubePos(x + dx, y + dy, z + dz);
    }

    public boolean containsBlock(int blockX, int blockY, int blockZ) {
        return blockToCube(blockX) == x && blockToCube(blockY) == y && blockToCube(blockZ) == z;
    }

    public static void checkLocal(int value, String name) {
        if (value < 0 || value >= SIZE) {
            throw new IllegalArgumentException(name + " must be in [0, 15], got " + value);
        }
    }
}
