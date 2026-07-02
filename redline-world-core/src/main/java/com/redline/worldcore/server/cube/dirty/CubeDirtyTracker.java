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
    private final Map<CubePos, CubeContentSummary> contentSummaries = new LinkedHashMap<>();

    private long totalMarked;
    private long totalContentRebuilt;
    private long totalSaved;
    private int contentRebuiltLastTick;
    private int savedLastTick;
    private long contentMicrosLastTick;
    private long contentMicrosMax;
    private long saveMicrosLastTick;
    private long saveMicrosMax;
    private boolean saveBudgetHitLastTick;
    private CubePos lastDirtyCube;
    private Set<CubeDirtyFlag> lastDirtyFlags = Set.of();
    private CubePos lastContentCube;
    private CubeContentSummary lastContentSummary = CubeContentSummary.empty();
    private CubePos lastSavedCube;

    public CubeDirtyTracker(int maxContentPerTick, int maxSavesPerTick, int maxSaveMicrosPerTick) {
        this.maxContentPerTick = Math.max(1, maxContentPerTick);
        this.maxSavesPerTick = Math.max(1, maxSavesPerTick);
        this.maxSaveMicrosPerTick = Math.max(1, maxSaveMicrosPerTick);
    }

    public void mark(CubePos cubePos, CubeDirtyFlag first, CubeDirtyFlag... rest) {
        EnumSet<CubeDirtyFlag> set = dirtyFlags.computeIfAbsent(cubePos, ignored -> EnumSet.noneOf(CubeDirtyFlag.class));
        set.add(first);
        for (CubeDirtyFlag flag : rest) {
            set.add(flag);
        }
        if (set.contains(CubeDirtyFlag.CONTENT_FLAGS) || set.contains(CubeDirtyFlag.COLUMN_INDEX)) {
            contentQueue.add(cubePos);
        }
        if (set.contains(CubeDirtyFlag.STORAGE)) {
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

    public List<CubePos> pollSaveWork(long alreadyElapsedMicros) {
        List<CubePos> result = new ArrayList<>(Math.min(maxSavesPerTick, saveQueue.size()));
        if (alreadyElapsedMicros >= maxSaveMicrosPerTick) {
            saveBudgetHitLastTick = !saveQueue.isEmpty();
            return result;
        }
        while (!saveQueue.isEmpty() && result.size() < maxSavesPerTick) {
            CubePos first = saveQueue.iterator().next();
            saveQueue.remove(first);
            result.add(first);
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

    public void requeueSave(CubePos cubePos) {
        saveQueue.add(cubePos);
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

    public void recordSaved(CubePos cubePos, long elapsedMicros) {
        totalSaved++;
        savedLastTick++;
        saveMicrosLastTick += Math.max(0L, elapsedMicros);
        saveMicrosMax = Math.max(saveMicrosMax, elapsedMicros);
        lastSavedCube = cubePos;
        clean(cubePos, CubeDirtyFlag.STORAGE);
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
    }

    public void clearQueues() {
        dirtyFlags.clear();
        contentQueue.clear();
        saveQueue.clear();
    }

    public void beginTick() {
        contentRebuiltLastTick = 0;
        savedLastTick = 0;
        contentMicrosLastTick = 0L;
        saveMicrosLastTick = 0L;
        saveBudgetHitLastTick = false;
    }

    public CubeDirtySnapshot snapshot() {
        return new CubeDirtySnapshot(
                dirtyFlags.size(),
                contentQueue.size(),
                saveQueue.size(),
                totalMarked,
                totalContentRebuilt,
                totalSaved,
                contentRebuiltLastTick,
                savedLastTick,
                contentMicrosLastTick,
                contentMicrosMax,
                saveMicrosLastTick,
                saveMicrosMax,
                maxContentPerTick,
                maxSavesPerTick,
                maxSaveMicrosPerTick,
                saveBudgetHitLastTick,
                lastDirtyCube,
                lastDirtyFlags,
                lastContentCube,
                lastContentSummary,
                lastSavedCube
        );
    }
}
