package com.redline.worldcore.server.cube.access;

import com.redline.worldcore.api.pos.CubeLocalPos;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** Result of one cube-owned block mutation. */
public record CubeMutationResult(
        boolean applied,
        boolean changed,
        boolean statusPromoted,
        boolean holderLoaded,
        boolean holderGenerated,
        boolean saved,
        boolean staticLightRebuilt,
        boolean skyLightRebuilt,
        boolean skyLightQueued,
        long elapsedMicros,
        BlockPos blockPos,
        CubePos cubePos,
        CubeLocalPos localPos,
        BlockState previousState,
        BlockState newState,
        CubeMutationOrigin origin,
        String reason
) {
    public CubeMutationResult {
        origin = Objects.requireNonNullElse(origin, CubeMutationOrigin.UNKNOWN);
        reason = reason == null ? "" : reason;
    }

    public static CubeMutationResult rejected(BlockPos blockPos, CubePos cubePos, CubeLocalPos localPos,
                                              BlockState requestedState, CubeMutationOrigin origin, String reason,
                                              long elapsedMicros) {
        return new CubeMutationResult(false, false, false, false, false, false, false, false, false,
                elapsedMicros, blockPos, cubePos, localPos, null, requestedState, origin, reason);
    }
}
