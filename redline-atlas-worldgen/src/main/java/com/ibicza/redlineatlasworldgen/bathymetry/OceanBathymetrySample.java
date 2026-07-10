package com.ibicza.redlineatlasworldgen.bathymetry;

public record OceanBathymetrySample(double bottomMeters, double depthMeters, String sourceId, double nominalResolutionMeters) {
}
