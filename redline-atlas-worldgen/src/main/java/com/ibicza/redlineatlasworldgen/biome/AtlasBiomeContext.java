package com.ibicza.redlineatlasworldgen.biome;

import com.ibicza.redlineatlasworldgen.landcover.LandcoverClass;

public record AtlasBiomeContext(int worldX, int worldY, int worldZ,
                                double latitude, double longitude,
                                double elevationMeters, int surfaceY, int relativeY,
                                LandcoverClass landcover, int landcoverRawCode, String landcoverSource,
                                double slope, double roughness,
                                double temperatureC, double humidity01,
                                WaterContext water, long seed) {
}
