package com.redline.worldcore.server.lighting;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;

/**
 * M10 sky-light MVP.
 *
 * <p>This computes cube-owned sky-light arrays by walking loaded cube columns from top to bottom. It is intentionally
 * vertical-only for the first MVP: side tunnels and full vanilla light-engine injection are later work.</p>
 */
public final class SkyLightLayer {
    public static final int MAX_SKY_LIGHT = 15;

    private SkyLightLayer() {
    }

    public static ColumnRebuildResult rebuildColumn(List<LevelCube> cubesTopToBottom) {
        if (cubesTopToBottom.isEmpty()) {
            return new ColumnRebuildResult(0, 0, 0, 0, 0, StaticBlockLightLayer.FNV64_OFFSET);
        }

        byte[] incoming = filledTopLight();
        int rebuilt = 0;
        int lit = 0;
        int blockedColumns = 0;
        int directColumns = 0;
        long hash = StaticBlockLightLayer.FNV64_OFFSET;

        for (LevelCube cube : cubesTopToBottom) {
            CubeRebuildResult result = rebuildCube(cube, incoming);
            incoming = result.transfer().bottomLevels();
            rebuilt++;
            lit += result.summary().litBlocks();
            blockedColumns += result.transfer().blockedColumns();
            directColumns += result.transfer().directVerticalOpenColumns();
            hash ^= result.summary().hash();
            hash *= StaticBlockLightLayer.FNV64_PRIME;
        }

        return new ColumnRebuildResult(rebuilt, lit, blockedColumns, directColumns, max(incoming), hash);
    }

    public static CubeRebuildResult rebuildSingleCubeFromOpenSky(LevelCube cube) {
        return rebuildCube(cube, filledTopLight());
    }

    public static CubeRebuildResult rebuildCube(LevelCube cube, byte[] incomingTopLevels) {
        if (incomingTopLevels.length != CubePos.SIZE * CubePos.SIZE) {
            throw new IllegalArgumentException("incomingTopLevels must contain 256 local column values");
        }

        cube.clearSkyLight();
        byte[] bottom = new byte[CubePos.SIZE * CubePos.SIZE];

        for (int z = 0; z < CubePos.SIZE; z++) {
            for (int x = 0; x < CubePos.SIZE; x++) {
                int columnIndex = SkyLightTransferData.localColumnIndex(x, z);
                int light = Byte.toUnsignedInt(incomingTopLevels[columnIndex]);
                for (int y = CubePos.MASK; y >= 0; y--) {
                    BlockState state = cube.getBlockState(x, y, z);
                    int levelAtBlock = levelInsideBlock(light, state);
                    cube.setSkyLight(x, y, z, levelAtBlock);
                    light = levelBelowBlock(light, state);
                }
                bottom[columnIndex] = (byte) light;
            }
        }

        if (!cube.status().isAtLeast(CubeStatus.LIGHT_READY)) {
            cube.setStatus(CubeStatus.LIGHT_READY);
        }

        SkyLightSummary summary = SkyLightSummary.from(cube);
        SkyLightTransferData transfer = SkyLightTransferData.fromCube(cube);
        return new CubeRebuildResult(cube.cubePos(), summary, transfer);
    }

    /** True for blocks that completely stop this MVP's vertical skylight. */
    public static boolean skyOpaque(BlockState state) {
        return skyDrop(state) >= MAX_SKY_LIGHT;
    }

    /**
     * Vertical sky-light drop for one block.
     *
     * <p>Air keeps direct vertical skylight at 15. Non-air blocks use Minecraft's light dampening value and guarantee at
     * least one point of loss for semi-transparent blocks.</p>
     */
    public static int skyDrop(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        int dampening = Mth.clamp(state.getLightDampening(), 0, MAX_SKY_LIGHT);
        return Math.max(1, dampening);
    }

    private static int levelInsideBlock(int incoming, BlockState state) {
        if (incoming <= 0) {
            return 0;
        }
        int drop = skyDrop(state);
        if (drop >= MAX_SKY_LIGHT) {
            return 0;
        }
        return Mth.clamp(incoming, 0, MAX_SKY_LIGHT);
    }

    private static int levelBelowBlock(int incoming, BlockState state) {
        if (incoming <= 0) {
            return 0;
        }
        int drop = skyDrop(state);
        if (drop >= MAX_SKY_LIGHT) {
            return 0;
        }
        return Mth.clamp(incoming - drop, 0, MAX_SKY_LIGHT);
    }

    public static boolean hasAnySkyLight(LevelCube cube) {
        byte[] light = cube.copySkyLight();
        for (byte value : light) {
            if (Byte.toUnsignedInt(value) > 0) {
                return true;
            }
        }
        return false;
    }

    public static byte[] filledTopLight() {
        byte[] result = new byte[CubePos.SIZE * CubePos.SIZE];
        Arrays.fill(result, (byte) MAX_SKY_LIGHT);
        return result;
    }

    private static int max(byte[] values) {
        int max = 0;
        for (byte value : values) {
            max = Math.max(max, Byte.toUnsignedInt(value));
        }
        return max;
    }

    public record CubeRebuildResult(CubePos cubePos, SkyLightSummary summary, SkyLightTransferData transfer) {
        public String oneLine() {
            return "cube=" + cubePos.x() + " " + cubePos.y() + " " + cubePos.z()
                    + ", " + summary.oneLine()
                    + ", " + transfer.oneLine();
        }
    }

    public record ColumnRebuildResult(
            int rebuiltCubes,
            int skyLitBlocks,
            int blockedColumnSamples,
            int directColumnSamples,
            int bottomMaxSky,
            long hash
    ) {
        public String oneLine() {
            return "rebuiltCubes=" + rebuiltCubes
                    + ", skyLitBlocks=" + skyLitBlocks
                    + ", blockedSamples=" + blockedColumnSamples
                    + ", directSamples=" + directColumnSamples
                    + ", bottomMaxSky=" + bottomMaxSky
                    + ", columnHash=" + StaticLightSummary.shortHex(hash);
        }
    }
}
