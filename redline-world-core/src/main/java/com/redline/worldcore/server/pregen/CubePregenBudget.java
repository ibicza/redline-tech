package com.redline.worldcore.server.pregen;

/** Runtime budget for M13.0 manual cube pregen. */
public record CubePregenBudget(int maxCubesPerTick, int maxMillisPerTick) {
    public static final int DEFAULT_MAX_CUBES_PER_TICK = 2;
    public static final int DEFAULT_MAX_MILLIS_PER_TICK = 4;
    public static final int MAX_CUBES_PER_TICK_LIMIT = 64;
    public static final int MAX_MILLIS_PER_TICK_LIMIT = 50;

    public CubePregenBudget {
        if (maxCubesPerTick < 1 || maxCubesPerTick > MAX_CUBES_PER_TICK_LIMIT) {
            throw new IllegalArgumentException("maxCubesPerTick must be 1.." + MAX_CUBES_PER_TICK_LIMIT);
        }
        if (maxMillisPerTick < 1 || maxMillisPerTick > MAX_MILLIS_PER_TICK_LIMIT) {
            throw new IllegalArgumentException("maxMillisPerTick must be 1.." + MAX_MILLIS_PER_TICK_LIMIT);
        }
    }

    public static CubePregenBudget defaults() {
        return new CubePregenBudget(DEFAULT_MAX_CUBES_PER_TICK, DEFAULT_MAX_MILLIS_PER_TICK);
    }
}
