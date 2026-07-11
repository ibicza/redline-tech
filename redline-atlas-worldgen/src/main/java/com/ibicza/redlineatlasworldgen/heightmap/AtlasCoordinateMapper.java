package com.ibicza.redlineatlasworldgen.heightmap;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;

public final class AtlasCoordinateMapper {
    public static GeoPoint toGeo(int blockX, int blockZ) {
        double latitude = AtlasWorldgenConfig.ORIGIN_LATITUDE.get()
                - blockZ * AtlasWorldgenConfig.DEGREES_PER_BLOCK_LATITUDE.get();
        double longitude = AtlasWorldgenConfig.ORIGIN_LONGITUDE.get()
                + blockX * AtlasWorldgenConfig.DEGREES_PER_BLOCK_LONGITUDE.get();
        longitude = wrapLongitude(longitude);
        latitude = Math.max(-90.0D, Math.min(90.0D, latitude));
        return new GeoPoint(latitude, longitude);
    }

    public static int metersToWorldY(double meters) {
        double verticalScale = AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get();
        return (int) Math.round(meters / verticalScale) + AtlasWorldgenConfig.SEA_LEVEL_Y.get();
    }

    public static double toWorldX(double longitude) {
        double delta = wrapLongitude(longitude - AtlasWorldgenConfig.ORIGIN_LONGITUDE.get());
        return delta / AtlasWorldgenConfig.DEGREES_PER_BLOCK_LONGITUDE.get();
    }

    public static double toWorldZ(double latitude) {
        return (AtlasWorldgenConfig.ORIGIN_LATITUDE.get() - latitude)
                / AtlasWorldgenConfig.DEGREES_PER_BLOCK_LATITUDE.get();
    }

    public static double worldYToMeters(int worldY) {
        return (worldY - AtlasWorldgenConfig.SEA_LEVEL_Y.get())
                * AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get();
    }

    private static double wrapLongitude(double longitude) {
        double result = longitude;
        while (result < -180.0D) {
            result += 360.0D;
        }
        while (result > 180.0D) {
            result -= 360.0D;
        }
        return result;
    }

    private AtlasCoordinateMapper() {
    }
}
