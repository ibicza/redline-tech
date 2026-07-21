package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;
import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @Unique
    private static final ThreadLocal<ProfileInvocation> REDLINE_ATLAS_WORLDGEN$PROFILE_INVOCATION =
            ThreadLocal.withInitial(ProfileInvocation::new);

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
    }

    @Inject(
            method = {"generateBiomes", "generateNoise", "generateSurface", "generateCarvers"},
            at = @At("HEAD")
    )
    private static void redlineAtlasWorldgen$registerAtlasGuidedChunk(WorldGenContext context, ChunkStep step,
                                                                      StaticCache2D<GenerationChunkHolder> cache,
                                                                      ChunkAccess chunk,
                                                                      CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        AtlasNoiseContext.register(
                chunk.getPos(), context.level().dimension(), context.level().getSeed(),
                context.level().registryAccess().lookupOrThrow(Registries.BIOME)
        );
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
        future.whenComplete((ignored, throwable) ->
                AtlasNoiseContext.unregister(chunk.getPos(), context.level().dimension()));
    }

    @Inject(
            method = {
                    "generateStructureStarts", "loadStructureStarts", "generateStructureReferences",
                    "generateBiomes", "generateNoise", "generateSurface", "generateCarvers",
                    "generateFeatures", "initializeLight", "light", "generateSpawn", "full"
            },
            at = @At("HEAD")
    )
    private static void redlineAtlasWorldgen$beginChunkStageProfile(WorldGenContext context, ChunkStep step,
                                                                    StaticCache2D<GenerationChunkHolder> cache,
                                                                    ChunkAccess chunk,
                                                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        String stageName = step.targetStatus().getName().toString();
        ProfileInvocation invocation = REDLINE_ATLAS_WORLDGEN$PROFILE_INVOCATION.get();
        invocation.stageName = stageName;
        invocation.startedNanos = AtlasWorldgenProfiler.start();
        invocation.token = AtlasWorldgenProfiler.beginChunkStage(
                context.level().dimension(), chunk.getPos(), stageName
        );
    }

    @Inject(
            method = {
                    "generateStructureStarts", "loadStructureStarts", "generateStructureReferences",
                    "generateBiomes", "generateNoise", "generateSurface", "generateCarvers",
                    "generateFeatures", "initializeLight", "light", "generateSpawn", "full"
            },
            at = @At("RETURN")
    )
    private static void redlineAtlasWorldgen$completeChunkStageProfile(WorldGenContext context, ChunkStep step,
                                                                       StaticCache2D<GenerationChunkHolder> cache,
                                                                       ChunkAccess chunk,
                                                                       CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ProfileInvocation invocation = REDLINE_ATLAS_WORLDGEN$PROFILE_INVOCATION.get();
        String counterName = "chunkStatus." + invocation.stageName;
        String stageName = invocation.stageName;
        long startedNanos = invocation.startedNanos;
        AtlasWorldgenProfiler.ChunkStageToken token = invocation.token;
        invocation.clear();

        if (startedNanos == 0L && token == null) {
            return;
        }
        ResourceKey<Level> dimension = context.level().dimension();
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (future == null) {
            AtlasWorldgenProfiler.completeChunkStage(token, counterName, startedNanos, null);
            redlineAtlasWorldgen$recordSectionSnapshot(stageName, dimension, chunk);
            return;
        }
        future.whenComplete((ignored, throwable) -> {
            AtlasWorldgenProfiler.completeChunkStage(token, counterName, startedNanos, throwable);
            redlineAtlasWorldgen$recordSectionSnapshot(stageName, dimension, chunk);
        });
    }

    @Unique
    private static void redlineAtlasWorldgen$recordSectionSnapshot(String stageName, ResourceKey<Level> dimension,
                                                                   ChunkAccess chunk) {
        String stageId = redlineAtlasWorldgen$stageId(stageName);
        if (!redlineAtlasWorldgen$shouldSnapshotSections(stageId)) {
            return;
        }

        LevelChunkSection[] sections = chunk.getSections();
        int empty = 0;
        for (LevelChunkSection section : sections) {
            if (section.hasOnlyAir()) {
                empty++;
            }
        }
        int topAir = 0;
        for (int index = sections.length - 1; index >= 0; index--) {
            if (!sections[index].hasOnlyAir()) {
                break;
            }
            topAir++;
        }

        int nonEmpty = sections.length - empty;
        String prefix = "worldgen.sections." + stageId + ".";
        AtlasWorldgenProfiler.recordChunkMetric(dimension, chunk.getPos(), prefix + "chunks");
        AtlasWorldgenProfiler.recordChunkMetric(dimension, chunk.getPos(), prefix + "total", sections.length);
        AtlasWorldgenProfiler.recordChunkMetric(dimension, chunk.getPos(), prefix + "empty", empty);
        AtlasWorldgenProfiler.recordChunkMetric(dimension, chunk.getPos(), prefix + "nonEmpty", nonEmpty);
        AtlasWorldgenProfiler.recordChunkMetric(dimension, chunk.getPos(), prefix + "topAir", topAir);
        AtlasWorldgenProfiler.recordChunkMetric(dimension, chunk.getPos(), prefix + "topAirBlocks", topAir * 16L);
    }

    @Unique
    private static boolean redlineAtlasWorldgen$shouldSnapshotSections(String stageId) {
        return switch (stageId) {
            case "noise", "surface", "carvers", "features", "initialize_light", "light", "full" -> true;
            default -> false;
        };
    }

    @Unique
    private static String redlineAtlasWorldgen$stageId(String stageName) {
        if (stageName == null || stageName.isBlank()) {
            return "unknown";
        }
        int separator = stageName.indexOf(':');
        return separator >= 0 && separator + 1 < stageName.length()
                ? stageName.substring(separator + 1)
                : stageName;
    }

    private static final class ProfileInvocation {
        private String stageName;
        private long startedNanos;
        private AtlasWorldgenProfiler.ChunkStageToken token;

        private void clear() {
            stageName = null;
            startedNanos = 0L;
            token = null;
        }
    }
}
