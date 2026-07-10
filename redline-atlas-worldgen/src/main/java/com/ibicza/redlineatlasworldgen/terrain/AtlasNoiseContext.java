package com.ibicza.redlineatlasworldgen.terrain;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Small bridge between the chunk-status pipeline and generation mixins.
 *
 * <p>NoiseChunk and MultiNoiseBiomeSource do not know which dimension/seed they are generating for.
 * ChunkStatusTasks does, so it registers the currently generated chunk here before vanilla asks the
 * generator for terrain density or biomes. The mixins then read this context and only guide the normal
 * Overworld pipeline.</p>
 */
public final class AtlasNoiseContext {
    private static final ConcurrentMap<Long, ChunkInfo> GENERATING_CHUNKS = new ConcurrentHashMap<>();

    public static void register(ChunkPos pos, ResourceKey<Level> dimension) {
        register(pos, dimension, 0L, null);
    }

    public static void register(ChunkPos pos, ResourceKey<Level> dimension, long seed) {
        register(pos, dimension, seed, null);
    }

    public static void register(ChunkPos pos, ResourceKey<Level> dimension, long seed, HolderGetter<Biome> biomeLookup) {
        if (!AtlasWorldgenConfig.ENABLED.get()) {
            return;
        }
        if (AtlasWorldgenConfig.OVERWORLD_ONLY.get() && dimension != Level.OVERWORLD) {
            return;
        }
        GENERATING_CHUNKS.put(pos.pack(), new ChunkInfo(dimension, seed, biomeLookup));
    }

    public static void unregister(ChunkPos pos, ResourceKey<Level> dimension) {
        GENERATING_CHUNKS.remove(pos.pack());
    }

    public static Optional<ResourceKey<Level>> dimensionFor(ChunkPos pos) {
        ChunkInfo info = GENERATING_CHUNKS.get(pos.pack());
        return info == null ? Optional.empty() : Optional.of(info.dimension());
    }

    public static OptionalLong seedFor(ChunkPos pos) {
        ChunkInfo info = GENERATING_CHUNKS.get(pos.pack());
        return info == null ? OptionalLong.empty() : OptionalLong.of(info.seed());
    }

    public static Optional<Holder<Biome>> biomeHolderFor(ChunkPos pos, ResourceKey<Biome> key) {
        ChunkInfo info = GENERATING_CHUNKS.get(pos.pack());
        if (info == null || info.biomeLookup() == null || key == null) {
            return Optional.empty();
        }
        return info.biomeLookup().get(key).map(holder -> (Holder<Biome>) holder);
    }

    public static boolean shouldGuideNoise(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return false;
        }
        if (!AtlasWorldgenConfig.ENABLED.get() || !AtlasWorldgenConfig.NOISE_GUIDE_ENABLED.get()) {
            return false;
        }
        return !AtlasWorldgenConfig.OVERWORLD_ONLY.get() || dimension == Level.OVERWORLD;
    }

    public static boolean shouldGuideBiome(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return false;
        }
        if (!AtlasWorldgenConfig.ENABLED.get() || !AtlasWorldgenConfig.BIOME_GUIDE_ENABLED.get()) {
            return false;
        }
        return !AtlasWorldgenConfig.OVERWORLD_ONLY.get() || dimension == Level.OVERWORLD;
    }

    /**
     * Backward-compatible name used by the terrain mixin in M27/M28.
     */
    public static boolean shouldGuide(ResourceKey<Level> dimension) {
        return shouldGuideNoise(dimension);
    }

    private record ChunkInfo(ResourceKey<Level> dimension, long seed, HolderGetter<Biome> biomeLookup) {
    }

    private AtlasNoiseContext() {
    }
}
