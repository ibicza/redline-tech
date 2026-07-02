package com.redline.worldcore.server.entity;

import com.redline.worldcore.api.pos.CubePos;

/** Immutable M12 debug snapshot of the runtime cube/entity index. */
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
        int busiestCubeEntities
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
                0
        );
    }

    public String playerCubeString() {
        return format(playerCube);
    }

    public String busiestCubeString() {
        return format(busiestCube);
    }

    private static String format(CubePos cubePos) {
        return cubePos == null ? "none" : cubePos.x() + " " + cubePos.y() + " " + cubePos.z();
    }
}
