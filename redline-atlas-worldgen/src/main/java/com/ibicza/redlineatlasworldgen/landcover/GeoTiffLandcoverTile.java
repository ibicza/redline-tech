package com.ibicza.redlineatlasworldgen.landcover;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.heightmap.CopernicusGeoTiffDem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class GeoTiffLandcoverTile implements LandcoverTile {
    private final Path path;
    private final LandcoverTileBounds bounds;
    private volatile CopernicusGeoTiffDem raster;
    private volatile int width;
    private volatile int height;
    private volatile boolean failed;

    public GeoTiffLandcoverTile(Path path, LandcoverTileBounds bounds) {
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
        return bounds.resolutionMeters();
    }

    @Override
    public double nominalResolutionMeters() {
        return bounds.resolutionMeters();
    }

    @Override
    public Optional<LandcoverSample> sample(double latitude, double longitude) throws IOException {
        CopernicusGeoTiffDem reader = loadRaster();
        if (reader == null) {
            return Optional.empty();
        }

        double fx = (longitude - west()) / (east() - west()) * width;
        double fy = (north() - latitude) / (north() - south()) * height;
        int x = clamp((int) Math.floor(fx), 0, width - 1);
        int y = clamp((int) Math.floor(fy), 0, height - 1);
        double value = reader.samplePixel(x, y);
        if (!Double.isFinite(value)) {
            return Optional.empty();
        }
        int raw = (int) Math.round(value);
        LandcoverClass landcover = LandcoverClass.fromEsaWorldCoverCode(raw);
        if (!landcover.isKnown()) {
            return Optional.empty();
        }
        return Optional.of(new LandcoverSample(landcover, raw, id(), nominalResolutionMeters()));
    }

    private CopernicusGeoTiffDem loadRaster() throws IOException {
        CopernicusGeoTiffDem existing = raster;
        if (existing != null || failed) {
            return existing;
        }

        synchronized (this) {
            if (raster != null || failed) {
                return raster;
            }
            if (!Files.isRegularFile(path)) {
                failed = true;
                return null;
            }

            try {
                CopernicusGeoTiffDem reader = CopernicusGeoTiffDem.open(path);
                this.width = reader.width();
                this.height = reader.height();
                this.raster = reader;
                RedlineAtlasWorldgen.LOGGER.info(
                        "Loaded ESA WorldCover tile {} ({}x{}, tile={}x{}, bits={}, sampleFormat={}, compression={}, predictor={}, bounds lat {}..{}, lon {}..{})",
                        path.getFileName(), width, height, reader.tileWidth(), reader.tileLength(), reader.bitsPerSample(), reader.sampleFormat(),
                        reader.compression(), reader.predictor(), south(), north(), west(), east());
                return reader;
            } catch (IOException | RuntimeException ex) {
                failed = true;
                RedlineAtlasWorldgen.LOGGER.warn("Failed to read ESA WorldCover tile {} with built-in TIFF reader: {}", path, ex.toString());
                return null;
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
