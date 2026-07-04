package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.generation.CubeGenerationContext;
import com.redline.worldcore.api.generation.CubeGenerator;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M16 deterministic seed-only cube-first terrain + static hydrology generator.
 *
 * <p>This is the Java reference backend for cubic_test. It owns terrain directly in LevelCube data and does not ask
 * vanilla ChunkGenerator to create terrain. Later milestones add rivers, caves, geology, features, structures, atlas
 * guidance and optional native math, but they must keep this cube-first ownership model.</p>
 */
public final class BasicCubicGenerator implements CubeGenerator {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @Override
    public LevelCube generate(CubeGenerationContext context, CubePos cubePos) {
        long profileStart = RuntimeProfiler.markStart();
        M15TerrainModel.beginCubeGeneration();
        M16WaterModel.beginCubeGeneration();
        try {
            LevelCube cube = new LevelCube(cubePos);
            M15WorldgenProfile profile = M15TerrainModel.profile(context);

            if (cubePos.minBlockY() > profile.highestSurfaceY() && cubePos.minBlockY() > profile.seaLevel()) {
                long fastStart = RuntimeProfiler.markStart();
                cube.fill(AIR);
                RuntimeProfiler.recordSince("worldgen.fast_air_fill", fastStart);
                cube.setStatus(CubeStatus.FULL);
                RuntimeProfiler.addCount("worldgen.fast_air_cubes", 1);
                return cube;
            }

            if (canUseSolidFastPath(context, profile, cubePos)) {
                long fastStart = RuntimeProfiler.markStart();
                cube.fill(fastPathState(profile, cubePos.minBlockY()));
                RuntimeProfiler.recordSince("worldgen.fast_solid_fill", fastStart);
                cube.setStatus(CubeStatus.FULL);
                RuntimeProfiler.addCount("worldgen.fast_solid_cubes", 1);
                return cube;
            }

            fillDetailed(context, cube, cubePos);
            cube.setStatus(CubeStatus.FULL);
            RuntimeProfiler.addCount("worldgen.detailed_cubes", 1);
            return cube;
        } finally {
            M16WaterModel.endCubeGeneration();
            M15TerrainModel.endCubeGeneration();
            RuntimeProfiler.recordSince("worldgen.generate_cube_total", profileStart);
        }
    }

    /** Backwards-compatible debug helper: uses the M15 default cubic_test height profile and a seed. */
    public static int surfaceHeight(long seed, int blockX, int blockZ) {
        return surfaceHeight(new CubeGenerationContext(CubicDimensionSettings.defaults(), seed), blockX, blockZ);
    }

    public static int surfaceHeight(CubeGenerationContext context, int blockX, int blockZ) {
        return M15TerrainModel.surfaceHeight(context, blockX, blockZ);
    }

    public static M15TerrainSample sample(CubeGenerationContext context, int blockX, int blockZ) {
        return M15TerrainModel.sample(context, blockX, blockZ);
    }

    public static BlockState sampleBlock(CubeGenerationContext context, int blockX, int blockY, int blockZ) {
        return M15TerrainModel.stateFor(context, blockX, blockY, blockZ);
    }

    private static void fillDetailed(CubeGenerationContext context, LevelCube cube, CubePos cubePos) {
        long detailedStart = RuntimeProfiler.markStart();
        M15TerrainSample[][] terrainColumns = new M15TerrainSample[CubePos.SIZE][CubePos.SIZE];
        M16WaterColumnShape[][] waterColumns = new M16WaterColumnShape[CubePos.SIZE][CubePos.SIZE];

        long terrainStart = RuntimeProfiler.markStart();
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            int worldZ = cubePos.minBlockZ() + localZ;
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                int worldX = cubePos.minBlockX() + localX;
                terrainColumns[localZ][localX] = M15TerrainModel.sampleDry(context, worldX, worldZ);
            }
        }
        RuntimeProfiler.recordSince("worldgen.columns_terrain", terrainStart);
        RuntimeProfiler.addCount("worldgen.terrain_columns", CubePos.SIZE * CubePos.SIZE);

        long waterStart = RuntimeProfiler.markStart();
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                waterColumns[localZ][localX] = M16WaterModel.columnShape(context, terrainColumns[localZ][localX]);
            }
        }
        RuntimeProfiler.recordSince("worldgen.columns_water", waterStart);
        RuntimeProfiler.addCount("worldgen.water_columns", CubePos.SIZE * CubePos.SIZE);

        long fillStart = RuntimeProfiler.markStart();
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            int worldY = cubePos.minBlockY() + localY;
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    cube.setBlockState(localX, localY, localZ, M15TerrainModel.stateFor(context, terrainColumns[localZ][localX], waterColumns[localZ][localX], worldY));
                }
            }
        }
        RuntimeProfiler.recordSince("worldgen.vertical_block_fill", fillStart);
        RuntimeProfiler.addCount("worldgen.blocks_filled", CubePos.SIZE * CubePos.SIZE * CubePos.SIZE);
        RuntimeProfiler.recordSince("worldgen.fill_detailed_total", detailedStart);
    }

    private static boolean canUseSolidFastPath(CubeGenerationContext context, M15WorldgenProfile profile, CubePos cubePos) {
        int minY = cubePos.minBlockY();
        int maxY = cubePos.maxBlockY();
        // M16 rivers/canyons can carve noticeably below the dry M15 lowest-surface envelope.
        // Keep near-surface/waterbelt cubes detailed so water bodies are never overwritten by a solid fast path.
        if (maxY >= profile.lowestSurfaceY() - 160) {
            return false;
        }
        if (M16WaterModel.mayContainWater(context, profile, cubePos.x(), cubePos.y(), cubePos.z())) {
            return false;
        }
        if (maxY <= profile.bedrockTopY()) {
            return true;
        }
        if (minY <= profile.bedrockTopY()) {
            return false;
        }
        if (minY <= profile.lavaBeltTopY() && M15TerrainModel.mayContainDeepLavaPocket(context.seed(), profile, cubePos.x(), cubePos.y(), cubePos.z())) {
            return false;
        }
        return (maxY <= profile.deepslateTopY()) || (minY > profile.deepslateTopY());
    }

    private static BlockState fastPathState(M15WorldgenProfile profile, int minY) {
        if (minY <= profile.bedrockTopY()) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        return profile.inDeepslateLayer(minY) ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }
}
