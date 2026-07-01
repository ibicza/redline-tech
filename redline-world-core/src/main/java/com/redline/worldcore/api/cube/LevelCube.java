package com.redline.worldcore.api.cube;

import com.redline.worldcore.api.pos.CubePos;

/** Runtime cube. Later this class will own light arrays, block entities, ticks and dirty flags. */
public final class LevelCube extends AbstractArrayCube {
    public LevelCube(CubePos cubePos) {
        super(cubePos, CubeStatus.FULL);
    }
}
