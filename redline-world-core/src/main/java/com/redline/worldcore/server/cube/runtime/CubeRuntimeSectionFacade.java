package com.redline.worldcore.server.cube.runtime;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.ServerCubeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Lightweight 16x16x16 cube section facade.
 *
 * <p>This is the M20 replacement for pretending that a whole 32k-high vanilla column exists.  Runtime systems can hold a
 * section facade for one {@link CubePos}; each local read/write maps directly to the owning cube.  It is deliberately not
 * a LevelChunk subclass: vanilla chunks stay at the boundary, while Redline's internal API stays cube-first.</p>
 */
public final class CubeRuntimeSectionFacade {
    private final ServerLevel level;
    private final ServerCubeCache cache;
    private final CubePos cubePos;

    public CubeRuntimeSectionFacade(ServerLevel level, ServerCubeCache cache, CubePos cubePos) {
        this.level = level;
        this.cache = cache;
        this.cubePos = cubePos;
    }

    public CubePos cubePos() {
        return cubePos;
    }

    public BlockState getBlockState(int localX, int localY, int localZ) {
        BlockPos worldPos = worldPos(localX, localY, localZ);
        return cache.readOrGenerateBlock(worldPos).orElseGet(() -> Blocks.AIR.defaultBlockState());
    }

    public FluidState getFluidState(int localX, int localY, int localZ) {
        return getBlockState(localX, localY, localZ).getFluidState();
    }

    public CubicChunksStyleRuntime.RuntimeWriteResult setBlockState(int localX, int localY, int localZ, BlockState state, String reason) {
        return CubicChunksStyleRuntime.setBlock(level, worldPos(localX, localY, localZ), state, reason);
    }

    public BlockPos worldPos(int localX, int localY, int localZ) {
        return new BlockPos(cubePos.minBlockX() + (localX & CubePos.MASK),
                cubePos.minBlockY() + (localY & CubePos.MASK),
                cubePos.minBlockZ() + (localZ & CubePos.MASK));
    }
}
