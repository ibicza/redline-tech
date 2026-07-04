package com.redline.worldcore.server.profiler;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Small always-available server-side profiler for redline-world-core development.
 *
 * <p>It is deliberately simple and allocation-light when disabled: every hot-path call first checks one volatile flag.
 * When enabled, it aggregates named timers and counters until /rwc profile stop writes a text report.</p>
 */
public final class RuntimeProfiler {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int MAX_SLOW_EVENTS = 32;
    private static final long SLOW_EVENT_MICROS = 5_000L;

    private static volatile boolean running;
    private static long startedNanos;
    private static long stoppedNanos;
    private static final Map<String, TimerStats> TIMERS = new LinkedHashMap<>();
    private static final Map<String, Long> COUNTERS = new LinkedHashMap<>();
    private static final List<SlowEvent> SLOW_EVENTS = new ArrayList<>();

    private RuntimeProfiler() {
    }

    public static boolean running() {
        return running;
    }

    public static synchronized boolean start() {
        if (running) {
            return false;
        }
        TIMERS.clear();
        COUNTERS.clear();
        SLOW_EVENTS.clear();
        startedNanos = System.nanoTime();
        stoppedNanos = 0L;
        running = true;
        return true;
    }

    public static synchronized StopResult stopAndWrite(MinecraftServer server) {
        if (!running) {
            return new StopResult(false, null, "profiler is not running", 0L, 0, 0);
        }
        running = false;
        stoppedNanos = System.nanoTime();
        String text = reportText();
        try {
            Path directory = server.getWorldPath(LevelResource.ROOT)
                    .resolve(com.redline.worldcore.RedlineWorldCore.MOD_ID)
                    .resolve("profiles");
            Files.createDirectories(directory);
            Path file = directory.resolve("rwc-profile-" + LocalDateTime.now().format(FILE_TIME) + ".txt");
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return new StopResult(true, file, "ok", elapsedMicros(), TIMERS.size(), COUNTERS.size());
        } catch (IOException exception) {
            return new StopResult(false, null, exception.getMessage(), elapsedMicros(), TIMERS.size(), COUNTERS.size());
        }
    }

    public static synchronized String statusLine() {
        if (!running) {
            return "RWC profiler: stopped, timers=" + TIMERS.size() + ", counters=" + COUNTERS.size();
        }
        return "RWC profiler: running for " + formatMicros((System.nanoTime() - startedNanos) / 1_000L)
                + ", timers=" + TIMERS.size()
                + ", counters=" + COUNTERS.size();
    }

    public static long markStart() {
        return running ? System.nanoTime() : 0L;
    }

    public static void recordSince(String name, long startNanos) {
        if (startNanos != 0L) {
            recordNanos(name, System.nanoTime() - startNanos);
        }
    }

    public static void recordNanos(String name, long nanos) {
        if (!running) {
            return;
        }
        recordMicros(name, Math.max(1L, nanos / 1_000L));
    }

    public static synchronized void recordMicros(String name, long micros) {
        if (!running) {
            return;
        }
        TimerStats stats = TIMERS.computeIfAbsent(name, ignored -> new TimerStats());
        stats.calls++;
        stats.totalMicros += Math.max(0L, micros);
        stats.maxMicros = Math.max(stats.maxMicros, micros);
        if (micros >= SLOW_EVENT_MICROS) {
            rememberSlowEvent(new SlowEvent(name, micros));
        }
    }

    public static void addCount(String name, long amount) {
        if (!running || amount == 0L) {
            return;
        }
        synchronized (RuntimeProfiler.class) {
            if (!running) {
                return;
            }
            COUNTERS.merge(name, amount, Long::sum);
        }
    }

    public static <T> T measure(String name, Supplier<T> supplier) {
        if (!running) {
            return supplier.get();
        }
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            recordNanos(name, System.nanoTime() - start);
        }
    }

    public static void measure(String name, Runnable runnable) {
        if (!running) {
            runnable.run();
            return;
        }
        long start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            recordNanos(name, System.nanoTime() - start);
        }
    }

    private static void rememberSlowEvent(SlowEvent event) {
        SLOW_EVENTS.add(event);
        SLOW_EVENTS.sort(Comparator.comparingLong(SlowEvent::micros).reversed());
        while (SLOW_EVENTS.size() > MAX_SLOW_EVENTS) {
            SLOW_EVENTS.remove(SLOW_EVENTS.size() - 1);
        }
    }

    private static synchronized String reportText() {
        StringBuilder builder = new StringBuilder(16_384);
        builder.append("Redline World Core runtime profile\n");
        builder.append("==================================\n");
        builder.append("duration: ").append(formatMicros(elapsedMicros())).append('\n');
        builder.append("timers: ").append(TIMERS.size()).append(", counters: ").append(COUNTERS.size()).append('\n');
        builder.append('\n');

        builder.append("Timers by total time\n");
        builder.append("--------------------\n");
        TIMERS.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, TimerStats>>comparingLong(entry -> entry.getValue().totalMicros).reversed())
                .forEach(entry -> {
                    TimerStats stats = entry.getValue();
                    long avg = stats.calls == 0L ? 0L : stats.totalMicros / stats.calls;
                    builder.append(String.format(java.util.Locale.ROOT,
                            "%-44s calls=%8d total=%12s avg=%10s max=%10s%n",
                            entry.getKey(),
                            stats.calls,
                            formatMicros(stats.totalMicros),
                            formatMicros(avg),
                            formatMicros(stats.maxMicros)));
                });
        builder.append('\n');

        builder.append("Counters\n");
        builder.append("--------\n");
        COUNTERS.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed())
                .forEach(entry -> builder.append(String.format(java.util.Locale.ROOT, "%-44s %12d%n", entry.getKey(), entry.getValue())));
        builder.append('\n');

        builder.append("Slowest events\n");
        builder.append("--------------\n");
        for (SlowEvent event : SLOW_EVENTS) {
            builder.append(String.format(java.util.Locale.ROOT, "%-44s %12s%n", event.name(), formatMicros(event.micros())));
        }
        return builder.toString();
    }

    private static long elapsedMicros() {
        long end = stoppedNanos == 0L ? System.nanoTime() : stoppedNanos;
        return Math.max(0L, (end - startedNanos) / 1_000L);
    }

    private static String formatMicros(long micros) {
        if (micros >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.3fs", micros / 1_000_000.0D);
        }
        if (micros >= 1_000L) {
            return String.format(java.util.Locale.ROOT, "%.3fms", micros / 1_000.0D);
        }
        return micros + "us";
    }

    private static final class TimerStats {
        private long calls;
        private long totalMicros;
        private long maxMicros;
    }

    private record SlowEvent(String name, long micros) {
    }

    public record StopResult(boolean written, Path file, String message, long elapsedMicros, int timers, int counters) {
    }
}
