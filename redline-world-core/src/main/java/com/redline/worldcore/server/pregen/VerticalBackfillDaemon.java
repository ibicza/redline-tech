package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * M13.1 low-priority vertical backfill.
 *
 * <p>The daemon never generates directly. It only starts tiny one-cube jobs when the normal pregen manager is idle, so it
 * inherits the same throttle and persistence behavior as manual pregen.</p>
 */
public final class VerticalBackfillDaemon {
    private static boolean enabled = true;
    private static int maxVerticalRadius = 8;
    private static int delayTicks = 120;
    private static CubeStatus targetStatus = CubeStatus.GEOLOGY_READY;
    private static long jobsStarted;
    private static String lastReason = "idle";

    public static synchronized void tick(MinecraftServer server, CubePregenManager manager, CubicDimensionSettings settings) {
        if (!enabled) {
            lastReason = "disabled";
            return;
        }
        if (!manager.canStartBackgroundJob()) {
            lastReason = "pregen_busy";
            return;
        }
        long gameTime = server.overworld().getGameTime();
        for (ColumnVisitEntry entry : ColumnVisitTracker.orderedForBackfill()) {
            if (gameTime - entry.lastVisitedGameTime() < delayTicks) {
                lastReason = "waiting_delay";
                continue;
            }
            Optional<CubePos> next = entry.nextBackfillCube(settings, maxVerticalRadius);
            ColumnVisitTracker.markDirty();
            if (next.isEmpty()) {
                lastReason = "column_done";
                continue;
            }
            CubePregenJob job = CubePregenJob.single(next.get(), targetStatus, "backfill:column");
            if (manager.startBackground(job, settings)) {
                jobsStarted++;
                lastReason = "started " + next.get().x() + " " + next.get().y() + " " + next.get().z();
                return;
            }
            lastReason = "manager_rejected";
            return;
        }
        lastReason = "no_pending_columns";
    }

    public static synchronized void configure(boolean newEnabled, int newMaxVerticalRadius, int newDelayTicks, CubeStatus newTargetStatus) {
        enabled = newEnabled;
        maxVerticalRadius = Math.max(0, Math.min(128, newMaxVerticalRadius));
        delayTicks = Math.max(0, Math.min(20 * 60 * 60, newDelayTicks));
        targetStatus = newTargetStatus == null ? CubeStatus.GEOLOGY_READY : newTargetStatus;
        lastReason = "configured";
        ColumnVisitTracker.markDirty();
    }

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        lastReason = value ? "enabled" : "disabled";
        ColumnVisitTracker.markDirty();
    }

    public static synchronized VerticalBackfillSnapshot snapshot() {
        int pending = 0;
        for (ColumnVisitEntry ignored : ColumnVisitTracker.orderedForBackfill()) {
            pending++;
        }
        return new VerticalBackfillSnapshot(enabled, pending, jobsStarted, maxVerticalRadius, delayTicks, targetStatus.name(), lastReason);
    }

    public static synchronized void load(boolean loadedEnabled, int loadedMaxVerticalRadius, int loadedDelayTicks, CubeStatus loadedTargetStatus, long loadedJobsStarted, String loadedLastReason) {
        enabled = loadedEnabled;
        maxVerticalRadius = Math.max(0, Math.min(128, loadedMaxVerticalRadius));
        delayTicks = Math.max(0, Math.min(20 * 60 * 60, loadedDelayTicks));
        targetStatus = loadedTargetStatus == null ? CubeStatus.GEOLOGY_READY : loadedTargetStatus;
        jobsStarted = Math.max(0L, loadedJobsStarted);
        lastReason = loadedLastReason == null ? "loaded" : loadedLastReason;
    }

    public static synchronized boolean enabled() {
        return enabled;
    }

    public static synchronized int maxVerticalRadius() {
        return maxVerticalRadius;
    }

    public static synchronized int delayTicks() {
        return delayTicks;
    }

    public static synchronized CubeStatus targetStatus() {
        return targetStatus;
    }

    public static synchronized long jobsStarted() {
        return jobsStarted;
    }

    public static synchronized String lastReason() {
        return lastReason;
    }

    private VerticalBackfillDaemon() {
    }
}
