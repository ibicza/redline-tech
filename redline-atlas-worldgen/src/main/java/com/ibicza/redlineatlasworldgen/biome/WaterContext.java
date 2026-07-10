package com.ibicza.redlineatlasworldgen.biome;

public record WaterContext(WaterKind kind, boolean hasWaterData, boolean exactWater,
                           double distanceToOceanBlocks, double distanceToLakeBlocks,
                           double distanceToRiverBlocks, double waterDepthMeters,
                           double bottomMeters, double waterSurfaceMeters,
                           String sourceId, double resolutionMeters) {
    public static final WaterContext NONE = new WaterContext(WaterKind.NONE, false, false,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            0.0D, Double.NaN, Double.NaN, "none", 0.0D);

    public enum WaterKind {
        NONE,
        OPEN_OCEAN,
        OPEN_OCEAN_COAST,
        GEBCO_LAND_OR_CLOSED_WATER
    }
}
