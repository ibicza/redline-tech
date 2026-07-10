package com.ibicza.redlineatlasworldgen.bathymetry;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.heightmap.CopernicusGeoTiffDem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;

public final class GeoTiffOceanBathymetryTile implements OceanBathymetryTile {
    private final Path path;
    private final OceanBathymetryTileBounds bounds;
    private volatile CopernicusGeoTiffDem dem;
    private volatile int width;
    private volatile int height;
    private volatile boolean failed;

    public GeoTiffOceanBathymetryTile(Path path, OceanBathymetryTileBounds bounds) {
        this.path = path;
        this.bounds = bounds;
    }

    @Override
    public String id() {
        return path.getFileName().toString();
    }

    @Override
    public double west() {
        return bounds.west();
    }

    @Override
    public double east() {
        return bounds.east();
    }

    @Override
    public double south() {
        return bounds.south();
    }

    @Override
    public double north() {
        return bounds.north();
    }

    @Override
    public double priority() {
        return bounds.nominalResolutionMeters();
    }

    @Override
    public double nominalResolutionMeters() {
        return bounds.nominalResolutionMeters();
    }

    @Override
    public OptionalDouble sampleBottomMeters(double latitude, double longitude) throws IOException {
        CopernicusGeoTiffDem reader = loadDem();
        if (reader == null) {
            return OptionalDouble.empty();
        }
        double fx = (longitude - west()) / (east() - west()) * width;
        double fy = (north() - latitude) / (north() - south()) * height;
        int x = clamp((int) Math.floor(fx), 0, width - 1);
        int y = clamp((int) Math.floor(fy), 0, height - 1);
        double value = reader.samplePixel(x, y);
        if (!Double.isFinite(value) || value < -12000.0D || value > 12000.0D) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    private CopernicusGeoTiffDem loadDem() throws IOException {
        CopernicusGeoTiffDem existing = dem;
        if (existing != null || failed) {
            return existing;
        }

        synchronized (this) {
            if (dem != null || failed) {
                return dem;
            }
            if (!Files.isRegularFile(path)) {
                failed = true;
                return null;
            }

            try {
                CopernicusGeoTiffDem reader = CopernicusGeoTiffDem.open(path);
                this.width = reader.width();
                this.height = reader.height();
                this.dem = reader;
                RedlineAtlasWorldgen.LOGGER.info(
                        "Loaded ocean bathymetry GeoTIFF {} ({}x{}, tile={}x{}, bits={}, sampleFormat={}, compression={}, predictor={}, bounds lat {}..{}, lon {}..{})",
                        path.getFileName(), width, height, reader.tileWidth(), reader.tileLength(), reader.bitsPerSample(), reader.sampleFormat(),
                        reader.compression(), reader.predictor(), south(), north(), west(), east());
                return reader;
            } catch (IOException | RuntimeException ex) {
                failed = true;
                RedlineAtlasWorldgen.LOGGER.warn("Failed to read ocean bathymetry GeoTIFF tile {}: {}", path, ex.toString());
                return null;
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
