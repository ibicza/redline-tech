package com.redline.worldcore.server.storage;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.pos.Region3DPos;
import com.redline.worldcore.api.storage.CubeStorage;
import com.redline.worldcore.api.storage.CubeStorageException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Region3D-backed cube storage. This is the first durable storage layer for M3. */
public final class CubeRegionStorage implements CubeStorage {
    private final Path rootDirectory;
    private final Path regionDirectory;
    private final CubeSerializer serializer;
    private final Map<CubePos, LevelCube> loadedCubes = new ConcurrentHashMap<>();
    private final Map<Region3DPos, Region3DFile> regionFiles = new ConcurrentHashMap<>();

    public CubeRegionStorage(Path rootDirectory) {
        this(rootDirectory, new CubeSerializer());
    }

    public CubeRegionStorage(Path rootDirectory, CubeSerializer serializer) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        this.regionDirectory = rootDirectory.resolve("region3d");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    public Path rootDirectory() {
        return rootDirectory;
    }

    public Path regionDirectory() {
        return regionDirectory;
    }

    public Path regionPath(Region3DPos regionPos) {
        return regionDirectory.resolve(regionPos.fileName());
    }

    @Override
    public Optional<LevelCube> get(CubePos cubePos) {
        LevelCube loaded = loadedCubes.get(cubePos);
        if (loaded != null) {
            return Optional.of(loaded);
        }

        try {
            Optional<LevelCube> loadedFromDisk = regionFile(cubePos.regionPos()).readCube(cubePos);
            loadedFromDisk.ifPresent(cube -> loadedCubes.put(cubePos, cube));
            return loadedFromDisk;
        } catch (IOException exception) {
            throw new CubeStorageException("Failed to read cube " + cubePos, exception);
        }
    }

    @Override
    public LevelCube getOrCreate(CubePos cubePos) {
        return get(cubePos).orElseGet(() -> {
            LevelCube cube = new LevelCube(cubePos);
            loadedCubes.put(cubePos, cube);
            return cube;
        });
    }

    @Override
    public void put(LevelCube cube) {
        Objects.requireNonNull(cube, "cube");
        loadedCubes.put(cube.cubePos(), cube);
        flush(cube);
    }

    public void flush(LevelCube cube) {
        flushOnly(cube);
    }

    /** Writes a detached cube snapshot to Region3D without replacing the live storage memory cache. */
    public void flushOnly(LevelCube cube) {
        try {
            regionFile(cube.cubePos().regionPos()).writeCube(cube);
        } catch (IOException exception) {
            throw new CubeStorageException("Failed to write cube " + cube.cubePos(), exception);
        }
    }

    public void flushAll() {
        for (LevelCube cube : loadedCubes.values()) {
            flush(cube);
        }
    }

    @Override
    public boolean remove(CubePos cubePos) {
        loadedCubes.remove(cubePos);
        try {
            return regionFile(cubePos.regionPos()).removeCube(cubePos);
        } catch (IOException exception) {
            throw new CubeStorageException("Failed to remove cube " + cubePos, exception);
        }
    }

    @Override
    public int loadedCount() {
        return loadedCubes.size();
    }

    /**
     * Removes a cube only from the in-memory storage cache.
     *
     * <p>This is not a disk deletion. M6 ServerCubeCache uses it when a cube holder leaves the runtime cache but should
     * remain persisted in its Region3D file if it was saved before.</p>
     */
    public Optional<LevelCube> unloadFromMemory(CubePos cubePos) {
        return Optional.ofNullable(loadedCubes.remove(cubePos));
    }

    public int usedEntries(Region3DPos regionPos) {
        try {
            return regionFile(regionPos).usedEntries();
        } catch (IOException exception) {
            throw new CubeStorageException("Failed to inspect region " + regionPos, exception);
        }
    }

    private Region3DFile regionFile(Region3DPos regionPos) {
        return regionFiles.computeIfAbsent(regionPos, pos -> new Region3DFile(regionPath(pos), pos, serializer));
    }
}
