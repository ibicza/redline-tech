package com.ibicza.redlineatlasworldgen.terrain;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Small bridge between the chunk-status pipeline and NoiseChunk mixin.
 *
 * <p>NoiseChunk itself does not know the dimension it is generating for. ChunkStatusTasks does,
 * so it registers the currently generated chunk here before vanilla asks the generator to create
 * a NoiseChunk. The NoiseChunk mixin then copies the dimension into the created NoiseChunk and can
 * safely decide whether atlas guidance is allowed.</p>
 */
public final class AtlasNoiseContext {
    private static final ConcurrentMap<Long, ResourceKey<Level>> GENERATING_CHUNKS = new ConcurrentHashMap<>();

    public static void register(ChunkPos pos, ResourceKey<Level> dimension) {
        if (!AtlasWorldgenConfig.ENABLED.get() || !AtlasWorldgenConfig.NOISE_GUIDE_ENABLED.get()) {
            return;
        }
        if (AtlasWorldgenConfig.OVERWORLD_ONLY.get() && dimension != Level.OVERWORLD) {
            return;
        }
        GENERATING_CHUNKS.put(pos.pack(), dimension);
    }

    public static void unregister(ChunkPos pos, ResourceKey<Level> dimension) {
        GENERATING_CHUNKS.remove(pos.pack(), dimension);
    }

    public static Optional<ResourceKey<Level>> dimensionFor(ChunkPos pos) {
        return Optional.ofNullable(GENERATING_CHUNKS.get(pos.pack()));
    }

    public static boolean shouldGuide(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return false;
        }
        if (!AtlasWorldgenConfig.ENABLED.get() || !AtlasWorldgenConfig.NOISE_GUIDE_ENABLED.get()) {
            return false;
        }
        return !AtlasWorldgenConfig.OVERWORLD_ONLY.get() || dimension == Level.OVERWORLD;
    }

    private AtlasNoiseContext() {
    }
}
