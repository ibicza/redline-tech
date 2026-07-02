package com.redline.worldcore.server.cube.tick;

import com.redline.worldcore.api.pos.CubePos;

/** Debug snapshot for M14.5 cube-owned scheduled block/fluid tick storage. */
public record CubeScheduledTickSnapshot(
        int loadedCubesWithTicks,
        int blockTicks,
        int fluidTicks,
        int dueTicks,
        int dueAllowed,
        int dueBlocked,
        long totalAdded,
        long totalRemoved,
        long totalEvaluated,
        int addedLastTick,
        int removedLastTick,
        int evaluatedLastTick,
        CubePos lastCube,
        String lastReason
) {
    public CubeScheduledTickSnapshot {
        lastReason = lastReason == null ? "none" : lastReason;
    }
}
