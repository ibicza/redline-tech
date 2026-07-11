package com.ibicza.redlineatlasworldgen.lake;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.heightmap.HeightSample;
import com.ibicza.redlineatlasworldgen.landcover.AtlasLandcoverIndex;
import com.ibicza.redlineatlasworldgen.landcover.LandcoverClass;
import com.ibicza.redlineatlasworldgen.landcover.LandcoverSample;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AtlasLakeGuide {
    private static final ConcurrentMap<Long, LakeSample> SAMPLE_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger CACHE_CLEAR_GUARD = new AtomicInteger();

    public static ManualLakeIndex reload(Path gameDirectory) {
        ManualLakeIndex index = ManualLakeIndex.reload(gameDirectory);
        clearCache();
        return index;
    }

    public static LakeSample sample(int blockX, int blockZ) {
        return computeSample(blockX, blockZ, true);
    }

    public static LakeSample sampleForBiome(int blockX, int blockZ) {
        long started = AtlasWorldgenProfiler.start();
        try {
            if (!AtlasWorldgenConfig.LAKE_GUIDE_ENABLED.get()) {
                return LakeSample.none();
            }
            int cell = Math.max(4, AtlasWorldgenConfig.LAKE_CACHE_CELL_SIZE_BLOCKS.get());
            int cellX = Math.floorDiv(blockX, cell);
            int cellZ = Math.floorDiv(blockZ, cell);
            long key = (((long) cellX) << 32) ^ (cellZ & 0xffffffffL);
            LakeSample cached = SAMPLE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            if (SAMPLE_CACHE.size() > AtlasWorldgenConfig.LAKE_SAMPLE_CACHE_LIMIT.get()
                    && CACHE_CLEAR_GUARD.compareAndSet(0, 1)) {
                try {
                    SAMPLE_CACHE.clear();
                } finally {
                    CACHE_CLEAR_GUARD.set(0);
                }
            }
            int sampleX = cellX * cell + cell / 2;
            int sampleZ = cellZ * cell + cell / 2;
            LakeSample computed = computeSample(sampleX, sampleZ, false);
            LakeSample existing = SAMPLE_CACHE.putIfAbsent(key, computed);
            return existing == null ? computed : existing;
        } finally {
            AtlasWorldgenProfiler.recordSince("lake.sample.cached", started);
        }
    }

    public static Optional<HeightSample> compositeHeightSample(int blockX, int blockZ) {
        LakeSample sample = sampleForBiome(blockX, blockZ);
        if (isLakeWater(sample.kind())) {
            return Optional.of(new HeightSample(sample.bottomMeters(), "lake:" + sample.sourceId(), sample.resolutionMeters()));
        }
        return Optional.empty();
    }

    public static int cacheSize() {
        return SAMPLE_CACHE.size();
    }

    public static void clearCache() {
        SAMPLE_CACHE.clear();
    }

    public static boolean isLakeWater(LakeKind kind) {
        return kind == LakeKind.MANUAL_LAKE || kind == LakeKind.SMALL_WATERBODY;
    }

    private static LakeSample computeSample(int blockX, int blockZ, boolean exact) {
        long started = AtlasWorldgenProfiler.start();
        try {
            if (!AtlasWorldgenConfig.LAKE_GUIDE_ENABLED.get()) {
                return LakeSample.none();
            }

            GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
            LakeSample manual = ManualLakeIndex.active().sample(geo.latitude(), geo.longitude(), blockX, blockZ);
            if (manual.hasLakeData()) {
                return manual;
            }

            if (!AtlasWorldgenConfig.LAKE_USE_LANDCOVER_WATER.get()) {
                return LakeSample.none();
            }

            Optional<HeightSample> height = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
            if (height.isEmpty()) {
                return LakeSample.none();
            }
            double waterSurfaceMeters = height.get().meters();
            if (waterSurfaceMeters < AtlasWorldgenConfig.LAKE_MIN_SURFACE_METERS.get()) {
                // Open-ocean/coast water is handled by the ocean layer; this pass is only for inland water.
                return LakeSample.none();
            }

            Optional<LandcoverSample> center = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
            boolean centerWater = center.isPresent() && center.get().landcover() == LandcoverClass.WATER;
            int radius = Math.max(0, AtlasWorldgenConfig.LAKE_WORLDCOVER_WATER_RADIUS_BLOCKS.get());
            int step = Math.max(1, AtlasWorldgenConfig.LAKE_WORLDCOVER_WATER_STEP_BLOCKS.get());
            WaterStats stats = waterStats(blockX, blockZ, radius, step);
            boolean waterCandidate = centerWater || stats.fraction() >= AtlasWorldgenConfig.LAKE_WORLDCOVER_MIN_WATER_FRACTION.get();
            if (waterCandidate) {
                double distanceToShore = estimateDistanceToShore(blockX, blockZ, Math.max(step, 8), Math.max(radius, AtlasWorldgenConfig.LAKE_MAX_SHORE_SEARCH_BLOCKS.get()));
                double depth = syntheticDepth(distanceToShore);
                double bottom = waterSurfaceMeters - depth;
                double resolution = center.map(LandcoverSample::nominalResolutionMeters).orElse(10.0D);
                String source = center.map(LandcoverSample::sourceId).orElse("worldcover");
                return new LakeSample(LakeKind.SMALL_WATERBODY, true, centerWater, 0.0D, distanceToShore, depth, bottom, waterSurfaceMeters,
                        "worldcover_small_water", resolution, source);
            }

            if (exact || AtlasWorldgenConfig.LAKE_SHORE_IN_BIOME_GUIDE.get()) {
                NearWater near = findNearWorldcoverWater(blockX, blockZ, AtlasWorldgenConfig.LAKE_SHORE_RADIUS_BLOCKS.get(), step, waterSurfaceMeters);
                if (near.found()) {
                    return new LakeSample(LakeKind.LAKE_SHORE, true, false, near.distanceBlocks(), 0.0D, 0.0D, Double.NaN, near.waterSurfaceMeters(),
                            "worldcover_lake_shore", 10.0D, near.sourceId());
                }
            }

            return LakeSample.none();
        } finally {
            AtlasWorldgenProfiler.recordSince("lake.sample", started);
        }
    }

    private static double syntheticDepth(double distanceToShoreBlocks) {
        double minDepth = AtlasWorldgenConfig.LAKE_SYNTHETIC_MIN_DEPTH_METERS.get();
        double maxDepth = Math.max(minDepth, AtlasWorldgenConfig.LAKE_SYNTHETIC_MAX_DEPTH_METERS.get());
        double fullDepthDistance = Math.max(1.0D, AtlasWorldgenConfig.LAKE_FULL_DEPTH_DISTANCE_BLOCKS.get());
        double factor = Math.min(1.0D, Math.max(0.0D, distanceToShoreBlocks / fullDepthDistance));
        return minDepth + (maxDepth - minDepth) * factor;
    }

    private static WaterStats waterStats(int blockX, int blockZ, int radius, int step) {
        if (radius <= 0) {
            return new WaterStats(0, 0);
        }
        int water = 0;
        int samples = 0;
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                if (dx * (double) dx + dz * (double) dz > radius * (double) radius) {
                    continue;
                }
                GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX + dx, blockZ + dz);
                Optional<LandcoverSample> sample = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
                if (sample.isEmpty()) {
                    continue;
                }
                samples++;
                if (sample.get().landcover() == LandcoverClass.WATER) {
                    water++;
                }
            }
        }
        return new WaterStats(water, samples);
    }

    private static double estimateDistanceToShore(int blockX, int blockZ, int step, int maxRadius) {
        for (int radius = step; radius <= maxRadius; radius += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                for (int dx = -radius; dx <= radius; dx += step) {
                    double distSq = dx * (double) dx + dz * (double) dz;
                    if (distSq > radius * (double) radius || distSq < (radius - step) * (double) (radius - step)) {
                        continue;
                    }
                    GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX + dx, blockZ + dz);
                    Optional<LandcoverSample> sample = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
                    if (sample.isPresent() && sample.get().landcover() != LandcoverClass.WATER) {
                        return Math.sqrt(distSq);
                    }
                }
            }
        }
        return maxRadius;
    }

    private static NearWater findNearWorldcoverWater(int blockX, int blockZ, int radius, int step, double landSurfaceMeters) {
        if (radius <= 0) {
            return NearWater.none();
        }
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        double bestSurface = Double.NaN;
        String bestSource = "none";
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                double distanceSq = dx * (double) dx + dz * (double) dz;
                if (distanceSq > radius * (double) radius || distanceSq >= bestDistanceSq) {
                    continue;
                }
                int sx = blockX + dx;
                int sz = blockZ + dz;
                GeoPoint geo = AtlasCoordinateMapper.toGeo(sx, sz);
                Optional<LandcoverSample> landcover = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
                if (landcover.isEmpty() || landcover.get().landcover() != LandcoverClass.WATER) {
                    continue;
                }
                Optional<HeightSample> waterHeight = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
                if (waterHeight.isEmpty()) {
                    continue;
                }
                if (waterHeight.get().meters() < AtlasWorldgenConfig.LAKE_MIN_SURFACE_METERS.get()) {
                    continue;
                }
                if (Math.abs(landSurfaceMeters - waterHeight.get().meters()) > AtlasWorldgenConfig.LAKE_SHORE_MAX_HEIGHT_DELTA_METERS.get()) {
                    continue;
                }
                bestDistanceSq = distanceSq;
                bestSurface = waterHeight.get().meters();
                bestSource = landcover.get().sourceId();
            }
        }
        if (!Double.isFinite(bestDistanceSq)) {
            return NearWater.none();
        }
        return new NearWater(true, Math.sqrt(bestDistanceSq), bestSurface, bestSource);
    }

    private record WaterStats(int water, int samples) {
        double fraction() {
            return samples == 0 ? 0.0D : water / (double) samples;
        }
    }

    private record NearWater(boolean found, double distanceBlocks, double waterSurfaceMeters, String sourceId) {
        static NearWater none() {
            return new NearWater(false, Double.POSITIVE_INFINITY, Double.NaN, "none");
        }
    }

    private AtlasLakeGuide() {
    }
}
