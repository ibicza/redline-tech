package com.redline.worldcore.server.pregen;

/** Immutable status for the M13.1 vertical backfill daemon. */
public record VerticalBackfillSnapshot(
        boolean enabled,
        int pendingColumns,
        long jobsStarted,
        int maxVerticalRadius,
        int delayTicks,
        String targetStatus,
        String lastReason
) {
}
