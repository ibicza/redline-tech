package com.ibicza.redlineatlasworldgen.bathymetry;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.heightmap.HeightSample;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AtlasOpenWaterGuide {
    private static final ConcurrentMap<Long, OpenWaterSample> BIOME_SAMPLE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Long, OpenWaterSample> COASTAL_FLOOD_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger CACHE_CLEAR_GUARD = new AtomicInteger();
    private static final AtomicInteger FLOOD_CACHE_CLEAR_GUARD = new AtomicInteger();

    /**
     * Exact/debug sample. This may scan a coast radius and is intentionally used by commands such as
     * /rla water_sample and /rla nearest_ocean.
     */
    public static OpenWaterSample sample(int blockX, int blockZ) {
        return computeSample(blockX, blockZ, true);
    }

    /**
     * Cached worldgen sample. Vanilla asks biomes at many Y quart levels in tall worlds; water is mostly
     * an XZ classification, so biome generation must not scan the coast radius on every call.
     */
    public static OpenWaterSample sampleForBiome(int blockX, int blockZ) {
        long started = AtlasWorldgenProfiler.start();
        try {
            if (!AtlasWorldgenConfig.OPEN_WATER_GUIDE_ENABLED.get()) {
                return OpenWaterSample.none();
            }

            int cell = Math.max(4, AtlasWorldgenConfig.OPEN_WATER_CACHE_CELL_SIZE_BLOCKS.get());
            int cellX = Math.floorDiv(blockX, cell);
            int cellZ = Math.floorDiv(blockZ, cell);
            long key = (((long) cellX) << 32) ^ (cellZ & 0xffffffffL);

            OpenWaterSample cached = BIOME_SAMPLE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }

            if (BIOME_SAMPLE_CACHE.size() > AtlasWorldgenConfig.OPEN_WATER_SAMPLE_CACHE_LIMIT.get()
                    && CACHE_CLEAR_GUARD.compareAndSet(0, 1)) {
                try {
                    BIOME_SAMPLE_CACHE.clear();
                } finally {
                    CACHE_CLEAR_GUARD.set(0);
                }
            }

            int sampleX = cellX * cell + cell / 2;
            int sampleZ = cellZ * cell + cell / 2;
            boolean scanCoast = AtlasWorldgenConfig.OPEN_WATER_ENABLE_COAST_SCAN_IN_BIOME_GUIDE.get();
            OpenWaterSample computed = computeSample(sampleX, sampleZ, scanCoast);
            OpenWaterSample existing = BIOME_SAMPLE_CACHE.putIfAbsent(key, computed);
            return existing == null ? computed : existing;
        } finally {
            AtlasWorldgenProfiler.recordSince("water.sample.cached", started);
        }
    }

    public static int cacheSize() {
        return BIOME_SAMPLE_CACHE.size();
    }

    public static int coastalFloodCacheSize() {
        return COASTAL_FLOOD_CACHE.size();
    }

    public static void clearCache() {
        BIOME_SAMPLE_CACHE.clear();
        COASTAL_FLOOD_CACHE.clear();
    }

    public static Optional<HeightSample> compositeHeightSample(int blockX, int blockZ) {
        long started = AtlasWorldgenProfiler.start();
        try {
            GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
            Optional<HeightSample> land = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
            Optional<OceanBathymetrySample> ocean = AtlasOceanBathymetryIndex.active().sample(geo.latitude(), geo.longitude());
            if (ocean.isPresent()) {
                double seaLevelMeters = AtlasWorldgenConfig.OPEN_WATER_SEA_LEVEL_METERS.get();
                double landOverride = AtlasWorldgenConfig.OPEN_WATER_LAND_OVERRIDE_METERS.get();
                if (land.isEmpty() || land.get().meters() <= seaLevelMeters + landOverride) {
                    OceanBathymetrySample sample = ocean.get();
                    return Optional.of(new HeightSample(sample.bottomMeters(), "ocean:" + sample.sourceId(), sample.nominalResolutionMeters()));
                }
            }

            OpenWaterSample flood = coastalFloodSample(blockX, blockZ);
            if (flood.kind() == OpenWaterKind.OCEAN_FLOOD) {
                return Optional.of(new HeightSample(flood.bottomMeters(), flood.sourceId(), flood.resolutionMeters()));
            }

            return land;
        } finally {
            AtlasWorldgenProfiler.recordSince("water.compositeHeight", started);
        }
    }

    private static OpenWaterSample computeSample(int blockX, int blockZ, boolean allowCoastScan) {
        long started = AtlasWorldgenProfiler.start();
        try {
            if (!AtlasWorldgenConfig.OPEN_WATER_GUIDE_ENABLED.get()) {
                return OpenWaterSample.none();
            }

            GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
            Optional<HeightSample> land = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
            Optional<OceanBathymetrySample> ocean = AtlasOceanBathymetryIndex.active().sample(geo.latitude(), geo.longitude());
            double seaLevelMeters = AtlasWorldgenConfig.OPEN_WATER_SEA_LEVEL_METERS.get();
            double landOverride = AtlasWorldgenConfig.OPEN_WATER_LAND_OVERRIDE_METERS.get();

            if (ocean.isPresent() && !(land.isPresent() && land.get().meters() > seaLevelMeters + landOverride)) {
                OceanBathymetrySample water = ocean.get();
                return new OpenWaterSample(OpenWaterKind.OCEAN, true, true, 0.0D, water.depthMeters(), water.bottomMeters(), seaLevelMeters,
                        water.sourceId(), water.nominalResolutionMeters());
            }

            OpenWaterSample flood = coastalFloodSample(blockX, blockZ);
            if (flood.kind() == OpenWaterKind.OCEAN_FLOOD) {
                return flood;
            }

            if (allowCoastScan) {
                NearOcean near = findNearOcean(blockX, blockZ, seaLevelMeters, landOverride);
                if (near.found()) {
                    return new OpenWaterSample(OpenWaterKind.COAST, true, false, near.distanceBlocks(), near.depthMeters(), near.bottomMeters(), seaLevelMeters,
                            near.sourceId(), near.resolutionMeters());
                }
            }

            Optional<OceanBathymetrySample> raw = AtlasOceanBathymetryIndex.active().rawSample(geo.latitude(), geo.longitude());
            if (raw.isPresent()) {
                return new OpenWaterSample(OpenWaterKind.NON_OCEAN_OR_LAND_GEBCO, false, false, Double.POSITIVE_INFINITY, raw.get().depthMeters(), raw.get().bottomMeters(), seaLevelMeters,
                        raw.get().sourceId(), raw.get().nominalResolutionMeters());
            }

            return OpenWaterSample.none();
        } finally {
            AtlasWorldgenProfiler.recordSince("water.sample", started);
        }
    }

    private static OpenWaterSample coastalFloodSample(int blockX, int blockZ) {
        long started = AtlasWorldgenProfiler.start();
        try {
            if (!AtlasWorldgenConfig.OPEN_WATER_COASTAL_FLOOD_ENABLED.get()
                    || AtlasWorldgenConfig.OPEN_WATER_COASTAL_FLOOD_MAX_DISTANCE_BLOCKS.get() <= 0) {
                return OpenWaterSample.none();
            }

            int cell = Math.max(4, AtlasWorldgenConfig.OPEN_WATER_COASTAL_FLOOD_CELL_SIZE_BLOCKS.get());
            int cellX = Math.floorDiv(blockX, cell);
            int cellZ = Math.floorDiv(blockZ, cell);
            long key = cellKey(cellX, cellZ);
            OpenWaterSample cached = COASTAL_FLOOD_CACHE.get(key);
            if (cached != null) {
                return cached;
            }

            if (COASTAL_FLOOD_CACHE.size() > AtlasWorldgenConfig.OPEN_WATER_COASTAL_FLOOD_CACHE_LIMIT.get()
                    && FLOOD_CACHE_CLEAR_GUARD.compareAndSet(0, 1)) {
                try {
                    COASTAL_FLOOD_CACHE.clear();
                } finally {
                    FLOOD_CACHE_CLEAR_GUARD.set(0);
                }
            }

            OpenWaterSample computed = computeCoastalFloodCell(cellX, cellZ, cell);
            OpenWaterSample existing = COASTAL_FLOOD_CACHE.putIfAbsent(key, computed);
            return existing == null ? computed : existing;
        } finally {
            AtlasWorldgenProfiler.recordSince("water.coastalFlood", started);
        }
    }

    private static OpenWaterSample computeCoastalFloodCell(int originCellX, int originCellZ, int cell) {
        double seaLevelMeters = AtlasWorldgenConfig.OPEN_WATER_SEA_LEVEL_METERS.get();
        double toleranceMeters = AtlasWorldgenConfig.OPEN_WATER_COASTAL_FLOOD_TOLERANCE_METERS.get();
        double landOverride = AtlasWorldgenConfig.OPEN_WATER_LAND_OVERRIDE_METERS.get();
        int maxDistanceBlocks = Math.max(0, AtlasWorldgenConfig.OPEN_WATER_COASTAL_FLOOD_MAX_DISTANCE_BLOCKS.get());
        int radiusCells = Math.max(1, (int) Math.ceil(maxDistanceBlocks / (double) cell));

        CellState origin = cellState(originCellX, originCellZ, cell, seaLevelMeters, toleranceMeters, landOverride);
        if (origin.seedOcean()) {
            OceanBathymetrySample ocean = origin.oceanSample();
            return new OpenWaterSample(OpenWaterKind.OCEAN, true, true, 0.0D, ocean.depthMeters(), ocean.bottomMeters(), seaLevelMeters,
                    ocean.sourceId(), ocean.nominalResolutionMeters());
        }
        if (!origin.floodCandidate()) {
            return OpenWaterSample.none();
        }

        ArrayDeque<CellNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.addLast(new CellNode(originCellX, originCellZ, 0));
        visited.add(cellKey(originCellX, originCellZ));

        while (!queue.isEmpty()) {
            CellNode node = queue.removeFirst();
            CellState state = cellState(node.cellX(), node.cellZ(), cell, seaLevelMeters, toleranceMeters, landOverride);
            if (state.seedOcean()) {
                double distanceBlocks = Math.hypot((node.cellX() - originCellX) * (double) cell, (node.cellZ() - originCellZ) * (double) cell);
                return inferredFloodSample(origin, state.oceanSample(), distanceBlocks, seaLevelMeters, cell);
            }
            if (!state.floodCandidate()) {
                continue;
            }
            if (node.steps() >= radiusCells) {
                continue;
            }
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = node.cellX() + dx;
                    int nz = node.cellZ() + dz;
                    if (Math.abs(nx - originCellX) > radiusCells || Math.abs(nz - originCellZ) > radiusCells) {
                        continue;
                    }
                    long key = cellKey(nx, nz);
                    if (visited.add(key)) {
                        queue.addLast(new CellNode(nx, nz, node.steps() + 1));
                    }
                }
            }
        }

        return OpenWaterSample.none();
    }

    private static OpenWaterSample inferredFloodSample(CellState origin, OceanBathymetrySample seedOcean, double distanceBlocks,
                                                       double seaLevelMeters, int cellSizeBlocks) {
        double minDepth = AtlasWorldgenConfig.OPEN_WATER_COASTAL_FLOOD_MIN_DEPTH_METERS.get();
        double maxDepth = AtlasWorldgenConfig.OPEN_WATER_COASTAL_FLOOD_MAX_DEPTH_METERS.get();
        double falloff = distanceBlocks / Math.max(1.0D, cellSizeBlocks * 2.0D);
        double inferredDepth = Math.max(minDepth, Math.min(maxDepth, maxDepth - falloff));
        double landMeters = origin.landMeters().orElse(seaLevelMeters);
        double bottomMeters = Math.min(landMeters - 0.5D, seaLevelMeters - inferredDepth);
        double depthMeters = Math.max(minDepth, seaLevelMeters - bottomMeters);
        return new OpenWaterSample(OpenWaterKind.OCEAN_FLOOD, true, false, distanceBlocks, depthMeters, bottomMeters, seaLevelMeters,
                "ocean_flood:" + seedOcean.sourceId(), seedOcean.nominalResolutionMeters());
    }

    private static CellState cellState(int cellX, int cellZ, int cell, double seaLevelMeters, double toleranceMeters, double landOverrideMeters) {
        int blockX = cellX * cell + cell / 2;
        int blockZ = cellZ * cell + cell / 2;
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        Optional<HeightSample> land = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
        Optional<OceanBathymetrySample> ocean = AtlasOceanBathymetryIndex.active().sample(geo.latitude(), geo.longitude());

        boolean seedOcean = ocean.isPresent() && !(land.isPresent() && land.get().meters() > seaLevelMeters + landOverrideMeters);
        boolean floodCandidate = land.isPresent() && land.get().meters() <= seaLevelMeters + toleranceMeters;
        if (seedOcean) {
            floodCandidate = true;
        }
        return new CellState(seedOcean, floodCandidate, land.map(HeightSample::meters), ocean.orElse(null));
    }

    private static long cellKey(int cellX, int cellZ) {
        return (((long) cellX) << 32) ^ (cellZ & 0xffffffffL);
    }

    private static NearOcean findNearOcean(int blockX, int blockZ, double seaLevelMeters, double landOverride) {
        int radius = Math.max(0, AtlasWorldgenConfig.OPEN_WATER_COAST_RADIUS_BLOCKS.get());
        if (radius <= 0) {
            return NearOcean.none();
        }
        int step = Math.max(1, AtlasWorldgenConfig.OPEN_WATER_COAST_STEP_BLOCKS.get());
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        OceanBathymetrySample best = null;
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
                Optional<OceanBathymetrySample> ocean = AtlasOceanBathymetryIndex.active().sample(geo.latitude(), geo.longitude());
                if (ocean.isEmpty()) {
                    continue;
                }
                Optional<HeightSample> land = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
                if (land.isPresent() && land.get().meters() > seaLevelMeters + landOverride) {
                    continue;
                }
                bestDistanceSq = distanceSq;
                best = ocean.get();
            }
        }
        if (best == null) {
            return NearOcean.none();
        }
        return new NearOcean(true, Math.sqrt(bestDistanceSq), best.depthMeters(), best.bottomMeters(), best.sourceId(), best.nominalResolutionMeters());
    }

    public enum OpenWaterKind {
        NONE,
        OCEAN,
        OCEAN_FLOOD,
        COAST,
        NON_OCEAN_OR_LAND_GEBCO
    }

    public record OpenWaterSample(OpenWaterKind kind, boolean hasOpenWaterData, boolean exactWater,
                                  double distanceToOceanBlocks, double depthMeters, double bottomMeters,
                                  double waterSurfaceMeters, String sourceId, double resolutionMeters) {
        public static OpenWaterSample none() {
            return new OpenWaterSample(OpenWaterKind.NONE, false, false, Double.POSITIVE_INFINITY, 0.0D, Double.NaN, Double.NaN, "none", 0.0D);
        }
    }

    private record NearOcean(boolean found, double distanceBlocks, double depthMeters, double bottomMeters, String sourceId, double resolutionMeters) {
        private static NearOcean none() {
            return new NearOcean(false, Double.POSITIVE_INFINITY, 0.0D, Double.NaN, "none", 0.0D);
        }
    }

    private record CellNode(int cellX, int cellZ, int steps) {
    }

    private record CellState(boolean seedOcean, boolean floodCandidate, Optional<Double> landMeters, OceanBathymetrySample oceanSample) {
    }

    private AtlasOpenWaterGuide() {
    }
}
