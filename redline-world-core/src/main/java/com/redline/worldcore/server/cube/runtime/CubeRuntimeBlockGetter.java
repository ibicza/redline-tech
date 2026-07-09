package com.redline.worldcore.server.cube.runtime;

import com.redline.worldcore.server.cube.ServerCubeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Read-only cube-backed BlockGetter for vanilla algorithms that only need state/fluid/collision reads.
 */
public final class CubeRuntimeBlockGetter implements BlockGetter {
    private final ServerLevel level;
    private final ServerCubeCache cache;

    public CubeRuntimeBlockGetter(ServerLevel level, ServerCubeCache cache) {
        this.level = level;
        this.cache = cache;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return level.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return cache.readOrGenerateBlock(pos).orElseGet(() -> net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return cache.settings().blockHeight();
    }

    @Override
    public int getMinY() {
        return cache.settings().minBlockY();
    }
}
