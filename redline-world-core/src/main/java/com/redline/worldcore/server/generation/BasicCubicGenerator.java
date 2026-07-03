package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.generation.CubeGenerationContext;
import com.redline.worldcore.api.generation.CubeGenerator;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M15 deterministic seed-only cube-first terrain generator.
 *
 * <p>This is the Java reference backend for cubic_test. It owns terrain directly in LevelCube data and does not ask
 * vanilla ChunkGenerator to create terrain. Later milestones add rivers, caves, geology, features, structures, atlas
 * guidance and optional native math, but they must keep this cube-first ownership model.</p>
 */
public final class BasicCubicGenerator implements CubeGenerator {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @Override
    public LevelCube generate(CubeGenerationContext context, CubePos cubePos) {
        LevelCube cube = new LevelCube(cubePos);
        M15WorldgenProfile profile = M15TerrainModel.profile(context);

        if (cubePos.minBlockY() > profile.highestSurfaceY() && cubePos.minBlockY() > profile.seaLevel()) {
            cube.fill(AIR);
            cube.setStatus(CubeStatus.FULL);
            return cube;
        }

        if (canUseSolidFastPath(context, profile, cubePos)) {
            cube.fill(fastPathState(profile, cubePos.minBlockY()));
            cube.setStatus(CubeStatus.FULL);
            return cube;
        }

        fillDetailed(context, cube, cubePos);
        cube.setStatus(CubeStatus.FULL);
        return cube;
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
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            int worldY = cubePos.minBlockY() + localY;
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                int worldZ = cubePos.minBlockZ() + localZ;
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    int worldX = cubePos.minBlockX() + localX;
                    cube.setBlockState(localX, localY, localZ, M15TerrainModel.stateFor(context, worldX, worldY, worldZ));
                }
            }
        }
    }

    private static boolean canUseSolidFastPath(CubeGenerationContext context, M15WorldgenProfile profile, CubePos cubePos) {
        int minY = cubePos.minBlockY();
        int maxY = cubePos.maxBlockY();
        if (maxY >= profile.lowestSurfaceY() - 8) {
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
