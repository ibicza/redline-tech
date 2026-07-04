package com.redline.worldcore.server.generation;

/** Lightweight runtime counters for M16 hydrology generation cost. */
public final class M16WaterPerfDebug {
    private static long sampleCalls;
    private static long sampleCacheHits;
    private static long columnShapeCalls;
    private static long dryShoreScans;
    private static long totalSampleMicros;
    private static long totalColumnMicros;
    private static long maxSampleMicros;
    private static long maxColumnMicros;

    private M16WaterPerfDebug() {
    }

    public static void recordSample(long micros) {
        sampleCalls++;
        totalSampleMicros += Math.max(0L, micros);
        maxSampleMicros = Math.max(maxSampleMicros, micros);
    }

    public static void recordSampleCacheHit() {
        sampleCacheHits++;
    }

    public static void recordColumnShape(long micros) {
        columnShapeCalls++;
        totalColumnMicros += Math.max(0L, micros);
        maxColumnMicros = Math.max(maxColumnMicros, micros);
    }

    public static void recordDryShoreScan() {
        dryShoreScans++;
    }

    public static String oneLine() {
        long avgSample = sampleCalls == 0L ? 0L : totalSampleMicros / sampleCalls;
        long avgColumn = columnShapeCalls == 0L ? 0L : totalColumnMicros / columnShapeCalls;
        return "sampleCalls=" + sampleCalls
                + ", cacheHits=" + sampleCacheHits
                + ", columnShapes=" + columnShapeCalls
                + ", dryShoreScans=" + dryShoreScans
                + ", avgSampleUs=" + avgSample
                + ", maxSampleUs=" + maxSampleMicros
                + ", avgColumnUs=" + avgColumn
                + ", maxColumnUs=" + maxColumnMicros;
    }

    public static void reset() {
        sampleCalls = 0L;
        sampleCacheHits = 0L;
        columnShapeCalls = 0L;
        dryShoreScans = 0L;
        totalSampleMicros = 0L;
        totalColumnMicros = 0L;
        maxSampleMicros = 0L;
        maxColumnMicros = 0L;
    }
}
