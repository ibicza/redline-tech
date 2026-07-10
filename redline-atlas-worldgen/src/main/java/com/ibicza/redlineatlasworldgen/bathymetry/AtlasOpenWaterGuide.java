package com.ibicza.redlineatlasworldgen.bathymetry;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.heightmap.HeightSample;

import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;

import java.util.Optional;

public final class AtlasOpenWaterGuide {
    public static OpenWaterSample sample(int blockX, int blockZ) {
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

        NearOcean near = findNearOcean(blockX, blockZ, seaLevelMeters, landOverride);
        if (near.found()) {
            return new OpenWaterSample(OpenWaterKind.COAST, true, false, near.distanceBlocks(), near.depthMeters(), near.bottomMeters(), seaLevelMeters,
                    near.sourceId(), near.resolutionMeters());
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
        return land;
        } finally {
            AtlasWorldgenProfiler.recordSince("water.compositeHeight", started);
        }
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

    private AtlasOpenWaterGuide() {
    }
}
