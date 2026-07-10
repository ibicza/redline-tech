package com.ibicza.redlineatlasworldgen.landcover;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record LandcoverTileBounds(double south, double north, double west, double east, double resolutionMeters) {
    private static final Pattern ESA_WORLDCOVER = Pattern.compile(
            "ESA_WorldCover_10m_2021_v200_([NS])(\\d{2})([EW])(\\d{3})_Map(?:\\.tif|\\.tiff)?",
            Pattern.CASE_INSENSITIVE
    );

    public static Optional<LandcoverTileBounds> parseEsaWorldCoverName(String fileName) {
        Matcher matcher = ESA_WORLDCOVER.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String ns = matcher.group(1).toUpperCase();
        int latAbs = Integer.parseInt(matcher.group(2));
        String ew = matcher.group(3).toUpperCase();
        int lonAbs = Integer.parseInt(matcher.group(4));

        double south = ns.equals("N") ? latAbs : -latAbs;
        double north = south + 3.0D;
        double west = ew.equals("E") ? lonAbs : -lonAbs;
        double east = west + 3.0D;
        return Optional.of(new LandcoverTileBounds(south, north, west, east, 10.0D));
    }
}
