package com.redline.worldcore.api.cube;

import com.redline.worldcore.api.pos.CubePos;

/** Runtime cube. Later this class will own light arrays, block entities, ticks and dirty flags. */
public final class LevelCube extends AbstractArrayCube {
    public LevelCube(CubePos cubePos) {
        super(cubePos, CubeStatus.FULL);
    }

    /**
     * Creates a detached snapshot for async IO. The snapshot is safe to write on a worker thread while the live holder
     * keeps changing on the server thread.
     */
    public LevelCube copy() {
        LevelCube copy = new LevelCube(cubePos());
        copy.setStatus(status());
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    copy.setBlockState(x, y, z, getBlockState(x, y, z));
                }
            }
        }
        copy.replaceBlockLight(copyBlockLight());
        copy.replaceSkyLight(copySkyLight());
        copy.replaceBlockEntityData(copyBlockEntityData());
        copy.replaceScheduledTicks(copyScheduledBlockTicks(), copyScheduledFluidTicks());
        return copy;
    }
}
