package com.ibicza.redlineatlasworldgen.profiler;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class AtlasWorldgenProfiler {
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final AtomicLong SERVER_TICKS = new AtomicLong();

    public static long start() {
        return AtlasWorldgenConfig.PROFILER_ENABLED.get() ? System.nanoTime() : 0L;
    }

    public static void recordSince(String name, long startedNanos) {
        if (startedNanos == 0L || !AtlasWorldgenConfig.PROFILER_ENABLED.get()) {
            return;
        }
        record(name, System.nanoTime() - startedNanos);
    }

    public static void record(String name, long nanos) {
        if (nanos < 0L || !AtlasWorldgenConfig.PROFILER_ENABLED.get()) {
            return;
        }
        COUNTERS.computeIfAbsent(name, ignored -> new Counter()).record(nanos);
    }

    public static void reset() {
        COUNTERS.clear();
        SERVER_TICKS.set(0L);
    }

    public static void serverTick() {
        if (!AtlasWorldgenConfig.PROFILER_ENABLED.get()) {
            return;
        }
        long tick = SERVER_TICKS.incrementAndGet();
        if (AtlasWorldgenConfig.PROFILER_LOG_PERIODICALLY.get()) {
            int interval = Math.max(20, AtlasWorldgenConfig.PROFILER_LOG_INTERVAL_TICKS.get());
            if (tick % interval == 0L) {
                RedlineAtlasWorldgen.LOGGER.info("RLA profiler: {}", String.join(" | ", summaryLines(12)));
            }
        }
    }

    public static List<String> summaryLines(int limit) {
        List<Map.Entry<String, CounterSnapshot>> snapshots = new ArrayList<>();
        for (Map.Entry<String, Counter> entry : COUNTERS.entrySet()) {
            snapshots.add(Map.entry(entry.getKey(), entry.getValue().snapshot()));
        }
        snapshots.sort(Comparator.<Map.Entry<String, CounterSnapshot>>comparingLong(entry -> entry.getValue().totalNanos()).reversed());

        List<String> lines = new ArrayList<>();
        long totalMeasured = snapshots.stream().mapToLong(entry -> entry.getValue().totalNanos()).sum();
        lines.add("measuredTotal=" + formatNanos(totalMeasured) + ", counters=" + snapshots.size());
        for (int i = 0; i < Math.min(limit, snapshots.size()); i++) {
            Map.Entry<String, CounterSnapshot> entry = snapshots.get(i);
            CounterSnapshot s = entry.getValue();
            lines.add(entry.getKey()
                    + ": count=" + s.count()
                    + ", total=" + formatNanos(s.totalNanos())
                    + ", avg=" + formatNanos(s.totalNanos() / Math.max(1L, s.count()))
                    + ", max=" + formatNanos(s.maxNanos()));
        }
        return lines;
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

    private AtlasWorldgenProfiler() {
    }
}
