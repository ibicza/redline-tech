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

        builder.push("debug");
        DEBUG_LOG_CHUNKS = builder.comment("Log old debug post-shaper chunk summaries. Noisy; does not affect noise-guided generation.")
                .define("logChunks", false);
        builder.pop();

        SPEC = builder.build();
    }

    private AtlasWorldgenConfig() {
    }
}
