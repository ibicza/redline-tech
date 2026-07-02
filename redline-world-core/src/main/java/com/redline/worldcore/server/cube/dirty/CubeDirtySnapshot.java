package com.redline.worldcore.server.cube.dirty;

import com.redline.worldcore.api.pos.CubePos;

import java.util.Set;

/** Diagnostics snapshot for M14.1 dirty queues. */
public record CubeDirtySnapshot(
        int dirtyCubes,
        int contentQueue,
        int saveQueue,
        long totalMarked,
        long totalContentRebuilt,
        long totalSaved,
        int contentRebuiltLastTick,
        int savedLastTick,
        long contentMicrosLastTick,
        long contentMicrosMax,
        long saveMicrosLastTick,
        long saveMicrosMax,
        int maxContentPerTick,
        int maxSavesPerTick,
        int maxSaveMicrosPerTick,
        boolean saveBudgetHitLastTick,
        CubePos lastDirtyCube,
        Set<CubeDirtyFlag> lastDirtyFlags,
        CubePos lastContentCube,
        CubeContentSummary lastContentSummary,
        CubePos lastSavedCube
) {
    public static CubeDirtySnapshot empty(int maxContentPerTick, int maxSavesPerTick, int maxSaveMicrosPerTick) {
        return new CubeDirtySnapshot(0, 0, 0, 0L, 0L, 0L, 0, 0, 0L, 0L, 0L, 0L,
                maxContentPerTick, maxSavesPerTick, maxSaveMicrosPerTick, false, null, Set.of(), null,
                CubeContentSummary.empty(), null);
    }
}
