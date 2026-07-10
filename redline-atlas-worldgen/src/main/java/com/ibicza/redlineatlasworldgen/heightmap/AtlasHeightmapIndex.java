package com.ibicza.redlineatlasworldgen.heightmap;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AtlasHeightmapIndex {
    private static volatile AtlasHeightmapIndex active = new AtlasHeightmapIndex(List.of(), Path.of("."));

    private final List<HeightTile> tiles;
    private final Path root;

    private AtlasHeightmapIndex(List<HeightTile> tiles, Path root) {
        this.tiles = List.copyOf(tiles);
        this.root = root;
    }

    public static AtlasHeightmapIndex active() {
        return active;
    }

    public static AtlasHeightmapIndex reload(Path gameDirectory) {
        Path configured = Path.of(AtlasWorldgenConfig.TILE_ROOT.get());
        Path root = configured.isAbsolute() ? configured : gameDirectory.resolve(configured).normalize();
        List<HeightTile> loaded = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            try {
                Files.createDirectories(root);
                RedlineAtlasWorldgen.LOGGER.info("Created atlas heightmap directory {}", root);
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Could not create atlas heightmap directory {}", root, ex);
            }
        }

        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(path -> scanFile(path, loaded));
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to scan atlas heightmap directory {}", root, ex);
            }
        }

        loaded.sort(Comparator.comparingDouble(HeightTile::priority).thenComparing(HeightTile::id));
        AtlasHeightmapIndex index = new AtlasHeightmapIndex(loaded, root);
        active = index;
        RedlineAtlasWorldgen.LOGGER.info("Atlas heightmap index loaded {} tile(s) from {}", loaded.size(), root);
        for (int i = 0; i < Math.min(loaded.size(), 16); i++) {
            RedlineAtlasWorldgen.LOGGER.info("  tile {}: {} priority={} res={}m", i, loaded.get(i).shortText(), loaded.get(i).priority(), loaded.get(i).nominalResolutionMeters());
        }
        return index;
    }

    private static void scanFile(Path path, List<HeightTile> loaded) {
        String fileName = path.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
                Optional<GeoTileBounds> bounds = GeoTileBounds.parseCopernicusName(fileName);
                if (bounds.isPresent()) {
                    loaded.add(new GeoTiffHeightTile(path, bounds.get()));
                } else {
                    RedlineAtlasWorldgen.LOGGER.warn("Skipping TIFF {} because its bounds cannot be inferred from Copernicus file name. Use .rheight.properties for custom bounds.", path);
                }
            } else if (lower.endsWith(".rheight.properties")) {
                loaded.add(RawHeightTile.fromProperties(path));
            }
        } catch (IOException | RuntimeException ex) {
            RedlineAtlasWorldgen.LOGGER.warn("Failed to register atlas height tile {}", path, ex);
        }
    }

    public Optional<HeightSample> sample(double latitude, double longitude) {
        for (HeightTile tile : tiles) {
            if (!tile.contains(latitude, longitude)) {
                continue;
            }
            try {
                var sample = tile.sampleMeters(latitude, longitude);
                if (sample.isPresent()) {
                    return Optional.of(new HeightSample(sample.getAsDouble(), tile.id(), tile.nominalResolutionMeters()));
                }
            } catch (IOException | RuntimeException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to sample height tile {} at lat={}, lon={}", tile.id(), latitude, longitude, ex);
            }
        }
        return Optional.empty();
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
            HeightTile tile = tiles.get(i);
            lines.add(tile.shortText() + " priority=" + tile.priority() + " res=" + tile.nominalResolutionMeters() + "m");
        }
        return lines;
    }
}
