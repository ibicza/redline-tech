package com.redline.worldcore.server.pregen;

/** Immutable status for M13.1 AFK pregen. */
public record AfkPregenSnapshot(
        boolean enabled,
        int trackedPlayers,
        int afkPlayers,
        long jobsStarted,
        int afkAfterTicks,
        int radiusBlocks,
        int verticalRadiusCubes,
        String targetStatus,
        String lastReason
) {
}
