package com.ibicza.redlineatlasworldgen.biome;

public record WaterContext(boolean hasWaterData, boolean exactWater,
                           double distanceToOceanBlocks, double distanceToLakeBlocks,
                           double distanceToRiverBlocks, double waterDepthMeters) {
    public static final WaterContext NONE = new WaterContext(false, false,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0D);
}
