package com.redline.worldcore.api.storage;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;

import java.util.Optional;

public interface CubeStorage {
    Optional<LevelCube> get(CubePos cubePos);

    LevelCube getOrCreate(CubePos cubePos);

    void put(LevelCube cube);

    boolean remove(CubePos cubePos);

    int loadedCount();
}
