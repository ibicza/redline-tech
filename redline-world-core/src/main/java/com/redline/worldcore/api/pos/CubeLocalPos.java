package com.redline.worldcore.api.pos;

import net.minecraft.core.BlockPos;

/** Local block coordinate inside a cube. Each component is in [0, 15]. */
public record CubeLocalPos(int x, int y, int z) {
    public CubeLocalPos {
        CubePos.checkLocal(x, "x");
        CubePos.checkLocal(y, "y");
        CubePos.checkLocal(z, "z");
    }

    public static CubeLocalPos fromBlock(BlockPos pos) {
        return new CubeLocalPos(CubePos.local(pos.getX()), CubePos.local(pos.getY()), CubePos.local(pos.getZ()));
    }

    public int localIndex() {
        return CubePos.localIndex(x, y, z);
    }
}
