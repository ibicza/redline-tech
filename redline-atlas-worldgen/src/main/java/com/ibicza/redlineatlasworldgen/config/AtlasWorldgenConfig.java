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

    public static final ModConfigSpec.ConfigValue<String> MANUAL_LAKES_ROOT;
    public static final ModConfigSpec.BooleanValue LAKE_GUIDE_ENABLED;
    public static final ModConfigSpec.BooleanValue LAKE_USE_LANDCOVER_WATER;
    public static final ModConfigSpec.BooleanValue LAKE_SHORE_IN_BIOME_GUIDE;
    public static final ModConfigSpec.DoubleValue LAKE_MIN_SURFACE_METERS;
    public static final ModConfigSpec.IntValue LAKE_CACHE_CELL_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_SAMPLE_CACHE_LIMIT;
    public static final ModConfigSpec.IntValue LAKE_WORLDCOVER_WATER_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_WORLDCOVER_WATER_STEP_BLOCKS;
    public static final ModConfigSpec.DoubleValue LAKE_WORLDCOVER_MIN_WATER_FRACTION;
    public static final ModConfigSpec.IntValue LAKE_WORLDCOVER_MIN_COMPONENT_SAMPLES;
    public static final ModConfigSpec.IntValue LAKE_WORLDCOVER_SURFACE_SEARCH_BLOCKS;
    public static final ModConfigSpec.DoubleValue LAKE_WORLDCOVER_SURFACE_PERCENTILE;
    public static final ModConfigSpec.DoubleValue LAKE_WORLDCOVER_SURFACE_MAX_ABOVE_MIN_METERS;
    public static final ModConfigSpec.BooleanValue LAKE_BASIN_FIT_ENABLED;
    public static final ModConfigSpec.IntValue LAKE_BASIN_FIT_SEARCH_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_BASIN_FIT_CACHE_LIMIT;
    public static final ModConfigSpec.IntValue LAKE_BASIN_CORE_MIN_WATER_NEIGHBORS;
    public static final ModConfigSpec.IntValue LAKE_BASIN_MIN_CORE_SAMPLES;
    public static final ModConfigSpec.DoubleValue LAKE_BASIN_CORE_SURFACE_PERCENTILE;
    public static final ModConfigSpec.DoubleValue LAKE_BASIN_LOW_MEAN_FRACTION;
    public static final ModConfigSpec.DoubleValue LAKE_BASIN_RIM_PERCENTILE;
    public static final ModConfigSpec.DoubleValue LAKE_BASIN_RIM_CLEARANCE_METERS;
    public static final ModConfigSpec.DoubleValue LAKE_BASIN_MAX_SURFACE_ABOVE_LOWEST_CORE_METERS;
    public static final ModConfigSpec.DoubleValue LAKE_BASIN_WATER_COLUMN_MAX_ABOVE_SURFACE_METERS;
    public static final ModConfigSpec.DoubleValue LAKE_BASIN_EDGE_WATER_COLUMN_MAX_ABOVE_SURFACE_METERS;
    public static final ModConfigSpec.BooleanValue LAKE_SNAP_WATER_SURFACE_TO_BLOCK;
    public static final ModConfigSpec.DoubleValue LAKE_SHORE_RING_LAND_FRACTION;
    public static final ModConfigSpec.IntValue LAKE_MAX_SHORE_SEARCH_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_SHORE_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_WORLDCOVER_SHORE_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_TERRAIN_SHOULDER_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue LAKE_SHORE_MAX_HEIGHT_DELTA_METERS;
    public static final ModConfigSpec.DoubleValue LAKE_SYNTHETIC_MIN_DEPTH_METERS;
    public static final ModConfigSpec.DoubleValue LAKE_SYNTHETIC_MAX_DEPTH_METERS;
    public static final ModConfigSpec.IntValue LAKE_TOPOGRAPHIC_SHORE_SCAN_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_SYNTHETIC_MIN_DEPTH_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_SYNTHETIC_MAX_DEPTH_BLOCKS;
    public static final ModConfigSpec.IntValue LAKE_FULL_DEPTH_DISTANCE_BLOCKS;
    public static final ModConfigSpec.DoubleValue LAKE_MANUAL_DEFAULT_SHORE_BLEND_METERS;
    public static final ModConfigSpec.ConfigValue<String> RIVER_ROOT;
    public static final ModConfigSpec.BooleanValue RIVER_GUIDE_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> RIVER_SOURCE_BOUNDS;
    public static final ModConfigSpec.IntValue RIVER_MIN_STRAHLER_ORDER;
    public static final ModConfigSpec.IntValue RIVER_MAX_SEGMENTS;
    public static final ModConfigSpec.IntValue RIVER_INDEX_CELL_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_BIOME_CACHE_CELL_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_SAMPLE_CACHE_LIMIT;
    public static final ModConfigSpec.BooleanValue RIVER_WRITE_COOKED_CACHE;
    public static final ModConfigSpec.BooleanValue RIVER_PREFER_COOKED_CACHE;
    public static final ModConfigSpec.BooleanValue RIVER_REFINE_ENABLED;
    public static final ModConfigSpec.IntValue RIVER_REFINE_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_REFINE_STEP_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_REFINE_POINT_SPACING_BLOCKS;
    public static final ModConfigSpec.DoubleValue RIVER_VALLEY_HEIGHT_WEIGHT;
    public static final ModConfigSpec.DoubleValue RIVER_SOURCE_DISTANCE_WEIGHT;
    public static final ModConfigSpec.DoubleValue RIVER_UPHILL_WEIGHT;
    public static final ModConfigSpec.DoubleValue RIVER_WORLDCOVER_WATER_BONUS;
    public static final ModConfigSpec.IntValue RIVER_MIN_WIDTH_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_MAX_WIDTH_BLOCKS;
    public static final ModConfigSpec.DoubleValue RIVER_WIDTH_DISCHARGE_FACTOR;
    public static final ModConfigSpec.IntValue RIVER_WIDTH_SCAN_STEP_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_MIN_DEPTH_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_MAX_DEPTH_BLOCKS;
    public static final ModConfigSpec.DoubleValue RIVER_DEPTH_WIDTH_FACTOR;
    public static final ModConfigSpec.IntValue RIVER_BANK_WIDTH_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_TERRAIN_SHOULDER_WIDTH_BLOCKS;
    public static final ModConfigSpec.IntValue RIVER_WATER_BELOW_BANK_BLOCKS;
    public static final ModConfigSpec.BooleanValue RIVER_PROFILE_SNAP_TO_BLOCK;
    public static final ModConfigSpec.BooleanValue RIVER_FLOW_PHYSICS_ENABLED;
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
    public static final ModConfigSpec.IntValue OPEN_WATER_CACHE_CELL_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue OPEN_WATER_SAMPLE_CACHE_LIMIT;
    public static final ModConfigSpec.BooleanValue OPEN_WATER_ENABLE_COAST_SCAN_IN_BIOME_GUIDE;
    public static final ModConfigSpec.BooleanValue OPEN_WATER_COASTAL_FLOOD_ENABLED;
    public static final ModConfigSpec.IntValue OPEN_WATER_COASTAL_FLOOD_CELL_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue OPEN_WATER_COASTAL_FLOOD_MAX_DISTANCE_BLOCKS;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_COASTAL_FLOOD_TOLERANCE_METERS;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_COASTAL_FLOOD_MIN_DEPTH_METERS;
    public static final ModConfigSpec.DoubleValue OPEN_WATER_COASTAL_FLOOD_MAX_DEPTH_METERS;
    public static final ModConfigSpec.IntValue OPEN_WATER_COASTAL_FLOOD_CACHE_LIMIT;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_COASTAL_FLOOD_CARVE_ABOVE_SEA_BLOCKS;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_ENABLED;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_COLUMNS_PER_TICK;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_TOP_SCAN_BLOCKS;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_ONLY_NEW_CHUNKS;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_FILL_OPEN_OCEAN_WATER;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_BUILD_OPEN_OCEAN_SHORES;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_EXACT_COAST_SAMPLES;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_FILL_COAST_DEPRESSION_WATER;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_COAST_WATER_MAX_DISTANCE_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_OCEAN_CARVE_ABOVE_SEA_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_OCEAN_MAX_FILL_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_SHORE_SAND_DEPTH_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_TERRAIN_CAP_DEPTH_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_BEACH_DISTANCE_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_BEACH_MAX_HEIGHT_ABOVE_SEA_BLOCKS;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_BEACH_MAX_SLOPE;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_OCEAN_SAND_DEPTH_METERS;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_OCEAN_GRAVEL_DEPTH_METERS;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_REPLACE_SURFACE_ORES;

    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_FILL_LAKE_WATER;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_MAX_FILL_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_CARVE_ABOVE_WATER_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_WATER_MASK_CARVE_ABOVE_WATER_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_SHORE_TARGET_HEIGHT_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_SHORE_SMOOTH_CARVE_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_LEAK_GUARD_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_LEAK_GUARD_STEP_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_LEAK_MAX_DROPOFF_BLOCKS;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_LAKE_BUILD_CONTAINMENT_BANKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_BANK_MAX_RAISE_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_LAKE_TERRAIN_SHOULDER_MAX_RAISE_BLOCKS;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_LAKE_TERRAIN_SHOULDER_MAX_SLOPE;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_LAKE_SAND_DEPTH_METERS;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_LAKE_GRAVEL_DEPTH_METERS;
    public static final ModConfigSpec.BooleanValue SURFACE_POLISH_FILL_RIVER_WATER;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_RIVER_MAX_CARVE_ABOVE_WATER_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_RIVER_MAX_FILL_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_RIVER_BANK_SMOOTH_CARVE_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_RIVER_BANK_MAX_RAISE_BLOCKS;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_RIVER_TERRAIN_SHOULDER_MAX_SLOPE;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_RIVER_OVERFLOW_CLEANUP_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_RIVER_LOOSE_FOUNDATION_MIN_BLOCKS;
    public static final ModConfigSpec.IntValue SURFACE_POLISH_RIVER_LOOSE_FOUNDATION_MAX_BLOCKS;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_RIVER_SAND_DEPTH_METERS;
    public static final ModConfigSpec.DoubleValue SURFACE_POLISH_RIVER_GRAVEL_DEPTH_METERS;
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

    public static final ModConfigSpec.ConfigValue<String> BIOME_LAKE_WATER;
    public static final ModConfigSpec.ConfigValue<String> BIOME_FROZEN_LAKE_WATER;
    public static final ModConfigSpec.ConfigValue<String> BIOME_LAKE_SHORE;
    public static final ModConfigSpec.ConfigValue<String> BIOME_RIVER;
    public static final ModConfigSpec.ConfigValue<String> BIOME_FROZEN_RIVER;
    public static final ModConfigSpec.BooleanValue BIOME_GUIDE_ENABLED;
    public static final ModConfigSpec.DoubleValue BIOME_GUIDE_STRENGTH;
    public static final ModConfigSpec.IntValue BIOME_CELL_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue BIOME_SURFACE_RELATIVE_MIN_Y;
    public static final ModConfigSpec.IntValue BIOME_LANDCOVER_SMOOTH_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue BIOME_LANDCOVER_SMOOTH_STEP_BLOCKS;
    public static final ModConfigSpec.BooleanValue BIOME_IGNORE_WATER_LANDCOVER;
    public static final ModConfigSpec.IntValue BIOME_COLUMN_CACHE_LIMIT;
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
    public static final ModConfigSpec.BooleanValue STRUCTURE_HEIGHT_GUIDE_ENABLED;

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

        builder.push("structure_guide");
        STRUCTURE_HEIGHT_GUIDE_ENABLED = builder.comment("Make vanilla surface structures query the same atlas-shifted base height and base column as generated terrain. Prevents villages, temples, outposts, shipwrecks and similar structures from floating near the old vanilla reference Y.")
                .define("enabled", true);
        builder.pop();

        builder.push("landcover");
        LANDCOVER_TILE_ROOT = builder.comment("Landcover tile directory. Relative paths are resolved against the game directory. Put ESA WorldCover .tif files here, for example ESA_WorldCover_10m_2021_v200_N45E006_Map.tif.")
                .define("tileRoot", "config/redline-atlas-worldgen/landcover");
        builder.pop();


        builder.push("lake_water");
        MANUAL_LAKES_ROOT = builder.comment("Manual local lake definitions. Put *.rlake.properties files here for quarries, reservoirs, or water bodies missing from source datasets.")
                .define("manualLakeRoot", "config/redline-atlas-worldgen/manual_lakes");
        LAKE_GUIDE_ENABLED = builder.comment("Enable inland lake/small waterbody guide. This is separate from open-ocean bathymetry and uses manual lakes plus ESA WorldCover water pixels.")
                .define("enabled", true);
        LAKE_USE_LANDCOVER_WATER = builder.comment("Use ESA WorldCover water class as a source for small inland water bodies when no HydroLAKES/GLOBathy data is present.")
                .define("useWorldCoverWater", true);
        LAKE_MIN_SURFACE_METERS = builder.comment("Minimum real-world water surface elevation for WorldCover-water to become an inland lake. Keeps ocean/coast water in the ocean layer.")
                .defineInRange("minSurfaceMeters", 2.0D, -1000.0D, 9000.0D);
        LAKE_CACHE_CELL_SIZE_BLOCKS = builder.comment("Cached lake classification cell size in blocks. 16 follows 10m WorldCover fairly tightly; 32 is faster and smoother.")
                .defineInRange("cacheCellSizeBlocks", 16, 4, 512);
        LAKE_SAMPLE_CACHE_LIMIT = builder.comment("Approximate maximum cached lake cells. Cache is cleared when this limit is exceeded.")
                .defineInRange("sampleCacheLimit", 262144, 1024, 8388608);
        LAKE_WORLDCOVER_WATER_RADIUS_BLOCKS = builder.comment("Radius for local WorldCover water majority. Used to avoid single-pixel noise creating lakes.")
                .defineInRange("worldcoverWaterRadiusBlocks", 16, 0, 512);
        LAKE_WORLDCOVER_WATER_STEP_BLOCKS = builder.comment("Step for local WorldCover water majority and shore search.")
                .defineInRange("worldcoverWaterStepBlocks", 8, 1, 128);
        LAKE_WORLDCOVER_MIN_WATER_FRACTION = builder.comment("Minimum water fraction in the local WorldCover window to accept a small waterbody when the exact center pixel is not water.")
                .defineInRange("worldcoverMinWaterFraction", 0.30D, 0.0D, 1.0D);
        LAKE_WORLDCOVER_MIN_COMPONENT_SAMPLES = builder.comment("Minimum connected residual WorldCover-water samples before a non-manual water patch can become a small lake. River-owned pixels are excluded first.")
                .defineInRange("worldcoverMinComponentSamples", 4, 1, 1024);
        LAKE_WORLDCOVER_SURFACE_SEARCH_BLOCKS = builder.comment("Radius for estimating one stable water-surface level for a WorldCover small waterbody. Uses nearby water pixels so lake surface does not step up/down per column.")
                .defineInRange("worldcoverSurfaceSearchBlocks", 128, 8, 2048);
        LAKE_WORLDCOVER_SURFACE_PERCENTILE = builder.comment("Low percentile of nearby water-pixel DEM heights used as the small-waterbody surface. Keep this low: WorldCover often marks quarry banks as water, and high DEM outliers must not lift the whole lake into the air.")
                .defineInRange("worldcoverSurfacePercentile", 0.05D, 0.0D, 1.0D);
        LAKE_WORLDCOVER_SURFACE_MAX_ABOVE_MIN_METERS = builder.comment("Maximum meters the inferred WorldCover lake surface may rise above the lowest nearby water pixel. Prevents misclassified high banks from raising the whole lake; depth is carved downward instead.")
                .defineInRange("worldcoverSurfaceMaxAboveMinMeters", 3.0D, 0.0D, 256.0D);
        LAKE_BASIN_FIT_ENABLED = builder.comment("Fit small WorldCover waterbodies as one basin component instead of independent columns. Uses a connected water mask, inner water core, and surrounding rim to keep lake water below the local shore.")
                .define("basinFitEnabled", true);
        LAKE_BASIN_FIT_SEARCH_BLOCKS = builder.comment("Maximum block radius used to collect the connected WorldCover water component for a small lake/quarry basin fit.")
                .defineInRange("basinFitSearchBlocks", 256, 32, 2048);
        LAKE_BASIN_FIT_CACHE_LIMIT = builder.comment("Approximate maximum cached WorldCover basin fits. Cache is cleared when this limit is exceeded.")
                .defineInRange("basinFitCacheLimit", 16384, 128, 1048576);
        LAKE_BASIN_CORE_MIN_WATER_NEIGHBORS = builder.comment("Minimum 8-neighbour water count for a WorldCover water pixel to be considered inner water core. Core pixels avoid mixed bank/rim DEM samples lifting the whole lake.")
                .defineInRange("basinCoreMinWaterNeighbors", 5, 0, 8);
        LAKE_BASIN_MIN_CORE_SAMPLES = builder.comment("Minimum inner-core DEM samples before falling back to all connected water samples for lake level fitting.")
                .defineInRange("basinMinCoreSamples", 4, 1, 4096);
        LAKE_BASIN_CORE_SURFACE_PERCENTILE = builder.comment("Fallback low percentile of inner water-core DEM heights used as the raw lake surface. Kept for compatibility; M30.6 primarily uses the low-mean of the water mask.")
                .defineInRange("basinCoreSurfacePercentile", 0.10D, 0.0D, 1.0D);
        LAKE_BASIN_LOW_MEAN_FRACTION = builder.comment("Fraction of the lowest DEM samples inside the connected WorldCover water patch used to average one stable lake surface. This overlays the water mask on topography and ignores high mixed shore/bank pixels.")
                .defineInRange("basinLowMeanFraction", 0.25D, 0.02D, 1.0D);
        LAKE_BASIN_RIM_PERCENTILE = builder.comment("Low percentile of non-water rim DEM heights used as the overflow/rim clamp. The final water level is never above rim percentile minus rim clearance.")
                .defineInRange("basinRimPercentile", 0.20D, 0.0D, 1.0D);
        LAKE_BASIN_RIM_CLEARANCE_METERS = builder.comment("Meters below the low rim where the inferred lake water surface must stay. Prevents floating water slabs and waterfall walls around incomplete/misaligned water masks.")
                .defineInRange("basinRimClearanceMeters", 1.0D, 0.0D, 64.0D);
        LAKE_BASIN_MAX_SURFACE_ABOVE_LOWEST_CORE_METERS = builder.comment("Additional cap over the lowest inner-core water DEM sample. This is a second guard against mixed high water pixels raising the lake surface.")
                .defineInRange("basinMaxSurfaceAboveLowestCoreMeters", 2.0D, 0.0D, 128.0D);
        LAKE_BASIN_WATER_COLUMN_MAX_ABOVE_SURFACE_METERS = builder.comment("Topographic acceptance gate for WorldCover lake water columns. A water(80) pixel whose DEM height is more than this many meters above the fitted lake surface is treated as shore/land, not as water. This intersects the water mask with the real DEM basin and prevents hanging lakes over fields/banks.")
                .defineInRange("basinWaterColumnMaxAboveSurfaceMeters", 6.0D, 0.0D, 256.0D);
        LAKE_BASIN_EDGE_WATER_COLUMN_MAX_ABOVE_SURFACE_METERS = builder.comment("Stricter topographic gate for WorldCover water pixels close to shore. Edge/mixed pixels are often quarry banks in Copernicus DSM; keep this low so only the true low water basin is filled.")
                .defineInRange("basinEdgeWaterColumnMaxAboveSurfaceMeters", 3.0D, 0.0D, 256.0D);
        LAKE_SNAP_WATER_SURFACE_TO_BLOCK = builder.comment("Snap inferred lake water surface down to a Minecraft Y block. This keeps one flat water level and never rounds the lake surface upward.")
                .define("snapWaterSurfaceToBlock", true);
        LAKE_SHORE_RING_LAND_FRACTION = builder.comment("A WorldCover search ring is considered shore only when at least this fraction is non-water. This ignores tiny holes/noisy pixels inside quarry/lake masks and gives deeper centers.")
                .defineInRange("shoreRingLandFraction", 0.35D, 0.0D, 1.0D);
        LAKE_MAX_SHORE_SEARCH_BLOCKS = builder.comment("Maximum distance searched from a water pixel to find lake shore. Controls synthetic bowl depth.")
                .defineInRange("maxShoreSearchBlocks", 160, 8, 2048);
        LAKE_SHORE_RADIUS_BLOCKS = builder.comment("Land columns within this distance from inland water can become lake shore.")
                .defineInRange("shoreRadiusBlocks", 48, 0, 1024);
        LAKE_WORLDCOVER_SHORE_RADIUS_BLOCKS = builder.comment("Residual WorldCover-only pond shore radius after rivers have claimed their masks. Keep this tight to avoid wide random sand patches near river rasters.")
                .defineInRange("worldcoverShoreRadiusBlocks", 8, 0, 256);
        LAKE_TERRAIN_SHOULDER_RADIUS_BLOCKS = builder.comment("Surface-polish-only radius around residual WorldCover lakes where low dry terrain may be raised toward the contained sandy shore. This context does not affect biome or water classification.")
                .defineInRange("terrainShoulderRadiusBlocks", 32, 0, 256);
        LAKE_SHORE_IN_BIOME_GUIDE = builder.comment("When true, biome guide can classify near-lake land as lake shore. Disable if too expensive or too sandy.")
                .define("shoreInBiomeGuide", true);
        LAKE_SHORE_MAX_HEIGHT_DELTA_METERS = builder.comment("Maximum real height difference between land and nearby WorldCover-water for lake shore classification.")
                .defineInRange("shoreMaxHeightDeltaMeters", 8.0D, 0.0D, 256.0D);
        LAKE_SYNTHETIC_MIN_DEPTH_METERS = builder.comment("Minimum synthetic depth for small WorldCover/manual lakes without real bathymetry.")
                .defineInRange("syntheticMinDepthMeters", 18.0D, 0.5D, 256.0D);
        LAKE_SYNTHETIC_MAX_DEPTH_METERS = builder.comment("Maximum synthetic depth for small WorldCover/manual lakes without real bathymetry.")
                .defineInRange("syntheticMaxDepthMeters", 54.0D, 1.0D, 512.0D);
        LAKE_TOPOGRAPHIC_SHORE_SCAN_BLOCKS = builder.comment("Radius used to check whether a WorldCover water pixel is close to shore/mixed land. Edge water pixels use a stricter height-above-surface gate than inner water pixels.")
                .defineInRange("topographicShoreScanBlocks", 24, 0, 256);
        LAKE_SYNTHETIC_MIN_DEPTH_BLOCKS = builder.comment("Minimum synthetic depth in Minecraft blocks for small WorldCover lakes. This protects old configs with too-small meter values from making 1-block puddles.")
                .defineInRange("syntheticMinDepthBlocks", 3, 1, 128);
        LAKE_SYNTHETIC_MAX_DEPTH_BLOCKS = builder.comment("Maximum synthetic depth in Minecraft blocks for small WorldCover lakes. Actual depth still depends on distance to shore.")
                .defineInRange("syntheticMaxDepthBlocks", 9, 1, 256);
        LAKE_FULL_DEPTH_DISTANCE_BLOCKS = builder.comment("Distance from shore where synthetic lakes reach max depth.")
                .defineInRange("fullDepthDistanceBlocks", 96, 1, 2048);
        LAKE_MANUAL_DEFAULT_SHORE_BLEND_METERS = builder.comment("Default shore blend around manual circular lakes, in meters.")
                .defineInRange("manualDefaultShoreBlendMeters", 45.0D, 0.0D, 1000.0D);
        builder.pop();

        builder.push("river_water");
        RIVER_ROOT = builder.comment("HydroRIVERS source directory. For regional testing put matching .shp and .dbf files here. Global runtime packs should use cooked river tiles later, not the raw global shapefile.")
                .define("tileRoot", "config/redline-atlas-worldgen/rivers");
        RIVER_GUIDE_ENABLED = builder.comment("Enable HydroRIVERS-guided channels, banks, river biomes and surface-water finishing.")
                .define("enabled", true);
        RIVER_SOURCE_BOUNDS = builder.comment("Optional south,north,west,east filter applied while reading a regional/continental HydroRIVERS shapefile. The default keeps the first Alps test small. Empty means no filter and is unsafe for the global shapefile.")
                .define("sourceBounds", "44.0,47.0,5.0,9.0");
        RIVER_MIN_STRAHLER_ORDER = builder.comment("Minimum HydroRIVERS ORD_STRA loaded. 1 keeps every mapped stream; 3 is a practical detailed test preset.")
                .defineInRange("minStrahlerOrder", 3, 1, 10);
        RIVER_MAX_SEGMENTS = builder.comment("Hard safety cap for loaded polyline parts after bounds/order filtering. Prevents accidentally loading the full global raw shapefile into the game JVM.")
                .defineInRange("maxSegments", 200000, 1, 5000000);
        RIVER_INDEX_CELL_SIZE_BLOCKS = builder.comment("XZ cell size of the river spatial index. It must remain comfortably larger than common river widths.")
                .defineInRange("indexCellSizeBlocks", 256, 32, 4096);
        RIVER_BIOME_CACHE_CELL_SIZE_BLOCKS = builder.comment("Coarse river sample cell used only by biome selection. Exact surface carving always samples per block.")
                .defineInRange("biomeCacheCellSizeBlocks", 16, 1, 256);
        RIVER_SAMPLE_CACHE_LIMIT = builder.comment("Approximate maximum cached coarse river samples.")
                .defineInRange("sampleCacheLimit", 262144, 1024, 8388608);
        RIVER_WRITE_COOKED_CACHE = builder.comment("After regional SHP+DBF conversion/refinement, write a compressed region-cache.rriver runtime tile. The raw GIS files may then be removed from a distributed atlas pack.")
                .define("writeCookedCache", true);
        RIVER_PREFER_COOKED_CACHE = builder.comment("Load a valid .rriver cache instead of repeating expensive SHP parsing and GLO-30/WorldCover refinement on every server start.")
                .define("preferCookedCache", true);
        RIVER_REFINE_ENABLED = builder.comment("Move HydroRIVERS' approximately 500m centerline onto the local GLO-30 valley/WorldCover water corridor during reload.")
                .define("refineEnabled", true);
        RIVER_REFINE_RADIUS_BLOCKS = builder.comment("Maximum perpendicular search radius used to fit a source line to the local valley. At 6m/block, 160 blocks is about 960m.")
                .defineInRange("refineRadiusBlocks", 160, 0, 1024);
        RIVER_REFINE_STEP_BLOCKS = builder.comment("Candidate spacing across the valley during line refinement. Smaller follows GLO-30 more closely but reloads more slowly.")
                .defineInRange("refineStepBlocks", 8, 1, 128);
        RIVER_REFINE_POINT_SPACING_BLOCKS = builder.comment("Maximum spacing along a source polyline before it is densified and fitted. This recovers broad meanders missing from the 15 arc-second source line.")
                .defineInRange("refinePointSpacingBlocks", 48, 4, 512);
        RIVER_VALLEY_HEIGHT_WEIGHT = builder.comment("Cost per real meter above the lowest candidate in the fitting cross-section.")
                .defineInRange("valleyHeightWeight", 3.0D, 0.0D, 1000.0D);
        RIVER_SOURCE_DISTANCE_WEIGHT = builder.comment("Cost per block moved away from the HydroRIVERS source line.")
                .defineInRange("sourceDistanceWeight", 0.12D, 0.0D, 1000.0D);
        RIVER_UPHILL_WEIGHT = builder.comment("Additional cost per real meter a downstream fitted point rises above the preceding point.")
                .defineInRange("uphillWeight", 8.0D, 0.0D, 1000.0D);
        RIVER_WORLDCOVER_WATER_BONUS = builder.comment("Negative fitting cost for candidates whose ESA WorldCover class is permanent water.")
                .defineInRange("worldcoverWaterBonus", 180.0D, 0.0D, 100000.0D);
        RIVER_MIN_WIDTH_BLOCKS = builder.comment("Minimum full gameplay channel width. Real sub-pixel streams are widened to remain visible and usable.")
                .defineInRange("minWidthBlocks", 3, 1, 128);
        RIVER_MAX_WIDTH_BLOCKS = builder.comment("Maximum full channel width reconstructed from discharge/WorldCover. Wide deltas should be supplied as prepared water masks later.")
                .defineInRange("maxWidthBlocks", 384, 4, 4096);
        RIVER_WIDTH_DISCHARGE_FACTOR = builder.comment("Fallback real width is factor * sqrt(DIS_AV_CMS), then converted through horizontalMetersPerBlock.")
                .defineInRange("widthDischargeFactor", 5.0D, 0.1D, 100.0D);
        RIVER_WIDTH_SCAN_STEP_BLOCKS = builder.comment("Perpendicular step used to measure visible WorldCover water width around a fitted centerline.")
                .defineInRange("widthScanStepBlocks", 1, 1, 32);
        RIVER_MIN_DEPTH_BLOCKS = builder.comment("Minimum carved river depth in Minecraft blocks.")
                .defineInRange("minDepthBlocks", 2, 1, 64);
        RIVER_MAX_DEPTH_BLOCKS = builder.comment("Maximum synthetic river depth in Minecraft blocks.")
                .defineInRange("maxDepthBlocks", 18, 1, 256);
        RIVER_DEPTH_WIDTH_FACTOR = builder.comment("Synthetic depth in blocks as a fraction of full channel width in blocks.")
                .defineInRange("depthWidthFactor", 0.22D, 0.01D, 2.0D);
        RIVER_BANK_WIDTH_BLOCKS = builder.comment("Sealed containment width outside each side of the water channel.")
                .defineInRange("bankWidthBlocks", 6, 0, 128);
        RIVER_TERRAIN_SHOULDER_WIDTH_BLOCKS = builder.comment("Additional dry-land width outside the sealed bank where low terrain may be raised toward the river. This is sampled as terrain context only and does not become a river biome or water mask.")
                .defineInRange("terrainShoulderWidthBlocks", 32, 0, 256);
        RIVER_WATER_BELOW_BANK_BLOCKS = builder.comment("Vertical distance from the symmetric channel rim to the river water surface. Allowed values are strictly 0 (same Y) or 1 (one block below); default 1.")
                .defineInRange("waterBelowBankBlocks", 1, 0, 1);
        RIVER_PROFILE_SNAP_TO_BLOCK = builder.comment("Fit a monotonic downstream profile and snap it down to block levels, producing stable pools separated by deterministic rapids/steps.")
                .define("snapProfileToBlock", true);
        RIVER_FLOW_PHYSICS_ENABLED = builder.comment("Enable the atlas-dimension water support guard: water resting on water may spread sideways over water/solid support, but not outward over unsupported air.")
                .define("flowPhysicsEnabled", true);
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
        OPEN_WATER_CACHE_CELL_SIZE_BLOCKS = builder.comment("Size of the cached water-classification grid in blocks. Worldgen/biome code samples water once per cell instead of once per biome quart/Y call. 16-64 is usually sane.")
                .defineInRange("cacheCellSizeBlocks", 32, 4, 1024);
        OPEN_WATER_SAMPLE_CACHE_LIMIT = builder.comment("Approximate maximum cached water cells. Cache is cleared when the limit is exceeded.")
                .defineInRange("sampleCacheLimit", 262144, 1024, 8388608);
        OPEN_WATER_ENABLE_COAST_SCAN_IN_BIOME_GUIDE = builder.comment("When false, biome generation uses only exact cached ocean/land classification and skips expensive coast-radius scans. /rla water_sample and /rla nearest_ocean still use exact scans.")
                .define("enableCoastScanInBiomeGuide", false);
        OPEN_WATER_COASTAL_FLOOD_ENABLED = builder.comment("Extend confirmed open ocean through connected low coastal land cells. This fixes coarse GEBCO coast gaps without flooding high land, lakes, or closed basins.")
                .define("coastalFloodEnabled", true);
        OPEN_WATER_COASTAL_FLOOD_CELL_SIZE_BLOCKS = builder.comment("Grid cell size for coastal flood fill. 16 is detailed enough for GLO-30 coasts; 32 is faster and smoother.")
                .defineInRange("coastalFloodCellSizeBlocks", 16, 4, 128);
        OPEN_WATER_COASTAL_FLOOD_MAX_DISTANCE_BLOCKS = builder.comment("Maximum distance from a confirmed GEBCO ocean seed that low coastal cells may be reconstructed as open ocean.")
                .defineInRange("coastalFloodMaxDistanceBlocks", 384, 0, 4096);
        OPEN_WATER_COASTAL_FLOOD_TOLERANCE_METERS = builder.comment("Land DEM cells at or below seaLevelMeters + this value can be flooded if connected to confirmed open ocean. Keep small to avoid flooding real lowland.")
                .defineInRange("coastalFloodToleranceMeters", 8.0D, 0.0D, 100.0D);
        OPEN_WATER_COASTAL_FLOOD_MIN_DEPTH_METERS = builder.comment("Minimum inferred water depth for reconstructed coastal flood cells where GEBCO is missing/positive.")
                .defineInRange("coastalFloodMinDepthMeters", 2.0D, 0.5D, 64.0D);
        OPEN_WATER_COASTAL_FLOOD_MAX_DEPTH_METERS = builder.comment("Maximum inferred water depth for reconstructed coastal flood cells. Exact negative GEBCO samples still keep their real depth.")
                .defineInRange("coastalFloodMaxDepthMeters", 18.0D, 1.0D, 256.0D);
        OPEN_WATER_COASTAL_FLOOD_CACHE_LIMIT = builder.comment("Approximate maximum cached coastal flood cells. Cache is cleared when the limit is exceeded.")
                .defineInRange("coastalFloodCacheLimit", 262144, 1024, 8388608);
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
                .defineInRange("topScanBlocks", 32, 1, 512);
        SURFACE_POLISH_FILL_OPEN_OCEAN_WATER = builder.comment("Fill open-ocean/ocean-connected sea columns with vanilla water up to mapping.seaLevelY. This is only for the ocean bathymetry layer, not lakes/rivers.")
                .define("fillOpenOceanWater", true);
        SURFACE_POLISH_BUILD_OPEN_OCEAN_SHORES = builder.comment("Replace exposed atlas-shift stone/deepslate at open-ocean coasts with sand/gravel/stone shore materials.")
                .define("buildOpenOceanShores", true);
        SURFACE_POLISH_EXACT_COAST_SAMPLES = builder.comment("Use exact coast-radius water samples during surface polish. This is slower than cached biome samples but runs only once per polished column and gives beaches/stony shores.")
                .define("exactCoastSamples", true);
        SURFACE_POLISH_FILL_COAST_DEPRESSION_WATER = builder.comment("Fill coast columns that are below sea level and directly near confirmed open ocean. This is conservative; exact ocean columns are always handled by fillOpenOceanWater.")
                .define("fillCoastDepressionWater", true);
        SURFACE_POLISH_COAST_WATER_MAX_DISTANCE_BLOCKS = builder.comment("Maximum distance to confirmed open ocean for coast depression water fill. Set to 0 to fill only exact ocean columns.")
                .defineInRange("coastWaterMaxDistanceBlocks", 32, 0, 2048);
        SURFACE_POLISH_COASTAL_FLOOD_CARVE_ABOVE_SEA_BLOCKS = builder.comment("For reconstructed coastal flood cells only, remove at most this many blocks above sea level before filling water. This handles one/two-block vanilla noise lips without flattening real shores.")
                .defineInRange("coastalFloodCarveAboveSeaBlocks", 2, 0, 16);
        SURFACE_POLISH_OCEAN_CARVE_ABOVE_SEA_BLOCKS = builder.comment("In confirmed open-ocean columns, remove accidental terrain above sea level by at most this many blocks before filling water. Prevents vanilla spikes/islands caused by coarse bathymetry. 0 disables carving.")
                .defineInRange("oceanCarveAboveSeaBlocks", 0, 0, 256);
        SURFACE_POLISH_OCEAN_MAX_FILL_BLOCKS = builder.comment("Safety cap for vertical water fill per ocean/coast column. Prevents filling extreme wrong columns if mapping or bathymetry is bad.")
                .defineInRange("oceanMaxFillBlocks", 512, 1, 4096);
        SURFACE_POLISH_SHORE_SAND_DEPTH_BLOCKS = builder.comment("How many top blocks in shore/ocean-bottom columns are converted to sand/gravel. The top block and this many blocks below become the chosen material.")
                .defineInRange("shoreSandDepthBlocks", 3, 1, 16);
        SURFACE_POLISH_TERRAIN_CAP_DEPTH_BLOCKS = builder.comment("How many blocks below exposed land surface are repaired when atlas Y-shift exposes underground stone/deepslate/ores. Grass creates dirt below it; sand/gravel/stone remain homogeneous.")
                .defineInRange("terrainCapDepthBlocks", 3, 1, 32);
        SURFACE_POLISH_BEACH_DISTANCE_BLOCKS = builder.comment("How far from confirmed open ocean a low land column may be capped as sandy beach. This is only a thin surface cap, not terrain carving.")
                .defineInRange("beachDistanceBlocks", 96, 0, 1024);
        SURFACE_POLISH_BEACH_MAX_HEIGHT_ABOVE_SEA_BLOCKS = builder.comment("Maximum terrain height above sea level that can become sandy beach through surface polish. Higher land keeps normal biome material.")
                .defineInRange("beachMaxHeightAboveSeaBlocks", 6, 0, 128);
        SURFACE_POLISH_BEACH_MAX_SLOPE = builder.comment("Maximum atlas slope for surface polish to force sand on a near-ocean land column. Steeper shores stay grass/stone according to biome.")
                .defineInRange("beachMaxSlope", 0.45D, 0.0D, 8.0D);
        SURFACE_POLISH_OCEAN_SAND_DEPTH_METERS = builder.comment("Ocean bottom depth threshold for sand. Shallow seabed up to this depth becomes sand.")
                .defineInRange("oceanSandDepthMeters", 32.0D, 0.0D, 1000.0D);
        SURFACE_POLISH_OCEAN_GRAVEL_DEPTH_METERS = builder.comment("Ocean bottom depth threshold for gravel. Depths above oceanSandDepthMeters and up to this become gravel; deeper bottoms remain stone-like later.")
                .defineInRange("oceanGravelDepthMeters", 128.0D, 0.0D, 12000.0D);
        SURFACE_POLISH_REPLACE_SURFACE_ORES = builder.comment("Replace exposed ore blocks in the surface repair pass. This prevents ore/deepslate patches on beaches and lowland surfaces created by shifted terrain.")
                .define("replaceSurfaceOres", true);

        SURFACE_POLISH_FILL_LAKE_WATER = builder.comment("Fill inland lake / small waterbody columns with vanilla water up to their local lake water level.")
                .define("fillLakeWater", true);
        SURFACE_POLISH_LAKE_MAX_FILL_BLOCKS = builder.comment("Safety cap for vertical water fill per lake column.")
                .defineInRange("lakeMaxFillBlocks", 256, 1, 4096);
        SURFACE_POLISH_LAKE_CARVE_ABOVE_WATER_BLOCKS = builder.comment("For lake shore columns, remove at most this many blocks above local water level before filling. Keep small to avoid flattening shores.")
                .defineInRange("lakeCarveAboveWaterBlocks", 2, 0, 64);
        SURFACE_POLISH_LAKE_WATER_MASK_CARVE_ABOVE_WATER_BLOCKS = builder.comment("For exact lake water-mask columns, carve generated terrain down to the fitted lake surface before filling. The water mask is authoritative for area; DEM/basin fit is authoritative for level.")
                .defineInRange("lakeWaterMaskCarveAboveWaterBlocks", 128, 0, 512);
        SURFACE_POLISH_LAKE_SHORE_TARGET_HEIGHT_BLOCKS = builder.comment("Target lake shore terrain height relative to local water block. 0 means the shore block exists at the same Y as the water block, which contains vanilla water without making a raised rim.")
                .defineInRange("lakeShoreTargetHeightBlocks", 0, 0, 16);
        SURFACE_POLISH_LAKE_SHORE_SMOOTH_CARVE_BLOCKS = builder.comment("For near-lake shore columns, carve at most this many blocks down to the target shore height. Keeps high quarry walls/cliffs while smoothing 2-4 block lips.")
                .defineInRange("lakeShoreSmoothCarveBlocks", 3, 0, 64);
        SURFACE_POLISH_LAKE_LEAK_GUARD_RADIUS_BLOCKS = builder.comment("Runtime safety radius for lake filling. If nearby non-lake terrain is lower than the lake surface by more than lakeLeakMaxDropoffBlocks, the current water column is not filled. This is the final guard against floating water slabs when WorldCover and terrain disagree.")
                .defineInRange("lakeLeakGuardRadiusBlocks", 24, 0, 256);
        SURFACE_POLISH_LAKE_LEAK_GUARD_STEP_BLOCKS = builder.comment("Step in blocks for lake leak guard terrain probes.")
                .defineInRange("lakeLeakGuardStepBlocks", 8, 1, 64);
        SURFACE_POLISH_LAKE_LEAK_MAX_DROPOFF_BLOCKS = builder.comment("Legacy safety value. M30.6 contains lakes by building shore/bank columns from the water mask boundary instead of rejecting water columns.")
                .defineInRange("lakeLeakMaxDropoffBlocks", 2, 0, 64);
        SURFACE_POLISH_LAKE_BUILD_CONTAINMENT_BANKS = builder.comment("When true, non-water columns near the lake mask are guaranteed to contain the lake: if generated terrain is below the fitted water level, the polish pass fills it up to the water level with shore material. This prevents floating lake slabs and waterfall walls.")
                .define("lakeBuildContainmentBanks", true);
        SURFACE_POLISH_LAKE_BANK_MAX_RAISE_BLOCKS = builder.comment("Maximum number of blocks a near-lake shore column may be raised to contain the fitted water level. Larger values repair mismatched generated terrain; too large can create artificial embankments far from bad data.")
                .defineInRange("lakeBankMaxRaiseBlocks", 24, 0, 256);
        SURFACE_POLISH_LAKE_TERRAIN_SHOULDER_MAX_RAISE_BLOCKS = builder.comment("Maximum number of blocks dry terrain may be raised in the outer lake shoulder. The inner lake shore keeps its separate containment cap.")
                .defineInRange("lakeTerrainShoulderMaxRaiseBlocks", 12, 0, 128);
        SURFACE_POLISH_LAKE_TERRAIN_SHOULDER_MAX_SLOPE = builder.comment("Maximum downward envelope slope, in vertical blocks per horizontal block, from the contained sandy lake shore across its dry terrain shoulder.")
                .defineInRange("lakeTerrainShoulderMaxSlope", 0.5D, 0.0D, 8.0D);
        SURFACE_POLISH_LAKE_SAND_DEPTH_METERS = builder.comment("Lake bed depth threshold for sand. Shallow lake bed up to this depth becomes sand.")
                .defineInRange("lakeSandDepthMeters", 4.0D, 0.0D, 256.0D);
        SURFACE_POLISH_LAKE_GRAVEL_DEPTH_METERS = builder.comment("Lake bed depth threshold for gravel. Depths above lakeSandDepthMeters and up to this become gravel; deeper bottoms use clay/mud-like material.")
                .defineInRange("lakeGravelDepthMeters", 14.0D, 0.0D, 512.0D);
        SURFACE_POLISH_FILL_RIVER_WATER = builder.comment("Carve HydroRIVERS channels after normal generation and fill every channel column directly with full vanilla water source blocks.")
                .define("fillRiverWater", true);
        SURFACE_POLISH_RIVER_MAX_CARVE_ABOVE_WATER_BLOCKS = builder.comment("Safety cap for removing generated terrain above the fitted river surface inside an authoritative channel column.")
                .defineInRange("riverMaxCarveAboveWaterBlocks", 96, 0, 1024);
        SURFACE_POLISH_RIVER_MAX_FILL_BLOCKS = builder.comment("Safety cap for vertical river water fill per column.")
                .defineInRange("riverMaxFillBlocks", 64, 1, 1024);
        SURFACE_POLISH_RIVER_BANK_SMOOTH_CARVE_BLOCKS = builder.comment("Maximum bank lowering toward the local water level. Mountain rivers keep cliffs beyond this small smoothing amount.")
                .defineInRange("riverBankSmoothCarveBlocks", 4, 0, 64);
        SURFACE_POLISH_RIVER_BANK_MAX_RAISE_BLOCKS = builder.comment("Maximum dry terrain raise inside the river shoulder. The sealed bank itself is always connected down to existing terrain so it cannot form a floating aqueduct.")
                .defineInRange("riverBankMaxRaiseBlocks", 12, 0, 128);
        SURFACE_POLISH_RIVER_TERRAIN_SHOULDER_MAX_SLOPE = builder.comment("Maximum downward envelope slope, in vertical blocks per horizontal block, from the sealed bank across the dry terrain shoulder. 0.5 produces at least a two-block horizontal run per block of descent.")
                .defineInRange("riverTerrainShoulderMaxSlope", 0.5D, 0.0D, 8.0D);
        SURFACE_POLISH_RIVER_OVERFLOW_CLEANUP_BLOCKS = builder.comment("Distance outside the fitted river bank where stale non-source flowing water from older builds is removed during surface polish. New atlas river source blocks cannot spread horizontally, so this is primarily a migration/repair pass.")
                .defineInRange("riverOverflowCleanupBlocks", 16, 0, 128);
        SURFACE_POLISH_RIVER_LOOSE_FOUNDATION_MIN_BLOCKS = builder.comment("Minimum clay support thickness placed directly below river sand/gravel. The support is created before the falling cap so riverbeds and banks cannot collapse into caves.")
                .defineInRange("riverLooseFoundationMinBlocks", 2, 1, 32);
        SURFACE_POLISH_RIVER_LOOSE_FOUNDATION_MAX_BLOCKS = builder.comment("Maximum deterministic clay support thickness placed below river sand/gravel. Values are selected per column between min and max so the foundation does not form a perfectly uniform artificial sheet.")
                .defineInRange("riverLooseFoundationMaxBlocks", 4, 1, 32);
        SURFACE_POLISH_RIVER_SAND_DEPTH_METERS = builder.comment("River-bed depth threshold for sand. Very shallow/slow edges use sand.")
                .defineInRange("riverSandDepthMeters", 3.0D, 0.0D, 128.0D);
        SURFACE_POLISH_RIVER_GRAVEL_DEPTH_METERS = builder.comment("River-bed depth threshold for gravel. Deeper channels use clay below this threshold.")
                .defineInRange("riverGravelDepthMeters", 18.0D, 0.0D, 512.0D);
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
        BIOME_COLUMN_CACHE_LIMIT = builder.comment("Approximate cached atlas biome columns. This is important in tall worlds because vanilla asks biomes for many Y quart levels, while atlas height/landcover/water are mostly XZ data.")
                .defineInRange("columnCacheLimit", 262144, 1024, 8388608);
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

        BIOME_LAKE_WATER = builder.comment("Biome returned for inland lake/small waterbody columns. Vanilla has no lake biome, so river is the least-bad default.")
                .define("lakeWaterBiome", "minecraft:river");
        BIOME_FROZEN_LAKE_WATER = builder.comment("Biome returned for freezing inland lake/small waterbody columns.")
                .define("frozenLakeWaterBiome", "minecraft:frozen_river");
        BIOME_LAKE_SHORE = builder.comment("Biome returned for low near-lake shore land. Beach gives sand, but surface polish is still controlled separately.")
                .define("lakeShoreBiome", "minecraft:beach");
        BIOME_RIVER = builder.comment("Biome returned inside a fitted non-frozen river channel.")
                .define("riverBiome", "minecraft:river");
        BIOME_FROZEN_RIVER = builder.comment("Biome returned inside a fitted river channel below the freezing temperature threshold.")
                .define("frozenRiverBiome", "minecraft:frozen_river");
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
