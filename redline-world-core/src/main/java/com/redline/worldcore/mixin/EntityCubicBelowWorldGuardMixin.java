package com.redline.worldcore.mixin;

import com.redline.worldcore.server.compat.CubicExtremeGameplayBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla behavior:
 *   Entity.checkBelowWorld() kills/discards entities below level.getMinY() - 64.
 * Cubic behavior:
 *   In cubic_test, level.getMinY() belongs only to the temporary vanilla shell. The real lower bound is the internal
 *   cube range from CubicDimensionSettings, so Y=-12000 must not be treated as void.
 * Reason:
 *   M19.4 detached cube storage/collision from vanilla height; M19.6 detaches the vanilla void-kill gate as well.
 * Risk:
 *   If the internal range is configured incorrectly, entities can survive lower than intended.
 * Fallback:
 *   Outside cubic_test, and below internalMinY - 64 inside cubic_test, vanilla behavior is unchanged.
 */
@Mixin(Entity.class)
public abstract class EntityCubicBelowWorldGuardMixin {
    @Shadow
    public abstract Level level();

    @Shadow
    public abstract double getY();

    @Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
    private void redline$useCubicInternalVoidLimit(CallbackInfo ci) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (CubicExtremeGameplayBridge.shouldSuppressBelowWorldKill(serverLevel, getY())) {
            ci.cancel();
        }
    }
}
