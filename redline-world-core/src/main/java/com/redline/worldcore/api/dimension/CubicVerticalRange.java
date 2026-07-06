package com.redline.worldcore.api.dimension;

import com.redline.worldcore.api.pos.CubePos;

/**
 * True cube-world vertical range.
 *
 * <p>This is the source of truth for Redline World Core height. Vanilla DimensionType height is only a temporary
 * compatibility shell and must not be used by cube storage, tickets, generation, collision or native sync as the world
 * limit.</p>
 */
public record CubicVerticalRange(int minCubeY, int maxCubeY) {
    public CubicVerticalRange {
        if (minCubeY > maxCubeY) {
            throw new IllegalArgumentException("minCubeY must be <= maxCubeY");
        }
    }

    public static CubicVerticalRange ofCubeRange(int minCubeY, int maxCubeY) {
        return new CubicVerticalRange(minCubeY, maxCubeY);
    }

    public static CubicVerticalRange ofBlockRangeFloorAligned(int minBlockY, int maxBlockYInclusive) {
        return new CubicVerticalRange(CubePos.blockToCube(minBlockY), CubePos.blockToCube(maxBlockYInclusive));
    }

    public int minBlockY() {
        return minCubeY << CubePos.SIZE_BITS;
    }

    public int maxBlockY() {
        return (maxCubeY << CubePos.SIZE_BITS) + CubePos.MASK;
    }

    public int blockHeight() {
        return maxBlockY() - minBlockY() + 1;
    }

    public int cubeHeight() {
        return maxCubeY - minCubeY + 1;
    }

    public boolean containsCubeY(int cubeY) {
        return cubeY >= minCubeY && cubeY <= maxCubeY;
    }

    public boolean containsBlockY(int blockY) {
        return blockY >= minBlockY() && blockY <= maxBlockY();
    }

    public boolean contains(CubePos cubePos) {
        return containsCubeY(cubePos.y());
    }

    public boolean fullyContains(CubePos cubePos) {
        return containsBlockY(cubePos.minBlockY()) && containsBlockY(cubePos.maxBlockY());
    }

    public String blocksString() {
        return minBlockY() + ".." + maxBlockY();
    }

    public String cubesString() {
        return minCubeY + ".." + maxCubeY;
    }

    public String oneLine() {
        return "cubes=" + cubesString() + ", blocks=" + blocksString() + ", height=" + blockHeight();
    }
}
