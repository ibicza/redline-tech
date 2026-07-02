package com.redline.worldcore.server.cube.dirty;

/** Lightweight M14.1 content flags derived from the cube's current block/light arrays. */
public enum CubeContentFlag {
    EMPTY,
    HAS_NON_AIR_BLOCKS,
    HAS_LIGHT_EMITTERS,
    HAS_BLOCK_LIGHT,
    HAS_SKY_LIGHT,
    HAS_BLOCK_ENTITIES,
    HAS_SCHEDULED_BLOCK_TICKS,
    HAS_SCHEDULED_FLUID_TICKS
}
