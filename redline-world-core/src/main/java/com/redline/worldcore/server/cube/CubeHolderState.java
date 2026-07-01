package com.redline.worldcore.server.cube;

/** Runtime origin/state of a cube holder in the M6 server cache. */
public enum CubeHolderState {
    /** Cube existed in Region3D storage and was read from disk. */
    REGION3D_LOADED,

    /** Cube was created only as an in-memory placeholder because M7 generation is not implemented yet. */
    PLACEHOLDER,

    /** Cube has been explicitly written to Region3D by a debug/admin cache command. */
    REGION3D_SAVED
}
