package com.redline.worldcore.server.storage;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;

import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * M3 IO facade.
 *
 * <p>For the first durable storage step this worker executes immediately. A real queue/thread budget can replace the
 * internals later without changing call sites.</p>
 */
public final class CubeIoWorker implements AutoCloseable {
    private final CubeRegionStorage storage;

    public CubeIoWorker(CubeRegionStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<Optional<LevelCube>> load(CubePos cubePos) {
        return CompletableFuture.completedFuture(storage.get(cubePos));
    }

    public CompletableFuture<Void> save(LevelCube cube) {
        storage.put(cube);
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Boolean> remove(CubePos cubePos) {
        return CompletableFuture.completedFuture(storage.remove(cubePos));
    }

    @Override
    public void close() {
        storage.flushAll();
    }
}
