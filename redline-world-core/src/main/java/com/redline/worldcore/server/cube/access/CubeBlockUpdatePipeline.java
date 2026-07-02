package com.redline.worldcore.server.cube.access;

/**
 * M14.0 diagnostics and accounting for cube-owned block updates.
 *
 * <p>The actual mutation still lives in {@code ServerCubeCache} because it owns holders, storage and generation. Keeping
 * the counters here gives later M14.x mixins one small pipeline object to expand with content flags, block entities,
 * scheduled ticks and client section invalidation.</p>
 */
public final class CubeBlockUpdatePipeline {
    private long totalMutations;
    private long totalApplied;
    private long totalChanged;
    private long totalUnchanged;
    private long totalRejected;
    private long totalStatusPromoted;
    private long totalHolderLoaded;
    private long totalHolderGenerated;
    private long totalSaved;
    private long totalStaticLightRebuilt;
    private long totalSkyLightRebuilt;
    private long totalSkyLightQueued;
    private long lastElapsedMicros;
    private long maxElapsedMicros;
    private boolean lastApplied;
    private boolean lastChanged;
    private CubeMutationOrigin lastOrigin = CubeMutationOrigin.UNKNOWN;
    private com.redline.worldcore.api.pos.CubePos lastCube;
    private com.redline.worldcore.api.pos.CubeLocalPos lastLocal;
    private String lastReason = "none";

    public synchronized void record(CubeMutationResult result) {
        totalMutations++;
        lastElapsedMicros = result.elapsedMicros();
        maxElapsedMicros = Math.max(maxElapsedMicros, result.elapsedMicros());
        lastApplied = result.applied();
        lastChanged = result.changed();
        lastOrigin = result.origin();
        lastCube = result.cubePos();
        lastLocal = result.localPos();
        lastReason = result.reason();

        if (!result.applied()) {
            totalRejected++;
            return;
        }
        totalApplied++;
        if (result.changed()) {
            totalChanged++;
        } else {
            totalUnchanged++;
        }
        if (result.statusPromoted()) {
            totalStatusPromoted++;
        }
        if (result.holderLoaded()) {
            totalHolderLoaded++;
        }
        if (result.holderGenerated()) {
            totalHolderGenerated++;
        }
        if (result.saved()) {
            totalSaved++;
        }
        if (result.staticLightRebuilt()) {
            totalStaticLightRebuilt++;
        }
        if (result.skyLightRebuilt()) {
            totalSkyLightRebuilt++;
        }
        if (result.skyLightQueued()) {
            totalSkyLightQueued++;
        }
    }

    public synchronized CubeMutationSnapshot snapshot() {
        return new CubeMutationSnapshot(
                totalMutations,
                totalApplied,
                totalChanged,
                totalUnchanged,
                totalRejected,
                totalStatusPromoted,
                totalHolderLoaded,
                totalHolderGenerated,
                totalSaved,
                totalStaticLightRebuilt,
                totalSkyLightRebuilt,
                totalSkyLightQueued,
                lastElapsedMicros,
                maxElapsedMicros,
                lastApplied,
                lastChanged,
                lastOrigin,
                lastCube,
                lastLocal,
                lastReason
        );
    }

    public synchronized void reset() {
        totalMutations = 0L;
        totalApplied = 0L;
        totalChanged = 0L;
        totalUnchanged = 0L;
        totalRejected = 0L;
        totalStatusPromoted = 0L;
        totalHolderLoaded = 0L;
        totalHolderGenerated = 0L;
        totalSaved = 0L;
        totalStaticLightRebuilt = 0L;
        totalSkyLightRebuilt = 0L;
        totalSkyLightQueued = 0L;
        lastElapsedMicros = 0L;
        maxElapsedMicros = 0L;
        lastApplied = false;
        lastChanged = false;
        lastOrigin = CubeMutationOrigin.UNKNOWN;
        lastCube = null;
        lastLocal = null;
        lastReason = "none";
    }
}
