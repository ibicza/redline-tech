package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.pos.ColumnPos;

/** Immutable status for visited-column diagnostics. */
public record ColumnVisitSnapshot(
        int visitedColumns,
        int backfillDoneColumns,
        ColumnPos currentColumn,
        int currentMinVisitedCubeY,
        int currentMaxVisitedCubeY,
        int currentLastVisitedCubeY,
        int currentVisits
) {
}
