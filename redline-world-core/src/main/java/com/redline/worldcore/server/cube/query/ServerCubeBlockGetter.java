package com.redline.worldcore.server.cube.query;

import com.redline.worldcore.server.cube.ServerCubeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** BlockGetter used by vanilla BlockCollisions so entity/item/falling-block physics sees cube terrain. */
public final class ServerCubeBlockGetter implements BlockGetter {
    private final ServerLevel level;
    private final ServerCubeCache cache;

    public ServerCubeBlockGetter(ServerLevel level, ServerCubeCache cache) {
        this.level = level;
        this.cache = cache;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return ServerCubeWorldQuery.blockStateOrAir(cache, pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinY() {
        return level.getMinY();
    }
}
