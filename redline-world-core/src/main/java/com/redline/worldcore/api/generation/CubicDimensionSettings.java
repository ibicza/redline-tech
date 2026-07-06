package com.redline.worldcore.api.generation;

import com.redline.worldcore.api.dimension.CubicVerticalRange;
import com.redline.worldcore.api.pos.CubePos;

public record CubicDimensionSettings(
        int minCubeY,
        int maxCubeY,
        int seaLevel,
        int vanillaShellMinY,
        int vanillaShellMaxY,
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
    /** True internal cube-only height. Vanilla DimensionType is not allowed to limit this range. */
    public static final int DEFAULT_INTERNAL_MIN_CUBE_Y = -1024;
    public static final int DEFAULT_INTERNAL_MAX_CUBE_Y = 1023;

    /** Temporary vanilla-safe shell used only by compatibility materialization/render mirror paths. */
    public static final int DEFAULT_VANILLA_SHELL_MIN_Y = -2032;
    public static final int DEFAULT_VANILLA_SHELL_MAX_Y = 2031;

    /** Earth-like probe targets requested for M19.4.6. */
    public static final int EXTREME_HIGH_TEST_Y = 9000;
    public static final int EXTREME_LOW_TEST_Y = -12000;

    /**
     * M19.4 true cube vertical range default for cubic_test.
     *
     * <p>The real backend now owns block Y -16384..16383. Vanilla's dimension JSON deliberately remains a legal
     * compatibility shell (-2032..2031) until the final native renderer/interaction layer no longer needs a vanilla
     * section container at all.</p>
     */
    public static CubicDimensionSettings defaults() {
        return new CubicDimensionSettings(
                DEFAULT_INTERNAL_MIN_CUBE_Y,
                DEFAULT_INTERNAL_MAX_CUBE_Y,
                0,
                DEFAULT_VANILLA_SHELL_MIN_Y,
                DEFAULT_VANILLA_SHELL_MAX_Y,
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
        if (vanillaShellMinY > vanillaShellMaxY) {
            throw new IllegalArgumentException("vanillaShellMinY must be <= vanillaShellMaxY");
        }
        if ((vanillaShellMinY & CubePos.MASK) != 0 || (vanillaShellMaxY & CubePos.MASK) != CubePos.MASK) {
            throw new IllegalArgumentException("vanilla shell must align to 16-block cube sections, got "
                    + vanillaShellMinY + ".." + vanillaShellMaxY);
        }
        int minBlockY = minCubeY << CubePos.SIZE_BITS;
        int maxBlockY = (maxCubeY << CubePos.SIZE_BITS) + CubePos.MASK;
        if (seaLevel < minBlockY || seaLevel > maxBlockY) {
            throw new IllegalArgumentException("seaLevel must be inside cubic dimension block range "
                    + minBlockY + ".." + maxBlockY + ", got " + seaLevel);
        }
        if (vanillaShellMinY < minBlockY || vanillaShellMaxY > maxBlockY) {
            throw new IllegalArgumentException("vanilla shell must be inside internal cubic range");
        }
        requireNonNegative(horizontalLoadDistance, "horizontalLoadDistance");
        requireNonNegative(verticalLoadDistance, "verticalLoadDistance");
        requireNonNegative(horizontalGenerationDistance, "horizontalGenerationDistance");
        requireNonNegative(verticalGenerationDistance, "verticalGenerationDistance");
        requireNonNegative(horizontalTickDistance, "horizontalTickDistance");
        requireNonNegative(verticalTickDistance, "verticalTickDistance");
    }

    public CubicVerticalRange internalRange() {
        return new CubicVerticalRange(minCubeY, maxCubeY);
    }

    public CubicVerticalRange vanillaShellRange() {
        return CubicVerticalRange.ofBlockRangeFloorAligned(vanillaShellMinY, vanillaShellMaxY);
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

    public int downwardDepthToSea() {
        return Math.max(1, seaLevel - minBlockY());
    }

    public int upwardHeightFromSea() {
        return Math.max(1, maxBlockY() - seaLevel);
    }

    public boolean containsCubeY(int cubeY) {
        return cubeY >= minCubeY && cubeY <= maxCubeY;
    }

    public boolean containsBlockY(int blockY) {
        return blockY >= minBlockY() && blockY <= maxBlockY();
    }

    public boolean containsCube(CubePos cubePos) {
        return containsCubeY(cubePos.y());
    }

    public boolean isCubeInsideVanillaShell(CubePos cubePos) {
        return cubePos.minBlockY() >= vanillaShellMinY && cubePos.maxBlockY() <= vanillaShellMaxY;
    }

    public boolean isBlockInsideVanillaShell(int blockY) {
        return blockY >= vanillaShellMinY && blockY <= vanillaShellMaxY;
    }

    public String internalHeightSummary() {
        return "cubes " + minCubeY + ".." + maxCubeY + ", blocks " + minBlockY() + ".." + maxBlockY()
                + ", height=" + blockHeight();
    }

    public String vanillaShellSummary() {
        return "blocks " + vanillaShellMinY + ".." + vanillaShellMaxY
                + ", cubes " + CubePos.blockToCube(vanillaShellMinY) + ".." + CubePos.blockToCube(vanillaShellMaxY)
                + ", height=" + (vanillaShellMaxY - vanillaShellMinY + 1);
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }
}
