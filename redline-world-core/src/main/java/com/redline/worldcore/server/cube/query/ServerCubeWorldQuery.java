package com.redline.worldcore.server.cube.query;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Optional;

/**
 * M19.2 server-side cube-backed world query layer.
 *
 * <p>The cube backend is the source of truth for generated/loaded cubic_test blocks. Vanilla gameplay code still asks
 * {@link Level#getBlockState(BlockPos)}, {@link Level#getFluidState(BlockPos)} and collision chunk views.  If those
 * queries are allowed to fall through to the flat air vanilla dimension, mobs, fluids, falling blocks and placement all
 * treat generated cube terrain as air.  This bridge answers those read queries from loaded cube holders without
 * materializing full vanilla columns.</p>
 */
public final class ServerCubeWorldQuery {
    private ServerCubeWorldQuery() {
    }

    public static boolean isCubicServerLevel(Level level) {
        return level instanceof ServerLevel serverLevel && serverLevel.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL);
    }

    public static Optional<BlockState> blockState(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return Optional.empty();
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(serverLevel.getServer());
        Optional<BlockState> state = cache.readLoadedBlock(pos);
        if (state.isPresent()) {
            RuntimeProfiler.addCount("gameplay.cube_query_server_block_hits", 1);
        } else {
            RuntimeProfiler.addCount("gameplay.cube_query_server_block_misses", 1);
        }
        return state;
    }

    public static Optional<FluidState> fluidState(Level level, BlockPos pos) {
        Optional<BlockState> state = blockState(level, pos);
        if (state.isPresent()) {
            RuntimeProfiler.addCount("gameplay.cube_query_server_fluid_hits", 1);
            return Optional.of(state.get().getFluidState());
        }
        RuntimeProfiler.addCount("gameplay.cube_query_server_fluid_misses", 1);
        return Optional.empty();
    }

    static BlockState blockStateOrAir(ServerCubeCache cache, BlockPos pos) {
        return cache.readLoadedBlock(pos).orElseGet(() -> Blocks.AIR.defaultBlockState());
    }
}
