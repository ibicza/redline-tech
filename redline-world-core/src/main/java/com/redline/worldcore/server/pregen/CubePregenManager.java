package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/**
 * M13.0 manual cube pregen queue.
 *
 * <p>This is intentionally not AFK/backfill yet. It is a small bounded queue that prepares cube data in the cube-first
 * backend and writes it to Region3D without running gameplay systems such as mob spawning, random ticks, machine logic or
 * block entity logic.</p>
 */
public final class CubePregenManager {
    public static final CubePregenManager MANAGER = new CubePregenManager();
    public static final int MAX_JOB_CUBES = 262_144;

    private final CubicTestDimensionService cubicTest = new CubicTestDimensionService();
    private final ArrayDeque<CubePos> queue = new ArrayDeque<>();
    private CubePregenBudget budget = CubePregenBudget.defaults();
    private CubePregenJob activeJob;
    private boolean paused;

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

    private CubePregenManager() {
    }

    public void onServerTick(ServerTickEvent.Post event) {
        if (activeJob == null || paused) {
            resetLastTickCounters();
            return;
        }
        if (!cubicTest.isRegistered(event.getServer())) {
            return;
        }
        Optional<ServerLevel> level = cubicTest.level(event.getServer());
        if (level.isEmpty()) {
            return;
        }
        tick(WorldCoreCubeLoading.cubicTestForServer(event.getServer()));
    }

    public synchronized CubePregenJob start(CubePregenJob requested, CubicDimensionSettings settings) {
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

        CubePregenJob clipped = CubePregenJob.cuboid(min, max, requested.targetStatus(), requested.ownerDescription());
        if (clipped.totalCubes() > MAX_JOB_CUBES) {
            throw new IllegalArgumentException("Pregen job is too large: " + clipped.totalCubes()
                    + " cubes. Max is " + MAX_JOB_CUBES + ". Use a smaller radius/range.");
        }

        activeJob = clipped;
        paused = false;
        activeProcessed = 0L;
        queue.clear();
        fillQueue(clipped);
        totalStartedJobs++;
        lastError = "";
        resetLastTickCounters();
        return clipped;
    }

    public synchronized boolean pause() {
        if (activeJob == null) {
            return false;
        }
        paused = true;
        return true;
    }

    public synchronized boolean resume() {
        if (activeJob == null) {
            return false;
        }
        paused = false;
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
        resetLastTickCounters();
        return removed;
    }

    public synchronized int clearQueue() {
        int removed = queue.size();
        queue.clear();
        activeJob = null;
        activeProcessed = 0L;
        paused = false;
        resetLastTickCounters();
        return removed;
    }

    public synchronized CubePregenBudget budget() {
        return budget;
    }

    public synchronized void configureBudget(int maxCubesPerTick, int maxMillisPerTick) {
        budget = new CubePregenBudget(maxCubesPerTick, maxMillisPerTick);
    }

    public synchronized CubePregenSnapshot snapshot() {
        if (activeJob == null) {
            return new CubePregenSnapshot(
                    false,
                    paused,
                    queue.size(),
                    0L,
                    0L,
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
                    CubeStatus.EMPTY,
                    null,
                    null,
                    "none",
                    lastError
            );
        }
        return new CubePregenSnapshot(
                true,
                paused,
                queue.size(),
                activeJob.totalCubes(),
                activeProcessed,
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
                activeJob.targetStatus(),
                activeJob.min(),
                activeJob.max(),
                activeJob.shortId(),
                lastError
        );
    }

    private synchronized void tick(ServerCubeCache cache) {
        resetLastTickCounters();
        if (activeJob == null || queue.isEmpty()) {
            completeIfDone();
            return;
        }

        long startNanos = System.nanoTime();
        long budgetNanos = budget.maxMillisPerTick() * 1_000_000L;
        while (!queue.isEmpty() && lastTickProcessed < budget.maxCubesPerTick()) {
            if (lastTickProcessed > 0 && System.nanoTime() - startNanos >= budgetNanos) {
                break;
            }

            CubePos cubePos = queue.removeFirst();
            try {
                ServerCubeCache.PregenCubeResult result = cache.pregenCube(cubePos, activeJob.targetStatus(), true);
                lastTickProcessed++;
                activeProcessed++;
                totalProcessed++;
                if (result.generated()) {
                    lastTickGenerated++;
                    totalGenerated++;
                } else {
                    lastTickSkipped++;
                    totalSkipped++;
                }
            } catch (RuntimeException exception) {
                lastTickProcessed++;
                lastTickFailed++;
                activeProcessed++;
                totalProcessed++;
                totalFailed++;
                lastError = cubePos.x() + " " + cubePos.y() + " " + cubePos.z() + ": " + exception.getMessage();
            }
        }

        lastTickMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
        maxTickMicros = Math.max(maxTickMicros, lastTickMicros);
        completeIfDone();
    }

    private void completeIfDone() {
        if (activeJob != null && queue.isEmpty()) {
            totalCompletedJobs++;
            activeJob = null;
            activeProcessed = 0L;
            paused = false;
        }
    }

    private void resetLastTickCounters() {
        lastTickProcessed = 0;
        lastTickGenerated = 0;
        lastTickSkipped = 0;
        lastTickFailed = 0;
        lastTickMicros = 0L;
    }

    private void fillQueue(CubePregenJob job) {
        // Center-out-ish order: Y-major waves are good enough for M13.0 manual jobs and deterministic for tests.
        for (int y = job.min().y(); y <= job.max().y(); y++) {
            for (int z = job.min().z(); z <= job.max().z(); z++) {
                for (int x = job.min().x(); x <= job.max().x(); x++) {
                    queue.addLast(new CubePos(x, y, z));
                }
            }
        }
    }
}
