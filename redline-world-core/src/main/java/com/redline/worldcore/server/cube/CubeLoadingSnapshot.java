package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.server.cube.access.CubeMutationSnapshot;

import java.util.Map;

/** Summary used by debug commands and later by the debug overlay. */
public record CubeLoadingSnapshot(
        int loadedCubes,
        int pendingLoads,
        int requestedCubes,
        long totalLoaded,
        long totalUnloaded,
        long totalSaved,
        long totalGenerated,
        long totalLightRebuilt,
        int lightRebuiltLastTick,
        int lightDirtyQueue,
        long totalSkyLightRebuilt,
        long totalSkyLightColumnsRebuilt,
        long totalSkyLightSkippedUnchanged,
        long totalSkyLightSavedChanged,
        int skyLightColumnsLastTick,
        int skyLightDirtyColumns,
        int skyLightChangedLastTick,
        int skyLightSkippedUnchangedLastTick,
        int skyLightSavedChangedLastTick,
        long skyLightRebuildMicrosLastTick,
        long skyLightRebuildMicrosMax,
        int skyLightAutoColumnsPerTick,
        int skyLightDirtyDelayTicks,
        int loadedLastTick,
        int generatedLastTick,
        int unloadedLastTick,
        int queuedLastTick,
        long loadMicrosLastTick,
        long loadMicrosMax,
        int maxLoadsPerTick,
        int maxGeneratedLoadsPerTick,
        int maxLoadMicrosPerTick,
        boolean loadGeneratedBudgetHitLastTick,
        boolean loadTimeBudgetHitLastTick,
        boolean requestLimitHitLastTick,
        CubeMutationSnapshot mutationSnapshot,
        Map<CubeTicketLevel, Integer> byTicketLevel,
        Map<CubeStatus, Integer> byCubeStatus,
        Map<CubeHolderState, Integer> byHolderState
) {
}
