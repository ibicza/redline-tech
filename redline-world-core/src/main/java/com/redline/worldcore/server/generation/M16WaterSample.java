package com.redline.worldcore.server.generation;

import java.util.Locale;

/** Deterministic M16 hydrology sample at one X/Z column. */
public record M16WaterSample(
        int x,
        int z,
        int drySurfaceY,
        int effectiveSurfaceY,
        int waterSurfaceY,
        int waterDepth,
        int carveDepth,
        int valleyWidth,
        double oceanMask,
        double shoreDistance,
        double riverDistance,
        int riverWidth,
        M16WaterType waterType,
        M16RiverProfile riverProfile,
        boolean greatRiver,
        boolean canyonRiver,
        boolean waterfallCandidate
) {
    public boolean hasWater() {
        return waterType != M16WaterType.NONE;
    }

    public boolean isRiverLike() {
        return waterType == M16WaterType.RIVER || waterType == M16WaterType.WATERFALL;
    }

    public String oneLine() {
        return String.format(Locale.ROOT,
                "x=%d, z=%d, waterType=%s, profile=%s, drySurfaceY=%d, effectiveSurfaceY=%d, waterSurfaceY=%d, waterDepth=%d, carveDepth=%d, valleyWidth=%d, riverWidth=%d, riverDistance=%.2f, oceanMask=%.3f, shoreDistance=%.2f, great=%s, canyon=%s, waterfall=%s",
                x, z, waterType, riverProfile, drySurfaceY, effectiveSurfaceY, waterSurfaceY, waterDepth, carveDepth, valleyWidth,
                riverWidth, riverDistance, oceanMask, shoreDistance, greatRiver, canyonRiver, waterfallCandidate);
    }
}
