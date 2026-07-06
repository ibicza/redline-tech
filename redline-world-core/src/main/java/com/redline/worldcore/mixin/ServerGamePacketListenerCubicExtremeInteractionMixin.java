package com.redline.worldcore.mixin;

import com.redline.worldcore.server.compat.CubicExtremeGameplayBridge;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla behavior:
 *   ServerGamePacketListenerImpl routes normal break/place packets through vanilla build-height checks and
 *   ServerPlayerGameMode, both of which still treat DimensionType height as the real world height.
 * Cubic behavior:
 *   For cubic_test positions outside the temporary vanilla shell but inside the true cube range, consume the packet and
 *   mutate the cube backend directly.
 * Reason:
 *   Blocks at Y=9000/-12000 already exist in Region3D storage and collision; hand interaction must not require debug
 *   commands or vanilla sections.
 * Risk:
 *   This is a temporary bridge: block hardness and complex BlockItem placement properties are simplified outside shell.
 * Fallback:
 *   Inside the vanilla shell and outside cubic_test, the vanilla packet path is untouched.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerCubicExtremeInteractionMixin {
    @Shadow
    @Final
    private ServerPlayer player;

    @Inject(
            method = "handlePlayerAction",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void redline$handleCubicExtremeBreak(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (CubicExtremeGameplayBridge.handlePlayerAction(player, packet)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleUseItemOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void redline$handleCubicExtremePlace(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (CubicExtremeGameplayBridge.handleUseItemOn(player, packet)) {
            ci.cancel();
        }
    }
}
