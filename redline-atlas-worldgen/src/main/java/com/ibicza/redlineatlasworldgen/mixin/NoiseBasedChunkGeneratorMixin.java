package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseContext;
import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseGuide;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Aligns vanilla structure surface probes with the atlas-shifted density terrain.
 *
 * <p>Vanilla asks {@code getBaseHeight}/{@code getBaseColumn} while creating structure starts,
 * before the target chunk has actual blocks. Those methods build a standalone vanilla noise
 * column and therefore do not pass through {@link NoiseChunkMixin}. Without this bridge, terrain
 * moves from the vanilla reference surface toward atlas Y while villages, temples and other
 * surface structures remain near the old vanilla height.</p>
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "getBaseHeight", at = @At("RETURN"), cancellable = true)
    private void redlineAtlasWorldgen$shiftStructureBaseHeight(int blockX, int blockZ, Heightmap.Types type,
                                                               LevelHeightAccessor heightAccessor, RandomState randomState,
                                                               CallbackInfoReturnable<Integer> cir) {
        if (!shouldGuide(blockX, blockZ)) {
            return;
        }

        int shift = AtlasNoiseGuide.effectiveVerticalShiftBlocks(blockX, blockZ);
        if (shift == 0) {
            return;
        }

        // Base height is the first free block and may legally be one block above max build Y.
        int shiftedHeight = Mth.clamp(cir.getReturnValue() + shift,
                heightAccessor.getMinY(), heightAccessor.getMaxY() + 1);
        cir.setReturnValue(shiftedHeight);
    }

    @Inject(method = "getBaseColumn", at = @At("RETURN"), cancellable = true)
    private void redlineAtlasWorldgen$shiftStructureBaseColumn(int blockX, int blockZ,
                                                               LevelHeightAccessor heightAccessor, RandomState randomState,
                                                               CallbackInfoReturnable<NoiseColumn> cir) {
        if (!shouldGuide(blockX, blockZ)) {
            return;
        }

        int shift = AtlasNoiseGuide.effectiveVerticalShiftBlocks(blockX, blockZ);
        NoiseColumn original = cir.getReturnValue();
        if (shift == 0 || original == null) {
            return;
        }

        int minY = heightAccessor.getMinY();
        BlockState[] shiftedStates = new BlockState[heightAccessor.getHeight()];
        for (int index = 0; index < shiftedStates.length; index++) {
            int worldY = minY + index;
            // Generated terrain at worldY samples vanilla density-space at worldY - shift.
            shiftedStates[index] = original.getBlock(worldY - shift);
        }
        cir.setReturnValue(new NoiseColumn(minY, shiftedStates));
    }

    private static boolean shouldGuide(int blockX, int blockZ) {
        return AtlasNoiseContext.dimensionForQuery(blockX, blockZ)
                .filter(AtlasNoiseContext::shouldGuideStructures)
                .isPresent();
    }
}
