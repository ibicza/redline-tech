package com.redline.worldcore.server.entity;

import com.redline.worldcore.api.pos.CubePos;

/** Immutable debug snapshot of the runtime cube/entity index and M19 cube-first ticking gate. */
public record EntityTrackingSnapshot(
        int trackedEntities,
        int entitySections,
        int entitiesInPlayerCube,
        int scannedLastTick,
        int addedLastTick,
        int movedLastTick,
        int removedLastTick,
        long totalAdded,
        long totalMoved,
        long totalRemoved,
        long lastTickGameTime,
        CubePos playerCube,
        CubePos busiestCube,
        int busiestCubeEntities,
        int playerEntities,
        int mobEntities,
        int itemEntities,
        int projectileEntities,
        int otherEntities,
        long scanMicrosLastTick,
        long scanMicrosAverage,
        long scanMicrosMax,
        int tickingAllowedEntities,
        int tickingBlockedEntities,
        int alwaysTickingEntities,
        int borderEntities,
        int unloadedEntities,
        int fullTickingSections,
        int borderSections,
        int blockedSections
) {
    public static EntityTrackingSnapshot empty(CubePos playerCube) {
        return new EntityTrackingSnapshot(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0L,
                0L,
                playerCube,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0L,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    public String playerCubeString() {
        return format(playerCube);
    }

    public String busiestCubeString() {
        return format(busiestCube);
    }

    public String kindBreakdown() {
        return "players=" + playerEntities
                + ", mobs=" + mobEntities
                + ", items=" + itemEntities
                + ", projectiles=" + projectileEntities
                + ", other=" + otherEntities;
    }

    public String perfLine() {
        return "scanUs=" + scanMicrosLastTick
                + ", avgUs=" + scanMicrosAverage
                + ", maxUs=" + scanMicrosMax;
    }

    public String tickingLine() {
        return "allowed=" + tickingAllowedEntities
                + ", blocked=" + tickingBlockedEntities
                + ", always=" + alwaysTickingEntities
                + ", border=" + borderEntities
                + ", unloaded=" + unloadedEntities
                + ", sections(full/border/blocked)=" + fullTickingSections + "/" + borderSections + "/" + blockedSections;
    }

    private static String format(CubePos cubePos) {
        return cubePos == null ? "none" : cubePos.x() + " " + cubePos.y() + " " + cubePos.z();
    }
}
