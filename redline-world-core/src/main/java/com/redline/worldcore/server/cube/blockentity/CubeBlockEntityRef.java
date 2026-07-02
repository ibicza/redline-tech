package com.redline.worldcore.server.cube.blockentity;

import com.redline.worldcore.api.pos.CubeLocalPos;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.BlockPos;

/** Runtime reference proving that a block entity slot is owned by a CubePos/local index. */
public record CubeBlockEntityRef(
        CubePos cubePos,
        CubeLocalPos localPos,
        BlockPos worldPos,
        int localIndex,
        String blockId,
        String blockEntityId,
        boolean placeholder,
        boolean realNbt,
        boolean tickingAllowed
) {
    public CubeBlockEntityRef withTickingAllowed(boolean allowed) {
        return new CubeBlockEntityRef(cubePos, localPos, worldPos, localIndex, blockId, blockEntityId, placeholder, realNbt, allowed);
    }
}
