package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseContext;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @Inject(
            method = {"generateBiomes", "generateNoise", "generateSurface", "generateCarvers"},
            at = @At("HEAD")
    )
    private static void redlineAtlasWorldgen$registerAtlasGuidedChunk(WorldGenContext context, ChunkStep step,
                                                                      StaticCache2D<GenerationChunkHolder> cache,
                                                                      ChunkAccess chunk,
                                                                      CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        AtlasNoiseContext.register(chunk.getPos(), context.level().dimension(), context.level().getSeed());
    }

    @Inject(
            method = {"generateBiomes", "generateNoise", "generateSurface", "generateCarvers"},
            at = @At("RETURN")
    )
    private static void redlineAtlasWorldgen$unregisterAtlasGuidedChunk(WorldGenContext context, ChunkStep step,
                                                                        StaticCache2D<GenerationChunkHolder> cache,
                                                                        ChunkAccess chunk,
                                                                        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (future == null) {
            AtlasNoiseContext.unregister(chunk.getPos(), context.level().dimension());
            return;
        }
        future.whenComplete((ignored, throwable) -> AtlasNoiseContext.unregister(chunk.getPos(), context.level().dimension()));
    }
}
