package com.redline.worldcore.server.cube.blockentity;

import com.redline.worldcore.api.pos.CubePos;

/** Debug snapshot for M14.2-M14.4 block entity cube ownership. */
public record CubeBlockEntitySnapshot(
        int trackedBlockEntities,
        int sections,
        int realNbtBlockEntities,
        int placeholderBlockEntities,
        int tickingAllowedBlockEntities,
        int tickingBlockedBlockEntities,
        long totalAdded,
        long totalRemoved,
        long totalUpdated,
        long totalRealNbtCaptured,
        long totalRebuiltCubes,
        int addedLastTick,
        int removedLastTick,
        int updatedLastTick,
        int realNbtCapturedLastTick,
        int rebuiltCubesLastTick,
        CubePos lastCube,
        String lastReason
) {
    public CubeBlockEntitySnapshot {
        lastReason = lastReason == null ? "none" : lastReason;
    }
}
