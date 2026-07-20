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
import com.ibicza.redlineatlasworldgen.river.AtlasRiverIndex;
import com.ibicza.redlineatlasworldgen.river.RiverSample;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AtlasLakeGuide {
    private static final ConcurrentMap<Long, LakeSample> SAMPLE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Long, BasinFit> BASIN_FIT_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger CACHE_CLEAR_GUARD = new AtomicInteger();

    public static ManualLakeIndex reload(Path gameDirectory) {
        ManualLakeIndex index = ManualLakeIndex.reload(gameDirectory);
        clearCache();
        return index;
    }

    public static LakeSample sample(int blockX, int blockZ) {
        return computeSample(blockX, blockZ, true, false);
    }

    public static LakeSample sampleForSurface(int blockX, int blockZ) {
        return computeSample(blockX, blockZ, true, true);
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
            long key = cacheKey(cellX, cellZ);
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
            LakeSample computed = computeSample(sampleX, sampleZ, false, true);
            LakeSample existing = SAMPLE_CACHE.putIfAbsent(key, computed);
            return existing == null ? computed : existing;
        } finally {
            AtlasWorldgenProfiler.recordSince("lake.sample.cached", started);
        }
    }

    public static Optional<HeightSample> compositeHeightSample(int blockX, int blockZ) {
        LakeSample sample = sampleForBiome(blockX, blockZ);
        if (isLakeWater(sample.kind())) {
            RiverSample river = AtlasRiverIndex.active().sample(blockX, blockZ);
            if (river.hasRiverData() && sample.kind() != LakeKind.MANUAL_LAKE) {
                return Optional.empty();
            }
            return Optional.of(new HeightSample(sample.bottomMeters(), "lake:" + sample.sourceId(), sample.resolutionMeters()));
        }
        return Optional.empty();
    }

    public static int cacheSize() {
        return SAMPLE_CACHE.size() + BASIN_FIT_CACHE.size();
    }

    public static void clearCache() {
        SAMPLE_CACHE.clear();
        BASIN_FIT_CACHE.clear();
    }

    public static boolean isLakeWater(LakeKind kind) {
        return kind == LakeKind.MANUAL_LAKE || kind == LakeKind.SMALL_WATERBODY;
    }

    private static LakeSample computeSample(int blockX, int blockZ, boolean exact, boolean includeTerrainShoulder) {
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
            if (AtlasRiverIndex.active().sample(blockX, blockZ).hasRiverData()) {
                return LakeSample.none();
            }

            Optional<HeightSample> height = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
            if (height.isEmpty()) {
                return LakeSample.none();
            }
            double rawSurfaceMeters = height.get().meters();
            if (rawSurfaceMeters < AtlasWorldgenConfig.LAKE_MIN_SURFACE_METERS.get()) {
                // Open-ocean/coast water is handled by the ocean layer; this pass is only for inland water.
                return LakeSample.none();
            }

            Optional<LandcoverSample> center = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
            boolean centerWater = center.isPresent()
                    && center.get().landcover() == LandcoverClass.WATER
                    && !isRiverClaimed(blockX, blockZ);
            int radius = Math.max(0, AtlasWorldgenConfig.LAKE_WORLDCOVER_WATER_RADIUS_BLOCKS.get());
            int step = Math.max(1, AtlasWorldgenConfig.LAKE_WORLDCOVER_WATER_STEP_BLOCKS.get());
            WaterStats stats = waterStats(blockX, blockZ, radius, step);

            if (centerWater) {
                BasinFit fit = estimateWorldcoverBasinFit(blockX, blockZ, rawSurfaceMeters, center.map(LandcoverSample::sourceId).orElse("worldcover"));
                if (fit.found() && fit.waterSamples() < AtlasWorldgenConfig.LAKE_WORLDCOVER_MIN_COMPONENT_SAMPLES.get()) {
                    return LakeSample.none();
                }
                double waterSurfaceMeters = fit.found() ? fit.surfaceMeters() : estimateWorldcoverWaterSurfaceLegacy(blockX, blockZ, rawSurfaceMeters);
                if (waterSurfaceMeters < AtlasWorldgenConfig.LAKE_MIN_SURFACE_METERS.get()) {
                    return LakeSample.none();
                }
                double distanceToShore = estimateDistanceToShore(blockX, blockZ, Math.max(step, 8), Math.max(radius, AtlasWorldgenConfig.LAKE_MAX_SHORE_SEARCH_BLOCKS.get()));
                double resolution = center.map(LandcoverSample::nominalResolutionMeters).orElse(10.0D);
                String source = fit.found() ? fit.sourceId() : center.map(LandcoverSample::sourceId).orElse("worldcover");

                // M30.6: the WorldCover water mask is authoritative for the horizontal water area.
                // Topography is used to fit one safe water level for the whole connected patch,
                // not to punch random holes in the mask. Holes/islands come from non-water pixels.
                double depth = syntheticDepth(distanceToShore);
                double bottom = waterSurfaceMeters - depth;
                return new LakeSample(LakeKind.SMALL_WATERBODY, true, true, 0.0D, distanceToShore, depth, bottom, waterSurfaceMeters,
                        fit.found() ? fit.lakeId() : "worldcover_small_water", resolution, source);
            }

            // A non-water center must never become a water column just because nearby cells are water.
            // Treat it as a shore candidate only. This prevents WorldCover radius checks from creating
            // hanging water slabs outside the real mask.
            int shoreRadius = Math.min(AtlasWorldgenConfig.LAKE_SHORE_RADIUS_BLOCKS.get(),
                    AtlasWorldgenConfig.LAKE_WORLDCOVER_SHORE_RADIUS_BLOCKS.get());
            if ((exact || AtlasWorldgenConfig.LAKE_SHORE_IN_BIOME_GUIDE.get())
                    && stats.fraction() >= AtlasWorldgenConfig.LAKE_WORLDCOVER_MIN_WATER_FRACTION.get()) {
                NearWater near = findNearWorldcoverWater(blockX, blockZ, shoreRadius, step, true);
                if (near.found()) {
                    return new LakeSample(LakeKind.LAKE_SHORE, true, false, near.distanceBlocks(), 0.0D, 0.0D, Double.NaN, near.waterSurfaceMeters(),
                            "worldcover_lake_shore", 10.0D, near.sourceId());
                }
            }

            if (exact || AtlasWorldgenConfig.LAKE_SHORE_IN_BIOME_GUIDE.get()) {
                NearWater near = findNearWorldcoverWater(blockX, blockZ, shoreRadius, step, true);
                if (near.found()) {
                    return new LakeSample(LakeKind.LAKE_SHORE, true, false, near.distanceBlocks(), 0.0D, 0.0D, Double.NaN, near.waterSurfaceMeters(),
                            "worldcover_lake_shore", 10.0D, near.sourceId());
                }
            }
            if (includeTerrainShoulder) {
                int configuredShoulderRadius = Math.max(0,
                        AtlasWorldgenConfig.LAKE_TERRAIN_SHOULDER_RADIUS_BLOCKS.get());
                int hintPadding = !exact && configuredShoulderRadius > shoreRadius
                        ? Math.max(4, AtlasWorldgenConfig.LAKE_CACHE_CELL_SIZE_BLOCKS.get()) : 0;
                int shoulderRadius = Math.max(shoreRadius, configuredShoulderRadius + hintPadding);
                if (shoulderRadius > shoreRadius) {
                    NearWater near = findNearWorldcoverWater(blockX, blockZ, shoulderRadius, step, exact);
                    if (near.found() && near.distanceBlocks() > shoreRadius) {
                        return new LakeSample(LakeKind.LAKE_TERRAIN_SHOULDER, false, false,
                                near.distanceBlocks(), 0.0D, 0.0D, Double.NaN, near.waterSurfaceMeters(),
                                "worldcover_lake_terrain_shoulder", 10.0D, near.sourceId());
                    }
                }
            }

            return LakeSample.none();
        } finally {
            AtlasWorldgenProfiler.recordSince("lake.sample", started);
        }
    }

    private static double syntheticDepth(double distanceToShoreBlocks) {
        double verticalScale = Math.max(0.001D, AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get());
        double minDepth = Math.max(AtlasWorldgenConfig.LAKE_SYNTHETIC_MIN_DEPTH_METERS.get(),
                AtlasWorldgenConfig.LAKE_SYNTHETIC_MIN_DEPTH_BLOCKS.get() * verticalScale);
        double maxDepth = Math.max(AtlasWorldgenConfig.LAKE_SYNTHETIC_MAX_DEPTH_METERS.get(),
                AtlasWorldgenConfig.LAKE_SYNTHETIC_MAX_DEPTH_BLOCKS.get() * verticalScale);
        maxDepth = Math.max(minDepth, maxDepth);
        double fullDepthDistance = Math.max(1.0D, AtlasWorldgenConfig.LAKE_FULL_DEPTH_DISTANCE_BLOCKS.get());
        double factor = Math.min(1.0D, Math.max(0.0D, distanceToShoreBlocks / fullDepthDistance));
        double shaped = factor * factor * (3.0D - 2.0D * factor);
        return minDepth + (maxDepth - minDepth) * shaped;
    }

    private static BasinFit estimateWorldcoverBasinFit(int blockX, int blockZ, double fallbackMeters, String fallbackSourceId) {
        if (!AtlasWorldgenConfig.LAKE_BASIN_FIT_ENABLED.get()) {
            return BasinFit.none();
        }
        int step = Math.max(1, AtlasWorldgenConfig.LAKE_WORLDCOVER_WATER_STEP_BLOCKS.get());
        int cacheCell = Math.max(8, step * 2);
        int cellX = Math.floorDiv(blockX, cacheCell);
        int cellZ = Math.floorDiv(blockZ, cacheCell);
        long key = cacheKey(cellX, cellZ);
        BasinFit cached = BASIN_FIT_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (BASIN_FIT_CACHE.size() > AtlasWorldgenConfig.LAKE_BASIN_FIT_CACHE_LIMIT.get()
                && CACHE_CLEAR_GUARD.compareAndSet(0, 1)) {
            try {
                BASIN_FIT_CACHE.clear();
            } finally {
                CACHE_CLEAR_GUARD.set(0);
            }
        }
        BasinFit computed = computeWorldcoverBasinFit(blockX, blockZ, fallbackMeters, fallbackSourceId, step);
        BasinFit existing = BASIN_FIT_CACHE.putIfAbsent(key, computed);
        return existing == null ? computed : existing;
    }

    private static BasinFit computeWorldcoverBasinFit(int blockX, int blockZ, double fallbackMeters, String fallbackSourceId, int step) {
        long started = AtlasWorldgenProfiler.start();
        try {
            if (!isResidualWorldcoverWater(blockX, blockZ)) {
                return BasinFit.none();
            }
            int maxRadius = Math.max(step, AtlasWorldgenConfig.LAKE_BASIN_FIT_SEARCH_BLOCKS.get());
            int maxGrid = Math.max(1, maxRadius / step);
            ArrayDeque<GridCell> queue = new ArrayDeque<>();
            LongOpenHashSet waterCells = new LongOpenHashSet();
            queue.add(new GridCell(0, 0));
            waterCells.add(gridKey(0, 0));

            while (!queue.isEmpty()) {
                GridCell cell = queue.removeFirst();
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        int nx = cell.gx() + dx;
                        int nz = cell.gz() + dz;
                        if (Math.abs(nx) > maxGrid || Math.abs(nz) > maxGrid) {
                            continue;
                        }
                        int bx = blockX + nx * step;
                        int bz = blockZ + nz * step;
                        if ((bx - blockX) * (double) (bx - blockX) + (bz - blockZ) * (double) (bz - blockZ) > maxRadius * (double) maxRadius) {
                            continue;
                        }
                        long key = gridKey(nx, nz);
                        if (waterCells.contains(key) || !isResidualWorldcoverWater(bx, bz)) {
                            continue;
                        }
                        waterCells.add(key);
                        queue.addLast(new GridCell(nx, nz));
                    }
                }
            }

            List<Double> allWaterHeights = new ArrayList<>();
            List<Double> coreHeights = new ArrayList<>();
            List<Double> rimHeights = new ArrayList<>();
            LongOpenHashSet rimCells = new LongOpenHashSet();
            int coreMinNeighbours = Math.max(0, Math.min(8, AtlasWorldgenConfig.LAKE_BASIN_CORE_MIN_WATER_NEIGHBORS.get()));

            LongIterator waterIterator = waterCells.iterator();
            while (waterIterator.hasNext()) {
                long encoded = waterIterator.nextLong();
                GridCell cell = decodeGridKey(encoded);
                int bx = blockX + cell.gx() * step;
                int bz = blockZ + cell.gz() * step;
                heightMeters(bx, bz).ifPresent(allWaterHeights::add);

                int neighbours = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((dx != 0 || dz != 0) && waterCells.contains(gridKey(cell.gx() + dx, cell.gz() + dz))) {
                            neighbours++;
                        }
                    }
                }
                if (neighbours >= coreMinNeighbours) {
                    heightMeters(bx, bz).ifPresent(coreHeights::add);
                }

                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        int rx = cell.gx() + dx;
                        int rz = cell.gz() + dz;
                        long rimKey = gridKey(rx, rz);
                        if (waterCells.contains(rimKey) || !rimCells.add(rimKey)) {
                            continue;
                        }
                        int rbX = blockX + rx * step;
                        int rbZ = blockZ + rz * step;
                        if (!isResidualWorldcoverWater(rbX, rbZ)) {
                            heightMeters(rbX, rbZ).ifPresent(rimHeights::add);
                        }
                    }
                }
            }

            List<Double> surfaceSource = coreHeights.size() >= AtlasWorldgenConfig.LAKE_BASIN_MIN_CORE_SAMPLES.get() ? coreHeights : allWaterHeights;
            if (surfaceSource.isEmpty()) {
                return BasinFit.none();
            }
            Collections.sort(surfaceSource);
            Collections.sort(rimHeights);

            double rawSurface = lowMean(surfaceSource, AtlasWorldgenConfig.LAKE_BASIN_LOW_MEAN_FRACTION.get());
            if (!Double.isFinite(rawSurface)) {
                rawSurface = percentile(surfaceSource, AtlasWorldgenConfig.LAKE_BASIN_CORE_SURFACE_PERCENTILE.get());
            }
            double lowestCore = surfaceSource.get(0);
            double capped = Math.min(rawSurface, lowestCore + Math.max(0.0D, AtlasWorldgenConfig.LAKE_BASIN_MAX_SURFACE_ABOVE_LOWEST_CORE_METERS.get()));
            double rim = Double.NaN;
            if (!rimHeights.isEmpty()) {
                rim = percentile(rimHeights, AtlasWorldgenConfig.LAKE_BASIN_RIM_PERCENTILE.get());
                capped = Math.min(capped, rim - Math.max(0.0D, AtlasWorldgenConfig.LAKE_BASIN_RIM_CLEARANCE_METERS.get()));
            }
            if (!Double.isFinite(capped)) {
                capped = fallbackMeters;
            }
            double surface = snapSurfaceMeters(capped);
            String lakeId = "worldcover_basin_" + Math.floorDiv(blockX, Math.max(1, maxRadius)) + "_" + Math.floorDiv(blockZ, Math.max(1, maxRadius));
            return new BasinFit(true, surface, rawSurface, Double.isFinite(rim) ? rim : Double.NaN, waterCells.size(), coreHeights.size(), rimHeights.size(), lakeId, fallbackSourceId);
        } finally {
            AtlasWorldgenProfiler.recordSince("lake.basinFit", started);
        }
    }

    private static double estimateWorldcoverWaterSurface(int blockX, int blockZ, double fallbackMeters) {
        BasinFit fit = estimateWorldcoverBasinFit(blockX, blockZ, fallbackMeters, "worldcover");
        return fit.found() ? fit.surfaceMeters() : estimateWorldcoverWaterSurfaceLegacy(blockX, blockZ, fallbackMeters);
    }

    private static double estimateWorldcoverWaterSurfaceLegacy(int blockX, int blockZ, double fallbackMeters) {
        int radius = Math.max(0, AtlasWorldgenConfig.LAKE_WORLDCOVER_SURFACE_SEARCH_BLOCKS.get());
        int step = Math.max(1, AtlasWorldgenConfig.LAKE_WORLDCOVER_WATER_STEP_BLOCKS.get());
        if (radius <= 0) {
            return snapSurfaceMeters(fallbackMeters);
        }

        List<Double> heights = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                if (dx * (double) dx + dz * (double) dz > radius * (double) radius) {
                    continue;
                }
                GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX + dx, blockZ + dz);
                Optional<LandcoverSample> landcover = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
                if (landcover.isEmpty() || landcover.get().landcover() != LandcoverClass.WATER
                        || isRiverClaimed(blockX + dx, blockZ + dz)) {
                    continue;
                }
                Optional<HeightSample> height = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
                if (height.isPresent() && Double.isFinite(height.get().meters())) {
                    heights.add(height.get().meters());
                }
            }
        }
        if (heights.isEmpty()) {
            return snapSurfaceMeters(fallbackMeters);
        }

        Collections.sort(heights);
        double picked = percentile(heights, AtlasWorldgenConfig.LAKE_WORLDCOVER_SURFACE_PERCENTILE.get());
        double lowest = heights.get(0);
        double maxAboveMin = Math.max(0.0D, AtlasWorldgenConfig.LAKE_WORLDCOVER_SURFACE_MAX_ABOVE_MIN_METERS.get());
        double capped = Math.min(picked, lowest + maxAboveMin);
        return snapSurfaceMeters(capped);
    }

    private static double snapSurfaceMeters(double meters) {
        if (!AtlasWorldgenConfig.LAKE_SNAP_WATER_SURFACE_TO_BLOCK.get()) {
            return meters;
        }
        double verticalScale = Math.max(0.001D, AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get());
        int seaY = AtlasWorldgenConfig.SEA_LEVEL_Y.get();
        int y = (int) Math.floor(meters / verticalScale + 1.0E-9D) + seaY;
        return (y - seaY) * verticalScale;
    }

    private static Optional<Double> heightMeters(int blockX, int blockZ) {
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        Optional<HeightSample> height = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
        return height.filter(sample -> Double.isFinite(sample.meters())).map(HeightSample::meters);
    }

    private static boolean isWorldcoverWater(int blockX, int blockZ) {
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        Optional<LandcoverSample> sample = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
        return sample.isPresent() && sample.get().landcover() == LandcoverClass.WATER;
    }

    private static boolean isTopographicallyAcceptedWorldcoverWaterColumn(int blockX, int blockZ, double waterSurfaceMeters,
                                                                          double distanceToShoreBlocks) {
        Optional<Double> height = heightMeters(blockX, blockZ);
        if (height.isEmpty() || !Double.isFinite(height.get()) || !Double.isFinite(waterSurfaceMeters)) {
            return false;
        }
        double tolerance = AtlasWorldgenConfig.LAKE_BASIN_WATER_COLUMN_MAX_ABOVE_SURFACE_METERS.get();
        int shoreScan = Math.max(0, AtlasWorldgenConfig.LAKE_TOPOGRAPHIC_SHORE_SCAN_BLOCKS.get());
        if (shoreScan > 0 && distanceToShoreBlocks <= shoreScan) {
            tolerance = Math.min(tolerance, AtlasWorldgenConfig.LAKE_BASIN_EDGE_WATER_COLUMN_MAX_ABOVE_SURFACE_METERS.get());
        }
        // If DEM says this water-mask pixel is significantly above the fitted water level,
        // it is not water in our worldgen. It is a shore/bank/mixed raster pixel.
        return height.get() <= waterSurfaceMeters + Math.max(0.0D, tolerance);
    }

    private static double lowMean(List<Double> sortedValues, double fraction) {
        if (sortedValues.isEmpty()) {
            return Double.NaN;
        }
        double f = Math.max(0.02D, Math.min(1.0D, fraction));
        int count = Math.max(1, (int) Math.ceil(sortedValues.size() * f));
        count = Math.min(count, sortedValues.size());
        double sum = 0.0D;
        for (int i = 0; i < count; i++) {
            sum += sortedValues.get(i);
        }
        return sum / count;
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return Double.NaN;
        }
        double p = Math.max(0.0D, Math.min(1.0D, percentile));
        int index = (int) Math.floor(p * (sortedValues.size() - 1));
        index = Math.max(0, Math.min(sortedValues.size() - 1, index));
        return sortedValues.get(index);
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
                if (sample.get().landcover() == LandcoverClass.WATER
                        && !isRiverClaimed(blockX + dx, blockZ + dz)) {
                    water++;
                }
            }
        }
        return new WaterStats(water, samples);
    }

    private static double estimateDistanceToShore(int blockX, int blockZ, int step, int maxRadius) {
        double shoreLandFraction = AtlasWorldgenConfig.LAKE_SHORE_RING_LAND_FRACTION.get();
        for (int radius = step; radius <= maxRadius; radius += step) {
            int land = 0;
            int samples = 0;
            for (int dz = -radius; dz <= radius; dz += step) {
                for (int dx = -radius; dx <= radius; dx += step) {
                    double distSq = dx * (double) dx + dz * (double) dz;
                    if (distSq > radius * (double) radius || distSq < (radius - step) * (double) (radius - step)) {
                        continue;
                    }
                    GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX + dx, blockZ + dz);
                    Optional<LandcoverSample> sample = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
                    if (sample.isEmpty()) {
                        continue;
                    }
                    samples++;
                    if (sample.get().landcover() != LandcoverClass.WATER
                            || isRiverClaimed(blockX + dx, blockZ + dz)) {
                        land++;
                    }
                }
            }
            if (samples > 0 && land / (double) samples >= shoreLandFraction) {
                return radius;
            }
        }
        return maxRadius;
    }

    private static NearWater findNearWorldcoverWater(int blockX, int blockZ, int radius, int step, boolean resolveSurface) {
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
                if (landcover.isEmpty() || landcover.get().landcover() != LandcoverClass.WATER
                        || isRiverClaimed(sx, sz)) {
                    continue;
                }
                Optional<HeightSample> waterHeight = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
                if (waterHeight.isEmpty()) {
                    continue;
                }
                if (!resolveSurface) {
                    if (waterHeight.get().meters() < AtlasWorldgenConfig.LAKE_MIN_SURFACE_METERS.get()) {
                        continue;
                    }
                    bestDistanceSq = distanceSq;
                    bestSurface = Double.NaN;
                    bestSource = landcover.get().sourceId();
                    continue;
                }
                double stableSurface = estimateWorldcoverWaterSurface(sx, sz, waterHeight.get().meters());
                if (stableSurface < AtlasWorldgenConfig.LAKE_MIN_SURFACE_METERS.get()) {
                    continue;
                }
                BasinFit fit = estimateWorldcoverBasinFit(sx, sz, waterHeight.get().meters(), landcover.get().sourceId());
                if (fit.found() && fit.waterSamples() < AtlasWorldgenConfig.LAKE_WORLDCOVER_MIN_COMPONENT_SAMPLES.get()) {
                    continue;
                }
                // For surface polishing we need the whole non-water boundary around the water mask,
                // even when DEM says that boundary is a high quarry wall/field. High terrain will be
                // left intact by the shore polisher, while low generated terrain is raised into a bank.
                bestDistanceSq = distanceSq;
                bestSurface = stableSurface;
                bestSource = landcover.get().sourceId();
            }
        }
        if (!Double.isFinite(bestDistanceSq)) {
            return NearWater.none();
        }
        return new NearWater(true, Math.sqrt(bestDistanceSq), bestSurface, bestSource);
    }

    private static boolean isResidualWorldcoverWater(int blockX, int blockZ) {
        return isWorldcoverWater(blockX, blockZ) && !isRiverClaimed(blockX, blockZ);
    }

    private static boolean isRiverClaimed(int blockX, int blockZ) {
        return AtlasRiverIndex.active().sample(blockX, blockZ).hasRiverData();
    }

    private static long gridKey(int gx, int gz) {
        return (((long) gx) << 32) ^ (gz & 0xffffffffL);
    }

    private static long cacheKey(int gx, int gz) {
        return HashCommon.mix(gridKey(gx, gz));
    }

    private static GridCell decodeGridKey(long key) {
        return new GridCell((int) (key >> 32), (int) key);
    }

    private record GridCell(int gx, int gz) {
    }

    private record WaterStats(int water, int samples) {
        double fraction() {
            return samples == 0 ? 0.0D : water / (double) samples;
        }
    }

    private record BasinFit(boolean found, double surfaceMeters, double rawSurfaceMeters, double rimMeters,
                            int waterSamples, int coreSamples, int rimSamples, String lakeId, String sourceId) {
        static BasinFit none() {
            return new BasinFit(false, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, "none", "none");
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
