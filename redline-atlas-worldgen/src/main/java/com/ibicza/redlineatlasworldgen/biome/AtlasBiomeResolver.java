package com.ibicza.redlineatlasworldgen.biome;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.heightmap.HeightSample;
import com.ibicza.redlineatlasworldgen.landcover.AtlasLandcoverIndex;
import com.ibicza.redlineatlasworldgen.landcover.LandcoverClass;
import com.ibicza.redlineatlasworldgen.landcover.LandcoverSample;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class AtlasBiomeResolver {
    public static Optional<AtlasBiomeContext> context(int blockX, int blockY, int blockZ, long seed) {
        if (!AtlasWorldgenConfig.BIOME_GUIDE_ENABLED.get()) {
            return Optional.empty();
        }

        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        Optional<HeightSample> height = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
        if (height.isEmpty()) {
            return Optional.empty();
        }

        LandcoverDecision landcover = landcoverDecision(blockX, blockZ, geo);
        double elevationMeters = height.get().meters();
        int surfaceY = AtlasCoordinateMapper.metersToWorldY(elevationMeters);
        int relativeY = blockY - surfaceY;
        SlopeInfo slope = slope(blockX, blockZ, elevationMeters);
        double temperature = temperatureC(blockX, blockZ, geo.latitude(), elevationMeters, seed);
        double humidity = humidity01(blockX, blockZ, landcover.landcover(), elevationMeters, slope.roughness(), geo.latitude(), seed);

        return Optional.of(new AtlasBiomeContext(blockX, blockY, blockZ,
                geo.latitude(), geo.longitude(), elevationMeters, surfaceY, relativeY,
                landcover.landcover(), landcover.rawCode(), landcover.source(), slope.slope(), slope.roughness(),
                temperature, humidity, WaterContext.NONE, seed));
    }

    public static ResourceKey<Biome> resolve(AtlasBiomeContext ctx, ResourceKey<Biome> vanillaBiome) {
        if (ctx.relativeY() < AtlasWorldgenConfig.BIOME_SURFACE_RELATIVE_MIN_Y.get()) {
            return vanillaBiome;
        }
        if (ctx.landcover() == LandcoverClass.WATER) {
            // Water/coast/river layer is intentionally not active yet. Avoid creating slope-water bugs again.
            return vanillaBiome;
        }

        double strength = clamp01(AtlasWorldgenConfig.BIOME_GUIDE_STRENGTH.get());
        if (strength <= 0.0D) {
            return vanillaBiome;
        }
        if (strength < 1.0D) {
            double chance = cellNoise01(ctx.worldX(), ctx.worldZ(), ctx.seed(), 911);
            if (chance > strength) {
                return vanillaBiome;
            }
        }

        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_EXTREME_PEAK_METERS.get()) {
            return configured(ctx.slope() >= AtlasWorldgenConfig.BIOME_STEEP_SLOPE.get()
                    ? AtlasWorldgenConfig.BIOME_HIGH_STEEP.get()
                    : AtlasWorldgenConfig.BIOME_HIGH_FLAT.get(), vanillaBiome);
        }
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_NIVAL_METERS.get()) {
            return configured(ctx.slope() >= AtlasWorldgenConfig.BIOME_STEEP_SLOPE.get()
                    ? AtlasWorldgenConfig.BIOME_HIGH_STEEP.get()
                    : AtlasWorldgenConfig.BIOME_HIGH_FLAT.get(), vanillaBiome);
        }
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_ALPINE_METERS.get() && ctx.temperatureC() < AtlasWorldgenConfig.BIOME_COLD_TEMPERATURE_C.get() + 2.0D) {
            return configured(ctx.slope() >= AtlasWorldgenConfig.BIOME_STEEP_SLOPE.get()
                    ? AtlasWorldgenConfig.BIOME_ALPINE_STEEP.get()
                    : AtlasWorldgenConfig.BIOME_ALPINE_FLAT.get(), vanillaBiome);
        }

        return switch (ctx.landcover()) {
            case SNOW_ICE -> resolveSnowIce(ctx, vanillaBiome);
            case BARE_SPARSE -> resolveBare(ctx, vanillaBiome);
            case TREES -> resolveTrees(ctx, vanillaBiome);
            case GRASS -> resolveGrass(ctx, vanillaBiome);
            case SHRUB -> resolveShrub(ctx, vanillaBiome);
            case CROPLAND, URBAN -> resolveGrassLike(ctx, vanillaBiome);
            case WETLAND -> configured(AtlasWorldgenConfig.BIOME_WETLAND.get(), vanillaBiome);
            case MANGROVE -> configured(AtlasWorldgenConfig.BIOME_MANGROVE.get(), vanillaBiome);
            case MOSS_LICHEN -> ctx.temperatureC() < AtlasWorldgenConfig.BIOME_COLD_TEMPERATURE_C.get()
                    ? configured(AtlasWorldgenConfig.BIOME_TREES_COLD.get(), vanillaBiome)
                    : configured(AtlasWorldgenConfig.BIOME_BARE_ROUGH.get(), vanillaBiome);
            case UNKNOWN, WATER -> fallbackByHeight(ctx, vanillaBiome);
        };
    }

    private static ResourceKey<Biome> resolveSnowIce(AtlasBiomeContext ctx, ResourceKey<Biome> vanillaBiome) {
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_ALPINE_METERS.get()) {
            return configured(ctx.slope() >= AtlasWorldgenConfig.BIOME_STEEP_SLOPE.get()
                    ? AtlasWorldgenConfig.BIOME_ALPINE_STEEP.get()
                    : AtlasWorldgenConfig.BIOME_HIGH_FLAT.get(), vanillaBiome);
        }
        return configured(AtlasWorldgenConfig.BIOME_SNOW_LOW.get(), vanillaBiome);
    }

    private static ResourceKey<Biome> resolveBare(AtlasBiomeContext ctx, ResourceKey<Biome> vanillaBiome) {
        if (ctx.temperatureC() > AtlasWorldgenConfig.BIOME_HOT_TEMPERATURE_C.get() && ctx.humidity01() < AtlasWorldgenConfig.BIOME_DRY_HUMIDITY.get()) {
            return configured(AtlasWorldgenConfig.BIOME_BARE_HOT_DRY.get(), vanillaBiome);
        }
        if (ctx.slope() >= AtlasWorldgenConfig.BIOME_STEEP_SLOPE.get() || ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_MONTANE_METERS.get()) {
            return configured(AtlasWorldgenConfig.BIOME_BARE_ROUGH.get(), vanillaBiome);
        }
        if (ctx.temperatureC() < AtlasWorldgenConfig.BIOME_FREEZING_TEMPERATURE_C.get()) {
            return configured(AtlasWorldgenConfig.BIOME_SNOW_LOW.get(), vanillaBiome);
        }
        return configured(AtlasWorldgenConfig.BIOME_GRASS_TEMPERATE.get(), vanillaBiome);
    }

    private static ResourceKey<Biome> resolveTrees(AtlasBiomeContext ctx, ResourceKey<Biome> vanillaBiome) {
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_ALPINE_METERS.get()) {
            return configured(ctx.temperatureC() < AtlasWorldgenConfig.BIOME_COLD_TEMPERATURE_C.get()
                    ? AtlasWorldgenConfig.BIOME_ALPINE_STEEP.get()
                    : AtlasWorldgenConfig.BIOME_ALPINE_FLAT.get(), vanillaBiome);
        }
        if (ctx.temperatureC() >= AtlasWorldgenConfig.BIOME_TROPICAL_TEMPERATURE_C.get()
                && ctx.humidity01() >= AtlasWorldgenConfig.BIOME_TROPICAL_WET_HUMIDITY.get()) {
            return configured(AtlasWorldgenConfig.BIOME_TREES_TROPICAL_WET.get(), vanillaBiome);
        }
        if (ctx.temperatureC() >= AtlasWorldgenConfig.BIOME_HOT_TEMPERATURE_C.get()
                && ctx.humidity01() < AtlasWorldgenConfig.BIOME_DRY_HUMIDITY.get()) {
            return configured(AtlasWorldgenConfig.BIOME_GRASS_HOT_DRY.get(), vanillaBiome);
        }
        if (ctx.temperatureC() >= AtlasWorldgenConfig.BIOME_COLD_TEMPERATURE_C.get() + 6.0D) {
            return configured(ctx.humidity01() >= AtlasWorldgenConfig.BIOME_WET_HUMIDITY.get()
                    ? AtlasWorldgenConfig.BIOME_TREES_TEMPERATE_WET.get()
                    : AtlasWorldgenConfig.BIOME_TREES_TEMPERATE.get(), vanillaBiome);
        }
        return configured(AtlasWorldgenConfig.BIOME_TREES_COLD.get(), vanillaBiome);
    }

    private static ResourceKey<Biome> resolveGrass(AtlasBiomeContext ctx, ResourceKey<Biome> vanillaBiome) {
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_ALPINE_METERS.get()) {
            return configured(ctx.temperatureC() < AtlasWorldgenConfig.BIOME_FREEZING_TEMPERATURE_C.get()
                    ? AtlasWorldgenConfig.BIOME_ALPINE_STEEP.get()
                    : AtlasWorldgenConfig.BIOME_ALPINE_FLAT.get(), vanillaBiome);
        }
        if (ctx.temperatureC() >= AtlasWorldgenConfig.BIOME_HOT_TEMPERATURE_C.get()
                && ctx.humidity01() < AtlasWorldgenConfig.BIOME_DRY_HUMIDITY.get()) {
            return configured(AtlasWorldgenConfig.BIOME_GRASS_HOT_DRY.get(), vanillaBiome);
        }
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_MONTANE_METERS.get()) {
            return configured(AtlasWorldgenConfig.BIOME_GRASS_HIGH.get(), vanillaBiome);
        }
        if (ctx.temperatureC() < AtlasWorldgenConfig.BIOME_COLD_TEMPERATURE_C.get()) {
            return configured(AtlasWorldgenConfig.BIOME_SNOW_LOW.get(), vanillaBiome);
        }
        return configured(AtlasWorldgenConfig.BIOME_GRASS_TEMPERATE.get(), vanillaBiome);
    }

    private static ResourceKey<Biome> resolveShrub(AtlasBiomeContext ctx, ResourceKey<Biome> vanillaBiome) {
        if (ctx.temperatureC() >= AtlasWorldgenConfig.BIOME_HOT_TEMPERATURE_C.get()
                && ctx.humidity01() < AtlasWorldgenConfig.BIOME_DRY_HUMIDITY.get() + 0.07D) {
            return configured(AtlasWorldgenConfig.BIOME_SHRUB_HOT_DRY.get(), vanillaBiome);
        }
        if (ctx.slope() >= AtlasWorldgenConfig.BIOME_STEEP_SLOPE.get()) {
            return configured(AtlasWorldgenConfig.BIOME_BARE_ROUGH.get(), vanillaBiome);
        }
        return resolveGrass(ctx, vanillaBiome);
    }

    private static ResourceKey<Biome> resolveGrassLike(AtlasBiomeContext ctx, ResourceKey<Biome> vanillaBiome) {
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_MONTANE_METERS.get()) {
            return configured(AtlasWorldgenConfig.BIOME_GRASS_HIGH.get(), vanillaBiome);
        }
        if (ctx.temperatureC() >= AtlasWorldgenConfig.BIOME_HOT_TEMPERATURE_C.get()
                && ctx.humidity01() < AtlasWorldgenConfig.BIOME_DRY_HUMIDITY.get()) {
            return configured(AtlasWorldgenConfig.BIOME_GRASS_HOT_DRY.get(), vanillaBiome);
        }
        if (ctx.temperatureC() < AtlasWorldgenConfig.BIOME_COLD_TEMPERATURE_C.get()) {
            return configured(AtlasWorldgenConfig.BIOME_SNOW_LOW.get(), vanillaBiome);
        }
        return configured(AtlasWorldgenConfig.BIOME_GRASS_TEMPERATE.get(), vanillaBiome);
    }

    private static ResourceKey<Biome> fallbackByHeight(AtlasBiomeContext ctx, ResourceKey<Biome> vanillaBiome) {
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_NIVAL_METERS.get()) {
            return configured(AtlasWorldgenConfig.BIOME_HIGH_FLAT.get(), vanillaBiome);
        }
        if (ctx.elevationMeters() >= AtlasWorldgenConfig.BIOME_ALPINE_METERS.get()) {
            return configured(AtlasWorldgenConfig.BIOME_ALPINE_FLAT.get(), vanillaBiome);
        }
        return vanillaBiome;
    }

    private static LandcoverDecision landcoverDecision(int blockX, int blockZ, GeoPoint centerGeo) {
        Optional<LandcoverSample> center = AtlasLandcoverIndex.active().sample(centerGeo.latitude(), centerGeo.longitude());
        int radius = Math.max(0, AtlasWorldgenConfig.BIOME_LANDCOVER_SMOOTH_RADIUS_BLOCKS.get());
        if (radius <= 0) {
            return fromSample(center, "local");
        }

        int step = Math.max(1, AtlasWorldgenConfig.BIOME_LANDCOVER_SMOOTH_STEP_BLOCKS.get());
        boolean ignoreWater = AtlasWorldgenConfig.BIOME_IGNORE_WATER_LANDCOVER.get();
        EnumMap<LandcoverClass, Integer> counts = new EnumMap<>(LandcoverClass.class);
        int samples = 0;
        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                GeoPoint geo = dx == 0 && dz == 0 ? centerGeo : AtlasCoordinateMapper.toGeo(blockX + dx, blockZ + dz);
                Optional<LandcoverSample> sample = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
                if (sample.isEmpty()) {
                    continue;
                }
                LandcoverClass landcover = sample.get().landcover();
                if (ignoreWater && landcover == LandcoverClass.WATER) {
                    continue;
                }
                counts.merge(landcover, 1, Integer::sum);
                samples++;
            }
        }

        if (counts.isEmpty()) {
            if (center.isPresent() && (!ignoreWater || center.get().landcover() != LandcoverClass.WATER)) {
                return fromSample(center, "local");
            }
            return new LandcoverDecision(LandcoverClass.UNKNOWN, 0, "none", 0);
        }

        LandcoverClass centerClass = center.map(LandcoverSample::landcover).orElse(LandcoverClass.UNKNOWN);
        LandcoverClass best = LandcoverClass.UNKNOWN;
        int bestCount = -1;
        for (Map.Entry<LandcoverClass, Integer> entry : counts.entrySet()) {
            LandcoverClass candidate = entry.getKey();
            int count = entry.getValue();
            if (count > bestCount
                    || (count == bestCount && candidate == centerClass)
                    || (count == bestCount && candidate != centerClass && landcoverPriority(candidate) > landcoverPriority(best))) {
                best = candidate;
                bestCount = count;
            }
        }
        return new LandcoverDecision(best, best.esaCode(), "smoothed:" + bestCount + "/" + samples, samples);
    }

    private static LandcoverDecision fromSample(Optional<LandcoverSample> sample, String sourcePrefix) {
        if (sample.isEmpty()) {
            return new LandcoverDecision(LandcoverClass.UNKNOWN, 0, "none", 0);
        }
        LandcoverSample value = sample.get();
        return new LandcoverDecision(value.landcover(), value.rawCode(), sourcePrefix + ":" + value.sourceId(), 1);
    }

    private static int landcoverPriority(LandcoverClass landcover) {
        return switch (landcover) {
            case SNOW_ICE -> 100;
            case WETLAND, MANGROVE -> 95;
            case TREES -> 90;
            case BARE_SPARSE -> 80;
            case GRASS -> 70;
            case SHRUB -> 60;
            case CROPLAND -> 50;
            case URBAN -> 40;
            case MOSS_LICHEN -> 30;
            case WATER -> 20;
            case UNKNOWN -> 0;
        };
    }

    private static ResourceKey<Biome> configured(String value, ResourceKey<Biome> fallback) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return fallback;
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }

        return switch (id) {
            case "minecraft:plains" -> Biomes.PLAINS;
            case "minecraft:sunflower_plains" -> Biomes.SUNFLOWER_PLAINS;
            case "minecraft:snowy_plains" -> Biomes.SNOWY_PLAINS;
            case "minecraft:ice_spikes" -> Biomes.ICE_SPIKES;

            case "minecraft:forest" -> Biomes.FOREST;
            case "minecraft:flower_forest" -> Biomes.FLOWER_FOREST;
            case "minecraft:birch_forest" -> Biomes.BIRCH_FOREST;
            case "minecraft:old_growth_birch_forest" -> Biomes.OLD_GROWTH_BIRCH_FOREST;
            case "minecraft:dark_forest" -> Biomes.DARK_FOREST;

            case "minecraft:taiga" -> Biomes.TAIGA;
            case "minecraft:snowy_taiga" -> Biomes.SNOWY_TAIGA;
            case "minecraft:old_growth_pine_taiga" -> Biomes.OLD_GROWTH_PINE_TAIGA;
            case "minecraft:old_growth_spruce_taiga" -> Biomes.OLD_GROWTH_SPRUCE_TAIGA;

            case "minecraft:jungle" -> Biomes.JUNGLE;
            case "minecraft:sparse_jungle" -> Biomes.SPARSE_JUNGLE;
            case "minecraft:bamboo_jungle" -> Biomes.BAMBOO_JUNGLE;

            case "minecraft:savanna" -> Biomes.SAVANNA;
            case "minecraft:savanna_plateau" -> Biomes.SAVANNA_PLATEAU;
            case "minecraft:windswept_savanna" -> Biomes.WINDSWEPT_SAVANNA;

            case "minecraft:desert" -> Biomes.DESERT;
            case "minecraft:badlands" -> Biomes.BADLANDS;
            case "minecraft:eroded_badlands" -> Biomes.ERODED_BADLANDS;
            case "minecraft:wooded_badlands" -> Biomes.WOODED_BADLANDS;

            case "minecraft:windswept_hills" -> Biomes.WINDSWEPT_HILLS;
            case "minecraft:windswept_gravelly_hills" -> Biomes.WINDSWEPT_GRAVELLY_HILLS;
            case "minecraft:windswept_forest" -> Biomes.WINDSWEPT_FOREST;

            case "minecraft:meadow" -> Biomes.MEADOW;
            case "minecraft:cherry_grove" -> Biomes.CHERRY_GROVE;
            case "minecraft:grove" -> Biomes.GROVE;
            case "minecraft:snowy_slopes" -> Biomes.SNOWY_SLOPES;
            case "minecraft:jagged_peaks" -> Biomes.JAGGED_PEAKS;
            case "minecraft:frozen_peaks" -> Biomes.FROZEN_PEAKS;
            case "minecraft:stony_peaks" -> Biomes.STONY_PEAKS;

            case "minecraft:swamp" -> Biomes.SWAMP;
            case "minecraft:mangrove_swamp" -> Biomes.MANGROVE_SWAMP;

            case "minecraft:stony_shore" -> Biomes.STONY_SHORE;
            case "minecraft:beach" -> Biomes.BEACH;
            case "minecraft:snowy_beach" -> Biomes.SNOWY_BEACH;

            case "minecraft:river" -> Biomes.RIVER;
            case "minecraft:frozen_river" -> Biomes.FROZEN_RIVER;

            case "minecraft:ocean" -> Biomes.OCEAN;
            case "minecraft:cold_ocean" -> Biomes.COLD_OCEAN;
            case "minecraft:frozen_ocean" -> Biomes.FROZEN_OCEAN;
            case "minecraft:deep_ocean" -> Biomes.DEEP_OCEAN;
            case "minecraft:deep_cold_ocean" -> Biomes.DEEP_COLD_OCEAN;
            case "minecraft:deep_frozen_ocean" -> Biomes.DEEP_FROZEN_OCEAN;

            default -> fallback;
        };
    }

    private static double temperatureC(int blockX, int blockZ, double latitude, double elevationMeters, long seed) {
        double base = AtlasWorldgenConfig.BIOME_EQUATOR_TEMPERATURE_C.get()
                - Math.abs(latitude) * AtlasWorldgenConfig.BIOME_LATITUDE_TEMPERATURE_LOSS_C.get();
        double elevationLoss = Math.max(0.0D, elevationMeters) / 1000.0D * AtlasWorldgenConfig.BIOME_LAPSE_RATE_C_PER_KM.get();
        double noise = signedCellNoise(blockX, blockZ, seed, 101) * AtlasWorldgenConfig.BIOME_TEMPERATURE_NOISE_C.get();
        return base - elevationLoss + noise;
    }

    private static double humidity01(int blockX, int blockZ, LandcoverClass landcover, double elevationMeters, double roughness, double latitude, long seed) {
        double humidity = baseHumidity(landcover);
        humidity -= Math.max(0.0D, elevationMeters) / 1000.0D * AtlasWorldgenConfig.BIOME_ELEVATION_DRYING_PER_KM.get();
        humidity -= Math.max(0.0D, roughness) * AtlasWorldgenConfig.BIOME_SLOPE_DRYING.get();
        humidity += signedCellNoise(blockX, blockZ, seed, 202) * AtlasWorldgenConfig.BIOME_HUMIDITY_NOISE.get();
        if (Math.abs(latitude) > 55.0D) {
            humidity += 0.05D;
        }
        return clamp01(humidity);
    }

    private static SlopeInfo slope(int blockX, int blockZ, double centerMeters) {
        int radius = Math.max(4, AtlasWorldgenConfig.BIOME_SLOPE_RADIUS_BLOCKS.get());
        AtlasHeightmapIndex index = AtlasHeightmapIndex.active();
        double maxDelta = 0.0D;
        double sumDelta = 0.0D;
        int count = 0;
        int[][] offsets = {
                {radius, 0}, {-radius, 0}, {0, radius}, {0, -radius},
                {radius, radius}, {radius, -radius}, {-radius, radius}, {-radius, -radius}
        };
        for (int[] offset : offsets) {
            GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX + offset[0], blockZ + offset[1]);
            Optional<HeightSample> sample = index.sample(geo.latitude(), geo.longitude());
            if (sample.isPresent()) {
                double delta = Math.abs(sample.get().meters() - centerMeters);
                maxDelta = Math.max(maxDelta, delta);
                sumDelta += delta;
                count++;
            }
        }
        if (count == 0) {
            return new SlopeInfo(0.0D, 0.0D);
        }
        double runMeters = Math.max(1.0D, radius * AtlasWorldgenConfig.HORIZONTAL_METERS_PER_BLOCK.get());
        return new SlopeInfo(maxDelta / runMeters, (sumDelta / count) / runMeters);
    }

    private static double baseHumidity(LandcoverClass landcover) {
        return switch (landcover) {
            case WATER -> 1.0D;
            case WETLAND, MANGROVE -> 0.95D;
            case TREES -> 0.75D;
            case CROPLAND -> 0.60D;
            case GRASS -> 0.55D;
            case MOSS_LICHEN -> 0.45D;
            case SNOW_ICE -> 0.35D;
            case SHRUB -> 0.35D;
            case URBAN -> 0.25D;
            case BARE_SPARSE -> 0.10D;
            case UNKNOWN -> 0.50D;
        };
    }

    private static double signedCellNoise(int blockX, int blockZ, long seed, int salt) {
        return cellNoise01(blockX, blockZ, seed, salt) * 2.0D - 1.0D;
    }

    private static double cellNoise01(int blockX, int blockZ, long seed, int salt) {
        int cell = Math.max(16, AtlasWorldgenConfig.BIOME_CELL_SIZE_BLOCKS.get());
        long cx = Math.floorDiv(blockX, cell);
        long cz = Math.floorDiv(blockZ, cell);
        long h = seed ^ (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private record LandcoverDecision(LandcoverClass landcover, int rawCode, String source, int sampleCount) {
    }

    private record SlopeInfo(double slope, double roughness) {
    }

    private AtlasBiomeResolver() {
    }
}
