package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.bridge.AtlasNoiseChunkBridge;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;
import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseContext;
import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseGuide;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin implements AtlasNoiseChunkBridge {
    @Unique
    private ResourceKey<Level> redlineAtlasWorldgen$atlasDimension;

    @Shadow
    public abstract int blockX();

    @Shadow
    public abstract int blockZ();

    @Override
    public void redlineAtlasWorldgen$setAtlasDimension(ResourceKey<Level> dimension) {
        this.redlineAtlasWorldgen$atlasDimension = dimension;
    }

    @Inject(method = "forChunk", at = @At("RETURN"))
    private static void redlineAtlasWorldgen$captureDimension(ChunkAccess chunk, RandomState randomState,
                                                              DensityFunctions.BeardifierOrMarker beardifier,
                                                              NoiseGeneratorSettings settings,
                                                              Aquifer.FluidPicker fluidPicker,
                                                              Blender blender,
                                                              CallbackInfoReturnable<NoiseChunk> cir) {
        AtlasNoiseContext.dimensionFor(chunk.getPos())
                .ifPresent(dimension -> ((AtlasNoiseChunkBridge) cir.getReturnValue()).redlineAtlasWorldgen$setAtlasDimension(dimension));
    }

    @Inject(method = "blockY", at = @At("RETURN"), cancellable = true)
    private void redlineAtlasWorldgen$shiftDensityY(CallbackInfoReturnable<Integer> cir) {
        // Structure pieces/junctions stored by vanilla are already at atlas-correct world Y after
        // the structure-height guide. Beardifier must compare against that unshifted world Y;
        // applying the density shift again moves its support terrain into a floating island.
        if (AtlasNoiseContext.isBeardifierQuery()
                && AtlasNoiseContext.shouldGuideStructures(this.redlineAtlasWorldgen$atlasDimension)) {
            return;
        }
        if (!AtlasNoiseContext.shouldGuide(this.redlineAtlasWorldgen$atlasDimension)) {
            return;
        }
        int originalY = cir.getReturnValue();
        boolean recordMetrics = AtlasWorldgenProfiler.hasActiveChunkProfile();
        if (recordMetrics) {
            AtlasWorldgenProfiler.recordMetric("noiseGuide.densityY.calls");
        }
        int shiftedY = AtlasNoiseGuide.shiftedBlockY(this.blockX(), originalY, this.blockZ());
        if (shiftedY != originalY) {
            if (recordMetrics) {
                AtlasWorldgenProfiler.recordMetric("noiseGuide.densityY.shiftedCalls");
            }
            cir.setReturnValue(shiftedY);
        }
    }
}
