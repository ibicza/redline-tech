package com.ibicza.redlineatlasworldgen.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AtlasWorldgenConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue SHAPE_ONLY_NEW_CHUNKS;
    public static final ModConfigSpec.BooleanValue AUTO_POST_SHAPE_CHUNKS;
    public static final ModConfigSpec.BooleanValue OVERWORLD_ONLY;
    public static final ModConfigSpec.IntValue MAX_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_COLUMNS_PER_TICK;
    public static final ModConfigSpec.ConfigValue<String> TILE_ROOT;
    public static final ModConfigSpec.DoubleValue HORIZONTAL_METERS_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue VERTICAL_METERS_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue ORIGIN_LATITUDE;
    public static final ModConfigSpec.DoubleValue ORIGIN_LONGITUDE;
    public static final ModConfigSpec.DoubleValue DEGREES_PER_BLOCK_LATITUDE;
    public static final ModConfigSpec.DoubleValue DEGREES_PER_BLOCK_LONGITUDE;
    public static final ModConfigSpec.IntValue SEA_LEVEL_Y;
    public static final ModConfigSpec.IntValue MAX_TERRAIN_DELTA_PER_PASS;
    public static final ModConfigSpec.BooleanValue FILL_WATER_BELOW_SEA_LEVEL;
    public static final ModConfigSpec.ConfigValue<String> LANDCOVER_TILE_ROOT;
    public static final ModConfigSpec.ConfigValue<String> OCEAN_BATHYMETRY_TILE_ROOT;
    public static final ModConfigSpec.BooleanValue OPEN_WATER_GUIDE_ENABLED;
    public static final ModConfigSpec.BooleanValue OPEN_WATER_USE_FOR_NOISE_GUIDE;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_SEA_LEVEL_METERS;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_MIN_OCEAN_DEPTH_METERS;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_LAND_OVERRIDE_METERS;
    public static final ModConfigSpec.IntValue OPEN_WATER_COAST_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue OPEN_WATER_COAST_STEP_BLOCKS;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_SHALLOW_DEPTH_METERS;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_DEEP_DEPTH_METERS;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_BEACH_MAX_SLOPE;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_STONY_SHORE_SLOPE;
    public static final ModConfigSpec.BooleanValue OPEN_WATER_USE_LAND_DEM_FOR_COAST;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_ENABLED;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_COLUMNS_PER_TICK;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_TOP_SCAN_BLOCKS;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_ONLY_NEW_CHUNKS;
    public static final ModConfigSpec.BooleanValue PROFILER_ENABLED;
    public static final ModConfigSpec.BooleanValue PROFILER_LOG_PERIODICALLY;
    public static final ModConfigSpec.IntValue PROFILER_LOG_INTERVAL_TICKS;
    public static final ModConfigSpec.ConfigValue<String> BIOME_OCEAN;
    public static final ModConfigSpec.ConfigValue<String> BIOME_COLD_OCEAN;
    public static final ModConfigSpec.ConfigValue<String> BIOME_FROZEN_OCEAN;
    public static final ModConfigSpec.ConfigValue<String> BIOME_DEEP_OCEAN;
    public static final ModConfigSpec.ConfigValue<String> BIOME_DEEP_COLD_OCEAN;
    public static final ModConfigSpec.ConfigValue<String> BIOME_DEEP_FROZEN_OCEAN;
    public static final ModConfigSpec.ConfigValue<String> BIOME_BEACH;
    public static final ModConfigSpec.ConfigValue<String> BIOME_STONY_SHORE;
    public static final ModConfigSpec.ConfigValue<String> BIOME_SNOWY_BEACH;
    public static final ModConfigSpec.BooleanValue BIOME_GUIDE_ENABLED;
    public static final ModConfigSpec.DoubleValue BIOME_GUIDE_STRENGTH;
    public static final ModConfigSpec.IntValue BIOME_CELL_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue BIOME_SURFACE_RELATIVE_MIN_Y;
    public static final ModConfigSpec.IntValue BIOME_LANDCOVER_SMOOTH_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue BIOME_LANDCOVER_SMOOTH_STEP_BLOCKS;
    public static final ModConfigSpec.BooleanValue BIOME_IGNORE_WATER_LANDCOVER;
    public static final ModConfigSpec.DoubleValue BIOME_EQUATOR_TEMPERATURE_C;
    public static final ModConfigSpec.DoubleValue BIOME_LATITUDE_TEMPERATURE_LOSS_C;
    public static final ModConfigSpec.DoubleValue BIOME_LAPSE_RATE_C_PER_KM;
    public static final ModConfigSpec.DoubleValue BIOME_TEMPERATURE_NOISE_C;
    public static final ModConfigSpec.DoubleValue BIOME_FREEZING_TEMPERATURE_C;
    public static final ModConfigSpec.DoubleValue BIOME_COLD_TEMPERATURE_C;
    public static final ModConfigSpec.DoubleValue BIOME_HOT_TEMPERATURE_C;
    public static final ModConfigSpec.DoubleValue BIOME_TROPICAL_TEMPERATURE_C;
    public static final ModConfigSpec.DoubleValue BIOME_HUMIDITY_NOISE;
    public static final ModConfigSpec.DoubleValue BIOME_DRY_HUMIDITY;
    public static final ModConfigSpec.DoubleValue BIOME_WET_HUMIDITY;
    public static final ModConfigSpec.DoubleValue BIOME_TROPICAL_WET_HUMIDITY;
    public static final ModConfigSpec.DoubleValue BIOME_ELEVATION_DRYING_PER_KM;
    public static final ModConfigSpec.DoubleValue BIOME_SLOPE_DRYING;
    public static final ModConfigSpec.IntValue BIOME_SLOPE_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue BIOME_STEEP_SLOPE;
    public static final ModConfigSpec.DoubleValue BIOME_CLIFF_SLOPE;
    public static final ModConfigSpec.DoubleValue BIOME_MONTANE_METERS;
    public static final ModConfigSpec.DoubleValue BIOME_ALPINE_METERS;
    public static final ModConfigSpec.DoubleValue BIOME_NIVAL_METERS;
    public static final ModConfigSpec.DoubleValue BIOME_EXTREME_PEAK_METERS;
    public static final ModConfigSpec.ConfigValue<String> BIOME_HIGH_STEEP;
    public static final ModConfigSpec.ConfigValue<String> BIOME_HIGH_FLAT;
    public static final ModConfigSpec.ConfigValue<String> BIOME_ALPINE_STEEP;
    public static final ModConfigSpec.ConfigValue<String> BIOME_ALPINE_FLAT;
    public static final ModConfigSpec.ConfigValue<String> BIOME_SNOW_LOW;
    public static final ModConfigSpec.ConfigValue<String> BIOME_TREES_TROPICAL_WET;
    public static final ModConfigSpec.ConfigValue<String> BIOME_TREES_TEMPERATE_WET;
    public static final ModConfigSpec.ConfigValue<String> BIOME_TREES_TEMPERATE;
    public static final ModConfigSpec.ConfigValue<String> BIOME_TREES_COLD;
    public static final ModConfigSpec.ConfigValue<String> BIOME_GRASS_TEMPERATE;
    public static final ModConfigSpec.ConfigValue<String> BIOME_GRASS_HIGH;
    public static final ModConfigSpec.ConfigValue<String> BIOME_GRASS_HOT_DRY;
    public static final ModConfigSpec.ConfigValue<String> BIOME_SHRUB_HOT_DRY;
    public static final ModConfigSpec.ConfigValue<String> BIOME_BARE_HOT_DRY;
    public static final ModConfigSpec.ConfigValue<String> BIOME_BARE_ROUGH;
    public static final ModConfigSpec.ConfigValue<String> BIOME_WETLAND;
    public static final ModConfigSpec.ConfigValue<String> BIOME_MANGROVE;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG_CHUNKS;
    public static final ModConfigSpec.BooleanValue NOISE_GUIDE_ENABLED;
    public static final ModConfigSpec.DoubleValue NOISE_GUIDE_STRENGTH;
    public static final ModConfigSpec.IntValue NOISE_REFERENCE_SURFACE_Y;
    public static final ModConfigSpec.IntValue NOISE_MAX_VERTICAL_SHIFT;
    public static final ModConfigSpec.IntValue NOISE_SMOOTHING_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue NOISE_SMOOTHING_STEP_BLOCKS;
    public static final ModConfigSpec.IntValue NOISE_COLUMN_CACHE_LIMIT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("general");
        ENABLED = builder.comment("Master switch for atlas guided terrain shaping.")
                .define("enabled", true);
        SHAPE_ONLY_NEW_CHUNKS = builder.comment("If true, debug post-shaping runs only for newly generated chunks. Manual /rla shape_here can still resculpt manually.")
                .define("shapeOnlyNewChunks", true);
        AUTO_POST_SHAPE_CHUNKS = builder.comment("Debug-only old post-generation sculpt pass. Keep false for normal atlas-guided worldgen: true rewrites already generated chunks and can create floating decoration.")
                .define("autoPostShapeChunks", false);
        OVERWORLD_ONLY = builder.comment("If true, automatic shaping only runs in minecraft:overworld.")
                .define("overworldOnly", true);
        MAX_CHUNKS_PER_TICK = builder.comment("Maximum queued chunks sculpted per server tick.")
                .defineInRange("maxChunksPerTick", 1, 1, 64);
        MAX_COLUMNS_PER_TICK = builder.comment("Safety cap for block columns processed per tick. One chunk has 256 columns.")
                .defineInRange("maxColumnsPerTick", 256, 1, 8192);
        TILE_ROOT = builder.comment("Height tile directory. Relative paths are resolved against the game directory. Put GLO-30/GLO-90 .tif files or custom .rheight.properties files here.")
                .define("tileRoot", "config/redline-atlas-worldgen/heightmaps");
        builder.pop();

        builder.push("mapping");
        HORIZONTAL_METERS_PER_BLOCK = builder.comment("Used by docs and future converters. The current lat/lon runtime mapping uses the degree-per-block values below.")
                .defineInRange("horizontalMetersPerBlock", 6.0D, 0.001D, 1000000.0D);
        VERTICAL_METERS_PER_BLOCK = builder.comment("Real meters per Minecraft vertical block. With 1:6 scale, Everest is around y=1475.")
                .defineInRange("verticalMetersPerBlock", 6.0D, 0.001D, 1000000.0D);
        ORIGIN_LATITUDE = builder.comment("Latitude at Minecraft block z=0. Positive z moves south when degreesPerBlockLatitude is positive.")
                .defineInRange("originLatitude", 45.5D, -90.0D, 90.0D);
        ORIGIN_LONGITUDE = builder.comment("Longitude at Minecraft block x=0. Positive x moves east.")
                .defineInRange("originLongitude", 6.5D, -180.0D, 180.0D);
        DEGREES_PER_BLOCK_LATITUDE = builder.comment("Latitude degrees per Minecraft block. Default is 6m/block divided by roughly 111.32km/degree.")
                .defineInRange("degreesPerBlockLatitude", 0.000053898917D, 0.0D, 1.0D);
        DEGREES_PER_BLOCK_LONGITUDE = builder.comment("Longitude degrees per Minecraft block. Default matches full Earth equirectangular 1:6 near the equator.")
                .defineInRange("degreesPerBlockLongitude", 0.000053894708D, 0.0D, 1.0D);
        SEA_LEVEL_Y = builder.comment("Minecraft sea level used by the atlas shaper. The tall-overworld datapack currently uses 0.")
                .defineInRange("seaLevelY", 0, -4096, 4096);
        builder.pop();

        builder.push("terrain");
        MAX_TERRAIN_DELTA_PER_PASS = builder.comment("Maximum vertical correction per automatic pass. Keeps the first MVP from rewriting absurd columns if mapping is wrong.")
                .defineInRange("maxTerrainDeltaPerPass", 384, 1, 4096);
        FILL_WATER_BELOW_SEA_LEVEL = builder.comment("Fill water from terrain surface up to sea level when sampled height is below sea level.")
                .define("fillWaterBelowSeaLevel", true);
        builder.pop();

        builder.push("noise_guide");
        NOISE_GUIDE_ENABLED = builder.comment("Patch the normal vanilla Overworld noise pipeline. This is the real atlas-guided path: it shifts vanilla density Y toward atlas macro-height instead of rewriting finished chunks.")
                .define("enabled", true);
        NOISE_GUIDE_STRENGTH = builder.comment("How strongly atlas height controls vanilla density. 1.0 = full macro-height guidance, 0.0 = pure vanilla.")
                .defineInRange("strength", 1.0D, 0.0D, 1.0D);
        NOISE_REFERENCE_SURFACE_Y = builder.comment("Vanilla reference surface used by the density shifter. For normal vanilla terrain use around 64; atlasY-referenceY becomes the vertical density shift.")
                .defineInRange("referenceSurfaceY", 64, -4096, 4096);
        NOISE_MAX_VERTICAL_SHIFT = builder.comment("Maximum Y shift applied to density samples. Safety clamp against wrong mapping/config. 0 disables clamping.")
                .defineInRange("maxVerticalShift", 2048, 0, 8192);
        NOISE_SMOOTHING_RADIUS_BLOCKS = builder.comment("Radius in Minecraft blocks used to smooth atlas samples before they guide density. 0 = no smoothing. For GLO-30 tests 8-24 is usually sane.")
                .defineInRange("smoothingRadiusBlocks", 16, 0, 512);
        NOISE_SMOOTHING_STEP_BLOCKS = builder.comment("Step in blocks for the smoothing kernel. Higher values are faster but rougher.")
                .defineInRange("smoothingStepBlocks", 8, 1, 128);
        NOISE_COLUMN_CACHE_LIMIT = builder.comment("Approximate cached atlas-guided columns. Cache is cleared when this limit is exceeded.")
                .defineInRange("columnCacheLimit", 262144, 1024, 8388608);
        builder.pop();

        builder.push("landcover");
        LANDCOVER_TILE_ROOT = builder.comment("Landcover tile directory. Relative paths are resolved against the game directory. Put ESA WorldCover .tif files here, for example ESA_WorldCover_10m_2021_v200_N45E006_Map.tif.")
                .define("tileRoot", "config/redline-atlas-worldgen/landcover");
        builder.pop();

        builder.push("ocean_bathymetry");
        OCEAN_BATHYMETRY_TILE_ROOT = builder.comment("Open ocean / ocean-connected sea bathymetry tile directory. Relative paths are resolved against the game directory. Put GEBCO data GeoTIFF tiles here. Bounds are inferred from filenames with n/s/w/e or bbox_west_south_east_north, or from .rbathy.properties sidecars.")
                .define("tileRoot", "config/redline-atlas-worldgen/ocean_bathymetry");
        OPEN_WATER_GUIDE_ENABLED = builder.comment("Enable open-ocean bathymetry/coast classification. This does not place fluids; it only guides terrain macro-height and biome choice.")
                .define("enabled", true);
        OPEN_WATER_USE_FOR_NOISE_GUIDE = builder.comment("When true, ocean bathymetry can guide the vanilla density shifter in places where land DEM does not override it. This creates ocean-floor macro-heights, but still does not manually place water.")
                .define("useForNoiseGuide", true);
        OPEN_WATER_SEA_LEVEL_METERS = builder.comment("Real-world open ocean water surface in meters. Open oceans and ocean-connected seas use 0m; closed seas/lakes will be separate layers later.")
                .defineInRange("seaLevelMeters", 0.0D, -10000.0D, 10000.0D);
        OPEN_WATER_MIN_OCEAN_DEPTH_METERS = builder.comment("Minimum positive depth for a bathymetry sample to be treated as open water. Keeps tiny/noisy near-zero values from turning land into ocean.")
                .defineInRange("minOceanDepthMeters", 1.0D, 0.0D, 1000.0D);
        OPEN_WATER_LAND_OVERRIDE_METERS = builder.comment("If land DEM at a point is above seaLevelMeters by more than this amount, land wins over coarse ocean bathymetry at that point. This protects detailed coasts/islands.")
                .defineInRange("landOverrideMeters", 2.0D, -100.0D, 1000.0D);
        OPEN_WATER_COAST_RADIUS_BLOCKS = builder.comment("Radius in Minecraft blocks to search for nearby open ocean when classifying land as coast.")
                .defineInRange("coastRadiusBlocks", 96, 0, 2048);
        OPEN_WATER_COAST_STEP_BLOCKS = builder.comment("Step in Minecraft blocks for open-ocean coast search. Larger is faster; smaller gives tighter shore detection.")
                .defineInRange("coastStepBlocks", 16, 1, 512);
        OPEN_WATER_SHALLOW_DEPTH_METERS = builder.comment("Depth under which water is considered shallow/coastal ocean for debug and future surface rules.")
                .defineInRange("shallowDepthMeters", 8.0D, 0.0D, 10000.0D);
        OPEN_WATER_DEEP_DEPTH_METERS = builder.comment("Depth at which ocean biomes switch from ocean to deep_ocean variants.")
                .defineInRange("deepDepthMeters", 80.0D, 1.0D, 12000.0D);
        OPEN_WATER_BEACH_MAX_SLOPE = builder.comment("Maximum atlas slope for coast land to become beach/snowy_beach.")
                .defineInRange("beachMaxSlope", 0.12D, 0.0D, 8.0D);
        OPEN_WATER_STONY_SHORE_SLOPE = builder.comment("Minimum atlas slope for coast land to become stony_shore. Between beachMaxSlope and this value the resolver keeps the normal land biome.")
                .defineInRange("stonyShoreSlope", 0.28D, 0.0D, 8.0D);
        OPEN_WATER_USE_LAND_DEM_FOR_COAST = builder.comment("When true, positive/negative land DEM near sea level is used as the precise coastline while coarse GEBCO remains the ocean-floor layer. This is the preferred mode for GLO-30 coasts.")
                .define("useLandDemForCoast", true);
        builder.pop();

        builder.push("surface_polish");
        SURFACE_POLISH_ENABLED = builder.comment("Apply a lightweight post-generation top-material polish on newly loaded chunks. This fixes stone/deepslate exposed by atlas Y-shift without rewriting terrain shape.")
                .define("enabled", true);
        SURFACE_POLISH_ONLY_NEW_CHUNKS = builder.comment("When true, surface polish is only queued for newly generated chunks. Disable for debugging old test chunks.")
                .define("onlyNewChunks", true);
        SURFACE_POLISH_CHUNKS_PER_TICK = builder.comment("Maximum chunks processed by surface polish per server tick.")
                .defineInRange("chunksPerTick", 1, 0, 64);
        SURFACE_POLISH_COLUMNS_PER_TICK = builder.comment("Maximum columns processed by surface polish per server tick.")
                .defineInRange("columnsPerTick", 512, 0, 65536);
        SURFACE_POLISH_TOP_SCAN_BLOCKS = builder.comment("How many blocks below the heightmap top to scan for the first solid block.")
                .defineInRange("topScanBlocks", 24, 1, 256);
        builder.pop();

        builder.push("profiler");
        PROFILER_ENABLED = builder.comment("Enable lightweight atlas/worldgen profiler counters. Use /rla profile to view timings.")
                .define("enabled", true);
        PROFILER_LOG_PERIODICALLY = builder.comment("When true, logs profiler summary every profiler.logIntervalTicks server ticks.")
                .define("logPeriodically", false);
        PROFILER_LOG_INTERVAL_TICKS = builder.comment("Profiler periodic log interval in server ticks.")
                .defineInRange("logIntervalTicks", 200, 20, 72000);
        builder.pop();

        builder.push("biome_guide");
        BIOME_GUIDE_ENABLED = builder.comment("Patch vanilla Overworld biome selection using atlas height + ESA WorldCover landcover + optional open-ocean bathymetry/coast guide.")
                .define("enabled", true);
        BIOME_GUIDE_STRENGTH = builder.comment("Chance/strength of replacing vanilla biome when atlas biome context is available. 1.0 = always use atlas resolver, 0.0 = vanilla.")
                .defineInRange("strength", 1.0D, 0.0D, 1.0D);
        BIOME_CELL_SIZE_BLOCKS = builder.comment("Stable cell size for biome variant noise. Larger values make bigger biome patches and less mosaic noise.")
                .defineInRange("cellSizeBlocks", 384, 16, 8192);
        BIOME_SURFACE_RELATIVE_MIN_Y = builder.comment("Biome guide is applied only above this Y relative to atlas surface. Lower values keep cave/underground biomes closer to vanilla.")
                .defineInRange("surfaceRelativeMinY", -48, -4096, 4096);
        BIOME_LANDCOVER_SMOOTH_RADIUS_BLOCKS = builder.comment("Radius in blocks for majority-filtering ESA WorldCover before biome choice. 0 = exact pixel. Helps avoid 10m landcover mosaic.")
                .defineInRange("landcoverSmoothRadiusBlocks", 48, 0, 512);
        BIOME_LANDCOVER_SMOOTH_STEP_BLOCKS = builder.comment("Step in blocks for landcover majority filtering. Larger is faster; smaller follows source tiles more tightly.")
                .defineInRange("landcoverSmoothStepBlocks", 24, 1, 256);
        BIOME_IGNORE_WATER_LANDCOVER = builder.comment("When true, WorldCover water pixels are ignored by the biome layer. Open-ocean water comes from ocean_bathymetry instead.")
                .define("ignoreWaterLandcover", true);
        BIOME_EQUATOR_TEMPERATURE_C = builder.comment("Base sea-level temperature at equator, before latitude/elevation/noise corrections.")
                .defineInRange("equatorTemperatureC", 30.0D, -100.0D, 100.0D);
        BIOME_LATITUDE_TEMPERATURE_LOSS_C = builder.comment("Temperature loss per absolute latitude degree.")
                .defineInRange("latitudeTemperatureLossC", 0.42D, 0.0D, 2.0D);
        BIOME_LAPSE_RATE_C_PER_KM = builder.comment("Temperature loss per 1000 meters of elevation. Earth-like default is about 6.5 C/km.")
                .defineInRange("lapseRateCPerKm", 6.5D, 0.0D, 20.0D);
        BIOME_TEMPERATURE_NOISE_C = builder.comment("Seed climate noise amplitude in Celsius.")
                .defineInRange("temperatureNoiseC", 3.0D, 0.0D, 30.0D);
        BIOME_FREEZING_TEMPERATURE_C = builder.comment("Temperature threshold treated as freezing for snow/ice decisions.")
                .defineInRange("freezingTemperatureC", 0.0D, -50.0D, 50.0D);
        BIOME_COLD_TEMPERATURE_C = builder.comment("Temperature threshold treated as cold/taiga/grove.")
                .defineInRange("coldTemperatureC", 2.0D, -50.0D, 50.0D);
        BIOME_HOT_TEMPERATURE_C = builder.comment("Temperature threshold treated as hot for savanna/desert decisions.")
                .defineInRange("hotTemperatureC", 22.0D, -50.0D, 80.0D);
        BIOME_TROPICAL_TEMPERATURE_C = builder.comment("Temperature threshold treated as tropical for jungle decisions.")
                .defineInRange("tropicalTemperatureC", 24.0D, -50.0D, 80.0D);
        BIOME_HUMIDITY_NOISE = builder.comment("Seed humidity noise amplitude, added to 0..1 humidity.")
                .defineInRange("humidityNoise", 0.12D, 0.0D, 1.0D);
        BIOME_DRY_HUMIDITY = builder.comment("Humidity threshold treated as dry for savanna/desert decisions.")
                .defineInRange("dryHumidity", 0.35D, 0.0D, 1.0D);
        BIOME_WET_HUMIDITY = builder.comment("Humidity threshold treated as wet for dark forest/wet variants.")
                .defineInRange("wetHumidity", 0.72D, 0.0D, 1.0D);
        BIOME_TROPICAL_WET_HUMIDITY = builder.comment("Humidity threshold for tropical wet tree cover to become jungle.")
                .defineInRange("tropicalWetHumidity", 0.62D, 0.0D, 1.0D);
        BIOME_ELEVATION_DRYING_PER_KM = builder.comment("Humidity removed per 1000 meters of elevation.")
                .defineInRange("elevationDryingPerKm", 0.08D, 0.0D, 1.0D);
        BIOME_SLOPE_DRYING = builder.comment("Humidity removed at steep slopes. Multiplied by computed slope.")
                .defineInRange("slopeDrying", 0.18D, 0.0D, 2.0D);
        BIOME_SLOPE_RADIUS_BLOCKS = builder.comment("Radius in blocks used to estimate atlas slope for biome choice.")
                .defineInRange("slopeRadiusBlocks", 32, 4, 512);
        BIOME_STEEP_SLOPE = builder.comment("Slope threshold after which terrain is treated as steep for biome choice.")
                .defineInRange("steepSlope", 0.28D, 0.0D, 4.0D);
        BIOME_CLIFF_SLOPE = builder.comment("Slope threshold after which terrain is treated as cliff/very rough.")
                .defineInRange("cliffSlope", 0.55D, 0.0D, 8.0D);
        BIOME_MONTANE_METERS = builder.comment("Start of mountain/montane biome tier in real meters.")
                .defineInRange("montaneMeters", 1200.0D, -1000.0D, 10000.0D);
        BIOME_ALPINE_METERS = builder.comment("Start of alpine biome tier in real meters.")
                .defineInRange("alpineMeters", 2200.0D, -1000.0D, 10000.0D);
        BIOME_NIVAL_METERS = builder.comment("Start of snow/ice peak tier in real meters.")
                .defineInRange("nivalMeters", 3500.0D, -1000.0D, 10000.0D);
        BIOME_EXTREME_PEAK_METERS = builder.comment("Extreme peak tier in real meters. Overrides landcover strongly.")
                .defineInRange("extremePeakMeters", 3800.0D, -1000.0D, 10000.0D);

        BIOME_HIGH_STEEP = builder.comment("Biome for very high steep terrain.")
                .define("highSteepBiome", "minecraft:jagged_peaks");
        BIOME_HIGH_FLAT = builder.comment("Biome for very high flatter terrain.")
                .define("highFlatBiome", "minecraft:frozen_peaks");
        BIOME_ALPINE_STEEP = builder.comment("Biome for alpine steep slopes.")
                .define("alpineSteepBiome", "minecraft:snowy_slopes");
        BIOME_ALPINE_FLAT = builder.comment("Biome for alpine flatter terrain.")
                .define("alpineFlatBiome", "minecraft:meadow");
        BIOME_SNOW_LOW = builder.comment("Biome for low/cold snow and ice landcover.")
                .define("snowLowBiome", "minecraft:snowy_plains");
        BIOME_TREES_TROPICAL_WET = builder.comment("Biome for hot wet tree cover.")
                .define("treesTropicalWetBiome", "minecraft:jungle");
        BIOME_TREES_TEMPERATE_WET = builder.comment("Biome for wet temperate tree cover.")
                .define("treesTemperateWetBiome", "minecraft:dark_forest");
        BIOME_TREES_TEMPERATE = builder.comment("Biome for normal temperate tree cover.")
                .define("treesTemperateBiome", "minecraft:forest");
        BIOME_TREES_COLD = builder.comment("Biome for cold tree cover.")
                .define("treesColdBiome", "minecraft:taiga");
        BIOME_GRASS_TEMPERATE = builder.comment("Biome for normal grassland/cropland/urban fallback.")
                .define("grassTemperateBiome", "minecraft:plains");
        BIOME_GRASS_HIGH = builder.comment("Biome for high grassland/cropland/urban terrain.")
                .define("grassHighBiome", "minecraft:meadow");
        BIOME_GRASS_HOT_DRY = builder.comment("Biome for hot dry grassland/cropland.")
                .define("grassHotDryBiome", "minecraft:savanna");
        BIOME_SHRUB_HOT_DRY = builder.comment("Biome for hot dry shrubland.")
                .define("shrubHotDryBiome", "minecraft:savanna");
        BIOME_BARE_HOT_DRY = builder.comment("Biome for hot dry bare/sparse land.")
                .define("bareHotDryBiome", "minecraft:desert");
        BIOME_BARE_ROUGH = builder.comment("Biome for rough bare/sparse land.")
                .define("bareRoughBiome", "minecraft:stony_peaks");
        BIOME_WETLAND = builder.comment("Biome for wetland. Water placement itself is not changed by this layer.")
                .define("wetlandBiome", "minecraft:swamp");
        BIOME_MANGROVE = builder.comment("Biome for mangrove landcover.")
                .define("mangroveBiome", "minecraft:mangrove_swamp");
        BIOME_OCEAN = builder.comment("Biome for normal open ocean water.")
                .define("oceanBiome", "minecraft:ocean");
        BIOME_COLD_OCEAN = builder.comment("Biome for cold open ocean water.")
                .define("coldOceanBiome", "minecraft:cold_ocean");
        BIOME_FROZEN_OCEAN = builder.comment("Biome for freezing open ocean water.")
                .define("frozenOceanBiome", "minecraft:frozen_ocean");
        BIOME_DEEP_OCEAN = builder.comment("Biome for deep open ocean water.")
                .define("deepOceanBiome", "minecraft:deep_ocean");
        BIOME_DEEP_COLD_OCEAN = builder.comment("Biome for deep cold open ocean water.")
                .define("deepColdOceanBiome", "minecraft:deep_cold_ocean");
        BIOME_DEEP_FROZEN_OCEAN = builder.comment("Biome for deep freezing open ocean water.")
                .define("deepFrozenOceanBiome", "minecraft:deep_frozen_ocean");
        BIOME_BEACH = builder.comment("Biome for low-slope open-ocean coast.")
                .define("beachBiome", "minecraft:beach");
        BIOME_STONY_SHORE = builder.comment("Biome for steep open-ocean coast.")
                .define("stonyShoreBiome", "minecraft:stony_shore");
        BIOME_SNOWY_BEACH = builder.comment("Biome for low-slope freezing open-ocean coast.")
                .define("snowyBeachBiome", "minecraft:snowy_beach");
        builder.pop();

        builder.push("debug");
        DEBUG_LOG_CHUNKS = builder.comment("Log old debug post-shaper chunk summaries. Noisy; does not affect noise-guided generation.")
                .define("logChunks", false);
        builder.pop();

        SPEC = builder.build();
    }

    private AtlasWorldgenConfig() {
    }
}
