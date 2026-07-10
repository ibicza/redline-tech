package com.ibicza.redlineatlasworldgen.bathymetry;

import java.io.IOException;
import java.util.OptionalDouble;

public interface OceanBathymetryTile {
    String id();

    double west();

    double east();

    double south();

    double north();

    double priority();

    double nominalResolutionMeters();

    OptionalDouble sampleBottomMeters(double latitude, double longitude) throws IOException;

    default boolean contains(double latitude, double longitude) {
        return latitude >= south() && latitude <= north() && longitude >= west() && longitude <= east();
    }

    default String shortText() {
        return id() + "[" + south() + ".." + north() + ", " + west() + ".." + east() + "]";
    }
}
