package com.ibicza.redlineatlasworldgen.terrain;

public record AtlasTerrainStats(
        boolean runtimeEnabled,
        int queued,
        long shapedChunks,
        long shapedColumns,
        long skippedChunks,
        long missingSampleColumns,
        String lastChunk,
        String lastSource
) {
    public static AtlasTerrainStats empty(boolean runtimeEnabled) {
        return new AtlasTerrainStats(runtimeEnabled, 0, 0, 0, 0, 0, "none", "none");
    }
}
