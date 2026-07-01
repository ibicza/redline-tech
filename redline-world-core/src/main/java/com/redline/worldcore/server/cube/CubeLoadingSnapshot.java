package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.ticket.CubeTicketLevel;

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
        int loadedLastTick,
        int generatedLastTick,
        int unloadedLastTick,
        int queuedLastTick,
        boolean requestLimitHitLastTick,
        Map<CubeTicketLevel, Integer> byTicketLevel,
        Map<CubeStatus, Integer> byCubeStatus,
        Map<CubeHolderState, Integer> byHolderState
) {
}
