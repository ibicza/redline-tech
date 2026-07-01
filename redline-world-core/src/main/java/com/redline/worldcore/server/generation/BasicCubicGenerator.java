package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.generation.CubeGenerationContext;
import com.redline.worldcore.api.generation.CubeGenerator;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M7 deterministic cube-first terrain generator.
 *
 * <p>This is deliberately small and stable: it proves that the runtime cache can create real generated LevelCube data
 * without asking vanilla ChunkGenerator to own the world. Later milestones replace the simple height/cave/geology rules
 * with the full CubicWorldgenPipeline.</p>
 */
public final class BasicCubicGenerator implements CubeGenerator {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState COAL_ORE = Blocks.COAL_ORE.defaultBlockState();
    private static final BlockState IRON_ORE = Blocks.IRON_ORE.defaultBlockState();
    private static final BlockState COPPER_ORE = Blocks.COPPER_ORE.defaultBlockState();

    @Override
    public LevelCube generate(CubeGenerationContext context, CubePos cubePos) {
        LevelCube cube = new LevelCube(cubePos);

        // Fast paths keep the first player cuboid cheap. Only cubes near terrain/cave/geology boundaries need per-block fill.
        int minY = cubePos.minBlockY();
        int maxY = cubePos.maxBlockY();
        int maxSurface = 6;
        int minSurface = -6;

        if (minY > maxSurface) {
            cube.fill(AIR);
            cube.setStatus(CubeStatus.FULL);
            return cube;
        }
        if (maxY < minSurface && !mayContainCaveOrOre(context.seed(), cubePos)) {
            cube.fill(stoneForY(minY));
            cube.setStatus(CubeStatus.FULL);
            return cube;
        }

        fillDetailed(context, cube, cubePos);
        cube.setStatus(CubeStatus.FULL);
        return cube;
    }

    private static void fillDetailed(CubeGenerationContext context, LevelCube cube, CubePos cubePos) {
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            int worldY = cubePos.minBlockY() + localY;
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                int worldZ = cubePos.minBlockZ() + localZ;
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    int worldX = cubePos.minBlockX() + localX;
                    int surfaceY = surfaceHeight(context.seed(), worldX, worldZ);
                    BlockState state = stateFor(context.seed(), worldX, worldY, worldZ, surfaceY);
                    cube.setBlockState(localX, localY, localZ, state);
                }
            }
        }
    }

    private static BlockState stateFor(long seed, int worldX, int worldY, int worldZ, int surfaceY) {
        if (worldY > surfaceY) {
            return AIR;
        }
        if (worldY == surfaceY) {
            return GRASS;
        }
        if (worldY >= surfaceY - 3) {
            return DIRT;
        }
        if (isCaveAir(seed, worldX, worldY, worldZ)) {
            return AIR;
        }
        BlockState ore = oreState(seed, worldX, worldY, worldZ);
        if (ore != null) {
            return ore;
        }
        return stoneForY(worldY);
    }

    /** Small smooth-ish deterministic terrain around Y=0. */
    public static int surfaceHeight(long seed, int blockX, int blockZ) {
        int coarseX = Math.floorDiv(blockX, 8);
        int coarseZ = Math.floorDiv(blockZ, 8);
        int n0 = hashToRange(seed, coarseX, coarseZ, 0, 9) - 4;
        int n1 = hashToRange(seed ^ 0x5DEECE66DL, Math.floorDiv(blockX, 24), Math.floorDiv(blockZ, 24), 0, 7) - 3;
        return clamp(n0 + n1, -6, 6);
    }

    private static BlockState stoneForY(int worldY) {
        return worldY < -48 ? DEEPSLATE : STONE;
    }

    private static boolean mayContainCaveOrOre(long seed, CubePos cubePos) {
        if (cubePos.y() >= 0) {
            return true;
        }
        if (cubePos.y() < -64) {
            return false;
        }
        int caveChance = hashToRange(seed, cubePos.x(), cubePos.y(), cubePos.z(), 10);
        int oreChance = hashToRange(seed ^ 0x9E3779B97F4A7C15L, cubePos.x(), cubePos.y(), cubePos.z(), 6);
        return caveChance == 0 || oreChance == 0;
    }

    /** Cheap, chunky M7 cave openings. Full cave networks come later, after column indexes/connectivity. */
    private static boolean isCaveAir(long seed, int worldX, int worldY, int worldZ) {
        if (worldY > -4 || worldY < -96) {
            return false;
        }
        int cellX = Math.floorDiv(worldX, 12);
        int cellY = Math.floorDiv(worldY, 8);
        int cellZ = Math.floorDiv(worldZ, 12);
        if (hashToRange(seed ^ 0xC0FFEE1234ABL, cellX, cellY, cellZ, 7) != 0) {
            return false;
        }

        int centerX = cellX * 12 + 6 + hashToRange(seed, cellX, cellY, cellZ, 5) - 2;
        int centerY = cellY * 8 + 4 + hashToRange(seed ^ 31L, cellX, cellY, cellZ, 3) - 1;
        int centerZ = cellZ * 12 + 6 + hashToRange(seed ^ 63L, cellX, cellY, cellZ, 5) - 2;
        int dx = worldX - centerX;
        int dy = worldY - centerY;
        int dz = worldZ - centerZ;
        return dx * dx + dy * dy * 2 + dz * dz <= 22;
    }

    private static BlockState oreState(long seed, int worldX, int worldY, int worldZ) {
        if (worldY > 16 || worldY < -96) {
            return null;
        }
        int hash = hashToRange(seed ^ 0x51EDBEEFCAFEL, worldX, worldY, worldZ, 2048);
        if (worldY <= 8 && hash < 3) {
            return COPPER_ORE;
        }
        if (worldY <= 0 && hash >= 3 && hash < 5) {
            return IRON_ORE;
        }
        if (worldY <= 32 && hash == 5) {
            return COAL_ORE;
        }
        return null;
    }

    private static int hashToRange(long seed, int x, int y, int z, int bound) {
        long value = seed;
        value ^= x * 0x9E3779B97F4A7C15L;
        value ^= y * 0xC2B2AE3D27D4EB4FL;
        value ^= z * 0x165667B19E3779F9L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return Math.floorMod((int) value, bound);
    }


    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
