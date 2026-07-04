package com.redline.worldcore.api.generation;

import com.redline.worldcore.api.pos.CubePos;

public record CubicDimensionSettings(
        int minCubeY,
        int maxCubeY,
        int seaLevel,
        int horizontalLoadDistance,
        int verticalLoadDistance,
        int horizontalGenerationDistance,
        int verticalGenerationDistance,
        int horizontalTickDistance,
        int verticalTickDistance,
        boolean dynamicLighting,
        boolean serverDynamicLighting,
        boolean afkPregen,
        boolean verticalBackfill
) {
    /**
     * M15 seed-only Java worldgen default for cubic_test.
     *
     * <p>The world is intentionally finite for now: block Y -2032..2031. Sea level is fixed at Y=0,
     * while terrain, deep layers and future ocean/river systems scale from min/max Y.</p>
     */
    public static CubicDimensionSettings defaults() {
        return new CubicDimensionSettings(
                -127,
                126,
                0,
                8,
                3,
                6,
                2,
                5,
                1,
                true,
                false,
                true,
                true
        );
    }

    public CubicDimensionSettings {
        if (minCubeY > maxCubeY) {
            throw new IllegalArgumentException("minCubeY must be <= maxCubeY");
        }
        int minBlockY = minCubeY << CubePos.SIZE_BITS;
        int maxBlockY = (maxCubeY << CubePos.SIZE_BITS) + CubePos.MASK;
        if (seaLevel < minBlockY || seaLevel > maxBlockY) {
            throw new IllegalArgumentException("seaLevel must be inside cubic dimension block range "
                    + minBlockY + ".." + maxBlockY + ", got " + seaLevel);
        }
        requireNonNegative(horizontalLoadDistance, "horizontalLoadDistance");
        requireNonNegative(verticalLoadDistance, "verticalLoadDistance");
        requireNonNegative(horizontalGenerationDistance, "horizontalGenerationDistance");
        requireNonNegative(verticalGenerationDistance, "verticalGenerationDistance");
        requireNonNegative(horizontalTickDistance, "horizontalTickDistance");
        requireNonNegative(verticalTickDistance, "verticalTickDistance");
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

    public int downwardDepthToSea() {
        return Math.max(1, seaLevel - minBlockY());
    }

    public int upwardHeightFromSea() {
        return Math.max(1, maxBlockY() - seaLevel);
    }

    public boolean containsCubeY(int cubeY) {
        return cubeY >= minCubeY && cubeY <= maxCubeY;
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }
}
