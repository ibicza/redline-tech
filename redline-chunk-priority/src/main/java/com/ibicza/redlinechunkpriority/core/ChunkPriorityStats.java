package com.ibicza.redlinechunkpriority.core;

import java.util.List;

public record ChunkPriorityStats(
        long gameTime,
        boolean runtimeEnabled,
        int players,
        int planned,
        int requested,
        int skippedLoaded,
        int capped,
        List<ChunkPriorityTarget> sample
) {
    public static ChunkPriorityStats empty(boolean runtimeEnabled) {
        return new ChunkPriorityStats(0L, runtimeEnabled, 0, 0, 0, 0, 0, List.of());
    }
}
