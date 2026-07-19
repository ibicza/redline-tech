package com.ibicza.redlineatlasworldgen.profiler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

final class ChunkProfileSession {
    static final int MAX_COUNTERS = 128;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final long MIB = 1024L * 1024L;

    private final long id;
    private final String label;
    private final String safeLabel;
    private final ResourceKey<Level> dimension;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radiusChunks;
    private final int diameterChunks;
    private final int targetChunks;
    private final int timeoutTicks;
    private final int settleTicks;
    private final int startedTick;
    private final Instant startedAt;
    private final long startedNanos;
    private final long heapStartBytes;
    private final GcSnapshot gcStart;
    private final AtomicIntegerArray loadedChunks;
    private final boolean[] forcedBySession;
    private final AtomicInteger completedChunks = new AtomicInteger();
    private final AtomicInteger newChunkLoads = new AtomicInteger();
    private final AtomicInteger existingChunkLoads = new AtomicInteger();
    private final AtomicInteger preloadedChunks = new AtomicInteger();
    private final AtomicInteger inFlightStages = new AtomicInteger();
    private final AtomicInteger failedStages = new AtomicInteger();
    private final AtomicLong peakHeapBytes;
    private final Counter serverTicks = new Counter();
    private final StageStats[] stages = new StageStats[ChunkStage.values().length];
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    private volatile boolean accepting = true;
    private volatile int allLoadedTick = -1;
    private int forcedChunksAdded;

    ChunkProfileSession(long id, String label, ResourceKey<Level> dimension,
                        int centerChunkX, int centerChunkZ, int radiusChunks,
                        int timeoutTicks, int settleTicks, int startedTick) {
        this.id = id;
        this.label = label;
        this.safeLabel = safeLabel(label);
        this.dimension = dimension;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radiusChunks = radiusChunks;
        this.diameterChunks = radiusChunks * 2 + 1;
        this.targetChunks = diameterChunks * diameterChunks;
        this.timeoutTicks = timeoutTicks;
        this.settleTicks = settleTicks;
        this.startedTick = startedTick;
        this.startedAt = Instant.now();
        this.startedNanos = System.nanoTime();
        this.heapStartBytes = usedHeapBytes();
        this.peakHeapBytes = new AtomicLong(heapStartBytes);
        this.gcStart = GcSnapshot.capture();
        this.loadedChunks = new AtomicIntegerArray(targetChunks);
        this.forcedBySession = new boolean[targetChunks];
        for (int i = 0; i < stages.length; i++) {
            stages[i] = new StageStats();
        }
    }

    String label() {
        return label;
    }

    ResourceKey<Level> dimension() {
        return dimension;
    }

    int forceChunks(ServerLevel level) {
        for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                int index = index(chunkX, chunkZ);
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
                    if (markChunkLoaded(chunkX, chunkZ, false)) {
                        preloadedChunks.incrementAndGet();
                    }
                }
                boolean added = level.setChunkForced(chunkX, chunkZ, true);
                forcedBySession[index] = added;
                if (added) {
                    forcedChunksAdded++;
                }
            }
        }
        return forcedChunksAdded;
    }

    void releaseChunks(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return;
        }
        for (int index = 0; index < forcedBySession.length; index++) {
            if (!forcedBySession[index]) {
                continue;
            }
            int dx = index % diameterChunks;
            int dz = index / diameterChunks;
            level.setChunkForced(centerChunkX - radiusChunks + dx, centerChunkZ - radiusChunks + dz, false);
        }
    }

    boolean markChunkLoaded(ServerLevel level, ChunkPos pos, boolean newChunk) {
        if (!accepting || !dimension.equals(level.dimension())) {
            return false;
        }
        return markChunkLoaded(pos.x(), pos.z(), newChunk);
    }

    private boolean markChunkLoaded(int chunkX, int chunkZ, boolean newChunk) {
        int index = index(chunkX, chunkZ);
        if (index < 0 || !loadedChunks.compareAndSet(index, 0, 1)) {
            return false;
        }
        completedChunks.incrementAndGet();
        if (newChunk) {
            newChunkLoads.incrementAndGet();
        } else {
            existingChunkLoads.incrementAndGet();
        }
        return true;
    }

    int beginStage(ResourceKey<Level> stageDimension, ChunkPos pos, String stageName) {
        if (!accepting || !dimension.equals(stageDimension) || index(pos.x(), pos.z()) < 0) {
            return -1;
        }
        inFlightStages.incrementAndGet();
        return ChunkStage.fromName(stageName).ordinal();
    }

    void completeStage(int stageIndex, String counterName, long nanos, Throwable throwable) {
        try {
            if (!accepting) {
                return;
            }
            StageStats stage = stages[stageIndex];
            stage.counter().record(nanos);
            if (throwable != null) {
                stage.failures().incrementAndGet();
                failedStages.incrementAndGet();
            }
            recordCounter(counterName, nanos);
        } finally {
            inFlightStages.decrementAndGet();
        }
    }

    void recordCounter(String name, long nanos) {
        if (!accepting || nanos < 0L) {
            return;
        }
        Counter counter = counters.get(name);
        if (counter == null) {
            synchronized (counters) {
                counter = counters.get(name);
                if (counter == null) {
                    if (counters.size() >= MAX_COUNTERS) {
                        return;
                    }
                    counter = new Counter();
                    counters.put(name, counter);
                }
            }
        }
        counter.record(nanos);
    }

    StopReason serverTick(int tick, long tickNanos) {
        if (!accepting) {
            return null;
        }
        if (tickNanos > 0L) {
            serverTicks.record(tickNanos);
        }
        peakHeapBytes.accumulateAndGet(usedHeapBytes(), Math::max);

        if (completedChunks.get() >= targetChunks) {
            if (allLoadedTick < 0) {
                allLoadedTick = tick;
            }
            if (tick - allLoadedTick >= settleTicks && inFlightStages.get() == 0) {
                return StopReason.COMPLETED;
            }
        }
        if (tick - startedTick >= timeoutTicks) {
            return StopReason.TIMED_OUT;
        }
        return null;
    }

    List<String> statusLines() {
        long elapsedMillis = Math.max(0L, System.nanoTime() - startedNanos) / 1_000_000L;
        List<String> lines = new ArrayList<>();
        lines.add("chunk_profile active label=" + label
                + ", dimension=" + dimension.identifier()
                + ", centerChunk=" + centerChunkX + "," + centerChunkZ
                + ", radius=" + radiusChunks
                + ", completed=" + completedChunks.get() + "/" + targetChunks
                + ", inFlightStages=" + inFlightStages.get()
                + ", elapsed=" + elapsedMillis + "ms");
        lines.add("forcedAdded=" + forcedChunksAdded
                + ", new=" + newChunkLoads.get()
                + ", existing=" + existingChunkLoads.get()
                + ", preloaded=" + preloadedChunks.get()
                + ", heapStart/peak=" + heapStartBytes / MIB + "/" + peakHeapBytes.get() / MIB + " MiB");
        counterSnapshots().stream().limit(8).forEach(entry -> lines.add("  " + formatCounter(entry.getKey(), entry.getValue())));
        return lines;
    }

    FinishResult finish(MinecraftServer server, StopReason reason) {
        accepting = false;
        releaseChunks(server);

        long endedNanos = System.nanoTime();
        Instant endedAt = Instant.now();
        long heapEndBytes = usedHeapBytes();
        peakHeapBytes.accumulateAndGet(heapEndBytes, Math::max);
        GcSnapshot gcEnd = GcSnapshot.capture();
        Path reportDirectory = server.getServerDirectory().resolve("profile-results");
        String baseName = FILE_TIMESTAMP.format(startedAt) + "-" + id + "-" + safeLabel;
        Path jsonPath = reportDirectory.resolve(baseName + ".json");
        Path csvPath = reportDirectory.resolve(baseName + ".csv");

        try {
            Files.createDirectories(reportDirectory);
            writeJson(jsonPath, reason, endedAt, endedNanos, heapEndBytes, gcEnd);
            writeCsv(csvPath);
            return new FinishResult(true, jsonPath, csvPath,
                    "chunk_profile " + reason.id + ": " + completedChunks.get() + "/" + targetChunks
                            + " chunks, report=" + jsonPath.toAbsolutePath());
        } catch (IOException exception) {
            return new FinishResult(false, jsonPath, csvPath,
                    "chunk_profile stopped but report write failed: " + exception.getMessage());
        }
    }

    private void writeJson(Path path, StopReason reason, Instant endedAt, long endedNanos,
                           long heapEndBytes, GcSnapshot gcEnd) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("label", label);
        report.put("dimension", dimension.identifier().toString());
        report.put("stopReason", reason.id);
        report.put("startedAtUtc", startedAt.toString());
        report.put("endedAtUtc", endedAt.toString());
        report.put("durationMillis", nanosToMillis(endedNanos - startedNanos));
        report.put("startedTick", startedTick);
        report.put("allLoadedTick", allLoadedTick);
        report.put("centerChunkX", centerChunkX);
        report.put("centerChunkZ", centerChunkZ);
        report.put("centerBlockX", centerChunkX * 16 + 8);
        report.put("centerBlockZ", centerChunkZ * 16 + 8);
        report.put("radiusChunks", radiusChunks);
        report.put("targetChunks", targetChunks);
        report.put("completedChunks", completedChunks.get());
        report.put("newChunkLoads", newChunkLoads.get());
        report.put("existingChunkLoads", existingChunkLoads.get());
        report.put("preloadedChunks", preloadedChunks.get());
        report.put("forcedChunksAdded", forcedChunksAdded);
        report.put("inFlightStagesAtStop", inFlightStages.get());
        report.put("failedStages", failedStages.get());
        report.put("timeoutTicks", timeoutTicks);
        report.put("settleTicks", settleTicks);

        Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("startBytes", heapStartBytes);
        heap.put("peakBytes", peakHeapBytes.get());
        heap.put("endBytes", heapEndBytes);
        heap.put("peakDeltaBytes", Math.max(0L, peakHeapBytes.get() - heapStartBytes));
        report.put("heap", heap);

        Map<String, Object> gc = new LinkedHashMap<>();
        gc.put("collections", Math.max(0L, gcEnd.collections() - gcStart.collections()));
        gc.put("collectionMillis", Math.max(0L, gcEnd.collectionMillis() - gcStart.collectionMillis()));
        report.put("garbageCollection", gc);
        report.put("serverTicks", counterMap(serverTicks.snapshot(), 0L));

        List<Map<String, Object>> stageRows = new ArrayList<>();
        for (ChunkStage stage : ChunkStage.values()) {
            StageStats stats = stages[stage.ordinal()];
            CounterSnapshot snapshot = stats.counter().snapshot();
            if (snapshot.count() == 0L && stats.failures().get() == 0L) {
                continue;
            }
            Map<String, Object> row = counterMap(snapshot, stats.failures().get());
            row.put("name", stage.id);
            stageRows.add(row);
        }
        report.put("chunkStages", stageRows);

        List<Map<String, Object>> counterRows = new ArrayList<>();
        for (Map.Entry<String, CounterSnapshot> entry : counterSnapshots()) {
            Map<String, Object> row = counterMap(entry.getValue(), 0L);
            row.put("name", entry.getKey());
            counterRows.add(row);
        }
        report.put("counters", counterRows);

        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(report, writer);
        }
    }

    private void writeCsv(Path path) throws IOException {
        StringBuilder csv = new StringBuilder("category,name,count,failures,total_ms,avg_ms,max_ms\n");
        for (ChunkStage stage : ChunkStage.values()) {
            StageStats stats = stages[stage.ordinal()];
            CounterSnapshot snapshot = stats.counter().snapshot();
            if (snapshot.count() == 0L && stats.failures().get() == 0L) {
                continue;
            }
            appendCsvRow(csv, "chunk_stage", stage.id, snapshot, stats.failures().get());
        }
        for (Map.Entry<String, CounterSnapshot> entry : counterSnapshots()) {
            appendCsvRow(csv, "counter", entry.getKey(), entry.getValue(), 0L);
        }
        appendCsvRow(csv, "server", "tick", serverTicks.snapshot(), 0L);
        Files.writeString(path, csv, StandardCharsets.UTF_8);
    }

    private List<Map.Entry<String, CounterSnapshot>> counterSnapshots() {
        List<Map.Entry<String, CounterSnapshot>> snapshots = new ArrayList<>();
        counters.forEach((name, counter) -> snapshots.add(Map.entry(name, counter.snapshot())));
        snapshots.sort(Comparator.<Map.Entry<String, CounterSnapshot>>comparingLong(entry -> entry.getValue().totalNanos()).reversed());
        return snapshots;
    }

    private int index(int chunkX, int chunkZ) {
        int dx = chunkX - (centerChunkX - radiusChunks);
        int dz = chunkZ - (centerChunkZ - radiusChunks);
        if (dx < 0 || dx >= diameterChunks || dz < 0 || dz >= diameterChunks) {
            return -1;
        }
        return dz * diameterChunks + dx;
    }

    private static Map<String, Object> counterMap(CounterSnapshot snapshot, long failures) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("count", snapshot.count());
        row.put("failures", failures);
        row.put("totalMillis", nanosToMillis(snapshot.totalNanos()));
        row.put("averageMillis", nanosToMillis(snapshot.totalNanos() / Math.max(1L, snapshot.count())));
        row.put("maxMillis", nanosToMillis(snapshot.maxNanos()));
        return row;
    }

    private static void appendCsvRow(StringBuilder csv, String category, String name,
                                     CounterSnapshot snapshot, long failures) {
        csv.append(csvCell(category)).append(',')
                .append(csvCell(name)).append(',')
                .append(snapshot.count()).append(',')
                .append(failures).append(',')
                .append(formatMillis(snapshot.totalNanos())).append(',')
                .append(formatMillis(snapshot.totalNanos() / Math.max(1L, snapshot.count()))).append(',')
                .append(formatMillis(snapshot.maxNanos())).append('\n');
    }

    private static String formatCounter(String name, CounterSnapshot snapshot) {
        return name + ": count=" + snapshot.count()
                + ", total=" + formatMillis(snapshot.totalNanos()) + "ms"
                + ", avg=" + formatMillis(snapshot.totalNanos() / Math.max(1L, snapshot.count())) + "ms"
                + ", max=" + formatMillis(snapshot.maxNanos()) + "ms";
    }

    private static String csvCell(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanosToMillis(nanos));
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String safeLabel(String label) {
        StringBuilder safe = new StringBuilder(Math.min(label.length(), 48));
        for (int i = 0; i < label.length() && safe.length() < 48; i++) {
            char character = label.charAt(i);
            if (character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_' || character == '.') {
                safe.append(character);
            } else {
                safe.append('_');
            }
        }
        return safe.isEmpty() ? "profile" : safe.toString();
    }

    enum StopReason {
        COMPLETED("completed"),
        TIMED_OUT("timed_out"),
        MANUAL("manual");

        private final String id;

        StopReason(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record FinishResult(boolean success, Path jsonPath, Path csvPath, String message) {
    }

    private enum ChunkStage {
        STRUCTURE_STARTS("structure_starts"),
        STRUCTURE_REFERENCES("structure_references"),
        BIOMES("biomes"),
        NOISE("noise"),
        SURFACE("surface"),
        CARVERS("carvers"),
        FEATURES("features"),
        INITIALIZE_LIGHT("initialize_light"),
        LIGHT("light"),
        SPAWN("spawn"),
        FULL("full"),
        OTHER("other");

        private final String id;

        ChunkStage(String id) {
            this.id = id;
        }

        static ChunkStage fromName(String name) {
            String path = name;
            int separator = name.indexOf(':');
            if (separator >= 0 && separator + 1 < name.length()) {
                path = name.substring(separator + 1);
            }
            for (ChunkStage stage : values()) {
                if (stage.id.equals(path)) {
                    return stage;
                }
            }
            return OTHER;
        }
    }

    private record StageStats(Counter counter, AtomicLong failures) {
        StageStats() {
            this(new Counter(), new AtomicLong());
        }
    }

    private static final class Counter {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();

        void record(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        CounterSnapshot snapshot() {
            return new CounterSnapshot(count.get(), totalNanos.get(), maxNanos.get());
        }
    }

    private record CounterSnapshot(long count, long totalNanos, long maxNanos) {
    }

    private record GcSnapshot(long collections, long collectionMillis) {
        static GcSnapshot capture() {
            long collections = 0L;
            long collectionMillis = 0L;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                collections += Math.max(0L, bean.getCollectionCount());
                collectionMillis += Math.max(0L, bean.getCollectionTime());
            }
            return new GcSnapshot(collections, collectionMillis);
        }
    }
}
