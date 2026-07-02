package com.redline.worldcore.server.cube.dirty;

import com.redline.worldcore.api.pos.CubePos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M14.1 runtime dirty queue manager.
 *
 * <p>This class does not touch storage or light engines directly. {@code ServerCubeCache} owns the actual world data;
 * this tracker owns the queueing/accounting so block mutation can stay cheap and downstream work can be budgeted.</p>
 */
public final class CubeDirtyTracker {
    private final int maxContentPerTick;
    private final int maxSavesPerTick;
    private final int maxSaveMicrosPerTick;

    private final LinkedHashMap<CubePos, EnumSet<CubeDirtyFlag>> dirtyFlags = new LinkedHashMap<>();
    private final LinkedHashSet<CubePos> contentQueue = new LinkedHashSet<>();
    private final LinkedHashSet<CubePos> saveQueue = new LinkedHashSet<>();
    private final Map<CubePos, Long> saveRevisions = new LinkedHashMap<>();
    private final Map<CubePos, Long> saveInFlight = new LinkedHashMap<>();
    private final Map<CubePos, CubeContentSummary> contentSummaries = new LinkedHashMap<>();

    private long totalMarked;
    private long totalContentRebuilt;
    private long totalSaved;
    private long totalSaveSubmitted;
    private long totalSaveFailed;
    private int contentRebuiltLastTick;
    private int savedLastTick;
    private int saveSubmittedLastTick;
    private int saveFailedLastTick;
    private long contentMicrosLastTick;
    private long contentMicrosMax;
    private long saveMicrosLastTick;
    private long saveMicrosMax;
    private boolean saveBudgetHitLastTick;
    private boolean saveIdleSkipLastTick;
    private int saveCooldownTicks;
    private int saveCooldownLastTick;
    private String saveLastReason = "none";
    private CubePos lastDirtyCube;
    private Set<CubeDirtyFlag> lastDirtyFlags = Set.of();
    private CubePos lastContentCube;
    private CubeContentSummary lastContentSummary = CubeContentSummary.empty();
    private CubePos lastSavedCube;
    private CubePos lastSubmittedCube;
    private CubePos lastFailedCube;

    public CubeDirtyTracker(int maxContentPerTick, int maxSavesPerTick, int maxSaveMicrosPerTick) {
        this.maxContentPerTick = Math.max(1, maxContentPerTick);
        this.maxSavesPerTick = Math.max(1, maxSavesPerTick);
        this.maxSaveMicrosPerTick = Math.max(1, maxSaveMicrosPerTick);
    }

    public void mark(CubePos cubePos, CubeDirtyFlag first, CubeDirtyFlag... rest) {
        EnumSet<CubeDirtyFlag> set = dirtyFlags.computeIfAbsent(cubePos, ignored -> EnumSet.noneOf(CubeDirtyFlag.class));
        set.add(first);
        boolean storageDirty = first == CubeDirtyFlag.STORAGE;
        for (CubeDirtyFlag flag : rest) {
            set.add(flag);
            storageDirty |= flag == CubeDirtyFlag.STORAGE;
        }
        if (set.contains(CubeDirtyFlag.CONTENT_FLAGS) || set.contains(CubeDirtyFlag.COLUMN_INDEX)) {
            contentQueue.add(cubePos);
        }
        if (storageDirty) {
            saveRevisions.merge(cubePos, 1L, Long::sum);
            if (!saveInFlight.containsKey(cubePos)) {
                saveQueue.add(cubePos);
            }
        } else if (set.contains(CubeDirtyFlag.STORAGE) && !saveInFlight.containsKey(cubePos)) {
            saveQueue.add(cubePos);
        }
        totalMarked++;
        lastDirtyCube = cubePos;
        lastDirtyFlags = Set.copyOf(set);
    }

    public List<CubePos> pollContentWork() {
        List<CubePos> result = new ArrayList<>(Math.min(maxContentPerTick, contentQueue.size()));
        while (!contentQueue.isEmpty() && result.size() < maxContentPerTick) {
            CubePos first = contentQueue.iterator().next();
            contentQueue.remove(first);
            result.add(first);
        }
        return result;
    }

    public List<CubeSaveWork> pollSaveWork(long alreadyElapsedMicros) {
        List<CubeSaveWork> result = new ArrayList<>(Math.min(maxSavesPerTick, saveQueue.size()));
        if (alreadyElapsedMicros >= maxSaveMicrosPerTick) {
            saveBudgetHitLastTick = !saveQueue.isEmpty();
            return result;
        }
        while (!saveQueue.isEmpty() && result.size() < maxSavesPerTick) {
            CubePos first = saveQueue.iterator().next();
            saveQueue.remove(first);
            long revision = saveRevisions.getOrDefault(first, 0L);
            saveInFlight.put(first, revision);
            result.add(new CubeSaveWork(first, revision));
        }
        return result;
    }

    public boolean shouldStopSaving(long elapsedMicros, int savedThisTick) {
        boolean hit = savedThisTick >= maxSavesPerTick || elapsedMicros >= maxSaveMicrosPerTick;
        if (hit) {
            saveBudgetHitLastTick = !saveQueue.isEmpty();
        }
        return hit;
    }

    public void requeueSave(CubeSaveWork work) {
        saveInFlight.remove(work.cubePos());
        saveQueue.add(work.cubePos());
    }

    public void cancelSaveWork(CubeSaveWork work) {
        saveInFlight.remove(work.cubePos());
    }

    public void recordSaveSubmitted(CubeSaveWork work) {
        totalSaveSubmitted++;
        saveSubmittedLastTick++;
        lastSubmittedCube = work.cubePos();
    }

    public boolean recordSaved(CubeSaveWork work, long elapsedMicros) {
        CubePos cubePos = work.cubePos();
        Long inFlightRevision = saveInFlight.get(cubePos);
        if (inFlightRevision != null && inFlightRevision == work.revision()) {
            saveInFlight.remove(cubePos);
        }
        totalSaved++;
        savedLastTick++;
        saveMicrosLastTick += Math.max(0L, elapsedMicros);
        saveMicrosMax = Math.max(saveMicrosMax, elapsedMicros);
        lastSavedCube = cubePos;

        long currentRevision = saveRevisions.getOrDefault(cubePos, 0L);
        if (currentRevision <= work.revision()) {
            saveRevisions.remove(cubePos);
            clean(cubePos, CubeDirtyFlag.STORAGE);
            return true;
        }

        saveQueue.add(cubePos);
        return false;
    }

    public void recordSaveFailed(CubeSaveWork work, String reason) {
        saveInFlight.remove(work.cubePos());
        saveQueue.add(work.cubePos());
        totalSaveFailed++;
        saveFailedLastTick++;
        lastFailedCube = work.cubePos();
        saveLastReason = reason == null || reason.isBlank() ? "failed" : reason;
    }

    public void recordContent(CubePos cubePos, CubeContentSummary summary, long elapsedMicros) {
        contentSummaries.put(cubePos, summary);
        totalContentRebuilt++;
        contentRebuiltLastTick++;
        contentMicrosLastTick += Math.max(0L, elapsedMicros);
        contentMicrosMax = Math.max(contentMicrosMax, elapsedMicros);
        lastContentCube = cubePos;
        lastContentSummary = summary;
        clean(cubePos, CubeDirtyFlag.CONTENT_FLAGS, CubeDirtyFlag.COLUMN_INDEX);
    }

    /** Compatibility helper for old immediate-save call sites. */
    public void recordSaved(CubePos cubePos, long elapsedMicros) {
        CubeSaveWork work = new CubeSaveWork(cubePos, saveRevisions.getOrDefault(cubePos, 0L));
        recordSaved(work, elapsedMicros);
    }

    public void clean(CubePos cubePos, CubeDirtyFlag... flags) {
        EnumSet<CubeDirtyFlag> set = dirtyFlags.get(cubePos);
        if (set == null) {
            return;
        }
        for (CubeDirtyFlag flag : flags) {
            set.remove(flag);
        }
        if (set.isEmpty()) {
            dirtyFlags.remove(cubePos);
        }
    }

    public void remove(CubePos cubePos) {
        dirtyFlags.remove(cubePos);
        contentQueue.remove(cubePos);
        saveQueue.remove(cubePos);
        saveRevisions.remove(cubePos);
        saveInFlight.remove(cubePos);
    }

    public void clearQueues() {
        dirtyFlags.clear();
        contentQueue.clear();
        saveQueue.clear();
        saveRevisions.clear();
        saveInFlight.clear();
    }

    public void beginTick() {
        contentRebuiltLastTick = 0;
        savedLastTick = 0;
        saveSubmittedLastTick = 0;
        saveFailedLastTick = 0;
        contentMicrosLastTick = 0L;
        saveMicrosLastTick = 0L;
        saveBudgetHitLastTick = false;
        saveIdleSkipLastTick = false;
        saveCooldownLastTick = saveCooldownTicks;
        if (saveCooldownTicks > 0) {
            saveCooldownTicks--;
        }
    }

    public boolean saveCooldownActive() {
        return saveCooldownTicks > 0;
    }

    public void recordSaveIdleSkip(String reason) {
        saveIdleSkipLastTick = true;
        saveLastReason = reason == null || reason.isBlank() ? "not_idle" : reason;
    }

    public void startSaveCooldown(int ticks, String reason) {
        saveCooldownTicks = Math.max(saveCooldownTicks, Math.max(0, ticks));
        saveLastReason = reason == null || reason.isBlank() ? "cooldown" : reason;
    }

    public CubeDirtySnapshot snapshot() {
        return new CubeDirtySnapshot(
                dirtyFlags.size(),
                contentQueue.size(),
                saveQueue.size(),
                saveInFlight.size(),
                totalMarked,
                totalContentRebuilt,
                totalSaved,
                totalSaveSubmitted,
                totalSaveFailed,
                contentRebuiltLastTick,
                savedLastTick,
                saveSubmittedLastTick,
                saveFailedLastTick,
                contentMicrosLastTick,
                contentMicrosMax,
                saveMicrosLastTick,
                saveMicrosMax,
                maxContentPerTick,
                maxSavesPerTick,
                maxSaveMicrosPerTick,
                saveBudgetHitLastTick,
                saveIdleSkipLastTick,
                saveCooldownLastTick,
                saveLastReason,
                lastDirtyCube,
                lastDirtyFlags,
                lastContentCube,
                lastContentSummary,
                lastSavedCube,
                lastSubmittedCube,
                lastFailedCube
        );
    }
}
