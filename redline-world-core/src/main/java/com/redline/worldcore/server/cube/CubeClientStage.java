package com.redline.worldcore.server.cube;

/**
 * M17.0 cube-first client readiness state.
 *
 * <p>The important split is that a cube can be ready in the Redline cube backend before the temporary vanilla chunk
 * shell has been mirrored. Later M17.x work can replace the vanilla shell path with cube-native packets without
 * changing the server-side holder lifecycle again.</p>
 */
public enum CubeClientStage {
    /** Cube exists in the cube backend, but no client-facing readiness has been recorded for its current data hash. */
    CUBE_NATIVE_READY,

    /** Cube data is ready for the cube-native client path; vanilla shell mirroring is optional/fallback work. */
    CLIENT_NATIVE_READY,

    /** Temporary vanilla compatibility shell has a queued mirror task. */
    VANILLA_SHELL_QUEUED,

    /** Temporary vanilla compatibility shell is being mirrored in bounded slices. */
    VANILLA_SHELL_MATERIALIZING,

    /** Temporary vanilla compatibility shell already matches the holder's current cube data hash. */
    VANILLA_SHELL_READY
}
