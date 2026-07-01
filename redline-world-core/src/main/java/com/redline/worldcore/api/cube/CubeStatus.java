package com.redline.worldcore.api.cube;

/** Generation/loading pipeline status for a cube. */
public enum CubeStatus {
    EMPTY,
    BIOMES,
    NOISE,
    SURFACE_CLASSIFIED,
    CARVED,
    GEOLOGY_READY,
    STRUCTURE_RESERVED,
    STRUCTURE_PLACED,
    FEATURES_READY,
    LIGHT_READY,
    FULL;

    public boolean isAtLeast(CubeStatus other) {
        return ordinal() >= other.ordinal();
    }
}
