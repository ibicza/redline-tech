package com.ibicza.redlinechunkpriority.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ChunkPriorityConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue RESPECT_SERVER_HAS_TIME;
    public static final ModConfigSpec.BooleanValue SKIP_ALREADY_FULL_CHUNKS;
    public static final ModConfigSpec.IntValue TICK_INTERVAL;
    public static final ModConfigSpec.IntValue TICKET_TIMEOUT_TICKS;
    public static final ModConfigSpec.IntValue MAX_REQUESTS_PER_PLAYER;
    public static final ModConfigSpec.IntValue MAX_REQUESTS_PER_TICK;
    public static final ModConfigSpec.BooleanValue REQUEST_FULL_STATUS;

    public static final ModConfigSpec.DoubleValue MIN_MOVEMENT_SPEED_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue MOVEMENT_LINE_DISTANCE_CHUNKS;
    public static final ModConfigSpec.IntValue MOVEMENT_MAX_SIDE_OFFSET_CHUNKS;
    public static final ModConfigSpec.IntValue MOVEMENT_OFFSET_STEP_DISTANCE;

    public static final ModConfigSpec.IntValue LOOK_LINE_DISTANCE_CHUNKS;
    public static final ModConfigSpec.IntValue LOOK_FOV_DEGREES;
    public static final ModConfigSpec.IntValue LOOK_ANGLE_STEP_DEGREES;

    public static final ModConfigSpec.IntValue SIDE_DISTANCE_CHUNKS;
    public static final ModConfigSpec.IntValue BACK_DISTANCE_CHUNKS;
    public static final ModConfigSpec.IntValue REST_DISTANCE_CHUNKS;

    public static final ModConfigSpec.BooleanValue DEBUG_LOG_TOP_CHUNKS;
    public static final ModConfigSpec.IntValue DEBUG_LOG_COUNT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("general");
        ENABLED = builder.comment("Master switch for chunk priority preloading.")
                .define("enabled", true);
        RESPECT_SERVER_HAS_TIME = builder.comment("Skip work when ServerTickEvent says the server has no spare time.")
                .define("respectServerHasTime", true);
        SKIP_ALREADY_FULL_CHUNKS = builder.comment("Do not request chunks that are already available as full LevelChunks.")
                .define("skipAlreadyFullChunks", true);
        TICK_INTERVAL = builder.comment("Run every N server ticks. Smaller = more aggressive.")
                .defineInRange("tickInterval", 5, 1, 200);
        TICKET_TIMEOUT_TICKS = builder.comment("Temporary loading-ticket lifetime. Should be higher than tickInterval.")
                .defineInRange("ticketTimeoutTicks", 40, 5, 1200);
        MAX_REQUESTS_PER_PLAYER = builder.comment("Maximum planned chunk requests per player per run.")
                .defineInRange("maxRequestsPerPlayer", 96, 1, 4096);
        MAX_REQUESTS_PER_TICK = builder.comment("Global cap across all players per run.")
                .defineInRange("maxRequestsPerTick", 384, 1, 8192);
        REQUEST_FULL_STATUS = builder.comment("Also submit getChunkFuture(..., FULL, true) after adding the loading ticket.")
                .define("requestFullStatus", true);
        builder.pop();

        builder.push("movement");
        MIN_MOVEMENT_SPEED_BLOCKS_PER_TICK = builder.comment("Horizontal velocity threshold for Tier 2 movement line.")
                .defineInRange("minSpeedBlocksPerTick", 0.08D, 0.0D, 20.0D);
        MOVEMENT_LINE_DISTANCE_CHUNKS = builder.comment("Maximum distance of the strict movement center line.")
                .defineInRange("lineDistanceChunks", 12, 1, 128);
        MOVEMENT_MAX_SIDE_OFFSET_CHUNKS = builder.comment("How far Tier 2 expands sideways after the strict center line.")
                .defineInRange("maxSideOffsetChunks", 4, 0, 64);
        MOVEMENT_OFFSET_STEP_DISTANCE = builder.comment("Every side offset starts after roughly offset*step chunks to avoid near-side spam.")
                .defineInRange("offsetStepDistance", 3, 1, 32);
        builder.pop();

        builder.push("look");
        LOOK_LINE_DISTANCE_CHUNKS = builder.comment("Maximum distance of the look center line and fan rays.")
                .defineInRange("lineDistanceChunks", 12, 1, 128);
        LOOK_FOV_DEGREES = builder.comment("Total look fan width. 90 means +/-45 degrees around the look line.")
                .defineInRange("fovDegrees", 90, 10, 180);
        LOOK_ANGLE_STEP_DEGREES = builder.comment("Look fan expands from the center line to the FOV edges by this angular step.")
                .defineInRange("angleStepDegrees", 10, 1, 45);
        builder.pop();

        builder.push("fallback");
        SIDE_DISTANCE_CHUNKS = builder.comment("Side chunks ring distance after movement/look tiers.")
                .defineInRange("sideDistanceChunks", 10, 1, 128);
        BACK_DISTANCE_CHUNKS = builder.comment("Back chunks ring distance after side chunks.")
                .defineInRange("backDistanceChunks", 8, 1, 128);
        REST_DISTANCE_CHUNKS = builder.comment("Remaining chunks by rings after all directional tiers.")
                .defineInRange("restDistanceChunks", 12, 1, 128);
        builder.pop();

        builder.push("debug");
        DEBUG_LOG_TOP_CHUNKS = builder.comment("Log top planned chunks for each player on each run. Very noisy.")
                .define("logTopChunks", false);
        DEBUG_LOG_COUNT = builder.comment("How many planned chunks to include in debug log.")
                .defineInRange("logCount", 16, 1, 128);
        builder.pop();

        SPEC = builder.build();
    }

    private ChunkPriorityConfig() {
    }
}
