package com.ibicza.redlineatlasworldgen.bathymetry;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AtlasOceanBathymetryIndex {
    private static volatile AtlasOceanBathymetryIndex active = new AtlasOceanBathymetryIndex(List.of(), Path.of("."));

    private final List<OceanBathymetryTile> tiles;
    private final Path root;

    private AtlasOceanBathymetryIndex(List<OceanBathymetryTile> tiles, Path root) {
        this.tiles = List.copyOf(tiles);
        this.root = root;
    }

    public static AtlasOceanBathymetryIndex active() {
        return active;
    }

    public static AtlasOceanBathymetryIndex reload(Path gameDirectory) {
        Path configured = Path.of(AtlasWorldgenConfig.OCEAN_BATHYMETRY_TILE_ROOT.get());
        Path root = configured.isAbsolute() ? configured : gameDirectory.resolve(configured).normalize();
        List<OceanBathymetryTile> loaded = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            try {
                Files.createDirectories(root);
                RedlineAtlasWorldgen.LOGGER.info("Created ocean bathymetry directory {}", root);
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Could not create ocean bathymetry directory {}", root, ex);
            }
        }

        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(path -> scanFile(path, loaded));
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to scan ocean bathymetry directory {}", root, ex);
            }
        }

        loaded.sort(Comparator.comparingDouble(OceanBathymetryTile::priority).thenComparing(OceanBathymetryTile::id));
        AtlasOceanBathymetryIndex index = new AtlasOceanBathymetryIndex(loaded, root);
        active = index;
        RedlineAtlasWorldgen.LOGGER.info("Ocean bathymetry index loaded {} tile(s) from {}", loaded.size(), root);
        for (int i = 0; i < Math.min(loaded.size(), 16); i++) {
            RedlineAtlasWorldgen.LOGGER.info("  ocean tile {}: {} priority={} res={}m", i, loaded.get(i).shortText(), loaded.get(i).priority(), loaded.get(i).nominalResolutionMeters());
        }
        return index;
    }

    private static void scanFile(Path path, List<OceanBathymetryTile> loaded) {
        String fileName = path.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
                Optional<OceanBathymetryTileBounds> bounds = OceanBathymetryTileBounds.parseFromFileName(fileName);
                if (bounds.isPresent()) {
                    loaded.add(new GeoTiffOceanBathymetryTile(path, bounds.get()));
                    return;
                }

                Path properties = sidecarProperties(path);
                if (Files.isRegularFile(properties)) {
                    loaded.add(new GeoTiffOceanBathymetryTile(path, OceanBathymetryTileBounds.fromProperties(properties)));
                    return;
                }

                RedlineAtlasWorldgen.LOGGER.warn("Skipping ocean bathymetry TIFF {} because bounds cannot be inferred. Rename as bbox_west_south_east_north_*.tif or add sidecar {}", path, properties.getFileName());
            }
        } catch (IOException | RuntimeException ex) {
            RedlineAtlasWorldgen.LOGGER.warn("Failed to register ocean bathymetry tile {}", path, ex);
        }
    }

    private static Path sidecarProperties(Path tifPath) {
        String name = tifPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        return tifPath.resolveSibling(base + ".rbathy.properties");
    }

    public Optional<OceanBathymetrySample> sample(double latitude, double longitude) {
        long started = AtlasWorldgenProfiler.start();
        try {
        if (!AtlasWorldgenConfig.OPEN_WATER_GUIDE_ENABLED.get()) {
            return Optional.empty();
        }
        double seaLevelMeters = AtlasWorldgenConfig.OPEN_WATER_SEA_LEVEL_METERS.get();
        double minDepth = AtlasWorldgenConfig.OPEN_WATER_MIN_OCEAN_DEPTH_METERS.get();
        for (OceanBathymetryTile tile : tiles) {
            if (!tile.contains(latitude, longitude)) {
                continue;
            }
            try {
                var bottom = tile.sampleBottomMeters(latitude, longitude);
                if (bottom.isPresent()) {
                    double bottomMeters = bottom.getAsDouble();
                    double depth = seaLevelMeters - bottomMeters;
                    if (depth >= minDepth) {
                        return Optional.of(new OceanBathymetrySample(bottomMeters, depth, tile.id(), tile.nominalResolutionMeters()));
                    }
                }
            } catch (IOException | RuntimeException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to sample ocean bathymetry tile {} at lat={}, lon={}", tile.id(), latitude, longitude, ex);
            }
        }
        return Optional.empty();
        } finally {
            AtlasWorldgenProfiler.recordSince("sample.oceanBathymetry", started);
        }
    }

    public Optional<OceanBathymetrySample> rawSample(double latitude, double longitude) {
        long started = AtlasWorldgenProfiler.start();
        try {
        double seaLevelMeters = AtlasWorldgenConfig.OPEN_WATER_SEA_LEVEL_METERS.get();
        for (OceanBathymetryTile tile : tiles) {
            if (!tile.contains(latitude, longitude)) {
                continue;
            }
            try {
                var bottom = tile.sampleBottomMeters(latitude, longitude);
                if (bottom.isPresent()) {
                    double bottomMeters = bottom.getAsDouble();
                    return Optional.of(new OceanBathymetrySample(bottomMeters, seaLevelMeters - bottomMeters, tile.id(), tile.nominalResolutionMeters()));
                }
            } catch (IOException | RuntimeException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to raw-sample ocean bathymetry tile {} at lat={}, lon={}", tile.id(), latitude, longitude, ex);
            }
        }
        return Optional.empty();
        } finally {
            AtlasWorldgenProfiler.recordSince("sample.oceanBathymetry.raw", started);
        }
    }

    public int tileCount() {
        return tiles.size();
    }

    public Path root() {
        return root;
    }

    public List<String> describeTiles(int limit) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, tiles.size()); i++) {
            OceanBathymetryTile tile = tiles.get(i);
            lines.add(tile.shortText() + " priority=" + tile.priority() + " res=" + tile.nominalResolutionMeters() + "m");
        }
        return lines;
    }
}
