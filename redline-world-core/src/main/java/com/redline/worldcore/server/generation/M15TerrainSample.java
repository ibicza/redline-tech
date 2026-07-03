package com.redline.worldcore.server.generation;

public record M15TerrainSample(
        int x,
        int z,
        int surfaceY,
        int seaLevel,
        double continentalness,
        double erosion,
        double temperature,
        double humidity,
        double ridge,
        M15SurfaceZone zone
) {
    public String oneLine() {
        return String.format(java.util.Locale.ROOT,
                "x=%d, z=%d, surfaceY=%d, seaLevel=%d, zone=%s, continentalness=%.3f, erosion=%.3f, temperature=%.3f, humidity=%.3f, ridge=%.3f",
                x, z, surfaceY, seaLevel, zone, continentalness, erosion, temperature, humidity, ridge);
    }
}
