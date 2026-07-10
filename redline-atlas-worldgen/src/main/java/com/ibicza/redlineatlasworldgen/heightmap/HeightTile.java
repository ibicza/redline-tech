package com.ibicza.redlineatlasworldgen.heightmap;

import java.io.IOException;
import java.util.OptionalDouble;

public interface HeightTile {
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

    OptionalDouble sampleMeters(double latitude, double longitude) throws IOException;

    default String shortText() {
        return id() + "[" + south() + ".." + north() + ", " + west() + ".." + east() + "]";
    }
}
