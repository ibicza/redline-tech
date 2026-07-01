package com.redline.worldcore.server.cube;

/** Per-tick work counters for the M6/M7 cube cache. */
public record CubeLoadingTickResult(
        long gameTime,
        int requestedCubes,
        int queuedLoads,
        int loadedThisTick,
        int generatedThisTick,
        int unloadedThisTick,
        boolean requestLimitHit
) {
}
