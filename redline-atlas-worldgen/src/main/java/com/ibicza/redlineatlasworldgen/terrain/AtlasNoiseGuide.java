package com.ibicza.redlineatlasworldgen.terrain;

import com.ibicza.redlineatlasworldgen.bathymetry.AtlasOpenWaterGuide;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.heightmap.HeightSample;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.util.Mth;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Atlas-guided vanilla density shift.
 *
 * <p>This does not place blocks. It only changes the Y coordinate seen by vanilla density
 * functions. A positive atlas delta makes vanilla sample lower density-space Y at the current
 * world Y, so the same vanilla terrain rises toward the atlas macro-height. Local vanilla noise,
 * caves, aquifers, surface rules and features still run through the vanilla pipeline.</p>
 */
public final class AtlasNoiseGuide {
    private static final ConcurrentMap<Long, GuideColumn> COLUMN_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger CACHE_CLEAR_GUARD = new AtomicInteger();

    public static int shiftedBlockY(int blockX, int originalY, int blockZ) {
        return shiftedBlockY(originalY, column(blockX, blockZ));
    }

    /**
     * Returns the exact integer world-space shift produced by {@link #shiftedBlockY}.
     *
     * <p>Structure placement works with sampled surface heights instead of density-function Y
     * coordinates. Reusing this value keeps structure height queries bit-for-bit aligned with the
     * rounding and safety clamp used by terrain generation.</p>
     */
    public static int effectiveVerticalShiftBlocks(int blockX, int blockZ) {
        GuideColumn column = column(blockX, blockZ);
        return -shiftedBlockY(0, column);
    }

    private static int shiftedBlockY(int originalY, GuideColumn column) {
        if (!column.hasSample()) {
            return originalY;
        }

        double strength = Mth.clamp(AtlasWorldgenConfig.NOISE_GUIDE_STRENGTH.get(), 0.0D, 1.0D);
        if (strength <= 0.0D) {
            return originalY;
        }

        double shift = column.verticalShiftBlocks() * strength;
        int shifted = (int) Math.round(originalY - shift);
        int maxShift = AtlasWorldgenConfig.NOISE_MAX_VERTICAL_SHIFT.get();
        if (maxShift > 0) {
            shifted = Mth.clamp(shifted, originalY - maxShift, originalY + maxShift);
        }
        return shifted;
    }

    public static GuideColumn preview(int blockX, int blockZ) {
        return column(blockX, blockZ);
    }

    public static void clearCache() {
        COLUMN_CACHE.clear();
    }

    private static GuideColumn column(int blockX, int blockZ) {
        long key = HashCommon.mix((((long) blockX) << 32) ^ (blockZ & 0xffffffffL));
        GuideColumn cached = COLUMN_CACHE.get(key);
        if (cached != null) {
            AtlasWorldgenProfiler.recordMetric("cache.noiseColumn.hit");
            return cached;
        }
        AtlasWorldgenProfiler.recordMetric("cache.noiseColumn.miss");

        if (COLUMN_CACHE.size() > AtlasWorldgenConfig.NOISE_COLUMN_CACHE_LIMIT.get()
                && CACHE_CLEAR_GUARD.compareAndSet(0, 1)) {
            try {
                AtlasWorldgenProfiler.recordMetric("cache.noiseColumn.clear");
                COLUMN_CACHE.clear();
            } finally {
                CACHE_CLEAR_GUARD.set(0);
            }
        }

        GuideColumn computed = computeColumn(blockX, blockZ);
        GuideColumn existing = COLUMN_CACHE.putIfAbsent(key, computed);
        return existing == null ? computed : existing;
    }

    private static GuideColumn computeColumn(int blockX, int blockZ) {
        AtlasHeightmapIndex index = AtlasHeightmapIndex.active();
        if (index.tileCount() <= 0) {
            return GuideColumn.missing();
        }

        int radius = Math.max(0, AtlasWorldgenConfig.NOISE_SMOOTHING_RADIUS_BLOCKS.get());
        int step = Math.max(1, AtlasWorldgenConfig.NOISE_SMOOTHING_STEP_BLOCKS.get());
        double weightedMeters = 0.0D;
        double totalWeight = 0.0D;
        String source = "none";
        double resolution = 0.0D;

        if (radius == 0) {
            Optional<HeightSample> sample = sample(index, blockX, blockZ);
            if (sample.isEmpty()) {
                return GuideColumn.missing();
            }
            weightedMeters = sample.get().meters();
            totalWeight = 1.0D;
            source = sample.get().sourceId();
            resolution = sample.get().nominalResolutionMeters();
        } else {
            for (int dz = -radius; dz <= radius; dz += step) {
                for (int dx = -radius; dx <= radius; dx += step) {
                    Optional<HeightSample> sample = sample(index, blockX + dx, blockZ + dz);
                    if (sample.isEmpty()) {
                        continue;
                    }
                    double distanceSq = dx * (double) dx + dz * (double) dz;
                    double weight = 1.0D / (1.0D + distanceSq / Math.max(1.0D, radius * (double) radius));
                    weightedMeters += sample.get().meters() * weight;
                    totalWeight += weight;
                    source = sample.get().sourceId();
                    resolution = Math.max(resolution, sample.get().nominalResolutionMeters());
                }
            }
        }

        if (totalWeight <= 0.0D) {
            return GuideColumn.missing();
        }

        double meters = weightedMeters / totalWeight;
        int atlasY = AtlasCoordinateMapper.metersToWorldY(meters);
        int referenceY = AtlasWorldgenConfig.NOISE_REFERENCE_SURFACE_Y.get();
        int shift = atlasY - referenceY;
        return new GuideColumn(true, meters, atlasY, referenceY, shift, source, resolution);
    }

    private static Optional<HeightSample> sample(AtlasHeightmapIndex index, int blockX, int blockZ) {
        AtlasWorldgenProfiler.recordMetric("noiseGuide.heightSample.attempts");
        if (AtlasWorldgenConfig.OPEN_WATER_USE_FOR_NOISE_GUIDE.get()) {
            Optional<HeightSample> sample = AtlasOpenWaterGuide.compositeHeightSample(blockX, blockZ);
            if (sample.isPresent()) {
                AtlasWorldgenProfiler.recordMetric("noiseGuide.heightSample.hits");
            }
            return sample;
        }
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        Optional<HeightSample> sample = index.sample(geo.latitude(), geo.longitude());
        if (sample.isPresent()) {
            AtlasWorldgenProfiler.recordMetric("noiseGuide.heightSample.hits");
        }
        return sample;
    }

    public record GuideColumn(boolean hasSample, double heightMeters, int atlasY, int referenceY,
                              int verticalShiftBlocks, String sourceId, double resolutionMeters) {
        private static GuideColumn missing() {
            return new GuideColumn(false, 0.0D, 0, 0, 0, "none", 0.0D);
        }
    }

    private AtlasNoiseGuide() {
    }
}
