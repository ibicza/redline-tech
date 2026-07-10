package com.ibicza.redlineatlasworldgen.landcover;

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

public final class AtlasLandcoverIndex {
    private static volatile AtlasLandcoverIndex active = new AtlasLandcoverIndex(List.of(), Path.of("."));

    private final List<LandcoverTile> tiles;
    private final Path root;

    private AtlasLandcoverIndex(List<LandcoverTile> tiles, Path root) {
        this.tiles = List.copyOf(tiles);
        this.root = root;
    }

    public static AtlasLandcoverIndex active() {
        return active;
    }

    public static AtlasLandcoverIndex reload(Path gameDirectory) {
        Path configured = Path.of(AtlasWorldgenConfig.LANDCOVER_TILE_ROOT.get());
        Path root = configured.isAbsolute() ? configured : gameDirectory.resolve(configured).normalize();
        List<LandcoverTile> loaded = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            try {
                Files.createDirectories(root);
                RedlineAtlasWorldgen.LOGGER.info("Created atlas landcover directory {}", root);
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Could not create atlas landcover directory {}", root, ex);
            }
        }

        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(path -> scanFile(path, loaded));
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to scan atlas landcover directory {}", root, ex);
            }
        }

        loaded.sort(Comparator.comparingDouble(LandcoverTile::priority).thenComparing(LandcoverTile::id));
        AtlasLandcoverIndex index = new AtlasLandcoverIndex(loaded, root);
        active = index;
        RedlineAtlasWorldgen.LOGGER.info("Atlas landcover index loaded {} tile(s) from {}", loaded.size(), root);
        for (int i = 0; i < Math.min(loaded.size(), 16); i++) {
            RedlineAtlasWorldgen.LOGGER.info("  landcover tile {}: {} priority={} res={}m", i, loaded.get(i).shortText(), loaded.get(i).priority(), loaded.get(i).nominalResolutionMeters());
        }
        return index;
    }

    private static void scanFile(Path path, List<LandcoverTile> loaded) {
        String fileName = path.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
                Optional<LandcoverTileBounds> bounds = LandcoverTileBounds.parseEsaWorldCoverName(fileName);
                if (bounds.isPresent()) {
                    loaded.add(new GeoTiffLandcoverTile(path, bounds.get()));
                } else {
                    RedlineAtlasWorldgen.LOGGER.warn("Skipping TIFF {} because its bounds cannot be inferred from ESA WorldCover file name.", path);
                }
            }
        } catch (RuntimeException ex) {
            RedlineAtlasWorldgen.LOGGER.warn("Failed to register atlas landcover tile {}", path, ex);
        }
    }

    public Optional<LandcoverSample> sample(double latitude, double longitude) {
        for (LandcoverTile tile : tiles) {
            if (!tile.contains(latitude, longitude)) {
                continue;
            }
            try {
                Optional<LandcoverSample> sample = tile.sample(latitude, longitude);
                if (sample.isPresent()) {
                    return sample;
                }
            } catch (IOException | RuntimeException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to sample landcover tile {} at lat={}, lon={}", tile.id(), latitude, longitude, ex);
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
            LandcoverTile tile = tiles.get(i);
            lines.add(tile.shortText() + " priority=" + tile.priority() + " res=" + tile.nominalResolutionMeters() + "m");
        }
        return lines;
    }
}
