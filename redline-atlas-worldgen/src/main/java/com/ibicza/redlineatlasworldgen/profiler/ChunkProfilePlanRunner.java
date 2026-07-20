package com.ibicza.redlineatlasworldgen.profiler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.river.AtlasRiverIndex;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.util.profiling.jfr.Environment;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChunkProfilePlanRunner {
    public static final String PLAN_ENV = "RLA_CHUNK_PROFILE_PLAN";
    public static final String RUN_ID_ENV = "RLA_CHUNK_PROFILE_RUN_ID";
    public static final String AUTO_STOP_ENV = "RLA_CHUNK_PROFILE_AUTO_STOP";

    private static final int MAX_POINTS = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean initialized;
    private static Runner runner;

    public static void tick(MinecraftServer server) {
        if (!initialized) {
            initialized = true;
            String planValue = System.getenv(PLAN_ENV);
            if (planValue == null || planValue.isBlank()) {
                return;
            }
            initialize(server, planValue);
        }
        if (runner != null) {
            runner.tick(server);
        }
    }

    public static void serverStopping(MinecraftServer server) {
        if (runner != null) {
            runner.serverStopping(server);
        }
    }

    private static void initialize(MinecraftServer server, String planValue) {
        String runId = safeRunId(System.getenv(RUN_ID_ENV));
        boolean autoStop = !"false".equalsIgnoreCase(System.getenv(AUTO_STOP_ENV));
        Path reportDirectory = server.getServerDirectory().resolve("profile-results");
        Path summaryPath = reportDirectory.resolve(runId + ".run.json");
        Path cancelPath = reportDirectory.resolve(runId + ".cancel");

        try {
            Path planPath = Path.of(planValue).toAbsolutePath().normalize();
            ProfilePlan plan = readPlan(planPath);
            runner = new Runner(runId, planPath, summaryPath, cancelPath, plan, autoStop);
            runner.begin(server);
        } catch (Exception exception) {
            String message = "Automated chunk profile initialization failed: " + exception.getMessage();
            RedlineAtlasWorldgen.LOGGER.error(message, exception);
            if (runner != null) {
                runner.finish(server, false, message, true);
            } else {
                writeInitializationFailure(summaryPath, runId, planValue, message);
                if (autoStop) {
                    server.halt(false);
                }
            }
        }
    }

    private static ProfilePlan readPlan(Path path) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("profile plan root must be an object");
            }
            root = element.getAsJsonObject();
        }

        int defaultRadius = intValue(root, "radiusChunks", 1);
        int defaultTimeout = intValue(root, "timeoutTicks",
                AtlasWorldgenProfiler.DEFAULT_CHUNK_PROFILE_TIMEOUT_TICKS);
        int defaultSettle = intValue(root, "settleTicks",
                AtlasWorldgenProfiler.DEFAULT_CHUNK_PROFILE_SETTLE_TICKS);
        JsonElement pointsElement = root.get("points");
        if (pointsElement == null || !pointsElement.isJsonArray()) {
            throw new IllegalArgumentException("profile plan must contain a points array");
        }
        JsonArray pointsArray = pointsElement.getAsJsonArray();
        if (pointsArray.isEmpty() || pointsArray.size() > MAX_POINTS) {
            throw new IllegalArgumentException("profile plan must contain between 1 and " + MAX_POINTS + " points");
        }

        List<ProfilePoint> points = new ArrayList<>(pointsArray.size());
        Set<String> labels = new HashSet<>();
        for (int index = 0; index < pointsArray.size(); index++) {
            JsonElement pointElement = pointsArray.get(index);
            if (!pointElement.isJsonObject()) {
                throw new IllegalArgumentException("profile point " + index + " must be an object");
            }
            JsonObject point = pointElement.getAsJsonObject();
            String label = requiredString(point, "label");
            if (!label.matches("[A-Za-z0-9._-]{1,48}")) {
                throw new IllegalArgumentException("invalid profile label: " + label);
            }
            if (!labels.add(label.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate profile label: " + label);
            }

            int blockX = requiredInt(point, "blockX");
            int blockZ = requiredInt(point, "blockZ");
            int radius = intValue(point, "radiusChunks", defaultRadius);
            int timeout = intValue(point, "timeoutTicks", defaultTimeout);
            int settle = intValue(point, "settleTicks", defaultSettle);
            ChunkProfileTerrainClassifier.TerrainClass expectedTerrainClass =
                    ChunkProfileTerrainClassifier.parseExpected(optionalString(point, "terrainClass"));
            int nearestRiverRadius = intValue(point, "nearestRiverRadiusBlocks", 0);
            validatePoint(label, blockX, blockZ, radius, timeout, settle);
            if (nearestRiverRadius < 0 || nearestRiverRadius > 32_768) {
                throw new IllegalArgumentException(
                        "profile point " + label + " has invalid nearestRiverRadiusBlocks"
                );
            }
            if (nearestRiverRadius > 0
                    && expectedTerrainClass != ChunkProfileTerrainClassifier.TerrainClass.RIVER) {
                throw new IllegalArgumentException(
                        "nearestRiverRadiusBlocks requires terrainClass=river for profile " + label
                );
            }
            points.add(new ProfilePoint(
                    label, blockX, blockZ, radius, timeout, settle, expectedTerrainClass,
                    nearestRiverRadius, blockX, blockZ
            ));
        }
        return new ProfilePlan(List.copyOf(points));
    }

    private static void validatePoint(String label, int blockX, int blockZ,
                                      int radius, int timeout, int settle) {
        if (blockX < -30_000_000 || blockX > 29_999_999
                || blockZ < -30_000_000 || blockZ > 29_999_999) {
            throw new IllegalArgumentException("profile point " + label + " is outside Minecraft bounds");
        }
        if (radius < 0 || radius > AtlasWorldgenProfiler.MAX_CHUNK_PROFILE_RADIUS) {
            throw new IllegalArgumentException("profile point " + label + " has invalid radiusChunks");
        }
        if (timeout < 20 || timeout > 72_000) {
            throw new IllegalArgumentException("profile point " + label + " has invalid timeoutTicks");
        }
        if (settle < 0 || settle > 1_200 || settle > timeout) {
            throw new IllegalArgumentException("profile point " + label + " has invalid settleTicks");
        }
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing string property: " + name);
        }
        return element.getAsString();
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing integer property: " + name);
        }
        return element.getAsInt();
    }

    private static int intValue(JsonObject object, String name, int defaultValue) {
        JsonElement element = object.get(name);
        return element == null ? defaultValue : element.getAsInt();
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null) {
            return null;
        }
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException("property must be a string: " + name);
        }
        return element.getAsString();
    }

    private static void writeInitializationFailure(Path summaryPath, String runId,
                                                   String planValue, String error) {
        Map<String, Object> summary = baseSummary(runId, planValue, Instant.now(), Instant.now());
        summary.put("success", false);
        summary.put("error", error);
        summary.put("pointsRequested", 0);
        summary.put("pointsCompleted", 0);
        summary.put("jfrPath", null);
        summary.put("reports", List.of());
        try {
            writeJsonAtomically(summaryPath, summary);
        } catch (IOException writeException) {
            RedlineAtlasWorldgen.LOGGER.error("Could not write automated profile failure marker {}",
                    summaryPath, writeException);
        }
    }

    private static Map<String, Object> baseSummary(String runId, String planPath,
                                                   Instant startedAt, Instant endedAt) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 2);
        summary.put("runId", runId);
        summary.put("planPath", planPath);
        summary.put("startedAtUtc", startedAt.toString());
        summary.put("endedAtUtc", endedAt.toString());
        return summary;
    }

    private static void writeJsonAtomically(Path path, Map<String, Object> value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeRunId(String value) {
        String input = value == null || value.isBlank() ? "automated-profile" : value;
        StringBuilder safe = new StringBuilder(Math.min(input.length(), 64));
        for (int index = 0; index < input.length() && safe.length() < 64; index++) {
            char character = input.charAt(index);
            if (character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_' || character == '.') {
                safe.append(character);
            } else {
                safe.append('_');
            }
        }
        return safe.toString();
    }

    private record ProfilePlan(List<ProfilePoint> points) {
    }

    private record ProfilePoint(String label, int blockX, int blockZ,
                                int radius, int timeout, int settle,
                                ChunkProfileTerrainClassifier.TerrainClass expectedTerrainClass,
                                int nearestRiverRadiusBlocks,
                                int requestedBlockX, int requestedBlockZ) {
        private ProfilePoint withCoordinates(int resolvedBlockX, int resolvedBlockZ) {
            return new ProfilePoint(
                    label, resolvedBlockX, resolvedBlockZ, radius, timeout, settle,
                    expectedTerrainClass, nearestRiverRadiusBlocks, requestedBlockX, requestedBlockZ
            );
        }
    }

    private static ProfilePlan resolveNearestRivers(ProfilePlan requested) {
        List<ProfilePoint> resolved = new ArrayList<>(requested.points().size());
        for (ProfilePoint point : requested.points()) {
            if (point.nearestRiverRadiusBlocks() == 0) {
                resolved.add(point);
                continue;
            }
            var nearest = AtlasRiverIndex.active().nearestChannel(
                    point.blockX(), point.blockZ(), point.nearestRiverRadiusBlocks()
            );
            if (nearest.isEmpty()) {
                throw new IllegalArgumentException(
                        "profile " + point.label() + " found no river within "
                                + point.nearestRiverRadiusBlocks() + " blocks of "
                                + point.blockX() + "," + point.blockZ()
                );
            }
            ProfilePoint resolvedPoint = point.withCoordinates(
                    nearest.get().blockX(), nearest.get().blockZ()
            );
            RedlineAtlasWorldgen.LOGGER.info(
                    "Resolved chunk profile river label={} from {},{} to {},{} (distance={} blocks)",
                    point.label(), point.blockX(), point.blockZ(), resolvedPoint.blockX(),
                    resolvedPoint.blockZ(), nearest.get().distanceBlocks()
            );
            resolved.add(resolvedPoint);
        }
        return new ProfilePlan(List.copyOf(resolved));
    }

    private static final class Runner {
        private final String runId;
        private final Path planPath;
        private final Path summaryPath;
        private final Path cancelPath;
        private ProfilePlan plan;
        private final boolean autoStop;
        private final List<AtlasWorldgenProfiler.ChunkProfileCompletion> completions = new ArrayList<>();
        private final Map<String, ChunkProfileTerrainClassifier.Classification> classifications =
                new LinkedHashMap<>();
        private final Instant startedAt = Instant.now();

        private int nextPointIndex;
        private String activeLabel;
        private boolean finished;
        private boolean jfrOwned;
        private boolean pauseChanged;
        private int previousPauseSeconds;

        private Runner(String runId, Path planPath, Path summaryPath, Path cancelPath,
                       ProfilePlan plan, boolean autoStop) {
            this.runId = runId;
            this.planPath = planPath;
            this.summaryPath = summaryPath;
            this.cancelPath = cancelPath;
            this.plan = plan;
            this.autoStop = autoStop;
        }

        private void begin(MinecraftServer server) {
            plan = resolveNearestRivers(plan);
            if (server instanceof DedicatedServer dedicatedServer) {
                previousPauseSeconds = dedicatedServer.pauseWhenEmptySeconds();
                if (previousPauseSeconds > 0) {
                    dedicatedServer.setPauseWhenEmptySeconds(0);
                    pauseChanged = true;
                }
            }

            JvmProfiler profiler = JvmProfiler.INSTANCE;
            if (!profiler.isAvailable()) {
                throw new IllegalStateException("Minecraft JFR profiler is unavailable in this JVM");
            }
            if (profiler.isRunning()) {
                RedlineAtlasWorldgen.LOGGER.warn("Automated chunk profile is reusing an existing JFR recording");
            } else {
                jfrOwned = profiler.start(Environment.from(server));
                if (!jfrOwned) {
                    throw new IllegalStateException("Minecraft JFR profiler refused to start");
                }
            }
            RedlineAtlasWorldgen.LOGGER.info(
                    "Automated chunk profile run {} started with {} point(s), plan={}",
                    runId, plan.points().size(), planPath
            );
        }

        private void tick(MinecraftServer server) {
            if (finished) {
                return;
            }
            if (Files.exists(cancelPath)) {
                abortActiveProfile(server);
                finish(server, false, "automation cancelled by host", true);
                return;
            }

            if (activeLabel != null) {
                AtlasWorldgenProfiler.ChunkProfileCompletion completion =
                        AtlasWorldgenProfiler.lastChunkProfileCompletion();
                if (completion == null || !activeLabel.equals(completion.label())) {
                    return;
                }
                completions.add(completion);
                RedlineAtlasWorldgen.LOGGER.info(
                        "Automated chunk profile {}/{} finished: label={}, reason={}, report={}",
                        nextPointIndex + 1, plan.points().size(), activeLabel,
                        completion.stopReason(), completion.jsonPath()
                );
                activeLabel = null;
                nextPointIndex++;
                if (!completion.reportWritten()) {
                    finish(server, false, completion.message(), true);
                    return;
                }
                if (!"completed".equals(completion.stopReason())) {
                    finish(server, false,
                            "profile " + completion.label() + " stopped with " + completion.stopReason(), true);
                    return;
                }
            }

            if (nextPointIndex >= plan.points().size()) {
                finish(server, true, null, true);
                return;
            }

            ProfilePoint point = plan.points().get(nextPointIndex);
            AtlasWorldgenProfiler.OperationResult result = AtlasWorldgenProfiler.startChunkProfile(
                    server,
                    Level.OVERWORLD,
                    point.label(),
                    SectionPos.blockToSectionCoord(point.blockX()),
                    SectionPos.blockToSectionCoord(point.blockZ()),
                    point.radius(),
                    point.timeout(),
                    point.settle()
            );
            if (!result.success()) {
                finish(server, false, result.message(), true);
                return;
            }
            activeLabel = point.label();
            RedlineAtlasWorldgen.LOGGER.info(
                    "Automated chunk profile {}/{} started: {}",
                    nextPointIndex + 1, plan.points().size(), result.message()
            );
        }

        private void serverStopping(MinecraftServer server) {
            if (!finished) {
                abortActiveProfile(server);
                finish(server, false, "server stopped before automated profile completed", false);
            }
        }

        private void abortActiveProfile(MinecraftServer server) {
            if (activeLabel == null) {
                return;
            }
            AtlasWorldgenProfiler.OperationResult result = AtlasWorldgenProfiler.stopChunkProfile(server);
            AtlasWorldgenProfiler.ChunkProfileCompletion completion =
                    AtlasWorldgenProfiler.lastChunkProfileCompletion();
            if (result.success() && completion != null && activeLabel.equals(completion.label())) {
                completions.add(completion);
            }
            activeLabel = null;
        }

        private void finish(MinecraftServer server, boolean success, String error, boolean requestStop) {
            if (finished) {
                return;
            }
            finished = true;
            String finalError = error;
            Path jfrPath = null;
            try {
                if (jfrOwned && JvmProfiler.INSTANCE.isRunning()) {
                    jfrPath = JvmProfiler.INSTANCE.stop().toAbsolutePath();
                }
            } catch (RuntimeException exception) {
                finalError = appendError(finalError, "JFR stop failed: " + exception.getMessage());
                success = false;
                RedlineAtlasWorldgen.LOGGER.error("Could not stop automated JFR recording", exception);
            }

            for (AtlasWorldgenProfiler.ChunkProfileCompletion completion : completions) {
                ProfilePoint point = point(completion.label());
                if (point == null) {
                    finalError = appendError(finalError,
                            "profile completion has no matching plan point: " + completion.label());
                    success = false;
                    continue;
                }
                try {
                    ChunkProfileTerrainClassifier.Classification classification =
                            ChunkProfileTerrainClassifier.classify(point.blockX(), point.blockZ());
                    classifications.put(point.label(), classification);
                    String expected = point.expectedTerrainClass() == null
                            ? "unspecified" : point.expectedTerrainClass().id();
                    RedlineAtlasWorldgen.LOGGER.info(
                            "Chunk profile terrain label={}, expected={}, actual={}, details={}",
                            point.label(), expected, classification.id(), classification.details()
                    );
                    if (point.expectedTerrainClass() != null
                            && point.expectedTerrainClass() != classification.terrainClass()) {
                        finalError = appendError(finalError,
                                "profile " + point.label() + " expected terrainClass=" + expected
                                        + " but classified as " + classification.id());
                        success = false;
                    }
                } catch (RuntimeException exception) {
                    finalError = appendError(finalError,
                            "terrain classification failed for " + point.label() + ": " + exception.getMessage());
                    success = false;
                    RedlineAtlasWorldgen.LOGGER.error(
                            "Could not classify chunk profile point {}", point.label(), exception
                    );
                }
            }

            if (pauseChanged && server instanceof DedicatedServer dedicatedServer) {
                dedicatedServer.setPauseWhenEmptySeconds(previousPauseSeconds);
                pauseChanged = false;
            }

            Map<String, Object> summary = baseSummary(
                    runId, planPath.toString(), startedAt, Instant.now()
            );
            summary.put("success", success);
            summary.put("error", finalError);
            summary.put("pointsRequested", plan.points().size());
            summary.put("pointsCompleted", completions.size());
            summary.put("jfrPath", jfrPath == null ? null : jfrPath.toString());
            List<Map<String, Object>> classificationRows = new ArrayList<>(classifications.size());
            for (ProfilePoint point : plan.points()) {
                ChunkProfileTerrainClassifier.Classification classification = classifications.get(point.label());
                if (classification == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", point.label());
                row.put("blockX", point.blockX());
                row.put("blockZ", point.blockZ());
                row.put("requestedBlockX", point.requestedBlockX());
                row.put("requestedBlockZ", point.requestedBlockZ());
                row.put("nearestRiverRadiusBlocks", point.nearestRiverRadiusBlocks());
                row.put("expectedTerrainClass", point.expectedTerrainClass() == null
                        ? null : point.expectedTerrainClass().id());
                row.put("terrainClass", classification.id());
                row.put("details", classification.details());
                classificationRows.add(row);
            }
            summary.put("classifications", classificationRows);
            List<Map<String, Object>> reports = new ArrayList<>(completions.size());
            for (AtlasWorldgenProfiler.ChunkProfileCompletion completion : completions) {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("label", completion.label());
                report.put("stopReason", completion.stopReason());
                report.put("reportWritten", completion.reportWritten());
                report.put("jsonPath", completion.jsonPath().toString());
                report.put("csvPath", completion.csvPath().toString());
                ChunkProfileTerrainClassifier.Classification classification =
                        classifications.get(completion.label());
                report.put("terrainClass", classification == null ? null : classification.id());
                reports.add(report);
            }
            summary.put("reports", reports);

            try {
                writeJsonAtomically(summaryPath, summary);
                RedlineAtlasWorldgen.LOGGER.info(
                        "Automated chunk profile run {} {}: summary={}, JFR={}",
                        runId, success ? "completed" : "failed", summaryPath.toAbsolutePath(), jfrPath
                );
            } catch (IOException exception) {
                RedlineAtlasWorldgen.LOGGER.error("Could not write automated profile summary {}",
                        summaryPath, exception);
            } finally {
                try {
                    Files.deleteIfExists(cancelPath);
                } catch (IOException exception) {
                    RedlineAtlasWorldgen.LOGGER.warn("Could not remove profile cancellation marker {}",
                            cancelPath, exception);
                }
            }

            if (requestStop && autoStop) {
                server.halt(false);
            }
        }

        private static String appendError(String current, String addition) {
            return current == null || current.isBlank() ? addition : current + "; " + addition;
        }

        private ProfilePoint point(String label) {
            for (ProfilePoint point : plan.points()) {
                if (point.label().equals(label)) {
                    return point;
                }
            }
            return null;
        }
    }

    private ChunkProfilePlanRunner() {
    }
}
