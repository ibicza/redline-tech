package com.ibicza.redlineatlasworldgen.lake;

public record LakeSample(LakeKind kind, boolean hasLakeData, boolean exactWater,
                         double distanceToLakeBlocks, double distanceToShoreBlocks,
                         double depthMeters, double bottomMeters, double waterSurfaceMeters,
                         String lakeId, double resolutionMeters, String sourceId) {
    public static LakeSample none() {
        return new LakeSample(LakeKind.NONE, false, false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                0.0D, Double.NaN, Double.NaN, "none", 0.0D, "none");
    }
}
