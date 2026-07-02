package com.redline.worldcore.server.entity;

import com.redline.worldcore.api.pos.CubePos;

import java.util.UUID;

/** Lightweight runtime copy of an entity location inside the cube index. */
public record EntityRef(
        int entityId,
        UUID uuid,
        String type,
        CubePos cubePos,
        double x,
        double y,
        double z,
        boolean alwaysTicking
) {
    public EntityRef withCube(CubePos newCubePos, double newX, double newY, double newZ, boolean newAlwaysTicking) {
        return new EntityRef(entityId, uuid, type, newCubePos, newX, newY, newZ, newAlwaysTicking);
    }
}
