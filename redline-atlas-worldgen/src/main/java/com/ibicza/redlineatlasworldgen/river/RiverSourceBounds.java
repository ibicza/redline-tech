package com.ibicza.redlineatlasworldgen.river;

import java.util.Locale;
import java.util.Optional;

record RiverSourceBounds(double south, double north, double west, double east) {
    static Optional<RiverSourceBounds> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] parts = text.trim().split("[,;\\s]+");
        if (parts.length != 4) {
            throw new IllegalArgumentException("river_water.sourceBounds must be south,north,west,east, got: " + text);
        }
        double south = Double.parseDouble(parts[0]);
        double north = Double.parseDouble(parts[1]);
        double west = Double.parseDouble(parts[2]);
        double east = Double.parseDouble(parts[3]);
        if (south > north || west > east || south < -90.0D || north > 90.0D || west < -180.0D || east > 180.0D) {
            throw new IllegalArgumentException("Invalid river source bounds: " + text);
        }
        return Optional.of(new RiverSourceBounds(south, north, west, east));
    }

    boolean intersects(double minLongitude, double minLatitude, double maxLongitude, double maxLatitude) {
        return maxLatitude >= south && minLatitude <= north && maxLongitude >= west && minLongitude <= east;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", south, north, west, east);
    }
}
