package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.ServerCubeCache;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Debug-only invariant check for M16 static safe hydrology. */
public final class M16WaterAutoTest {
    public static Result run(ServerCubeCache cache) {
        List<String> problems = new ArrayList<>();
        M15WorldgenProfile profile = M15TerrainModel.profile(cache.generationContext());

        M16WaterModel.LocateResult ocean = M16WaterModel.locateNearest(cache.generationContext(), M16WaterType.OCEAN, 0, 0, 4096);
        M16WaterModel.LocateResult river = M16WaterModel.locateNearest(cache.generationContext(), M16WaterType.RIVER, 0, 0, 4096);
        M16WaterModel.LocateResult lake = M16WaterModel.locateNearest(cache.generationContext(), M16WaterType.LAKE, 0, 0, 4096);
        M16WaterModel.LocateResult waterfall = M16WaterModel.locateNearest(cache.generationContext(), M16WaterType.WATERFALL, 0, 0, 8192);

        expect(ocean.found(), problems, "no ocean found within 4096 blocks");
        expect(river.found(), problems, "no river found within 4096 blocks");
        expect(lake.found(), problems, "no lake found within 4096 blocks");

        int waterBlocks = 0;
        int unsafeSand = 0;
        int riverDepth = river.found() ? river.sample().waterDepth() : 0;
        int greatOrCanyonDepth = 0;
        for (int z = -768; z <= 768; z += 16) {
            for (int x = -768; x <= 768; x += 16) {
                M16WaterSample sample = M16WaterModel.sample(cache.generationContext(), x, z);
                if (sample.hasWater()) {
                    BlockState waterState = M15TerrainModel.stateFor(cache.generationContext(), x, sample.waterSurfaceY(), z);
                    if (waterState.is(Blocks.WATER)) {
                        waterBlocks++;
                    }
                    BlockState bed = M15TerrainModel.stateFor(cache.generationContext(), x, sample.effectiveSurfaceY(), z);
                    if (bed.is(Blocks.SAND)) {
                        BlockState support = M15TerrainModel.stateFor(cache.generationContext(), x, sample.effectiveSurfaceY() - 1, z);
                        if (support.isAir() || support.is(Blocks.WATER) || support.is(Blocks.LAVA)) {
                            unsafeSand++;
                        }
                    }
                    if (sample.riverProfile() == M16RiverProfile.GREAT_RIVER || sample.riverProfile() == M16RiverProfile.CANYON_RIVER) {
                        greatOrCanyonDepth = Math.max(greatOrCanyonDepth, sample.waterDepth());
                    }
                }
            }
        }
        expect(waterBlocks > 0, problems, "sample scan did not find water block states");
        expect(unsafeSand == 0, problems, "found generated sand without solid support: " + unsafeSand);
        expect(riverDepth >= 1, problems, "located river has invalid depth: " + riverDepth);

        if (river.found()) {
            M16WaterSample s = river.sample();
            CubePos cubePos = CubePos.fromBlock(s.x(), s.waterSurfaceY(), s.z());
            LevelCube first = cache.generateTemporary(cubePos);
            LevelCube second = cache.generateTemporary(cubePos);
            expect(CubeGenerationSummary.from(first).sameGeneratedData(CubeGenerationSummary.from(second)), problems,
                    "river cube generation is not deterministic at " + cubePos);
        }

        return new Result(problems.isEmpty(), problems, ocean, river, lake, waterfall, waterBlocks, unsafeSand, greatOrCanyonDepth, profile);
    }

    private static void expect(boolean value, List<String> problems, String problem) {
        if (!value) {
            problems.add(problem);
        }
    }

    public record Result(
            boolean passed,
            List<String> problems,
            M16WaterModel.LocateResult ocean,
            M16WaterModel.LocateResult river,
            M16WaterModel.LocateResult lake,
            M16WaterModel.LocateResult waterfall,
            int sampledWaterBlocks,
            int unsafeSand,
            int greatOrCanyonDepth,
            M15WorldgenProfile profile
    ) {
        public String oneLine() {
            return "passed=" + passed
                    + ", waterBlocks=" + sampledWaterBlocks
                    + ", unsafeSand=" + unsafeSand
                    + ", greatOrCanyonDepth=" + greatOrCanyonDepth
                    + ", ocean={" + ocean.oneLine() + "}"
                    + ", river={" + river.oneLine() + "}"
                    + ", lake={" + lake.oneLine() + "}"
                    + ", waterfall={" + waterfall.oneLine() + "}"
                    + ", profile={" + profile.oneLine() + "}";
        }
    }

    private M16WaterAutoTest() {
    }
}
