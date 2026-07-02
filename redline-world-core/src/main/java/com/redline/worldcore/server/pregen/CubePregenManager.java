package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/**
 * M13.1 pregen/backfill/AFK manager.
 *
 * <p>Manual pregen, vertical backfill and AFK pregen all pass through this manager so they share one anti-lag throttle and
 * one persistent state file. Pregen still does not run gameplay simulation: no mob spawning, random ticks, machine ticks,
 * block entity logic or fluid/gas simulation.</p>
 */
public final class CubePregenManager {
    public static final CubePregenManager MANAGER = new CubePregenManager();
    public static final int MAX_JOB_CUBES = 262_144;

    private static final int PERSIST_INTERVAL_TICKS = 40;

    private final CubicTestDimensionService cubicTest = new CubicTestDimensionService();
    private final ArrayDeque<CubePos> queue = new ArrayDeque<>();
    private CubePregenBudget budget = CubePregenBudget.defaults();
    private CubePregenJob activeJob;
    private boolean paused;

    private Path persistentFile;
    private boolean persistentLoaded;
    private boolean persistentDirty;

    private long activeProcessed;
    private long totalStartedJobs;
    private long totalCompletedJobs;
    private long totalProcessed;
    private long totalGenerated;
    private long totalSkipped;
    private long totalFailed;
    private int lastTickProcessed;
    private int lastTickGenerated;
    private int lastTickSkipped;
    private int lastTickFailed;
    private long lastTickMicros;
    private long maxTickMicros;
    private String lastError = "";

    private long managerTick;
    private int throttleCooldownTicks;
    private int generatedThisSecond;
    private long generatedSecondStartTick;
    private String throttleReason = "idle";

    private CubePregenManager() {
    }

    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ensurePersistentLoaded(server);
        if (!cubicTest.isRegistered(server)) {
            resetLastTickCounters();
            return;
        }
        Optional<ServerLevel> level = cubicTest.level(server);
        if (level.isEmpty()) {
            resetLastTickCounters();
            return;
        }

        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(server);
        synchronized (this) {
            managerTick++;
            tickManualOrBackground(cache);
        }
        if (canStartBackgroundJob()) {
            VerticalBackfillDaemon.tick(server, this, cache.settings());
        }
        if (canStartBackgroundJob()) {
            AfkPregenController.tick(server, this, cache.settings());
        }
        persistPeriodically(server);
    }

    public synchronized CubePregenJob start(CubePregenJob requested, CubicDimensionSettings settings) {
        CubePregenJob clipped = prepareJob(requested, settings);
        startPreparedJob(clipped);
        return clipped;
    }

    public synchronized boolean startBackground(CubePregenJob requested, CubicDimensionSettings settings) {
        if (!canStartBackgroundJob()) {
            return false;
        }
        CubePregenJob clipped = prepareJob(requested, settings);
        startPreparedJob(clipped);
        return true;
    }

    public synchronized boolean canStartBackgroundJob() {
        return activeJob == null && queue.isEmpty() && !paused;
    }

    private CubePregenJob prepareJob(CubePregenJob requested, CubicDimensionSettings settings) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(settings, "settings");

        CubePos min = new CubePos(
                requested.min().x(),
                Math.max(settings.minCubeY(), requested.min().y()),
                requested.min().z()
        );
        CubePos max = new CubePos(
                requested.max().x(),
                Math.min(settings.maxCubeY(), requested.max().y()),
                requested.max().z()
        );
        if (min.y() > max.y()) {
            throw new IllegalArgumentException("Pregen Y range is outside cubic dimension settings: "
                    + requested.min().y() + ".." + requested.max().y());
        }

        CubePregenJob clipped = CubePregenJob.cuboid(requested.id(), min, max, requested.targetStatus(), requested.ownerDescription());
        if (clipped.totalCubes() > MAX_JOB_CUBES) {
            throw new IllegalArgumentException("Pregen job is too large: " + clipped.totalCubes()
                    + " cubes. Max is " + MAX_JOB_CUBES + ". Use a smaller radius/range.");
        }
        return clipped;
    }

    private void startPreparedJob(CubePregenJob job) {
        activeJob = job;
        paused = false;
        activeProcessed = 0L;
        queue.clear();
        fillQueue(job, 0L);
        totalStartedJobs++;
        lastError = "";
        throttleReason = "started";
        resetLastTickCounters();
        markPersistentDirty();
    }

    public synchronized boolean pause() {
        if (activeJob == null) {
            return false;
        }
        paused = true;
        throttleReason = "manual_pause";
        resetLastTickCounters();
        markPersistentDirty();
        return true;
    }

    public synchronized boolean resume() {
        if (activeJob == null) {
            return false;
        }
        paused = false;
        throttleReason = "manual_resume";
        markPersistentDirty();
        return true;
    }

    public synchronized int stop() {
        int removed = queue.size();
        if (activeJob != null) {
            removed++;
        }
        activeJob = null;
        activeProcessed = 0L;
        queue.clear();
        paused = false;
        throttleCooldownTicks = 0;
        throttleReason = "stopped";
        resetLastTickCounters();
        markPersistentDirty();
        return removed;
    }

    public synchronized int clearQueue() {
        int removed = queue.size();
        queue.clear();
        activeJob = null;
        activeProcessed = 0L;
        paused = false;
        throttleCooldownTicks = 0;
        throttleReason = "cleared";
        resetLastTickCounters();
        markPersistentDirty();
        return removed;
    }

    public synchronized CubePregenBudget budget() {
        return budget;
    }

    public synchronized void configureBudget(int maxCubesPerTick, int maxMillisPerTick) {
        budget = new CubePregenBudget(maxCubesPerTick, maxMillisPerTick,
                budget.maxSkippedCubesPerTick(),
                budget.maxGeneratedCubesPerSecond(),
                budget.expensiveCubeMillis(),
                budget.cooldownAfterExpensiveTicks());
        throttleReason = "budget_configured";
        markPersistentDirty();
    }

    public synchronized void configureThrottle(int maxSkippedCubesPerTick, int maxGeneratedCubesPerSecond, int expensiveCubeMillis, int cooldownAfterExpensiveTicks) {
        budget = new CubePregenBudget(
                budget.maxCubesPerTick(),
                budget.maxMillisPerTick(),
                maxSkippedCubesPerTick,
                maxGeneratedCubesPerSecond,
                expensiveCubeMillis,
                cooldownAfterExpensiveTicks
        );
        throttleReason = "throttle_configured";
        markPersistentDirty();
    }

    public synchronized CubePregenSnapshot snapshot() {
        ColumnVisitSnapshot visits = ColumnVisitTracker.snapshot(null);
        VerticalBackfillSnapshot backfill = VerticalBackfillDaemon.snapshot();
        AfkPregenSnapshot afk = AfkPregenController.snapshot();
        if (activeJob == null) {
            return snapshot(false, visits, backfill, afk, CubeStatus.EMPTY, null, null, "none", "none", 0L, 0L);
        }
        return snapshot(true, visits, backfill, afk, activeJob.targetStatus(), activeJob.min(), activeJob.max(), activeJob.shortId(), activeJob.ownerDescription(), activeJob.totalCubes(), activeProcessed);
    }

    private CubePregenSnapshot snapshot(boolean running, ColumnVisitSnapshot visits, VerticalBackfillSnapshot backfill, AfkPregenSnapshot afk, CubeStatus target, CubePos min, CubePos max, String jobId, String owner, long total, long processed) {
        return new CubePregenSnapshot(
                running,
                paused,
                queue.size(),
                total,
                processed,
                totalStartedJobs,
                totalCompletedJobs,
                totalProcessed,
                totalGenerated,
                totalSkipped,
                totalFailed,
                lastTickProcessed,
                lastTickGenerated,
                lastTickSkipped,
                lastTickFailed,
                lastTickMicros,
                maxTickMicros,
                budget.maxCubesPerTick(),
                budget.maxMillisPerTick(),
                budget.maxSkippedCubesPerTick(),
                budget.maxGeneratedCubesPerSecond(),
                budget.expensiveCubeMillis(),
                budget.cooldownAfterExpensiveTicks(),
                throttleCooldownTicks,
                generatedThisSecond,
                throttleReason,
                target,
                min,
                max,
                jobId,
                owner,
                lastError,
                visits.visitedColumns(),
                visits.backfillDoneColumns(),
                backfill.enabled(),
                backfill.pendingColumns(),
                backfill.jobsStarted(),
                backfill.maxVerticalRadius(),
                backfill.delayTicks(),
                backfill.targetStatus(),
                backfill.lastReason(),
                afk.enabled(),
                afk.trackedPlayers(),
                afk.afkPlayers(),
                afk.jobsStarted(),
                afk.afkAfterTicks(),
                afk.radiusBlocks(),
                afk.verticalRadiusCubes(),
                afk.targetStatus(),
                afk.lastReason()
        );
    }

    private synchronized void tickManualOrBackground(ServerCubeCache cache) {
        resetLastTickCounters();
        rollGeneratedSecondWindow();
        if (throttleCooldownTicks > 0) {
            throttleCooldownTicks--;
            throttleReason = "cooldown " + throttleCooldownTicks + "t";
            return;
        }
        if (activeJob == null || queue.isEmpty()) {
            completeIfDone();
            if (activeJob == null) {
                throttleReason = "idle";
            }
            return;
        }
        if (paused) {
            throttleReason = "paused";
            return;
        }

        long startNanos = System.nanoTime();
        long budgetNanos = budget.maxMillisPerTick() * 1_000_000L;
        while (!queue.isEmpty() && lastTickProcessed < budget.maxCubesPerTick()) {
            if (lastTickProcessed > 0 && System.nanoTime() - startNanos >= budgetNanos) {
                throttleReason = "time_budget";
                break;
            }

            CubePos cubePos = queue.peekFirst();
            boolean willGenerate = cache.wouldPregenGenerate(cubePos, activeJob.targetStatus());
            if (willGenerate && generatedThisSecond >= budget.maxGeneratedCubesPerSecond()) {
                throttleReason = "generated_per_second";
                break;
            }
            if (!willGenerate && lastTickSkipped >= budget.maxSkippedCubesPerTick()) {
                throttleReason = "skip_budget";
                break;
            }

            queue.removeFirst();
            long operationStartNanos = System.nanoTime();
            try {
                ServerCubeCache.PregenCubeResult result = cache.pregenCube(cubePos, activeJob.targetStatus(), true);
                long operationMicros = Math.max(1L, (System.nanoTime() - operationStartNanos) / 1_000L);
                lastTickProcessed++;
                activeProcessed++;
                totalProcessed++;
                if (result.generated()) {
                    lastTickGenerated++;
                    totalGenerated++;
                    generatedThisSecond++;
                    if (operationMicros >= (long) budget.expensiveCubeMillis() * 1_000L) {
                        throttleCooldownTicks = budget.cooldownAfterExpensiveTicks();
                        throttleReason = "expensive_cube " + operationMicros + "us";
                        break;
                    }
                    throttleReason = "generated";
                    // One generated cube can already be expensive. Let the next tick decide again.
                    break;
                } else {
                    lastTickSkipped++;
                    totalSkipped++;
                    throttleReason = result.reason();
                }
            } catch (RuntimeException exception) {
                lastTickProcessed++;
                lastTickFailed++;
                activeProcessed++;
                totalProcessed++;
                totalFailed++;
                lastError = cubePos.x() + " " + cubePos.y() + " " + cubePos.z() + ": " + exception.getMessage();
                throttleReason = "exception";
            }
        }

        lastTickMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
        maxTickMicros = Math.max(maxTickMicros, lastTickMicros);
        completeIfDone();
        if (lastTickProcessed > 0) {
            markPersistentDirty();
        }
    }

    private void rollGeneratedSecondWindow() {
        if (managerTick - generatedSecondStartTick >= 20L) {
            generatedSecondStartTick = managerTick;
            generatedThisSecond = 0;
        }
    }

    private void completeIfDone() {
        if (activeJob != null && queue.isEmpty()) {
            totalCompletedJobs++;
            activeJob = null;
            activeProcessed = 0L;
            paused = false;
            throttleReason = "completed";
            markPersistentDirty();
        }
    }

    private void resetLastTickCounters() {
        lastTickProcessed = 0;
        lastTickGenerated = 0;
        lastTickSkipped = 0;
        lastTickFailed = 0;
        lastTickMicros = 0L;
    }

    private void fillQueue(CubePregenJob job, long skipProcessed) {
        long index = 0L;
        for (int y = job.min().y(); y <= job.max().y(); y++) {
            for (int z = job.min().z(); z <= job.max().z(); z++) {
                for (int x = job.min().x(); x <= job.max().x(); x++) {
                    if (index++ < skipProcessed) {
                        continue;
                    }
                    queue.addLast(new CubePos(x, y, z));
                }
            }
        }
    }

    public synchronized void ensurePersistentLoaded(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT)
                .resolve("redline_world_core")
                .resolve("cubic_test")
                .resolve("m13_state.properties");
        if (persistentLoaded && file.equals(persistentFile)) {
            return;
        }
        persistentFile = file;
        persistentLoaded = true;
        CubePregenPersistentState.LoadedState loaded = CubePregenPersistentState.load(file);
        budget = loaded.budget();
        activeJob = loaded.activeJob();
        activeProcessed = Math.min(loaded.activeProcessed(), activeJob == null ? 0L : activeJob.totalCubes());
        paused = loaded.paused();
        totalStartedJobs = loaded.totalStartedJobs();
        totalCompletedJobs = loaded.totalCompletedJobs();
        totalProcessed = loaded.totalProcessed();
        totalGenerated = loaded.totalGenerated();
        totalSkipped = loaded.totalSkipped();
        totalFailed = loaded.totalFailed();
        lastError = loaded.lastError();
        queue.clear();
        if (activeJob != null) {
            fillQueue(activeJob, activeProcessed);
            throttleReason = "restored";
        }
        ColumnVisitTracker.replaceEntries(loaded.visits());
        VerticalBackfillDaemon.load(loaded.backfillEnabled(), loaded.backfillMaxVerticalRadius(), loaded.backfillDelayTicks(), loaded.backfillTargetStatus(), loaded.backfillJobsStarted(), loaded.backfillLastReason());
        AfkPregenController.load(loaded.afkEnabled(), loaded.afkAfterTicks(), loaded.afkRadiusBlocks(), loaded.afkVerticalRadiusCubes(), loaded.afkTargetStatus(), loaded.afkJobsStarted(), loaded.afkLastReason());
        persistentDirty = false;
    }

    public synchronized void savePersistentNow(MinecraftServer server) {
        ensurePersistentLoaded(server);
        try {
            CubePregenPersistentState.save(persistentFile, this);
            persistentDirty = false;
            ColumnVisitTracker.consumeDirty();
        } catch (IOException exception) {
            lastError = "persistent save failed: " + exception.getMessage();
        }
    }

    private void persistPeriodically(MinecraftServer server) {
        if (ColumnVisitTracker.consumeDirty()) {
            markPersistentDirty();
        }
        if (persistentDirty && managerTick % PERSIST_INTERVAL_TICKS == 0L) {
            savePersistentNow(server);
        }
    }

    public synchronized void markPersistentDirty() {
        persistentDirty = true;
    }

    synchronized CubePregenJob activeJobForPersistence() {
        return activeJob;
    }

    synchronized long activeProcessedForPersistence() {
        return activeProcessed;
    }

    synchronized boolean pausedForPersistence() {
        return paused;
    }

    synchronized long totalStartedJobsForPersistence() {
        return totalStartedJobs;
    }

    synchronized long totalCompletedJobsForPersistence() {
        return totalCompletedJobs;
    }

    synchronized long totalProcessedForPersistence() {
        return totalProcessed;
    }

    synchronized long totalGeneratedForPersistence() {
        return totalGenerated;
    }

    synchronized long totalSkippedForPersistence() {
        return totalSkipped;
    }

    synchronized long totalFailedForPersistence() {
        return totalFailed;
    }

    synchronized String lastErrorForPersistence() {
        return lastError;
    }
}
