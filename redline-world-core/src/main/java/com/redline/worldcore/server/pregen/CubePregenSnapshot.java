package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.pos.CubePos;

/** Immutable debug snapshot for commands and client overlay. */
public record CubePregenSnapshot(
        boolean running,
        boolean paused,
        int queuedCubes,
        long activeTotalCubes,
        long activeProcessedCubes,
        long totalStartedJobs,
        long totalCompletedJobs,
        long totalProcessedCubes,
        long totalGeneratedCubes,
        long totalSkippedCubes,
        long totalFailedCubes,
        int lastTickProcessed,
        int lastTickGenerated,
        int lastTickSkipped,
        int lastTickFailed,
        long lastTickMicros,
        long maxTickMicros,
        int maxCubesPerTick,
        int maxMillisPerTick,
        CubeStatus targetStatus,
        CubePos activeMin,
        CubePos activeMax,
        String activeJobId,
        String lastError
) {
    public static CubePregenSnapshot empty(CubePregenBudget budget) {
        return new CubePregenSnapshot(
                false,
                false,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0,
                0,
                0,
                0,
                0L,
                0L,
                budget.maxCubesPerTick(),
                budget.maxMillisPerTick(),
                CubeStatus.EMPTY,
                null,
                null,
                "none",
                ""
        );
    }

    public long remainingCubes() {
        return Math.max(0L, activeTotalCubes - activeProcessedCubes);
    }
}
