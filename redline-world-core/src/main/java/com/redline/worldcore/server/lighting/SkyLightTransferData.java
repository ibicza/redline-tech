package com.redline.worldcore.server.lighting;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;

import java.util.Arrays;

/**
 * M10 top-to-bottom sky transfer snapshot for one cube.
 *
 * <p>The data is intentionally compact and derived from the cube-owned sky-light array. Later M10.x/M11 work can add
 * side transfer, tunnels and cached connectivity. For the MVP it is enough to prove finite-top vertical skylight across
 * loaded cube columns.</p>
 */
public record SkyLightTransferData(
        byte[] bottomLevels,
        int topLitColumns,
        int bottomLitColumns,
        int directVerticalOpenColumns,
        int blockedColumns,
        int maxBottomLevel,
        long hash
) {
    public SkyLightTransferData {
        bottomLevels = Arrays.copyOf(bottomLevels, CubePos.SIZE * CubePos.SIZE);
    }

    public static SkyLightTransferData fromCube(LevelCube cube) {
        byte[] bottom = new byte[CubePos.SIZE * CubePos.SIZE];
        int topLit = 0;
        int bottomLit = 0;
        int direct = 0;
        int blocked = 0;
        int maxBottom = 0;
        long hash = StaticBlockLightLayer.FNV64_OFFSET;

        for (int z = 0; z < CubePos.SIZE; z++) {
            for (int x = 0; x < CubePos.SIZE; x++) {
                int index = localColumnIndex(x, z);
                int topLevel = cube.getSkyLight(x, CubePos.MASK, z);
                int bottomLevel = cube.getSkyLight(x, 0, z);
                bottom[index] = (byte) bottomLevel;
                if (topLevel > 0) {
                    topLit++;
                }
                if (bottomLevel > 0) {
                    bottomLit++;
                } else {
                    blocked++;
                }
                boolean columnDirect = topLevel == 15 && bottomLevel == 15;
                for (int y = 0; y < CubePos.SIZE && columnDirect; y++) {
                    columnDirect = cube.getSkyLight(x, y, z) == 15;
                }
                if (columnDirect) {
                    direct++;
                }
                maxBottom = Math.max(maxBottom, bottomLevel);
                hash ^= bottomLevel & 0xF;
                hash *= StaticBlockLightLayer.FNV64_PRIME;
            }
        }

        return new SkyLightTransferData(bottom, topLit, bottomLit, direct, blocked, maxBottom, hash);
    }

    public int bottomLevel(int localX, int localZ) {
        return Byte.toUnsignedInt(bottomLevels[localColumnIndex(localX, localZ)]);
    }

    public String oneLine() {
        return "topLitColumns=" + topLitColumns
                + ", bottomLitColumns=" + bottomLitColumns
                + ", directOpenColumns=" + directVerticalOpenColumns
                + ", blockedColumns=" + blockedColumns
                + ", maxBottom=" + maxBottomLevel
                + ", transferHash=" + StaticLightSummary.shortHex(hash);
    }

    public static int localColumnIndex(int localX, int localZ) {
        CubePos.checkLocal(localX, "localX");
        CubePos.checkLocal(localZ, "localZ");
        return localX | (localZ << CubePos.SIZE_BITS);
    }
}
