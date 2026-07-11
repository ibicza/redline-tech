package com.ibicza.redlineatlasworldgen.lake;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.heightmap.HeightSample;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class ManualLakeIndex {
    private static final AtomicReference<ManualLakeIndex> ACTIVE = new AtomicReference<>(new ManualLakeIndex(Path.of("config/redline-atlas-worldgen/manual_lakes"), List.of()));

    private final Path root;
    private final List<ManualLake> lakes;

    private ManualLakeIndex(Path root, List<ManualLake> lakes) {
        this.root = root;
        this.lakes = lakes;
    }

    public static ManualLakeIndex active() {
        return ACTIVE.get();
    }

    public static ManualLakeIndex reload(Path gameDirectory) {
        Path configuredRoot = Path.of(AtlasWorldgenConfig.MANUAL_LAKES_ROOT.get());
        Path root = configuredRoot.isAbsolute() ? configuredRoot : gameDirectory.resolve(configuredRoot);
        List<ManualLake> lakes = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rlake.properties"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(path -> readLake(path).ifPresent(lakes::add));
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to scan manual lakes in {}", root, ex);
            }
        }
        ManualLakeIndex index = new ManualLakeIndex(root, List.copyOf(lakes));
        ACTIVE.set(index);
        RedlineAtlasWorldgen.LOGGER.info("Manual lake index loaded {} lake(s) from {}", index.lakeCount(), index.root());
        int i = 0;
        for (ManualLake lake : index.lakes) {
            if (i >= 8) {
                RedlineAtlasWorldgen.LOGGER.info("  ... {} more manual lake(s)", index.lakes.size() - i);
                break;
            }
            RedlineAtlasWorldgen.LOGGER.info("  lake {}: {} lat={}, lon={}, radius={}m, maxDepth={}m", i++, lake.id(), lake.centerLatitude(), lake.centerLongitude(), lake.radiusMeters(), lake.maxDepthMeters());
        }
        return index;
    }

    private static Optional<ManualLake> readLake(Path path) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            String fileId = path.getFileName().toString().replace(".rlake.properties", "");
            String id = string(properties, "id", fileId);
            double lat = doubleValue(properties, "centerLatitude");
            double lon = doubleValue(properties, "centerLongitude");
            double radius = doubleValue(properties, "radiusMeters");
            double shoreBlend = doubleValue(properties, "shoreBlendMeters", AtlasWorldgenConfig.LAKE_MANUAL_DEFAULT_SHORE_BLEND_METERS.get());
            double maxDepth = doubleValue(properties, "maxDepthMeters", AtlasWorldgenConfig.LAKE_SYNTHETIC_MAX_DEPTH_METERS.get());
            double minDepth = doubleValue(properties, "minDepthMeters", AtlasWorldgenConfig.LAKE_SYNTHETIC_MIN_DEPTH_METERS.get());
            Double surface = optionalDouble(properties, "waterSurfaceMeters").orElse(null);
            int priority = (int) doubleValue(properties, "priority", 100.0D);
            if (radius <= 0.0D) {
                RedlineAtlasWorldgen.LOGGER.warn("Ignoring manual lake {} because radiusMeters <= 0", path);
                return Optional.empty();
            }
            return Optional.of(new ManualLake(id, lat, lon, radius, shoreBlend, surface, minDepth, maxDepth, priority, path.getFileName().toString()));
        } catch (IOException | RuntimeException ex) {
            RedlineAtlasWorldgen.LOGGER.warn("Failed to read manual lake {}", path, ex);
            return Optional.empty();
        }
    }

    private static String string(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double doubleValue(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return Double.parseDouble(value.trim());
    }

    private static double doubleValue(Properties properties, String key, double fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
    }

    private static Optional<Double> optionalDouble(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("null")) {
            return Optional.empty();
        }
        return Optional.of(Double.parseDouble(value.trim()));
    }

    public LakeSample sample(double latitude, double longitude, int blockX, int blockZ) {
        ManualLake best = null;
        double bestSignedDistance = Double.POSITIVE_INFINITY;
        for (ManualLake lake : lakes) {
            double distance = lake.distanceMeters(latitude, longitude);
            double signed = distance - lake.radiusMeters();
            if (signed <= lake.shoreBlendMeters() && (best == null || signed < bestSignedDistance || lake.priority() > best.priority())) {
                best = lake;
                bestSignedDistance = signed;
            }
        }
        if (best == null) {
            return LakeSample.none();
        }

        double surface = best.waterSurfaceMeters() != null
                ? best.waterSurfaceMeters()
                : estimateSurfaceMeters(blockX, blockZ, latitude, longitude);
        if (!Double.isFinite(surface)) {
            return LakeSample.none();
        }

        if (bestSignedDistance <= 0.0D) {
            double distanceToShore = Math.max(0.0D, -bestSignedDistance);
            double centerFactor = Math.min(1.0D, distanceToShore / Math.max(1.0D, best.radiusMeters()));
            double shapedFactor = 0.35D + 0.65D * centerFactor;
            double depth = Math.max(best.minDepthMeters(), Math.min(best.maxDepthMeters(), best.maxDepthMeters() * shapedFactor));
            double bottom = surface - depth;
            return new LakeSample(LakeKind.MANUAL_LAKE, true, true, 0.0D, Math.max(0.0D, distanceToShore), depth, bottom, surface,
                    best.id(), 30.0D, best.sourceId());
        }

        return new LakeSample(LakeKind.LAKE_SHORE, true, false, bestSignedDistance, 0.0D, 0.0D, Double.NaN, surface,
                best.id(), 30.0D, best.sourceId());
    }

    private static double estimateSurfaceMeters(int blockX, int blockZ, double latitude, double longitude) {
        Optional<HeightSample> center = AtlasHeightmapIndex.active().sample(latitude, longitude);
        if (center.isPresent()) {
            return center.get().meters();
        }
        double sum = 0.0D;
        int count = 0;
        int step = 64;
        for (int dz = -step; dz <= step; dz += step) {
            for (int dx = -step; dx <= step; dx += step) {
                GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX + dx, blockZ + dz);
                Optional<HeightSample> sample = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
                if (sample.isPresent()) {
                    sum += sample.get().meters();
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    public int lakeCount() {
        return lakes.size();
    }

    public Path root() {
        return root;
    }

    public List<String> describeLakes(int limit) {
        List<String> result = new ArrayList<>();
        int max = Math.min(limit, lakes.size());
        for (int i = 0; i < max; i++) {
            ManualLake lake = lakes.get(i);
            result.add(lake.id() + " lat=" + lake.centerLatitude() + " lon=" + lake.centerLongitude()
                    + " radius=" + lake.radiusMeters() + "m maxDepth=" + lake.maxDepthMeters() + "m source=" + lake.sourceId());
        }
        return result;
    }

    public record ManualLake(String id, double centerLatitude, double centerLongitude, double radiusMeters,
                             double shoreBlendMeters, Double waterSurfaceMeters, double minDepthMeters,
                             double maxDepthMeters, int priority, String sourceId) {
        double distanceMeters(double latitude, double longitude) {
            double metersPerDegreeLat = 111_320.0D;
            double metersPerDegreeLon = 111_320.0D * Math.cos(Math.toRadians(centerLatitude));
            double dx = (longitude - centerLongitude) * metersPerDegreeLon;
            double dz = (latitude - centerLatitude) * metersPerDegreeLat;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }
}
