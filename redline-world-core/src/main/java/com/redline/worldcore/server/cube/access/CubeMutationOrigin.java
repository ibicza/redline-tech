package com.redline.worldcore.server.cube.access;

/** Identifies the system that requested a cube-owned block mutation. */
public enum CubeMutationOrigin {
    PLAYER_EDIT,
    COMMAND,
    WORLD_CORE_INTERNAL,
    TEST,
    UNKNOWN
}
