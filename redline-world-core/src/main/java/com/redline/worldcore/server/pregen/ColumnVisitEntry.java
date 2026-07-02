package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.ColumnPos;
import com.redline.worldcore.api.pos.CubePos;

import java.util.Optional;

/** Mutable per-column visit/backfill progress entry. Runtime + persisted by the M13.1 state file. */
public final class ColumnVisitEntry {
    private final ColumnPos columnPos;
    private long firstVisitedGameTime;
    private long lastVisitedGameTime;
    private int minVisitedCubeY;
    private int maxVisitedCubeY;
    private int lastVisitedCubeY;
    private int visits;
    private int nextBackfillStep;
    private boolean backfillDone;

    public ColumnVisitEntry(ColumnPos columnPos, int cubeY, long gameTime) {
        this(columnPos, gameTime, gameTime, cubeY, cubeY, cubeY, 1, 1, false);
    }

    public ColumnVisitEntry(
            ColumnPos columnPos,
            long firstVisitedGameTime,
            long lastVisitedGameTime,
            int minVisitedCubeY,
            int maxVisitedCubeY,
            int lastVisitedCubeY,
            int visits,
            int nextBackfillStep,
            boolean backfillDone
    ) {
        this.columnPos = columnPos;
        this.firstVisitedGameTime = firstVisitedGameTime;
        this.lastVisitedGameTime = lastVisitedGameTime;
        this.minVisitedCubeY = minVisitedCubeY;
        this.maxVisitedCubeY = maxVisitedCubeY;
        this.lastVisitedCubeY = lastVisitedCubeY;
        this.visits = Math.max(0, visits);
        this.nextBackfillStep = Math.max(1, nextBackfillStep);
        this.backfillDone = backfillDone;
    }

    public void markVisited(int cubeY, long gameTime) {
        lastVisitedGameTime = gameTime;
        lastVisitedCubeY = cubeY;
        minVisitedCubeY = Math.min(minVisitedCubeY, cubeY);
        maxVisitedCubeY = Math.max(maxVisitedCubeY, cubeY);
        visits++;
        if (backfillDone && isInsideKnownVisitedRange(cubeY)) {
            return;
        }
        backfillDone = false;
    }

    public Optional<CubePos> nextBackfillCube(CubicDimensionSettings settings, int maxVerticalRadius) {
        while (nextBackfillStep <= maxVerticalRadius * 2) {
            int offset = offsetForStep(nextBackfillStep++);
            int cubeY = lastVisitedCubeY + offset;
            if (settings.containsCubeY(cubeY)) {
                return Optional.of(new CubePos(columnPos.x(), cubeY, columnPos.z()));
            }
        }
        backfillDone = true;
        return Optional.empty();
    }

    public void resetBackfillProgress() {
        nextBackfillStep = 1;
        backfillDone = false;
    }

    private boolean isInsideKnownVisitedRange(int cubeY) {
        return cubeY >= minVisitedCubeY && cubeY <= maxVisitedCubeY;
    }

    private static int offsetForStep(int step) {
        int distance = (step + 1) / 2;
        return step % 2 == 1 ? -distance : distance;
    }

    public ColumnPos columnPos() {
        return columnPos;
    }

    public long firstVisitedGameTime() {
        return firstVisitedGameTime;
    }

    public long lastVisitedGameTime() {
        return lastVisitedGameTime;
    }

    public int minVisitedCubeY() {
        return minVisitedCubeY;
    }

    public int maxVisitedCubeY() {
        return maxVisitedCubeY;
    }

    public int lastVisitedCubeY() {
        return lastVisitedCubeY;
    }

    public int visits() {
        return visits;
    }

    public int nextBackfillStep() {
        return nextBackfillStep;
    }

    public boolean backfillDone() {
        return backfillDone;
    }
}
