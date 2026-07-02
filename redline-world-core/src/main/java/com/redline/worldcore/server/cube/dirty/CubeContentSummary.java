package com.redline.worldcore.server.cube.dirty;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Set;

/** Runtime summary of content inside one 16x16x16 cube. */
public record CubeContentSummary(
        Set<CubeContentFlag> flags,
        int nonAirBlocks,
        int emittingBlocks,
        int blockLitBlocks,
        int skyLitBlocks
) {
    public CubeContentSummary {
        flags = flags.isEmpty() ? Set.of() : Set.copyOf(flags);
    }

    public static CubeContentSummary empty() {
        return new CubeContentSummary(Set.of(CubeContentFlag.EMPTY), 0, 0, 0, 0);
    }

    public static CubeContentSummary from(LevelCube cube) {
        int nonAir = 0;
        int emitters = 0;
        int blockLit = 0;
        int skyLit = 0;

        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    BlockState state = cube.getBlockState(x, y, z);
                    if (!state.isAir()) {
                        nonAir++;
                    }
                    if (state.getLightEmission() > 0) {
                        emitters++;
                    }
                    if (cube.getBlockLight(x, y, z) > 0) {
                        blockLit++;
                    }
                    if (cube.getSkyLight(x, y, z) > 0) {
                        skyLit++;
                    }
                }
            }
        }

        EnumSet<CubeContentFlag> flags = EnumSet.noneOf(CubeContentFlag.class);
        if (nonAir == 0) {
            flags.add(CubeContentFlag.EMPTY);
        } else {
            flags.add(CubeContentFlag.HAS_NON_AIR_BLOCKS);
        }
        if (emitters > 0) {
            flags.add(CubeContentFlag.HAS_LIGHT_EMITTERS);
        }
        if (blockLit > 0) {
            flags.add(CubeContentFlag.HAS_BLOCK_LIGHT);
        }
        if (skyLit > 0) {
            flags.add(CubeContentFlag.HAS_SKY_LIGHT);
        }
        return new CubeContentSummary(flags, nonAir, emitters, blockLit, skyLit);
    }

    public String compact() {
        return "nonAir=" + nonAirBlocks
                + " emit=" + emittingBlocks
                + " blockLit=" + blockLitBlocks
                + " skyLit=" + skyLitBlocks
                + " flags=" + flags;
    }
}
