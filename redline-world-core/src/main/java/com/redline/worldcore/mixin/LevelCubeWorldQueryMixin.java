package com.redline.worldcore.mixin;

import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.cube.query.ServerCubeBlockGetter;
import com.redline.worldcore.server.cube.query.ServerCubeWorldQuery;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * M19.2: makes generated/loaded cube terrain authoritative for vanilla server block reads.
 *
 * <p>This is not a return to real vanilla columns: no full columns are materialized here. Vanilla systems simply receive
 * block/fluid/collision answers from the cube cache when they query cubic_test.</p>
 */
@Mixin(Level.class)
public abstract class LevelCubeWorldQueryMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void redline$cubeBackedGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        Level level = (Level) (Object) this;
        ServerCubeWorldQuery.blockState(level, pos).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void redline$cubeBackedGetFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        Level level = (Level) (Object) this;
        ServerCubeWorldQuery.fluidState(level, pos).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void redline$cubeBackedCollisionGetter(int chunkX, int chunkZ, CallbackInfoReturnable<BlockGetter> cir) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel) || !ServerCubeWorldQuery.isCubicServerLevel(level)) {
            return;
        }
        RuntimeProfiler.addCount("gameplay.cube_query_server_collision_getters", 1);
        cir.setReturnValue(new ServerCubeBlockGetter(serverLevel, WorldCoreCubeLoading.cubicTestForServer(serverLevel.getServer())));
    }
}
