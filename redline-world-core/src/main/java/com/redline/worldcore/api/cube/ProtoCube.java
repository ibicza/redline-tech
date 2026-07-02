package com.redline.worldcore.api.cube;

import com.redline.worldcore.api.pos.CubePos;

/** Mutable cube used by generation before it becomes a fully usable LevelCube. */
public final class ProtoCube extends AbstractArrayCube {
    public ProtoCube(CubePos cubePos) {
        super(cubePos, CubeStatus.EMPTY);
    }

    public LevelCube promoteToLevelCube() {
        LevelCube levelCube = new LevelCube(cubePos());
        levelCube.setStatus(status());
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    levelCube.setBlockState(x, y, z, getBlockState(x, y, z));
                    levelCube.setBlockLight(x, y, z, getBlockLight(x, y, z));
                    levelCube.setSkyLight(x, y, z, getSkyLight(x, y, z));
                }
            }
        }
        return levelCube;
    }
}
