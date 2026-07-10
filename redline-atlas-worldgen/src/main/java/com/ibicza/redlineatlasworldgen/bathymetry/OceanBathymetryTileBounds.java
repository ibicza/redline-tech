package com.ibicza.redlineatlasworldgen.bathymetry;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record OceanBathymetryTileBounds(double south, double north, double west, double east, double nominalResolutionMeters) {
    private static final Pattern NSWE_PATTERN = Pattern.compile("(?i)(?:^|[_\\-])n(-?\\d+(?:\\.\\d+)?)[_\\-]s(-?\\d+(?:\\.\\d+)?)[_\\-]w(-?\\d+(?:\\.\\d+)?)[_\\-]e(-?\\d+(?:\\.\\d+)?)(?:[_\\-.]|$)");
    private static final Pattern SNWE_PATTERN = Pattern.compile("(?i)(?:^|[_\\-])s(-?\\d+(?:\\.\\d+)?)[_\\-]n(-?\\d+(?:\\.\\d+)?)[_\\-]w(-?\\d+(?:\\.\\d+)?)[_\\-]e(-?\\d+(?:\\.\\d+)?)(?:[_\\-.]|$)");
    private static final Pattern BBOX_PATTERN = Pattern.compile("(?i)(?:^|[_\\-])bbox[_\\-](-?\\d+(?:\\.\\d+)?)[_\\-](-?\\d+(?:\\.\\d+)?)[_\\-](-?\\d+(?:\\.\\d+)?)[_\\-](-?\\d+(?:\\.\\d+)?)(?:[_\\-.]|$)");

    public OceanBathymetryTileBounds {
        if (north < south) {
            double tmp = north;
            north = south;
            south = tmp;
        }
        if (east < west) {
            double tmp = east;
            east = west;
            west = tmp;
        }
    }

    public static Optional<OceanBathymetryTileBounds> parseFromFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        Matcher nswe = NSWE_PATTERN.matcher(lower);
        if (nswe.find()) {
            double north = Double.parseDouble(nswe.group(1));
            double south = Double.parseDouble(nswe.group(2));
            double west = Double.parseDouble(nswe.group(3));
            double east = Double.parseDouble(nswe.group(4));
            return Optional.of(new OceanBathymetryTileBounds(south, north, west, east, inferResolutionMeters(lower)));
        }

        Matcher snwe = SNWE_PATTERN.matcher(lower);
        if (snwe.find()) {
            double south = Double.parseDouble(snwe.group(1));
            double north = Double.parseDouble(snwe.group(2));
            double west = Double.parseDouble(snwe.group(3));
            double east = Double.parseDouble(snwe.group(4));
            return Optional.of(new OceanBathymetryTileBounds(south, north, west, east, inferResolutionMeters(lower)));
        }

        // bbox_west_south_east_north helper for manually renamed subsets.
        Matcher bbox = BBOX_PATTERN.matcher(lower);
        if (bbox.find()) {
            double west = Double.parseDouble(bbox.group(1));
            double south = Double.parseDouble(bbox.group(2));
            double east = Double.parseDouble(bbox.group(3));
            double north = Double.parseDouble(bbox.group(4));
            return Optional.of(new OceanBathymetryTileBounds(south, north, west, east, inferResolutionMeters(lower)));
        }
        return Optional.empty();
    }

    public static OceanBathymetryTileBounds fromProperties(Path propertiesPath) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(propertiesPath)) {
            properties.load(reader);
        }
        double south = requiredDouble(properties, "south");
        double north = requiredDouble(properties, "north");
        double west = requiredDouble(properties, "west");
        double east = requiredDouble(properties, "east");
        double resolution = doubleOrDefault(properties, "nominalResolutionMeters", 463.0D);
        return new OceanBathymetryTileBounds(south, north, west, east, resolution);
    }

    private static double inferResolutionMeters(String lower) {
        if (lower.contains("15s") || lower.contains("15sec") || lower.contains("gebco")) {
            return 463.0D;
        }
        if (lower.contains("30s") || lower.contains("30sec")) {
            return 926.0D;
        }
        return 463.0D;
    }

    private static double requiredDouble(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing required property '" + key + "'");
        }
        return Double.parseDouble(value.trim());
    }

    private static double doubleOrDefault(Properties properties, String key, double fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Double.parseDouble(value.trim());
    }
}
