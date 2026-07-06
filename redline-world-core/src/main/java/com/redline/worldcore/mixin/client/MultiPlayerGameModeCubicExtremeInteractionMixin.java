package com.redline.worldcore.mixin.client;

import com.redline.worldcore.client.compat.ClientCubicExtremeInteractionBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla behavior:
 *   MultiPlayerGameMode predicts hand interaction using ClientLevel/DimensionType and then sends vanilla game packets.
 * Cubic behavior:
 *   For cubic_test hits outside the temporary vanilla shell, send an RWC native interaction payload and consume the
 *   client action so vanilla build-height checks cannot silently no-op it.
 * Reason:
 *   Blocks at Y=9000/-12000 are real cube backend blocks but do not have vanilla render sections yet.
 * Risk:
 *   This is temporary and simplified; proper block hardness/models come with the native renderer/interaction stack.
 * Fallback:
 *   Inside the shell, outside cubic_test, and non-block item use keep vanilla behavior.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeCubicExtremeInteractionMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void redline$startExtremeDestroy(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (ClientCubicExtremeInteractionBridge.startDestroyBlock(minecraft, pos, direction)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void redline$useExtremeItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = ClientCubicExtremeInteractionBridge.useItemOn(minecraft, hand, hit);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
