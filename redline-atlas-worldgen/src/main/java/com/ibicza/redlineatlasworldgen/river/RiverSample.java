package com.ibicza.redlineatlasworldgen.river;

public record RiverSample(RiverKind kind, boolean exactWater, double distanceToCenterBlocks,
                          double halfWidthBlocks, double distanceToBankBlocks,
                          double waterSurfaceMeters, double bottomMeters, double depthMeters,
                          long riverId, long nextDownId, int strahlerOrder, double dischargeCms,
                          String sourceId, double resolutionMeters,
                          double centerXBlocks, double centerZBlocks,
                          double normalX, double normalZ, double alongRiverBlocks) {
    public static RiverSample none() {
        return new RiverSample(RiverKind.NONE, false, Double.POSITIVE_INFINITY, 0.0D,
                Double.POSITIVE_INFINITY, Double.NaN, Double.NaN, 0.0D,
                0L, 0L, 0, 0.0D, "none", 0.0D,
                Double.NaN, Double.NaN, 0.0D, 0.0D, Double.NaN);
    }

    public boolean hasRiverData() {
        return kind != RiverKind.NONE;
    }
}
