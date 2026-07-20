package com.ibicza.redlineatlasworldgen.profiler;

import com.ibicza.redlineatlasworldgen.bathymetry.AtlasOpenWaterGuide;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.heightmap.HeightSample;
import com.ibicza.redlineatlasworldgen.lake.AtlasLakeGuide;
import com.ibicza.redlineatlasworldgen.lake.LakeSample;
import com.ibicza.redlineatlasworldgen.river.AtlasRiverIndex;
import com.ibicza.redlineatlasworldgen.river.RiverSample;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class ChunkProfileTerrainClassifier {
    static Classification classify(int blockX, int blockZ) {
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("latitude", geo.latitude());
        details.put("longitude", geo.longitude());

        RiverSample river = AtlasRiverIndex.active().sample(blockX, blockZ);
        if (river.hasRiverData()) {
            details.put("riverKind", river.kind().name().toLowerCase(Locale.ROOT));
            details.put("riverId", river.riverId());
            details.put("distanceToCenterBlocks", river.distanceToCenterBlocks());
            details.put("halfWidthBlocks", river.halfWidthBlocks());
            details.put("waterSurfaceMeters", river.waterSurfaceMeters());
            return classification(TerrainClass.RIVER, details);
        }

        LakeSample lake = AtlasLakeGuide.sample(blockX, blockZ);
        if (AtlasLakeGuide.isLakeWater(lake.kind())) {
            details.put("lakeKind", lake.kind().name().toLowerCase(Locale.ROOT));
            details.put("lakeId", lake.lakeId());
            details.put("distanceToShoreBlocks", lake.distanceToShoreBlocks());
            details.put("waterSurfaceMeters", lake.waterSurfaceMeters());
            details.put("depthMeters", lake.depthMeters());
            details.put("sourceId", lake.sourceId());
            return classification(TerrainClass.LAKE, details);
        }

        AtlasOpenWaterGuide.OpenWaterSample water = AtlasOpenWaterGuide.sample(blockX, blockZ);
        details.put("openWaterKind", water.kind().name().toLowerCase(Locale.ROOT));
        details.put("exactOpenWater", water.exactWater());
        putFinite(details, "waterDepthMeters", water.depthMeters());
        putFinite(details, "waterBottomMeters", water.bottomMeters());
        if (water.sourceId() != null) {
            details.put("waterSourceId", water.sourceId());
        }
        if (water.kind() == AtlasOpenWaterGuide.OpenWaterKind.OCEAN) {
            return classification(TerrainClass.OCEAN, details);
        }
        if (water.kind() == AtlasOpenWaterGuide.OpenWaterKind.OCEAN_FLOOD
                || water.kind() == AtlasOpenWaterGuide.OpenWaterKind.COAST) {
            return classification(TerrainClass.COAST, details);
        }

        Optional<HeightSample> land = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
        if (land.isPresent()) {
            details.put("landHeightMeters", land.get().meters());
            details.put("landSourceId", land.get().sourceId());
            return classification(TerrainClass.ORDINARY, details);
        }
        return classification(TerrainClass.UNKNOWN, details);
    }

    static TerrainClass parseExpected(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "ordinary" -> TerrainClass.ORDINARY;
            case "river" -> TerrainClass.RIVER;
            case "lake" -> TerrainClass.LAKE;
            case "ocean" -> TerrainClass.OCEAN;
            default -> throw new IllegalArgumentException(
                    "terrainClass must be one of ordinary, river, lake, ocean: " + value
            );
        };
    }

    private static Classification classification(TerrainClass terrainClass, Map<String, Object> details) {
        return new Classification(terrainClass, Collections.unmodifiableMap(new LinkedHashMap<>(details)));
    }

    private static void putFinite(Map<String, Object> details, String name, double value) {
        if (Double.isFinite(value)) {
            details.put(name, value);
        }
    }

    enum TerrainClass {
        ORDINARY,
        RIVER,
        LAKE,
        OCEAN,
        COAST,
        UNKNOWN;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Classification(TerrainClass terrainClass, Map<String, Object> details) {
        String id() {
            return terrainClass.id();
        }
    }

    private ChunkProfileTerrainClassifier() {
    }
}
