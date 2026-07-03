package com.redline.worldcore.server.cube.dirty;

import com.redline.worldcore.api.pos.CubePos;

import java.util.Set;

/** Diagnostics snapshot for M14.1 dirty queues. */
public record CubeDirtySnapshot(
        int dirtyCubes,
        int storageDirtyCubes,
        int clientSyncDirtyCubes,
        int lightDirtyCubes,
        int contentDirtyCubes,
        int contentQueue,
        int saveQueue,
        int saveInFlight,
        long totalMarked,
        long totalContentRebuilt,
        long totalSaved,
        long totalSaveSubmitted,
        long totalSaveFailed,
        long totalSaveCompletionsDrained,
        int contentRebuiltLastTick,
        int savedLastTick,
        int saveSubmittedLastTick,
        int saveFailedLastTick,
        int saveCompletionsDrainedLastTick,
        long contentMicrosLastTick,
        long contentMicrosMax,
        long saveMicrosLastTick,
        long saveMicrosMax,
        long completionDrainMicrosLastTick,
        long completionDrainMicrosMax,
        int maxContentPerTick,
        int maxSavesPerTick,
        int maxSaveMicrosPerTick,
        int maxSaveCompletionsPerTick,
        int maxCompletionDrainMicrosPerTick,
        boolean saveBudgetHitLastTick,
        boolean saveIdleSkipLastTick,
        boolean completionBudgetHitLastTick,
        int saveCooldownTicks,
        String saveLastReason,
        CubePos lastDirtyCube,
        Set<CubeDirtyFlag> lastDirtyFlags,
        CubePos lastContentCube,
        CubeContentSummary lastContentSummary,
        CubePos lastSavedCube,
        CubePos lastSubmittedCube,
        CubePos lastFailedCube
) {
    public CubeDirtySnapshot {
        saveLastReason = saveLastReason == null ? "none" : saveLastReason;
        lastDirtyFlags = lastDirtyFlags == null ? Set.of() : Set.copyOf(lastDirtyFlags);
    }

    public static CubeDirtySnapshot empty(int maxContentPerTick, int maxSavesPerTick, int maxSaveMicrosPerTick) {
        return new CubeDirtySnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0, 0, 0, 0,
                0L, 0L, 0L, 0L, 0L, 0L, maxContentPerTick, maxSavesPerTick, maxSaveMicrosPerTick, 0, 0,
                false, false, false, 0, "none", null, Set.of(), null, CubeContentSummary.empty(), null, null, null);
    }
}
