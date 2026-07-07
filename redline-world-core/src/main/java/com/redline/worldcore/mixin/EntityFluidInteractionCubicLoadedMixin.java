package com.redline.worldcore.mixin;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla behavior:
 *   EntityFluidInteraction.hasFluidAndLoaded(...) first asks Level#getChunk and then inspects vanilla LevelChunkSection
 *   fluid flags. Outside the temporary DimensionType shell, those section indexes are absent, so the method returns false
 *   before it ever calls Level#getFluidState.
 * Cubic behavior:
 *   In cubic_test outside the vanilla shell, scan the small entity fluid AABB directly through Level#getFluidState. That
 *   call is already cube-backed by LevelCubeWorldQueryMixin / ClientLevelCubeWorldQueryMixin, so no vanilla column is
 *   materialized.
 * Reason:
 *   M19.9.2 made water/lava render correctly at extreme Y, but player underwater state/bubbles still depended on the
 *   vanilla section pre-check. This bridge lets vanilla entity fluid code see cube-native fluids.
 * Risk:
 *   The scan is intentionally limited to the tiny entity fluid interaction box. Large arbitrary ranges still use vanilla.
 * Fallback:
 *   Inside the shell or outside cubic_test, vanilla hasFluidAndLoaded remains unchanged.
 */
@Mixin(targets = "net.minecraft.world.entity.EntityFluidInteraction")
public abstract class EntityFluidInteractionCubicLoadedMixin {
    private static final int MAX_CUBIC_FLUID_SCAN_VOLUME = 256;

    @Inject(method = "hasFluidAndLoaded", at = @At("HEAD"), cancellable = true)
    private static void redline$cubeBackedHasFluidAndLoaded(Level level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (!level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return;
        }
        int shellMinY = level.getMinY();
        int shellMaxExclusive = level.getMinY() + level.getHeight();
        boolean fullyOutsideShell = maxY < shellMinY || minY >= shellMaxExclusive;
        if (!fullyOutsideShell) {
            return;
        }

        int volume = Math.max(0, maxX - minX + 1) * Math.max(0, maxY - minY + 1) * Math.max(0, maxZ - minZ + 1);
        if (volume <= 0 || volume > MAX_CUBIC_FLUID_SCAN_VOLUME) {
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    FluidState fluid = level.getFluidState(cursor);
                    if (!fluid.isEmpty()) {
                        RuntimeProfiler.addCount("gameplay.cube_entity_fluid_loaded_hits", 1);
                        cir.setReturnValue(true);
                        return;
                    }
                }
            }
        }
        RuntimeProfiler.addCount("gameplay.cube_entity_fluid_loaded_misses", 1);
        cir.setReturnValue(false);
    }
}
