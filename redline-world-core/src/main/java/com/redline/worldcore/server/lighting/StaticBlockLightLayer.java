package com.redline.worldcore.server.lighting;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

/**
 * M9 static block-light MVP.
 *
 * <p>This layer computes a cube-owned block-light array for one 16x16x16 cube. The first implementation is deliberately
 * local to a single cube: it stores/rebuilds block light, exposes debug summaries, persists through Region3D, and marks
 * cube status as LIGHT_READY/FULL. Cross-cube propagation and vanilla visual light-engine injection are later M9.x/M10
 * work.</p>
 */
public final class StaticBlockLightLayer {
    public static final long FNV64_OFFSET = 0xcbf29ce484222325L;
    public static final long FNV64_PRIME = 0x100000001b3L;

    private StaticBlockLightLayer() {
    }

    public static RebuildResult rebuild(LevelCube cube) {
        cube.clearBlockLight();

        ArrayDeque<Node> queue = new ArrayDeque<>();
        int emitters = 0;
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    int emission = emission(cube.getBlockState(x, y, z));
                    if (emission <= 0) {
                        continue;
                    }
                    cube.setBlockLight(x, y, z, emission);
                    queue.addLast(new Node(x, y, z, emission));
                    emitters++;
                }
            }
        }

        int propagatedSteps = 0;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.level() <= 1) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                int nx = node.x() + direction.getStepX();
                int ny = node.y() + direction.getStepY();
                int nz = node.z() + direction.getStepZ();
                if (!CubePos.isLocal(nx) || !CubePos.isLocal(ny) || !CubePos.isLocal(nz)) {
                    continue;
                }

                BlockState targetState = cube.getBlockState(nx, ny, nz);
                int nextLevel = node.level() - lightDrop(targetState);
                if (nextLevel <= 0 || nextLevel <= cube.getBlockLight(nx, ny, nz)) {
                    continue;
                }

                cube.setBlockLight(nx, ny, nz, nextLevel);
                queue.addLast(new Node(nx, ny, nz, nextLevel));
                propagatedSteps++;
            }
        }

        if (!cube.status().isAtLeast(CubeStatus.LIGHT_READY)) {
            cube.setStatus(CubeStatus.LIGHT_READY);
        }

        return new RebuildResult(cube.cubePos(), emitters, propagatedSteps, StaticLightSummary.from(cube));
    }

    public static boolean hasEmitter(LevelCube cube) {
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    if (emission(cube.getBlockState(x, y, z)) > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean hasAnyBlockLight(LevelCube cube) {
        byte[] light = cube.copyBlockLight();
        for (byte value : light) {
            if (Byte.toUnsignedInt(value) > 0) {
                return true;
            }
        }
        return false;
    }

    public static boolean needsBootstrap(LevelCube cube) {
        return !hasAnyBlockLight(cube) && hasEmitter(cube);
    }

    public static int emission(BlockState state) {
        return Mth.clamp(state.getLightEmission(), 0, 15);
    }

    public static int lightDrop(BlockState state) {
        return Math.max(1, Mth.clamp(state.getLightDampening(), 0, 15));
    }

    private record Node(int x, int y, int z, int level) {
    }

    public record RebuildResult(CubePos cubePos, int emittingBlocks, int propagatedSteps, StaticLightSummary summary) {
        public String oneLine() {
            return "cube=" + cubePos.x() + " " + cubePos.y() + " " + cubePos.z()
                    + ", emitters=" + emittingBlocks
                    + ", propagatedSteps=" + propagatedSteps
                    + ", " + summary.oneLine();
        }
    }
}
