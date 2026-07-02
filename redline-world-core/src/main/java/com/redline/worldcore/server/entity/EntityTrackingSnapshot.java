package com.redline.worldcore.server.entity;

import com.redline.worldcore.api.pos.CubePos;

/** Immutable M12.1 debug snapshot of the runtime cube/entity index. */
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
        long scanMicrosMax
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
                0L
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

    private static String format(CubePos cubePos) {
        return cubePos == null ? "none" : cubePos.x() + " " + cubePos.y() + " " + cubePos.z();
    }
}
