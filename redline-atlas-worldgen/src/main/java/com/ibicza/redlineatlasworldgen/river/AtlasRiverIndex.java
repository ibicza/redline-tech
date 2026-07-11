package com.ibicza.redlineatlasworldgen.river;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AtlasRiverIndex {
    private static volatile AtlasRiverIndex active = empty(Path.of("config/redline-atlas-worldgen/rivers"));
    private static final ConcurrentMap<Long, RiverSample> BIOME_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger CACHE_CLEAR_GUARD = new AtomicInteger();

    private final List<RiverSegment> segments;
    private final Map<Long, List<RiverSegment>> cells;
    private final Path root;
    private final int sourceFileCount;
    private final Optional<RiverSourceBounds> sourceBounds;

    private AtlasRiverIndex(List<RiverSegment> segments, Map<Long, List<RiverSegment>> cells,
                            Path root, int sourceFileCount, Optional<RiverSourceBounds> sourceBounds) {
        this.segments = List.copyOf(segments);
        this.cells = Map.copyOf(cells);
        this.root = root;
        this.sourceFileCount = sourceFileCount;
        this.sourceBounds = sourceBounds;
    }

    public static AtlasRiverIndex active() {
        return active;
    }

    public static AtlasRiverIndex reload(Path gameDirectory) {
        Path configured = Path.of(AtlasWorldgenConfig.RIVER_ROOT.get());
        Path root = configured.isAbsolute() ? configured : gameDirectory.resolve(configured).normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            RedlineAtlasWorldgen.LOGGER.warn("Could not create atlas river directory {}", root, ex);
        }

        Optional<RiverSourceBounds> bounds;
        try {
            bounds = RiverSourceBounds.parse(AtlasWorldgenConfig.RIVER_SOURCE_BOUNDS.get());
        } catch (RuntimeException ex) {
            RedlineAtlasWorldgen.LOGGER.error("Invalid river source bounds; no raw HydroRIVERS data will be loaded", ex);
            AtlasRiverIndex index = empty(root);
            active = index;
            return index;
        }

        List<Path> shapefiles = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp"))
                        .sorted()
                        .forEach(shapefiles::add);
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to scan atlas river directory {}", root, ex);
            }
        }

        String configurationFingerprint = configurationFingerprint(gameDirectory, bounds);
        String sourceFingerprint = sourceFingerprint(gameDirectory, shapefiles);
        Path cookedCache = root.resolve("region-cache.rriver");
        if (AtlasWorldgenConfig.RIVER_PREFER_COOKED_CACHE.get() && Files.isRegularFile(cookedCache)) {
            try {
                RiverAtlasCodec.CookedRiverAtlas cooked = RiverAtlasCodec.read(cookedCache);
                boolean configurationMatches = cooked.configurationFingerprint().equals(configurationFingerprint);
                boolean sourcesMatch = shapefiles.isEmpty() || cooked.sourceFingerprint().equals(sourceFingerprint);
                if (configurationMatches && sourcesMatch) {
                    List<RiverSegment> cookedSegments = new ArrayList<>(cooked.segments());
                    cookedSegments.sort(Comparator.comparingLong(RiverSegment::riverId).thenComparing(RiverSegment::sourceId));
                    Map<Long, List<RiverSegment>> cookedCells = buildCells(cookedSegments);
                    AtlasRiverIndex index = new AtlasRiverIndex(cookedSegments, cookedCells, root, shapefiles.size(), bounds);
                    active = index;
                    clearCache();
                    RedlineAtlasWorldgen.LOGGER.info("Atlas river index loaded {} cooked segment(s) from {}, bounds={}",
                            cookedSegments.size(), cookedCache, bounds.map(RiverSourceBounds::toString).orElse("stored"));
                    return index;
                }
                RedlineAtlasWorldgen.LOGGER.info("Ignoring stale cooked river cache {}; source/config fingerprint changed", cookedCache);
            } catch (IOException | RuntimeException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to read cooked river cache {}; raw HydroRIVERS sources will be tried", cookedCache, ex);
            }
        }

        int maxSegments = AtlasWorldgenConfig.RIVER_MAX_SEGMENTS.get();
        int minOrder = AtlasWorldgenConfig.RIVER_MIN_STRAHLER_ORDER.get();
        List<RawHydroRiver> raw = new ArrayList<>();
        for (Path shapefile : shapefiles) {
            if (raw.size() >= maxSegments) {
                break;
            }
            try {
                raw.addAll(HydroRiversShapefileReader.read(shapefile, bounds, minOrder, maxSegments - raw.size()));
            } catch (IOException | RuntimeException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to load HydroRIVERS source {}", shapefile, ex);
            }
        }

        RiverSegmentBuilder builder = new RiverSegmentBuilder();
        List<RiverSegment> segments = new ArrayList<>(raw.size());
        for (RawHydroRiver source : raw) {
            try {
                RiverSegment segment = builder.build(source);
                if (segment != null) {
                    segments.add(segment);
                }
            } catch (RuntimeException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Failed to refine river {} from {}", source.attributes().riverId(), source.sourceId(), ex);
            }
        }
        segments.sort(Comparator.comparingLong(RiverSegment::riverId).thenComparing(RiverSegment::sourceId));
        joinDownstreamProfiles(segments);
        if (!segments.isEmpty() && AtlasWorldgenConfig.RIVER_WRITE_COOKED_CACHE.get()) {
            try {
                RiverAtlasCodec.write(cookedCache, configurationFingerprint, sourceFingerprint, segments);
                RedlineAtlasWorldgen.LOGGER.info("Wrote {} cooked river segment(s) to {}", segments.size(), cookedCache);
            } catch (IOException ex) {
                RedlineAtlasWorldgen.LOGGER.warn("Could not write cooked river cache {}", cookedCache, ex);
            }
        }
        Map<Long, List<RiverSegment>> cells = buildCells(segments);

        AtlasRiverIndex index = new AtlasRiverIndex(segments, cells, root, shapefiles.size(), bounds);
        active = index;
        clearCache();
        RedlineAtlasWorldgen.LOGGER.info("Atlas river index loaded {} segment(s) from {} HydroRIVERS shapefile(s) at {}, bounds={}, minOrder={}",
                segments.size(), shapefiles.size(), root, bounds.map(RiverSourceBounds::toString).orElse("all"), minOrder);
        for (int i = 0; i < Math.min(segments.size(), 16); i++) {
            RedlineAtlasWorldgen.LOGGER.info("  river {}: {}", i, segments.get(i).shortText());
        }
        return index;
    }

    public RiverSample sample(int blockX, int blockZ) {
        long started = AtlasWorldgenProfiler.start();
        try {
            if (!AtlasWorldgenConfig.RIVER_GUIDE_ENABLED.get() || segments.isEmpty()) {
                return RiverSample.none();
            }
            int cellSize = AtlasWorldgenConfig.RIVER_INDEX_CELL_SIZE_BLOCKS.get();
            List<RiverSegment> candidates = cells.get(cellKey(Math.floorDiv(blockX, cellSize), Math.floorDiv(blockZ, cellSize)));
            if (candidates == null || candidates.isEmpty()) {
                return RiverSample.none();
            }
            RiverSample best = RiverSample.none();
            for (RiverSegment segment : candidates) {
                RiverSample candidate = segment.sample(blockX + 0.5D, blockZ + 0.5D);
                if (better(candidate, best)) {
                    best = candidate;
                }
            }
            return best;
        } finally {
            AtlasWorldgenProfiler.recordSince("sample.river", started);
        }
    }

    public RiverSample sampleForBiome(int blockX, int blockZ) {
        if (!AtlasWorldgenConfig.RIVER_GUIDE_ENABLED.get()) {
            return RiverSample.none();
        }
        int cell = AtlasWorldgenConfig.RIVER_BIOME_CACHE_CELL_SIZE_BLOCKS.get();
        int gx = Math.floorDiv(blockX, cell);
        int gz = Math.floorDiv(blockZ, cell);
        long key = cellKey(gx, gz);
        RiverSample cached = BIOME_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (BIOME_CACHE.size() >= AtlasWorldgenConfig.RIVER_SAMPLE_CACHE_LIMIT.get()
                && CACHE_CLEAR_GUARD.compareAndSet(0, 1)) {
            try {
                BIOME_CACHE.clear();
            } finally {
                CACHE_CLEAR_GUARD.set(0);
            }
        }
        int centerX = gx * cell + cell / 2;
        int centerZ = gz * cell + cell / 2;
        RiverSample computed = sample(centerX, centerZ);
        RiverSample existing = BIOME_CACHE.putIfAbsent(key, computed);
        return existing == null ? computed : existing;
    }

    public int segmentCount() {
        return segments.size();
    }

    public int sourceFileCount() {
        return sourceFileCount;
    }

    public int indexCellCount() {
        return cells.size();
    }

    public int cacheSize() {
        return BIOME_CACHE.size();
    }

    public Optional<NearestRiver> nearestChannel(int blockX, int blockZ, int radiusBlocks) {
        if (segments.isEmpty() || radiusBlocks < 0) {
            return Optional.empty();
        }
        int cellSize = AtlasWorldgenConfig.RIVER_INDEX_CELL_SIZE_BLOCKS.get();
        int minCellX = Math.floorDiv(blockX - radiusBlocks, cellSize);
        int maxCellX = Math.floorDiv(blockX + radiusBlocks, cellSize);
        int minCellZ = Math.floorDiv(blockZ - radiusBlocks, cellSize);
        int maxCellZ = Math.floorDiv(blockZ + radiusBlocks, cellSize);
        Set<RiverSegment> visited = new HashSet<>();
        RiverSegment bestSegment = null;
        RiverSegment.CenterPoint bestCenter = null;
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                List<RiverSegment> candidates = cells.get(cellKey(cellX, cellZ));
                if (candidates == null) {
                    continue;
                }
                for (RiverSegment segment : candidates) {
                    if (!visited.add(segment)) {
                        continue;
                    }
                    RiverSegment.CenterPoint center = segment.nearestCenter(blockX + 0.5D, blockZ + 0.5D);
                    if (center.distanceBlocks() <= radiusBlocks
                            && (bestCenter == null || center.distanceBlocks() < bestCenter.distanceBlocks())) {
                        bestSegment = segment;
                        bestCenter = center;
                    }
                }
            }
        }
        if (bestSegment == null) {
            return Optional.empty();
        }
        int nearestX = (int) Math.floor(bestCenter.x());
        int nearestZ = (int) Math.floor(bestCenter.z());
        RiverSample sample = bestSegment.sample(bestCenter.x(), bestCenter.z());
        return Optional.of(new NearestRiver(sample, nearestX, nearestZ, bestCenter.distanceBlocks()));
    }

    public Path root() {
        return root;
    }

    public String boundsText() {
        return sourceBounds.map(RiverSourceBounds::toString).orElse("all");
    }

    public List<String> describeSegments(int limit) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, segments.size()); i++) {
            result.add(segments.get(i).shortText());
        }
        return result;
    }

    public static void clearCache() {
        BIOME_CACHE.clear();
    }

    private static AtlasRiverIndex empty(Path root) {
        return new AtlasRiverIndex(List.of(), Map.of(), root, 0, Optional.empty());
    }

    private static boolean better(RiverSample candidate, RiverSample current) {
        if (!candidate.hasRiverData()) {
            return false;
        }
        if (!current.hasRiverData()) {
            return true;
        }
        if (candidate.kind() != current.kind()) {
            return candidate.kind() == RiverKind.CHANNEL;
        }
        if (candidate.distanceToCenterBlocks() != current.distanceToCenterBlocks()) {
            return candidate.distanceToCenterBlocks() < current.distanceToCenterBlocks();
        }
        return candidate.strahlerOrder() > current.strahlerOrder();
    }

    private static void joinDownstreamProfiles(List<RiverSegment> segments) {
        Map<Long, RiverSegment> byId = new LinkedHashMap<>();
        for (RiverSegment segment : segments) {
            byId.putIfAbsent(segment.riverId(), segment);
        }
        for (int pass = 0; pass < 4; pass++) {
            for (RiverSegment upstream : segments) {
                RiverSegment downstream = byId.get(upstream.nextDownId());
                if (downstream == null || downstream == upstream) {
                    continue;
                }
                double junction = Math.min(upstream.endWaterMeters(), downstream.startWaterMeters());
                upstream.lowerEndTo(junction);
                downstream.lowerStartTo(junction);
            }
        }
    }

    private static Map<Long, List<RiverSegment>> buildCells(List<RiverSegment> segments) {
        int cellSize = AtlasWorldgenConfig.RIVER_INDEX_CELL_SIZE_BLOCKS.get();
        Map<Long, List<RiverSegment>> mutable = new HashMap<>();
        for (RiverSegment segment : segments) {
            int minCellX = Math.floorDiv((int) Math.floor(segment.minX()), cellSize);
            int maxCellX = Math.floorDiv((int) Math.floor(segment.maxX()), cellSize);
            int minCellZ = Math.floorDiv((int) Math.floor(segment.minZ()), cellSize);
            int maxCellZ = Math.floorDiv((int) Math.floor(segment.maxZ()), cellSize);
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    double minX = cellX * (double) cellSize;
                    double minZ = cellZ * (double) cellSize;
                    if (!segment.boundsIntersects(minX, minZ, minX + cellSize, minZ + cellSize)) {
                        continue;
                    }
                    mutable.computeIfAbsent(cellKey(cellX, cellZ), ignored -> new ArrayList<>()).add(segment);
                }
            }
        }
        Map<Long, List<RiverSegment>> immutable = new HashMap<>(mutable.size());
        mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return immutable;
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public record NearestRiver(RiverSample sample, int blockX, int blockZ, double distanceBlocks) {
    }

    private static String configurationFingerprint(Path gameDirectory, Optional<RiverSourceBounds> bounds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // v4 changes the cooked profile semantics without changing the binary codec:
            // water is clamped by both DEM banks and every profile correction is lower-only.
            update(digest, "river-profile-bank-support-v4");
            update(digest, bounds.map(RiverSourceBounds::toString).orElse("all"));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_MIN_STRAHLER_ORDER.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.ORIGIN_LATITUDE.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.ORIGIN_LONGITUDE.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.DEGREES_PER_BLOCK_LATITUDE.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.DEGREES_PER_BLOCK_LONGITUDE.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get()));
            update(digest, Boolean.toString(AtlasWorldgenConfig.RIVER_REFINE_ENABLED.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_REFINE_RADIUS_BLOCKS.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_REFINE_STEP_BLOCKS.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_REFINE_POINT_SPACING_BLOCKS.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.RIVER_VALLEY_HEIGHT_WEIGHT.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.RIVER_SOURCE_DISTANCE_WEIGHT.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.RIVER_UPHILL_WEIGHT.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.RIVER_WORLDCOVER_WATER_BONUS.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_MIN_WIDTH_BLOCKS.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_MAX_WIDTH_BLOCKS.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.RIVER_WIDTH_DISCHARGE_FACTOR.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_WIDTH_SCAN_STEP_BLOCKS.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_MIN_DEPTH_BLOCKS.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_MAX_DEPTH_BLOCKS.get()));
            update(digest, Double.toString(AtlasWorldgenConfig.RIVER_DEPTH_WIDTH_FACTOR.get()));
            update(digest, Integer.toString(AtlasWorldgenConfig.RIVER_BANK_WIDTH_BLOCKS.get()));
            update(digest, Boolean.toString(AtlasWorldgenConfig.RIVER_PROFILE_SNAP_TO_BLOCK.get()));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sourceFingerprint(Path gameDirectory, List<Path> shapefiles) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path shapefile : shapefiles) {
                updateFile(digest, shapefile);
                String base = shapefile.getFileName().toString().replaceFirst("(?i)\\.shp$", "");
                try (var siblings = Files.list(shapefile.getParent())) {
                    siblings.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().equalsIgnoreCase(base + ".dbf"))
                            .findFirst().ifPresent(path -> updateFile(digest, path));
                } catch (IOException ignored) {
                    update(digest, base + ".dbf-missing");
                }
            }
            updateTree(digest, resolve(gameDirectory, AtlasWorldgenConfig.TILE_ROOT.get()));
            updateTree(digest, resolve(gameDirectory, AtlasWorldgenConfig.LANDCOVER_TILE_ROOT.get()));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void updateFile(MessageDigest digest, Path path) {
        try {
            update(digest, path.getFileName().toString());
            update(digest, Long.toString(Files.size(path)));
            update(digest, Long.toString(Files.getLastModifiedTime(path).toMillis()));
        } catch (IOException ex) {
            update(digest, path.toString());
        }
    }

    private static void updateTree(MessageDigest digest, Path root) {
        if (!Files.isDirectory(root)) {
            update(digest, root.toString());
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).sorted().forEach(path -> updateStableFile(digest, root, path));
        } catch (IOException ex) {
            update(digest, root.toString());
        }
    }

    private static void updateStableFile(MessageDigest digest, Path root, Path path) {
        try {
            update(digest, root.relativize(path).toString());
            update(digest, Long.toString(Files.size(path)));
        } catch (IOException ex) {
            update(digest, path.toString());
        }
    }

    private static Path resolve(Path gameDirectory, String configuredPath) {
        Path configured = Path.of(configuredPath);
        return configured.isAbsolute() ? configured : gameDirectory.resolve(configured).normalize();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private AtlasRiverIndex() {
        this(List.of(), Map.of(), Path.of("."), 0, Optional.empty());
    }
}
