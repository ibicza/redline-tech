package com.redline.worldcore.server.cube.dirty;

import com.redline.worldcore.api.pos.CubePos;

/** One dirty storage flush request with a revision guard for async writes. */
public record CubeSaveWork(CubePos cubePos, long revision) {
}
