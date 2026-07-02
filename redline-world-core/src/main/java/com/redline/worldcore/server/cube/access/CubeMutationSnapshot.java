package com.redline.worldcore.server.cube.access;

import com.redline.worldcore.api.pos.CubeLocalPos;
import com.redline.worldcore.api.pos.CubePos;

/** Runtime diagnostics for the M14.0 cube mutation pipeline. */
public record CubeMutationSnapshot(
        long totalMutations,
        long totalApplied,
        long totalChanged,
        long totalUnchanged,
        long totalRejected,
        long totalStatusPromoted,
        long totalHolderLoaded,
        long totalHolderGenerated,
        long totalSaved,
        long totalStaticLightRebuilt,
        long totalSkyLightRebuilt,
        long totalSkyLightQueued,
        long lastElapsedMicros,
        long maxElapsedMicros,
        boolean lastApplied,
        boolean lastChanged,
        CubeMutationOrigin lastOrigin,
        CubePos lastCube,
        CubeLocalPos lastLocal,
        String lastReason
) {
    public static CubeMutationSnapshot empty() {
        return new CubeMutationSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, false, false, CubeMutationOrigin.UNKNOWN, null, null, "none");
    }
}
