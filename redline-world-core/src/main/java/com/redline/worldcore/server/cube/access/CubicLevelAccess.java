package com.redline.worldcore.server.cube.access;

import com.redline.worldcore.server.cube.ServerCubeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Optional;

/**
 * M14.0 cube-first access facade for world code.
 *
 * <p>Use this facade from new Redline World Core systems instead of reaching directly into vanilla chunks. It is still a
 * safe bridge over {@link ServerCubeCache}; later M14.x work can move vanilla mixins onto this API without changing every
 * subsystem again.</p>
 */
public final class CubicLevelAccess {
    private final ServerCubeCache cache;

    public CubicLevelAccess(ServerCubeCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public Optional<BlockState> getBlockState(BlockPos blockPos) {
        return cache.readBlock(blockPos);
    }

    public Optional<BlockState> getOrGenerateBlockState(BlockPos blockPos) {
        return cache.readOrGenerateBlock(blockPos);
    }

    public CubeMutationResult setBlockState(BlockPos blockPos, BlockState state, CubeMutationContext context) {
        return cache.mutateBlock(blockPos, state, context);
    }

    public CubeMutationSnapshot mutationSnapshot() {
        return cache.mutationSnapshot();
    }
}
