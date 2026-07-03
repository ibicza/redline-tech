package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Compact debug summary for one cube. It is intentionally command-friendly, not a gameplay data structure. */
public record CubeGenerationSummary(
        CubePos cubePos,
        CubeStatus status,
        int air,
        int grass,
        int dirt,
        int stone,
        int deepslate,
        int bedrock,
        int sand,
        int snow,
        int water,
        int lava,
        int ores,
        int other,
        long hash
) {
    public static CubeGenerationSummary from(LevelCube cube) {
        int air = 0;
        int grass = 0;
        int dirt = 0;
        int stone = 0;
        int deepslate = 0;
        int bedrock = 0;
        int sand = 0;
        int snow = 0;
        int water = 0;
        int lava = 0;
        int ores = 0;
        int other = 0;

        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    BlockState state = cube.getBlockState(localX, localY, localZ);
                    if (state.isAir()) {
                        air++;
                    } else if (state.getBlock() == Blocks.GRASS_BLOCK) {
                        grass++;
                    } else if (state.getBlock() == Blocks.DIRT) {
                        dirt++;
                    } else if (state.getBlock() == Blocks.STONE) {
                        stone++;
                    } else if (state.getBlock() == Blocks.DEEPSLATE) {
                        deepslate++;
                    } else if (state.getBlock() == Blocks.BEDROCK) {
                        bedrock++;
                    } else if (state.getBlock() == Blocks.SAND || state.getBlock() == Blocks.SANDSTONE || state.getBlock() == Blocks.GRAVEL) {
                        sand++;
                    } else if (state.getBlock() == Blocks.SNOW_BLOCK) {
                        snow++;
                    } else if (state.getBlock() == Blocks.WATER) {
                        water++;
                    } else if (state.getBlock() == Blocks.LAVA) {
                        lava++;
                    } else if (state.getBlock() == Blocks.COAL_ORE
                            || state.getBlock() == Blocks.IRON_ORE
                            || state.getBlock() == Blocks.COPPER_ORE) {
                        ores++;
                    } else {
                        other++;
                    }
                }
            }
        }

        return new CubeGenerationSummary(
                cube.cubePos(),
                cube.status(),
                air,
                grass,
                dirt,
                stone,
                deepslate,
                bedrock,
                sand,
                snow,
                water,
                lava,
                ores,
                other,
                CubeGenerationHasher.hash(cube)
        );
    }

    public int totalBlocks() {
        return air + grass + dirt + stone + deepslate + bedrock + sand + snow + water + lava + ores + other;
    }

    public String counts() {
        return "air=" + air
                + ", grass=" + grass
                + ", dirt=" + dirt
                + ", stone=" + stone
                + ", deepslate=" + deepslate
                + ", bedrock=" + bedrock
                + ", sandLike=" + sand
                + ", snow=" + snow
                + ", water=" + water
                + ", lava=" + lava
                + ", ores=" + ores
                + ", other=" + other;
    }

    public String oneLine() {
        return "status=" + status
                + ", " + counts()
                + ", total=" + totalBlocks()
                + ", hash=" + CubeGenerationHasher.shortHex(hash);
    }

    public boolean sameGeneratedData(CubeGenerationSummary other) {
        return other != null
                && status == other.status
                && air == other.air
                && grass == other.grass
                && dirt == other.dirt
                && stone == other.stone
                && deepslate == other.deepslate
                && bedrock == other.bedrock
                && sand == other.sand
                && snow == other.snow
                && water == other.water
                && lava == other.lava
                && ores == other.ores
                && this.other == other.other
                && hash == other.hash;
    }
}
