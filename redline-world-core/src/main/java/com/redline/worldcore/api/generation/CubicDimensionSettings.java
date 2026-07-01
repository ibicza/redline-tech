package com.redline.worldcore.api.generation;

import com.redline.worldcore.api.pos.CubePos;

public record CubicDimensionSettings(
        int minCubeY,
        int maxCubeY,
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
    public static CubicDimensionSettings defaults() {
        return new CubicDimensionSettings(
                -256,
                255,
                10,
                4,
                8,
                3,
                6,
                2,
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

    public boolean containsCubeY(int cubeY) {
        return cubeY >= minCubeY && cubeY <= maxCubeY;
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }
}
