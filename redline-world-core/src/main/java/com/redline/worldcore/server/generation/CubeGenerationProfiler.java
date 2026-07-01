package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.ServerCubeCache;

/** Small synchronous debug benchmark. It never inserts generated cubes into ServerCubeCache or Region3D. */
public final class CubeGenerationProfiler {
    public static Result benchmark(ServerCubeCache cache, CubePos center, int radius) {
        int generated = 0;
        long minNanos = Long.MAX_VALUE;
        long maxNanos = 0L;
        long startAll = System.nanoTime();

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    CubePos cubePos = center.offset(dx, dy, dz);
                    if (!cache.settings().containsCubeY(cubePos.y())) {
                        continue;
                    }
                    long start = System.nanoTime();
                    LevelCube ignored = cache.generateTemporary(cubePos);
                    if (ignored.status() == null) {
                        throw new IllegalStateException("Generated cube returned null status: " + cubePos);
                    }
                    long elapsed = System.nanoTime() - start;
                    minNanos = Math.min(minNanos, elapsed);
                    maxNanos = Math.max(maxNanos, elapsed);
                    generated++;
                }
            }
        }

        long totalNanos = System.nanoTime() - startAll;
        if (generated == 0) {
            minNanos = 0L;
        }
        return new Result(generated, totalNanos, minNanos, maxNanos);
    }

    public record Result(int generatedCubes, long totalNanos, long minNanos, long maxNanos) {
        public double totalMillis() {
            return totalNanos / 1_000_000.0D;
        }

        public double averageMillisPerCube() {
            return generatedCubes == 0 ? 0.0D : totalMillis() / generatedCubes;
        }

        public double minMillis() {
            return minNanos / 1_000_000.0D;
        }

        public double maxMillis() {
            return maxNanos / 1_000_000.0D;
        }
    }

    private CubeGenerationProfiler() {
    }
}
