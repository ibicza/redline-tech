package com.redline.worldcore.mixin;

import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * M19.1: observes every successful vanilla Level#setBlock in cubic_test and writes it back into the cube backend.
 *
 * <p>Direct player edit events are not enough for gameplay: falling sand removes/places blocks from entity ticks, fluids
 * spread from fluid ticks, redstone toggles block states, levers/repeaters mutate properties after placement, and pistons
 * move blocks through vanilla internals. Without this observer the next cube-native snapshot/mirror uses stale cube data
 * and visually/server-side resets those blocks to their old state.</p>
 */
@Mixin(Level.class)
public abstract class LevelLiveBlockChangeCaptureMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void redline$captureLiveBlockChange(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        CubicClientSyncBridge.onVanillaBlockStateChanged(level, pos.immutable(), state);
    }
}
