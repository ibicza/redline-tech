package com.redline.worldcore.server.lighting;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;

/** Compact debug summary for the M10 sky-light layer. */
public record SkyLightSummary(
        int litBlocks,
        int maxLight,
        int topLitBlocks,
        int bottomLitBlocks,
        int directVerticalColumns,
        int blockedColumns,
        long hash
) {
    public static SkyLightSummary from(LevelCube cube) {
        int lit = 0;
        int max = 0;
        int topLit = 0;
        int bottomLit = 0;
        int directColumns = 0;
        int blockedColumns = 0;
        long hash = StaticBlockLightLayer.FNV64_OFFSET;

        for (int z = 0; z < CubePos.SIZE; z++) {
            for (int x = 0; x < CubePos.SIZE; x++) {
                boolean direct = true;
                for (int y = 0; y < CubePos.SIZE; y++) {
                    int level = cube.getSkyLight(x, y, z);
                    if (level > 0) {
                        lit++;
                    }
                    if (y == CubePos.MASK && level > 0) {
                        topLit++;
                    }
                    if (y == 0 && level > 0) {
                        bottomLit++;
                    }
                    if (level < 15) {
                        direct = false;
                    }
                    max = Math.max(max, level);
                    hash ^= level & 0xF;
                    hash *= StaticBlockLightLayer.FNV64_PRIME;
                }
                if (direct) {
                    directColumns++;
                }
                if (cube.getSkyLight(x, 0, z) == 0) {
                    blockedColumns++;
                }
            }
        }

        return new SkyLightSummary(lit, max, topLit, bottomLit, directColumns, blockedColumns, hash);
    }

    public String oneLine() {
        return "skyLit=" + litBlocks
                + ", skyMax=" + maxLight
                + ", topLit=" + topLitBlocks
                + ", bottomLit=" + bottomLitBlocks
                + ", directColumns=" + directVerticalColumns
                + ", blockedColumns=" + blockedColumns
                + ", skyHash=" + StaticLightSummary.shortHex(hash);
    }
}
