package com.redline.worldcore.mixin.client;

import com.redline.worldcore.client.compat.ClientCubicExtremeInteractionBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla behavior:
 *   Pick block asks the vanilla hit result/client level for the selected block.
 * Cubic behavior:
 *   Outside the temporary vanilla shell, delegate pick-block to the cube-backed client query layer.
 * Reason:
 *   Native cube blocks at Y=9000/-12000 are not backed by LevelChunkSection storage, so the normal pick path can miss.
 * Risk:
 *   Only applies to cubic_test outside-shell block hits; vanilla shell and other dimensions are untouched.
 * Fallback:
 *   If the cube-backed pick path does not claim the current hit, vanilla pick-block continues normally.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftCubicPickBlockMixin {
    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void redline$pickNativeCubeBlock(CallbackInfo ci) {
        if (ClientCubicExtremeInteractionBridge.pickBlock((Minecraft) (Object) this)) {
            ci.cancel();
        }
    }
}
