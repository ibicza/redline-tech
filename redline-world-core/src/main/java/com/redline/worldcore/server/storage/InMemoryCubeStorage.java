package com.redline.worldcore.server.storage;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.storage.CubeStorage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Temporary M2 storage. Region3D disk IO replaces this in M3. */
public final class InMemoryCubeStorage implements CubeStorage {
    private final Map<CubePos, LevelCube> cubes = new ConcurrentHashMap<>();

    @Override
    public Optional<LevelCube> get(CubePos cubePos) {
        return Optional.ofNullable(cubes.get(cubePos));
    }

    @Override
    public LevelCube getOrCreate(CubePos cubePos) {
        return cubes.computeIfAbsent(cubePos, LevelCube::new);
    }

    @Override
    public void put(LevelCube cube) {
        cubes.put(cube.cubePos(), cube);
    }

    @Override
    public boolean remove(CubePos cubePos) {
        return cubes.remove(cubePos) != null;
    }

    @Override
    public int loadedCount() {
        return cubes.size();
    }
}
