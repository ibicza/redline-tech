package com.redline.worldcore.server.lighting;

import com.redline.worldcore.api.pos.ColumnPos;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.CubeHolder;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** M10 in-memory X/Z column index used by debug and the first sky-light propagation pass. */
public final class ColumnSkyIndex {
    public static final int NO_WORLD_Y = Integer.MIN_VALUE;
    public static final int NO_CUBE_Y = Integer.MIN_VALUE;

    private final ColumnPos columnPos;
    private final int[] topOpaqueWorldY = new int[CubePos.SIZE * CubePos.SIZE];
    private final int[] firstSkyBlockedCubeY = new int[CubePos.SIZE * CubePos.SIZE];
    private final byte[] bottomSkyLevels = new byte[CubePos.SIZE * CubePos.SIZE];
    private int minCubeY = Integer.MAX_VALUE;
    private int maxCubeY = Integer.MIN_VALUE;
    private int indexedCubes;
    private int columnsWithOpaque;
    private int blockedColumns;
    private long hash = StaticBlockLightLayer.FNV64_OFFSET;

    private ColumnSkyIndex(ColumnPos columnPos) {
        this.columnPos = columnPos;
        Arrays.fill(topOpaqueWorldY, NO_WORLD_Y);
        Arrays.fill(firstSkyBlockedCubeY, NO_CUBE_Y);
    }

    public static ColumnSkyIndex build(ColumnPos columnPos, List<CubeHolder> holders) {
        ColumnSkyIndex index = new ColumnSkyIndex(columnPos);
        List<CubeHolder> sorted = holders.stream()
                .filter(holder -> holder.cubePos().x() == columnPos.x() && holder.cubePos().z() == columnPos.z())
                .sorted(Comparator.comparingInt((CubeHolder holder) -> holder.cubePos().y()).reversed())
                .toList();

        index.indexedCubes = sorted.size();
        for (CubeHolder holder : sorted) {
            index.indexCube(holder);
        }
        index.finish();
        return index;
    }

    private void indexCube(CubeHolder holder) {
        CubePos cubePos = holder.cubePos();
        minCubeY = Math.min(minCubeY, cubePos.y());
        maxCubeY = Math.max(maxCubeY, cubePos.y());

        for (int z = 0; z < CubePos.SIZE; z++) {
            for (int x = 0; x < CubePos.SIZE; x++) {
                int columnIndex = SkyLightTransferData.localColumnIndex(x, z);
                int bottomSky = holder.cube().getSkyLight(x, 0, z);
                if (firstSkyBlockedCubeY[columnIndex] == NO_CUBE_Y && bottomSky == 0) {
                    firstSkyBlockedCubeY[columnIndex] = cubePos.y();
                }
                bottomSkyLevels[columnIndex] = (byte) bottomSky;

                for (int y = CubePos.MASK; y >= 0; y--) {
                    if (!SkyLightLayer.skyOpaque(holder.cube().getBlockState(x, y, z))) {
                        continue;
                    }
                    int worldY = cubePos.minBlockY() + y;
                    if (topOpaqueWorldY[columnIndex] == NO_WORLD_Y || worldY > topOpaqueWorldY[columnIndex]) {
                        topOpaqueWorldY[columnIndex] = worldY;
                    }
                    break;
                }
            }
        }
    }

    private void finish() {
        for (int index = 0; index < topOpaqueWorldY.length; index++) {
            if (topOpaqueWorldY[index] != NO_WORLD_Y) {
                columnsWithOpaque++;
            }
            if (firstSkyBlockedCubeY[index] != NO_CUBE_Y) {
                blockedColumns++;
            }
            hash ^= (topOpaqueWorldY[index] == NO_WORLD_Y ? 0 : topOpaqueWorldY[index]);
            hash *= StaticBlockLightLayer.FNV64_PRIME;
            hash ^= Byte.toUnsignedInt(bottomSkyLevels[index]) & 0xF;
            hash *= StaticBlockLightLayer.FNV64_PRIME;
        }
        if (indexedCubes == 0) {
            minCubeY = 0;
            maxCubeY = 0;
        }
    }

    public ColumnPos columnPos() {
        return columnPos;
    }

    public int indexedCubes() {
        return indexedCubes;
    }

    public int minCubeY() {
        return minCubeY;
    }

    public int maxCubeY() {
        return maxCubeY;
    }

    public int columnsWithOpaque() {
        return columnsWithOpaque;
    }

    public int blockedColumns() {
        return blockedColumns;
    }

    public int topOpaqueWorldY(int localX, int localZ) {
        return topOpaqueWorldY[SkyLightTransferData.localColumnIndex(localX, localZ)];
    }

    public int firstSkyBlockedCubeY(int localX, int localZ) {
        return firstSkyBlockedCubeY[SkyLightTransferData.localColumnIndex(localX, localZ)];
    }

    public int bottomSkyLevel(int localX, int localZ) {
        return Byte.toUnsignedInt(bottomSkyLevels[SkyLightTransferData.localColumnIndex(localX, localZ)]);
    }

    public long hash() {
        return hash;
    }

    public String oneLine() {
        return "column=" + columnPos.x() + " " + columnPos.z()
                + ", indexedCubes=" + indexedCubes
                + ", cubeY=" + minCubeY + ".." + maxCubeY
                + ", columnsWithOpaque=" + columnsWithOpaque
                + ", blockedColumns=" + blockedColumns
                + ", indexHash=" + StaticLightSummary.shortHex(hash);
    }
}
