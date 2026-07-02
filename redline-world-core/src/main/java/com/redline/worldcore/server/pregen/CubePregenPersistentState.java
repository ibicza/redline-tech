package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.pos.ColumnPos;
import com.redline.worldcore.api.pos.CubePos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Small M13.1 file-backed state. This deliberately avoids gameplay SavedData until cube ownership is deeper. */
final class CubePregenPersistentState {
    static LoadedState load(Path file) {
        if (!Files.exists(file)) {
            return LoadedState.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException exception) {
            return LoadedState.empty();
        }

        CubePregenBudget budget = new CubePregenBudget(
                intValue(properties, "budget.maxCubesPerTick", CubePregenBudget.DEFAULT_MAX_CUBES_PER_TICK),
                intValue(properties, "budget.maxMillisPerTick", CubePregenBudget.DEFAULT_MAX_MILLIS_PER_TICK),
                intValue(properties, "budget.maxSkippedCubesPerTick", CubePregenBudget.DEFAULT_MAX_SKIPPED_CUBES_PER_TICK),
                intValue(properties, "budget.maxGeneratedCubesPerSecond", CubePregenBudget.DEFAULT_MAX_GENERATED_CUBES_PER_SECOND),
                intValue(properties, "budget.expensiveCubeMillis", CubePregenBudget.DEFAULT_EXPENSIVE_CUBE_MILLIS),
                intValue(properties, "budget.cooldownAfterExpensiveTicks", CubePregenBudget.DEFAULT_COOLDOWN_AFTER_EXPENSIVE_TICKS)
        );

        CubePregenJob activeJob = null;
        if (boolValue(properties, "active.present", false)) {
            activeJob = CubePregenJob.fromPersistent(
                    uuidValue(properties, "active.id", UUID.randomUUID()),
                    cubeValue(properties, "active.min", new CubePos(0, 0, 0)),
                    cubeValue(properties, "active.max", new CubePos(0, 0, 0)),
                    statusValue(properties, "active.target", CubeStatus.FULL),
                    properties.getProperty("active.owner", "restored")
            );
        }

        List<ColumnVisitEntry> visits = new ArrayList<>();
        int count = intValue(properties, "visit.count", 0);
        for (int i = 0; i < count; i++) {
            String prefix = "visit." + i + ".";
            visits.add(new ColumnVisitEntry(
                    columnValue(properties, prefix + "column", new ColumnPos(0, 0)),
                    longValue(properties, prefix + "first", 0L),
                    longValue(properties, prefix + "last", 0L),
                    intValue(properties, prefix + "minY", 0),
                    intValue(properties, prefix + "maxY", 0),
                    intValue(properties, prefix + "lastY", 0),
                    intValue(properties, prefix + "visits", 0),
                    intValue(properties, prefix + "nextBackfillStep", 1),
                    boolValue(properties, prefix + "backfillDone", false)
            ));
        }

        return new LoadedState(
                budget,
                activeJob,
                longValue(properties, "active.processed", 0L),
                boolValue(properties, "manager.paused", false),
                longValue(properties, "total.started", 0L),
                longValue(properties, "total.completed", 0L),
                longValue(properties, "total.processed", 0L),
                longValue(properties, "total.generated", 0L),
                longValue(properties, "total.skipped", 0L),
                longValue(properties, "total.failed", 0L),
                properties.getProperty("last.error", ""),
                visits,
                boolValue(properties, "backfill.enabled", true),
                intValue(properties, "backfill.maxVerticalRadius", 8),
                intValue(properties, "backfill.delayTicks", 120),
                statusValue(properties, "backfill.target", CubeStatus.GEOLOGY_READY),
                longValue(properties, "backfill.jobsStarted", 0L),
                properties.getProperty("backfill.lastReason", "loaded"),
                boolValue(properties, "afk.enabled", false),
                intValue(properties, "afk.afterTicks", 20 * 300),
                intValue(properties, "afk.radiusBlocks", 96),
                intValue(properties, "afk.verticalRadiusCubes", 4),
                statusValue(properties, "afk.target", CubeStatus.LIGHT_READY),
                longValue(properties, "afk.jobsStarted", 0L),
                properties.getProperty("afk.lastReason", "loaded")
        );
    }

    static void save(Path file, CubePregenManager manager) throws IOException {
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        CubePregenBudget budget = manager.budget();
        properties.setProperty("version", "13.1");
        properties.setProperty("budget.maxCubesPerTick", Integer.toString(budget.maxCubesPerTick()));
        properties.setProperty("budget.maxMillisPerTick", Integer.toString(budget.maxMillisPerTick()));
        properties.setProperty("budget.maxSkippedCubesPerTick", Integer.toString(budget.maxSkippedCubesPerTick()));
        properties.setProperty("budget.maxGeneratedCubesPerSecond", Integer.toString(budget.maxGeneratedCubesPerSecond()));
        properties.setProperty("budget.expensiveCubeMillis", Integer.toString(budget.expensiveCubeMillis()));
        properties.setProperty("budget.cooldownAfterExpensiveTicks", Integer.toString(budget.cooldownAfterExpensiveTicks()));

        CubePregenJob active = manager.activeJobForPersistence();
        properties.setProperty("active.present", Boolean.toString(active != null));
        if (active != null) {
            properties.setProperty("active.id", active.id().toString());
            properties.setProperty("active.min", cubeString(active.min()));
            properties.setProperty("active.max", cubeString(active.max()));
            properties.setProperty("active.target", active.targetStatus().name());
            properties.setProperty("active.owner", active.ownerDescription());
            properties.setProperty("active.processed", Long.toString(manager.activeProcessedForPersistence()));
        }
        properties.setProperty("manager.paused", Boolean.toString(manager.pausedForPersistence()));
        properties.setProperty("total.started", Long.toString(manager.totalStartedJobsForPersistence()));
        properties.setProperty("total.completed", Long.toString(manager.totalCompletedJobsForPersistence()));
        properties.setProperty("total.processed", Long.toString(manager.totalProcessedForPersistence()));
        properties.setProperty("total.generated", Long.toString(manager.totalGeneratedForPersistence()));
        properties.setProperty("total.skipped", Long.toString(manager.totalSkippedForPersistence()));
        properties.setProperty("total.failed", Long.toString(manager.totalFailedForPersistence()));
        properties.setProperty("last.error", manager.lastErrorForPersistence());

        List<ColumnVisitEntry> visits = new ArrayList<>(ColumnVisitTracker.copyEntries().values());
        properties.setProperty("visit.count", Integer.toString(visits.size()));
        for (int i = 0; i < visits.size(); i++) {
            ColumnVisitEntry entry = visits.get(i);
            String prefix = "visit." + i + ".";
            properties.setProperty(prefix + "column", entry.columnPos().x() + "," + entry.columnPos().z());
            properties.setProperty(prefix + "first", Long.toString(entry.firstVisitedGameTime()));
            properties.setProperty(prefix + "last", Long.toString(entry.lastVisitedGameTime()));
            properties.setProperty(prefix + "minY", Integer.toString(entry.minVisitedCubeY()));
            properties.setProperty(prefix + "maxY", Integer.toString(entry.maxVisitedCubeY()));
            properties.setProperty(prefix + "lastY", Integer.toString(entry.lastVisitedCubeY()));
            properties.setProperty(prefix + "visits", Integer.toString(entry.visits()));
            properties.setProperty(prefix + "nextBackfillStep", Integer.toString(entry.nextBackfillStep()));
            properties.setProperty(prefix + "backfillDone", Boolean.toString(entry.backfillDone()));
        }

        VerticalBackfillSnapshot backfill = VerticalBackfillDaemon.snapshot();
        properties.setProperty("backfill.enabled", Boolean.toString(backfill.enabled()));
        properties.setProperty("backfill.maxVerticalRadius", Integer.toString(backfill.maxVerticalRadius()));
        properties.setProperty("backfill.delayTicks", Integer.toString(backfill.delayTicks()));
        properties.setProperty("backfill.target", backfill.targetStatus());
        properties.setProperty("backfill.jobsStarted", Long.toString(backfill.jobsStarted()));
        properties.setProperty("backfill.lastReason", backfill.lastReason());

        AfkPregenSnapshot afk = AfkPregenController.snapshot();
        properties.setProperty("afk.enabled", Boolean.toString(afk.enabled()));
        properties.setProperty("afk.afterTicks", Integer.toString(afk.afkAfterTicks()));
        properties.setProperty("afk.radiusBlocks", Integer.toString(afk.radiusBlocks()));
        properties.setProperty("afk.verticalRadiusCubes", Integer.toString(afk.verticalRadiusCubes()));
        properties.setProperty("afk.target", afk.targetStatus());
        properties.setProperty("afk.jobsStarted", Long.toString(afk.jobsStarted()));
        properties.setProperty("afk.lastReason", afk.lastReason());

        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "Redline World Core M13.1 pregen/backfill state");
        }
    }

    private static String cubeString(CubePos cubePos) {
        return cubePos.x() + "," + cubePos.y() + "," + cubePos.z();
    }

    private static CubePos cubeValue(Properties properties, String key, CubePos fallback) {
        String[] parts = properties.getProperty(key, "").split(",");
        if (parts.length != 3) {
            return fallback;
        }
        try {
            return new CubePos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static ColumnPos columnValue(Properties properties, String key, ColumnPos fallback) {
        String[] parts = properties.getProperty(key, "").split(",");
        if (parts.length != 2) {
            return fallback;
        }
        try {
            return new ColumnPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static CubeStatus statusValue(Properties properties, String key, CubeStatus fallback) {
        try {
            return CubeStatus.valueOf(properties.getProperty(key, fallback.name()));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static UUID uuidValue(Properties properties, String key, UUID fallback) {
        try {
            return UUID.fromString(properties.getProperty(key, fallback.toString()));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static int intValue(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long longValue(Properties properties, String key, long fallback) {
        try {
            return Long.parseLong(properties.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean boolValue(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(fallback)));
    }

    record LoadedState(
            CubePregenBudget budget,
            CubePregenJob activeJob,
            long activeProcessed,
            boolean paused,
            long totalStartedJobs,
            long totalCompletedJobs,
            long totalProcessed,
            long totalGenerated,
            long totalSkipped,
            long totalFailed,
            String lastError,
            List<ColumnVisitEntry> visits,
            boolean backfillEnabled,
            int backfillMaxVerticalRadius,
            int backfillDelayTicks,
            CubeStatus backfillTargetStatus,
            long backfillJobsStarted,
            String backfillLastReason,
            boolean afkEnabled,
            int afkAfterTicks,
            int afkRadiusBlocks,
            int afkVerticalRadiusCubes,
            CubeStatus afkTargetStatus,
            long afkJobsStarted,
            String afkLastReason
    ) {
        static LoadedState empty() {
            return new LoadedState(
                    CubePregenBudget.defaults(),
                    null,
                    0L,
                    false,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    "",
                    List.of(),
                    true,
                    8,
                    120,
                    CubeStatus.GEOLOGY_READY,
                    0L,
                    "empty",
                    false,
                    20 * 300,
                    96,
                    4,
                    CubeStatus.LIGHT_READY,
                    0L,
                    "empty"
            );
        }
    }

    private CubePregenPersistentState() {
    }
}
