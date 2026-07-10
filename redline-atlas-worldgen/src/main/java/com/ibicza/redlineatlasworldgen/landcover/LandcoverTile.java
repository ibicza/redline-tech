package com.ibicza.redlineatlasworldgen.landcover;

import java.io.IOException;
import java.util.Optional;

public interface LandcoverTile {
    String id();

    double west();

    double east();

    double south();

    double north();

    double priority();

    double nominalResolutionMeters();

    default boolean contains(double latitude, double longitude) {
        return latitude >= south() && latitude <= north() && longitude >= west() && longitude <= east();
    }

    Optional<LandcoverSample> sample(double latitude, double longitude) throws IOException;

    default String shortText() {
        return id() + "[" + south() + ".." + north() + ", " + west() + ".." + east() + "]";
    }
}
