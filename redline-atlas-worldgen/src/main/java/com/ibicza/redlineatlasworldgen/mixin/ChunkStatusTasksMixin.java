package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseContext;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;
import net.minecraft.core.registries.Registries;
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

    @Inject(method = "generateStructureStarts", at = @At("HEAD"))
    private static void redlineAtlasWorldgen$beginStructureQueries(WorldGenContext context, ChunkStep step,
                                                                    StaticCache2D<GenerationChunkHolder> cache,
                                                                    ChunkAccess chunk,
                                                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        AtlasNoiseContext.beginStructureQueries(
                context.level().dimension(),
                context.level().getSeed(),
                context.level().registryAccess().lookupOrThrow(Registries.BIOME)
        );
    }

    @Inject(method = "generateStructureStarts", at = @At("RETURN"))
    private static void redlineAtlasWorldgen$endStructureQueries(WorldGenContext context, ChunkStep step,
                                                                  StaticCache2D<GenerationChunkHolder> cache,
                                                                  ChunkAccess chunk,
                                                                  CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        AtlasNoiseContext.endStructureQueries(context.level().dimension());
        profileFuture("chunkStatus.generateStructureStarts", cir);
    }
    @Inject(
            method = {"generateBiomes", "generateNoise", "generateSurface", "generateCarvers"},
            at = @At("HEAD")
    )
    private static void redlineAtlasWorldgen$registerAtlasGuidedChunk(WorldGenContext context, ChunkStep step,
                                                                      StaticCache2D<GenerationChunkHolder> cache,
                                                                      ChunkAccess chunk,
                                                                      CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        AtlasNoiseContext.register(chunk.getPos(), context.level().dimension(), context.level().getSeed(), context.level().registryAccess().lookupOrThrow(Registries.BIOME));
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

    @Inject(method = "generateBiomes", at = @At("RETURN"))
    private static void redlineAtlasWorldgen$profileGenerateBiomes(WorldGenContext context, ChunkStep step,
                                                                   StaticCache2D<GenerationChunkHolder> cache,
                                                                   ChunkAccess chunk,
                                                                   CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        profileFuture("chunkStatus.generateBiomes", cir);
    }

    @Inject(method = "generateNoise", at = @At("RETURN"))
    private static void redlineAtlasWorldgen$profileGenerateNoise(WorldGenContext context, ChunkStep step,
                                                                  StaticCache2D<GenerationChunkHolder> cache,
                                                                  ChunkAccess chunk,
                                                                  CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        profileFuture("chunkStatus.generateNoise", cir);
    }

    @Inject(method = "generateSurface", at = @At("RETURN"))
    private static void redlineAtlasWorldgen$profileGenerateSurface(WorldGenContext context, ChunkStep step,
                                                                    StaticCache2D<GenerationChunkHolder> cache,
                                                                    ChunkAccess chunk,
                                                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        profileFuture("chunkStatus.generateSurface", cir);
    }

    @Inject(method = "generateCarvers", at = @At("RETURN"))
    private static void redlineAtlasWorldgen$profileGenerateCarvers(WorldGenContext context, ChunkStep step,
                                                                    StaticCache2D<GenerationChunkHolder> cache,
                                                                    ChunkAccess chunk,
                                                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        profileFuture("chunkStatus.generateCarvers", cir);
    }

    private static void profileFuture(String stage, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        long started = AtlasWorldgenProfiler.start();
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (future == null) {
            AtlasWorldgenProfiler.recordSince(stage, started);
            return;
        }
        future.whenComplete((ignored, throwable) -> AtlasWorldgenProfiler.recordSince(stage, started));
    }

}
