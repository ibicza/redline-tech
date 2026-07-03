package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.ServerCubeCache;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Synchronous debug-only invariant check for the M15 Java reference generator. */
public final class M15WorldgenAutoTest {
    public static Result run(ServerCubeCache cache) {
        List<String> problems = new ArrayList<>();
        M15WorldgenProfile profile = M15TerrainModel.profile(cache.generationContext());

        expect(profile.minY() == cache.settings().minBlockY(), problems, "profile minY mismatch");
        expect(profile.maxY() == cache.settings().maxBlockY(), problems, "profile maxY mismatch");
        expect(profile.seaLevel() == 0, problems, "seaLevel must be 0 in M15 cubic_test");

        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        for (int z = -256; z <= 256; z += 32) {
            for (int x = -256; x <= 256; x += 32) {
                int h = M15TerrainModel.surfaceHeight(cache.generationContext(), x, z);
                minHeight = Math.min(minHeight, h);
                maxHeight = Math.max(maxHeight, h);
            }
        }
        expect(maxHeight - minHeight >= 24, problems, "surface height variation too low: " + minHeight + ".." + maxHeight);
        expect(minHeight >= profile.lowestSurfaceY(), problems, "surface below profile minimum");
        expect(maxHeight <= profile.highestSurfaceY(), problems, "surface above profile maximum");
        M15TerrainSample spawn = M15TerrainModel.findSafeDrySpawn(cache.generationContext(), 0, 0, 1024);
        expect(M15TerrainModel.isSafeDrySpawn(cache.generationContext(), spawn.x(), spawn.z()), problems, "safe dry spawn search failed: " + spawn.oneLine());

        CubePos surfaceCube = CubePos.fromBlock(0, M15TerrainModel.surfaceHeight(cache.generationContext(), 0, 0), 0);
        CubeGenerationSummary first = CubeGenerationSummary.from(cache.generateTemporary(surfaceCube));
        CubeGenerationSummary second = CubeGenerationSummary.from(cache.generateTemporary(surfaceCube));
        expect(first.sameGeneratedData(second), problems, "temporary generation is not deterministic at " + surfaceCube);
        expect(first.air() > 0 && first.totalBlocks() == CubePos.BLOCK_COUNT, problems, "surface cube summary is invalid: " + first.oneLine());

        int bedrockY = profile.minY();
        BlockState bedrock = M15TerrainModel.stateFor(cache.generationContext(), 0, bedrockY, 0);
        expect(bedrock.is(Blocks.BEDROCK), problems, "bottom block is not bedrock: " + blockName(bedrock));

        int deepslateY = Math.min(profile.deepslateTopY(), profile.seaLevel() - 256);
        BlockState deepslate = M15TerrainModel.stateFor(cache.generationContext(), 16, deepslateY, 16);
        expect(deepslate.is(Blocks.DEEPSLATE) || deepslate.is(Blocks.LAVA), problems,
                "deep layer is not deepslate/lava at Y=" + deepslateY + ": " + blockName(deepslate));

        int stoneY = Math.max(profile.deepslateTopY() + 32, profile.seaLevel() - 64);
        BlockState stone = M15TerrainModel.stateFor(cache.generationContext(), 32, stoneY, 32);
        expect(stone.is(Blocks.STONE) || stone.is(Blocks.DIRT) || stone.is(Blocks.GRASS_BLOCK) || stone.is(Blocks.SANDSTONE), problems,
                "upper stone/surface layer unexpected at Y=" + stoneY + ": " + blockName(stone));

        int lavaFound = 0;
        int scanMinY = profile.bedrockTopY() + 8;
        int scanMaxY = Math.min(profile.lavaBeltTopY(), scanMinY + 384);
        outer:
        for (int y = scanMinY; y <= scanMaxY; y += 6) {
            for (int z = -96; z <= 96; z += 8) {
                for (int x = -96; x <= 96; x += 8) {
                    if (M15TerrainModel.stateFor(cache.generationContext(), x, y, z).is(Blocks.LAVA)) {
                        lavaFound++;
                        if (lavaFound >= 8) {
                            break outer;
                        }
                    }
                }
            }
        }
        expect(lavaFound > 0, problems, "deep lava belt scan did not find lava pockets");

        int surfaceY = M15TerrainModel.surfaceHeight(cache.generationContext(), 64, 64);
        BlockState top = M15TerrainModel.stateFor(cache.generationContext(), 64, surfaceY, 64);
        BlockState above = M15TerrainModel.stateFor(cache.generationContext(), 64, surfaceY + 1, 64);
        expect(!top.isAir(), problems, "surface top is air");
        expect(above.isAir(), problems, "block above surface is not air in M15.1 dry terrain: " + blockName(above));

        CubePos airCube = CubePos.fromBlock(0, profile.highestSurfaceY() + 64, 0);
        LevelCube air = cache.generateTemporary(airCube);
        CubeGenerationSummary airSummary = CubeGenerationSummary.from(air);
        expect(airSummary.air() == CubePos.BLOCK_COUNT, problems, "high cube must be all air: " + airSummary.oneLine());

        return new Result(problems.isEmpty(), problems, profile, minHeight, maxHeight, lavaFound, first.hash());
    }

    private static void expect(boolean value, List<String> problems, String problem) {
        if (!value) {
            problems.add(problem);
        }
    }

    private static String blockName(BlockState state) {
        return CubeGenerationHasher.blockStateDebugName(state);
    }

    public record Result(
            boolean passed,
            List<String> problems,
            M15WorldgenProfile profile,
            int sampledMinSurface,
            int sampledMaxSurface,
            int lavaFound,
            long surfaceHash
    ) {
        public String oneLine() {
            return "passed=" + passed
                    + ", sampledSurface=" + sampledMinSurface + ".." + sampledMaxSurface
                    + ", lavaFound=" + lavaFound
                    + ", surfaceHash=" + CubeGenerationHasher.shortHex(surfaceHash)
                    + ", profile={" + profile.oneLine() + "}";
        }
    }

    private M15WorldgenAutoTest() {
    }
}
