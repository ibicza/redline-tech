package com.redline.worldcore.server.cube.dirty;

/**
 * Runtime dirty reasons for a cube-owned update.
 *
 * <p>M14.1 keeps these flags separate from {@code CubeHolder.dirty()}: the holder boolean still means "needs durable
 * save", while this enum explains which downstream queues must process the cube first.</p>
 */
public enum CubeDirtyFlag {
    BLOCKS,
    CONTENT_FLAGS,
    STATIC_LIGHT,
    SKY_LIGHT,
    COLUMN_INDEX,
    STORAGE,
    CLIENT_SYNC
}
