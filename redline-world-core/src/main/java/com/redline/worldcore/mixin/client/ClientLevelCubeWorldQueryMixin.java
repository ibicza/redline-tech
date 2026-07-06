package com.redline.worldcore.mixin.client;

import com.redline.worldcore.client.query.ClientCubeBlockGetter;
import com.redline.worldcore.client.query.ClientCubeWorldQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** M19.2 client read layer: pick-block/raycast/local collision read cube-native sections, not only vanilla air shell. */
@Mixin(Level.class)
public abstract class ClientLevelCubeWorldQueryMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void redline$clientCubeBackedGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        Level level = (Level) (Object) this;
        ClientCubeWorldQuery.blockState(level, pos).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void redline$clientCubeBackedGetFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        Level level = (Level) (Object) this;
        ClientCubeWorldQuery.fluidState(level, pos).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void redline$clientCubeBackedCollisionGetter(int chunkX, int chunkZ, CallbackInfoReturnable<BlockGetter> cir) {
        Level level = (Level) (Object) this;
        if (ClientCubeWorldQuery.isCubicClientLevel(level)) {
            cir.setReturnValue(new ClientCubeBlockGetter(level));
        }
    }
}
