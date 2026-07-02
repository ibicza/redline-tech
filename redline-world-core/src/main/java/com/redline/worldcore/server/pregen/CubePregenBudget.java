package com.redline.worldcore.server.pregen;

/** Runtime budget and anti-lag throttle for M13.1 pregen/backfill/AFK generation. */
public record CubePregenBudget(
        int maxCubesPerTick,
        int maxMillisPerTick,
        int maxSkippedCubesPerTick,
        int maxGeneratedCubesPerSecond,
        int expensiveCubeMillis,
        int cooldownAfterExpensiveTicks
) {
    public static final int DEFAULT_MAX_CUBES_PER_TICK = 2;
    public static final int DEFAULT_MAX_MILLIS_PER_TICK = 4;
    public static final int DEFAULT_MAX_SKIPPED_CUBES_PER_TICK = 64;
    public static final int DEFAULT_MAX_GENERATED_CUBES_PER_SECOND = 2;
    public static final int DEFAULT_EXPENSIVE_CUBE_MILLIS = 20;
    public static final int DEFAULT_COOLDOWN_AFTER_EXPENSIVE_TICKS = 10;

    public static final int MAX_CUBES_PER_TICK_LIMIT = 64;
    public static final int MAX_MILLIS_PER_TICK_LIMIT = 50;
    public static final int MAX_SKIPPED_CUBES_PER_TICK_LIMIT = 1024;
    public static final int MAX_GENERATED_CUBES_PER_SECOND_LIMIT = 20;
    public static final int MAX_EXPENSIVE_CUBE_MILLIS_LIMIT = 500;
    public static final int MAX_COOLDOWN_AFTER_EXPENSIVE_TICKS_LIMIT = 200;

    public CubePregenBudget(int maxCubesPerTick, int maxMillisPerTick) {
        this(maxCubesPerTick,
                maxMillisPerTick,
                DEFAULT_MAX_SKIPPED_CUBES_PER_TICK,
                DEFAULT_MAX_GENERATED_CUBES_PER_SECOND,
                DEFAULT_EXPENSIVE_CUBE_MILLIS,
                DEFAULT_COOLDOWN_AFTER_EXPENSIVE_TICKS);
    }

    public CubePregenBudget {
        if (maxCubesPerTick < 1 || maxCubesPerTick > MAX_CUBES_PER_TICK_LIMIT) {
            throw new IllegalArgumentException("maxCubesPerTick must be 1.." + MAX_CUBES_PER_TICK_LIMIT);
        }
        if (maxMillisPerTick < 1 || maxMillisPerTick > MAX_MILLIS_PER_TICK_LIMIT) {
            throw new IllegalArgumentException("maxMillisPerTick must be 1.." + MAX_MILLIS_PER_TICK_LIMIT);
        }
        if (maxSkippedCubesPerTick < 1 || maxSkippedCubesPerTick > MAX_SKIPPED_CUBES_PER_TICK_LIMIT) {
            throw new IllegalArgumentException("maxSkippedCubesPerTick must be 1.." + MAX_SKIPPED_CUBES_PER_TICK_LIMIT);
        }
        if (maxGeneratedCubesPerSecond < 1 || maxGeneratedCubesPerSecond > MAX_GENERATED_CUBES_PER_SECOND_LIMIT) {
            throw new IllegalArgumentException("maxGeneratedCubesPerSecond must be 1.." + MAX_GENERATED_CUBES_PER_SECOND_LIMIT);
        }
        if (expensiveCubeMillis < 1 || expensiveCubeMillis > MAX_EXPENSIVE_CUBE_MILLIS_LIMIT) {
            throw new IllegalArgumentException("expensiveCubeMillis must be 1.." + MAX_EXPENSIVE_CUBE_MILLIS_LIMIT);
        }
        if (cooldownAfterExpensiveTicks < 0 || cooldownAfterExpensiveTicks > MAX_COOLDOWN_AFTER_EXPENSIVE_TICKS_LIMIT) {
            throw new IllegalArgumentException("cooldownAfterExpensiveTicks must be 0.." + MAX_COOLDOWN_AFTER_EXPENSIVE_TICKS_LIMIT);
        }
    }

    public static CubePregenBudget defaults() {
        return new CubePregenBudget(
                DEFAULT_MAX_CUBES_PER_TICK,
                DEFAULT_MAX_MILLIS_PER_TICK,
                DEFAULT_MAX_SKIPPED_CUBES_PER_TICK,
                DEFAULT_MAX_GENERATED_CUBES_PER_SECOND,
                DEFAULT_EXPENSIVE_CUBE_MILLIS,
                DEFAULT_COOLDOWN_AFTER_EXPENSIVE_TICKS
        );
    }
}
