package com.redline.worldcore.server.lighting;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;

/** Compact debug summary for the M9 static block-light layer. */
public record StaticLightSummary(
        int litBlocks,
        int emittingBlocks,
        int maxLight,
        int boundaryLitBlocks,
        long hash
) {
    public static StaticLightSummary from(LevelCube cube) {
        int lit = 0;
        int emitters = 0;
        int max = 0;
        int boundary = 0;
        long hash = StaticBlockLightLayer.FNV64_OFFSET;

        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    int level = cube.getBlockLight(x, y, z);
                    int emission = StaticBlockLightLayer.emission(cube.getBlockState(x, y, z));
                    if (level > 0) {
                        lit++;
                    }
                    if (emission > 0) {
                        emitters++;
                    }
                    if (isBoundary(x, y, z) && level > 0) {
                        boundary++;
                    }
                    max = Math.max(max, level);
                    hash ^= level & 0xF;
                    hash *= StaticBlockLightLayer.FNV64_PRIME;
                }
            }
        }

        return new StaticLightSummary(lit, emitters, max, boundary, hash);
    }

    public String oneLine() {
        return "lit=" + litBlocks
                + ", emitters=" + emittingBlocks
                + ", max=" + maxLight
                + ", boundaryLit=" + boundaryLitBlocks
                + ", hash=" + shortHex(hash);
    }

    public static String shortHex(long value) {
        return Long.toUnsignedString(value, 16);
    }

    private static boolean isBoundary(int x, int y, int z) {
        return x == 0 || y == 0 || z == 0 || x == CubePos.MASK || y == CubePos.MASK || z == CubePos.MASK;
    }
}
