package com.ibicza.redlineatlasworldgen.profiler;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class AtlasWorldgenProfiler {
    public static final int MAX_CHUNK_PROFILE_RADIUS = 7;
    public static final int DEFAULT_CHUNK_PROFILE_TIMEOUT_TICKS = 12_000;
    public static final int DEFAULT_CHUNK_PROFILE_SETTLE_TICKS = 100;

    private static final int MAX_COUNTERS = 256;
    private static final int MAX_METRICS = 256;
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, Metric> METRICS = new ConcurrentHashMap<>();
    private static final AtomicLong SERVER_TICKS = new AtomicLong();
    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private static final AtomicReference<ChunkProfileSession> ACTIVE_CHUNK_PROFILE = new AtomicReference<>();

    private static volatile String lastChunkProfileReport = "none";
    private static volatile ChunkProfileCompletion lastChunkProfileCompletion;

    public static long start() {
        return shouldMeasure() ? System.nanoTime() : 0L;
    }

    public static void recordSince(String name, long startedNanos) {
        if (startedNanos == 0L) {
            return;
        }
        record(name, System.nanoTime() - startedNanos);
    }

    public static void record(String name, long nanos) {
        if (nanos < 0L) {
            return;
        }
        if (AtlasWorldgenConfig.PROFILER_ENABLED.get()) {
            globalCounter(name).record(nanos);
        }
        ChunkProfileSession session = ACTIVE_CHUNK_PROFILE.get();
        if (session != null) {
            session.recordCounter(name, nanos);
        }
    }

    public static void recordMetric(String name) {
        recordMetric(name, 1L);
    }

    public static void recordMetric(String name, long amount) {
        if (amount < 0L || !shouldMeasure()) {
            return;
        }
        if (AtlasWorldgenConfig.PROFILER_ENABLED.get()) {
            globalMetric(name).record(amount);
        }
        ChunkProfileSession session = ACTIVE_CHUNK_PROFILE.get();
        if (session != null) {
            session.recordMetric(name, amount);
        }
    }

    public static void recordChunkMetric(ResourceKey<Level> dimension, ChunkPos pos, String name) {
        recordChunkMetric(dimension, pos, name, 1L);
    }

    public static void recordChunkMetric(ResourceKey<Level> dimension, ChunkPos pos, String name, long amount) {
        if (amount < 0L || !shouldMeasure() || dimension == null || pos == null) {
            return;
        }
        if (AtlasWorldgenConfig.PROFILER_ENABLED.get()) {
            globalMetric(name).record(amount);
        }
        ChunkProfileSession session = ACTIVE_CHUNK_PROFILE.get();
        if (session != null) {
            session.recordMetric(dimension, pos, name, amount);
        }
    }

    public static void reset() {
        COUNTERS.clear();
        METRICS.clear();
        SERVER_TICKS.set(0L);
    }

    public static long serverTickStarted() {
        return shouldMeasure() ? System.nanoTime() : 0L;
    }

    public static void serverTick(MinecraftServer server, long startedNanos) {
        long tickNanos = startedNanos == 0L ? 0L : Math.max(0L, System.nanoTime() - startedNanos);
        if (AtlasWorldgenConfig.PROFILER_ENABLED.get()) {
            long tick = SERVER_TICKS.incrementAndGet();
            if (AtlasWorldgenConfig.PROFILER_LOG_PERIODICALLY.get()) {
                int interval = Math.max(20, AtlasWorldgenConfig.PROFILER_LOG_INTERVAL_TICKS.get());
                if (tick % interval == 0L) {
                    RedlineAtlasWorldgen.LOGGER.info("RLA profiler: {}", String.join(" | ", summaryLines(12)));
                }
            }
        }

        ChunkProfileSession session = ACTIVE_CHUNK_PROFILE.get();
        if (session == null) {
            return;
        }
        ChunkProfileSession.StopReason reason = session.serverTick(server.getTickCount(), tickNanos);
        if (reason != null && ACTIVE_CHUNK_PROFILE.compareAndSet(session, null)) {
            finishSession(server, session, reason);
        }
    }

    public static OperationResult startChunkProfile(MinecraftServer server, ResourceKey<Level> dimension,
                                                     String label, int centerChunkX, int centerChunkZ,
                                                     int radiusChunks, int timeoutTicks, int settleTicks) {
        if (label == null || label.isBlank()) {
            return OperationResult.failure("chunk_profile label must not be blank");
        }
        if (radiusChunks < 0 || radiusChunks > MAX_CHUNK_PROFILE_RADIUS) {
            return OperationResult.failure("chunk_profile radius must be between 0 and " + MAX_CHUNK_PROFILE_RADIUS);
        }
        if (timeoutTicks < 20) {
            return OperationResult.failure("chunk_profile timeout must be at least 20 ticks");
        }
        if (settleTicks < 0 || settleTicks > timeoutTicks) {
            return OperationResult.failure("chunk_profile settle ticks must be between 0 and timeout ticks");
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return OperationResult.failure("chunk_profile dimension is not loaded: " + dimension.identifier());
        }

        ChunkProfileSession session = new ChunkProfileSession(
                SESSION_IDS.incrementAndGet(), label, dimension, centerChunkX, centerChunkZ,
                radiusChunks, timeoutTicks, settleTicks, server.getTickCount()
        );
        if (!ACTIVE_CHUNK_PROFILE.compareAndSet(null, session)) {
            ChunkProfileSession active = ACTIVE_CHUNK_PROFILE.get();
            return OperationResult.failure("chunk_profile already active: " + (active == null ? "unknown" : active.label()));
        }

        try {
            lastChunkProfileCompletion = null;
            int forced = session.forceChunks(level);
            return OperationResult.success("chunk_profile started label=" + label
                    + ", dimension=" + dimension.identifier()
                    + ", centerChunk=" + centerChunkX + "," + centerChunkZ
                    + ", radius=" + radiusChunks
                    + ", targetChunks=" + ((radiusChunks * 2 + 1) * (radiusChunks * 2 + 1))
                    + ", forceTicketsAdded=" + forced);
        } catch (RuntimeException exception) {
            ACTIVE_CHUNK_PROFILE.compareAndSet(session, null);
            session.releaseChunks(server);
            RedlineAtlasWorldgen.LOGGER.error("Failed to start chunk_profile {}", label, exception);
            return OperationResult.failure("chunk_profile start failed: " + exception.getMessage());
        }
    }

    public static OperationResult stopChunkProfile(MinecraftServer server) {
        ChunkProfileSession session = ACTIVE_CHUNK_PROFILE.getAndSet(null);
        if (session == null) {
            return OperationResult.failure("no active chunk_profile session");
        }
        return finishSession(server, session, ChunkProfileSession.StopReason.MANUAL);
    }

    public static List<String> chunkProfileStatusLines() {
        ChunkProfileSession session = ACTIVE_CHUNK_PROFILE.get();
        if (session == null) {
            return List.of("chunk_profile inactive, lastReport=" + lastChunkProfileReport);
        }
        return session.statusLines();
    }

    public static ChunkProfileCompletion lastChunkProfileCompletion() {
        return lastChunkProfileCompletion;
    }

    public static boolean hasActiveChunkProfile() {
        return ACTIVE_CHUNK_PROFILE.get() != null;
    }

    public static void chunkLoaded(ServerLevel level, ChunkPos pos, boolean newChunk) {
        ChunkProfileSession session = ACTIVE_CHUNK_PROFILE.get();
        if (session != null) {
            session.markChunkLoaded(level, pos, newChunk);
        }
    }

    public static ChunkStageToken beginChunkStage(ResourceKey<Level> dimension, ChunkPos pos, String stageName) {
        ChunkProfileSession session = ACTIVE_CHUNK_PROFILE.get();
        if (session == null) {
            return null;
        }
        int stageIndex = session.beginStage(dimension, pos, stageName);
        return stageIndex < 0 ? null : new ChunkStageToken(session, stageIndex);
    }

    public static void completeChunkStage(ChunkStageToken token, String counterName,
                                          long startedNanos, Throwable throwable) {
        if (startedNanos == 0L && token == null) {
            return;
        }
        long effectiveStart = startedNanos == 0L ? token.startedNanos : startedNanos;
        long nanos = Math.max(0L, System.nanoTime() - effectiveStart);
        if (AtlasWorldgenConfig.PROFILER_ENABLED.get()) {
            globalCounter(counterName).record(nanos);
        }
        if (token != null) {
            token.session.completeStage(token.stageIndex, counterName, nanos, throwable);
        }
    }

    public static List<String> summaryLines(int limit) {
        List<Map.Entry<String, CounterSnapshot>> snapshots = new ArrayList<>();
        for (Map.Entry<String, Counter> entry : COUNTERS.entrySet()) {
            snapshots.add(Map.entry(entry.getKey(), entry.getValue().snapshot()));
        }
        snapshots.sort(Comparator.<Map.Entry<String, CounterSnapshot>>comparingLong(
                entry -> entry.getValue().totalNanos()).reversed());
        List<Map.Entry<String, MetricSnapshot>> metricSnapshots = new ArrayList<>();
        for (Map.Entry<String, Metric> entry : METRICS.entrySet()) {
            metricSnapshots.add(Map.entry(entry.getKey(), entry.getValue().snapshot()));
        }
        metricSnapshots.sort(Comparator.<Map.Entry<String, MetricSnapshot>>comparingLong(
                entry -> entry.getValue().total()).reversed());

        List<String> lines = new ArrayList<>();
        long totalMeasured = snapshots.stream().mapToLong(entry -> entry.getValue().totalNanos()).sum();
        lines.add("measuredTotal=" + formatNanos(totalMeasured)
                + ", counters=" + snapshots.size()
                + ", metrics=" + metricSnapshots.size());
        for (int i = 0; i < Math.min(limit, snapshots.size()); i++) {
            Map.Entry<String, CounterSnapshot> entry = snapshots.get(i);
            CounterSnapshot snapshot = entry.getValue();
            lines.add(entry.getKey()
                    + ": count=" + snapshot.count()
                    + ", total=" + formatNanos(snapshot.totalNanos())
                    + ", avg=" + formatNanos(snapshot.totalNanos() / Math.max(1L, snapshot.count()))
                    + ", max=" + formatNanos(snapshot.maxNanos()));
        }
        for (int i = 0; i < Math.min(limit, metricSnapshots.size()); i++) {
            Map.Entry<String, MetricSnapshot> entry = metricSnapshots.get(i);
            MetricSnapshot snapshot = entry.getValue();
            lines.add("metric." + entry.getKey()
                    + ": count=" + snapshot.count()
                    + ", total=" + snapshot.total()
                    + ", avg=" + (snapshot.total() / Math.max(1L, snapshot.count()))
                    + ", max=" + snapshot.max());
        }
        return lines;
    }

    private static OperationResult finishSession(MinecraftServer server, ChunkProfileSession session,
                                                  ChunkProfileSession.StopReason reason) {
        ChunkProfileSession.FinishResult result = session.finish(server, reason);
        Path report = result.jsonPath().toAbsolutePath();
        lastChunkProfileReport = report.toString();
        lastChunkProfileCompletion = new ChunkProfileCompletion(
                session.label(), reason.id(), result.success(), report,
                result.csvPath().toAbsolutePath(), result.message()
        );
        if (result.success()) {
            RedlineAtlasWorldgen.LOGGER.info("{}", result.message());
            return OperationResult.success(result.message());
        }
        RedlineAtlasWorldgen.LOGGER.error("{}", result.message());
        return OperationResult.failure(result.message());
    }

    private static boolean shouldMeasure() {
        return AtlasWorldgenConfig.PROFILER_ENABLED.get() || ACTIVE_CHUNK_PROFILE.get() != null;
    }

    private static Counter globalCounter(String name) {
        Counter counter = COUNTERS.get(name);
        if (counter != null) {
            return counter;
        }
        synchronized (COUNTERS) {
            counter = COUNTERS.get(name);
            if (counter != null) {
                return counter;
            }
            if (COUNTERS.size() >= MAX_COUNTERS) {
                return Counter.DISCARDING;
            }
            counter = new Counter();
            COUNTERS.put(name, counter);
            return counter;
        }
    }

    private static Metric globalMetric(String name) {
        Metric metric = METRICS.get(name);
        if (metric != null) {
            return metric;
        }
        synchronized (METRICS) {
            metric = METRICS.get(name);
            if (metric != null) {
                return metric;
            }
            if (METRICS.size() >= MAX_METRICS) {
                return Metric.DISCARDING;
            }
            metric = new Metric();
            METRICS.put(name, metric);
            return metric;
        }
    }

    private static String formatNanos(long nanos) {
        if (nanos < 1_000_000L) {
            return String.format(Locale.ROOT, "%.3fms", nanos / 1_000_000.0D);
        }
        if (nanos < TimeUnit.SECONDS.toNanos(1L)) {
            return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0D);
        }
        return String.format(Locale.ROOT, "%.2fs", nanos / 1_000_000_000.0D);
    }

    public record OperationResult(boolean success, String message) {
        static OperationResult success(String message) {
            return new OperationResult(true, message);
        }

        static OperationResult failure(String message) {
            return new OperationResult(false, message);
        }
    }

    public record ChunkProfileCompletion(String label, String stopReason, boolean reportWritten,
                                         Path jsonPath, Path csvPath, String message) {
    }

    public static final class ChunkStageToken {
        private final ChunkProfileSession session;
        private final int stageIndex;
        private final long startedNanos = System.nanoTime();

        private ChunkStageToken(ChunkProfileSession session, int stageIndex) {
            this.session = session;
            this.stageIndex = stageIndex;
        }
    }

    private static final class Counter {
        private static final Counter DISCARDING = new Counter(true);

        private final boolean discarding;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();

        Counter() {
            this(false);
        }

        private Counter(boolean discarding) {
            this.discarding = discarding;
        }

        void record(long nanos) {
            if (discarding) {
                return;
            }
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

    private static final class Metric {
        private static final Metric DISCARDING = new Metric(true);

        private final boolean discarding;
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        Metric() {
            this(false);
        }

        private Metric(boolean discarding) {
            this.discarding = discarding;
        }

        void record(long amount) {
            if (discarding) {
                return;
            }
            count.increment();
            total.add(amount);
            max.accumulateAndGet(amount, Math::max);
        }

        MetricSnapshot snapshot() {
            return new MetricSnapshot(count.sum(), total.sum(), max.get());
        }
    }

    private record MetricSnapshot(long count, long total, long max) {
    }

    private AtlasWorldgenProfiler() {
    }
}
