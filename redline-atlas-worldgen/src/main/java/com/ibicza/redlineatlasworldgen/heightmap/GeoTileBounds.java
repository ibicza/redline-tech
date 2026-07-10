package com.ibicza.redlineatlasworldgen.heightmap;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record GeoTileBounds(double south, double north, double west, double east, double arcSeconds) {
    private static final Pattern COPERNICUS = Pattern.compile(
            "Copernicus_DSM_COG_(\\d+)_([NS])(\\d{2})_00_([EW])(\\d{3})_00_DEM(?:\\.tif|\\.tiff)?",
            Pattern.CASE_INSENSITIVE
    );

    public static Optional<GeoTileBounds> parseCopernicusName(String fileName) {
        Matcher matcher = COPERNICUS.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        int arcCode = Integer.parseInt(matcher.group(1));
        String ns = matcher.group(2).toUpperCase();
        int latAbs = Integer.parseInt(matcher.group(3));
        String ew = matcher.group(4).toUpperCase();
        int lonAbs = Integer.parseInt(matcher.group(5));

        double south = ns.equals("N") ? latAbs : -latAbs;
        double north = south + 1.0D;
        double west = ew.equals("E") ? lonAbs : -lonAbs;
        double east = west + 1.0D;
        return Optional.of(new GeoTileBounds(south, north, west, east, copernicusArcSeconds(arcCode)));
    }

    public double nominalResolutionMeters() {
        return arcSeconds * 30.87D;
    }

    private static double copernicusArcSeconds(int arcCode) {
        return switch (arcCode) {
            case 10 -> 1.0D; // GLO-30: Copernicus uses 10 to mean 1 arc-second / ~30 m.
            case 30 -> 3.0D; // GLO-90: Copernicus uses 30 to mean 3 arc-seconds / ~90 m.
            default -> arcCode;
        };
    }
}

