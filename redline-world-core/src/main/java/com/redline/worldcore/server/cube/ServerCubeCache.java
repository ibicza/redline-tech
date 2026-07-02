package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.ColumnPos;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.api.pos.CubeLocalPos;
import com.redline.worldcore.server.cube.access.CubeBlockUpdatePipeline;
import com.redline.worldcore.server.cube.access.CubeMutationContext;
import com.redline.worldcore.server.cube.access.CubeMutationResult;
import com.redline.worldcore.server.cube.access.CubeMutationSnapshot;
import com.redline.worldcore.server.cube.access.CubicLevelAccess;
import com.redline.worldcore.server.cube.dirty.CubeContentSummary;
import com.redline.worldcore.server.cube.dirty.CubeDirtyFlag;
import com.redline.worldcore.server.cube.dirty.CubeDirtyTracker;
import com.redline.worldcore.server.generation.CubicWorldgenPipeline;
import com.redline.worldcore.server.lighting.ColumnSkyIndex;
import com.redline.worldcore.server.lighting.SkyLightLayer;
import com.redline.worldcore.server.lighting.SkyLightSummary;
import com.redline.worldcore.server.lighting.StaticBlockLightLayer;
import com.redline.worldcore.server.lighting.StaticLightSummary;
import com.redline.worldcore.server.storage.CubeRegionStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M6/M7 cube-first server cache.
 *
 * <p>This layer consumes CubeTicket requests and turns them into loaded cube holders. M7 adds cube-first generation when
 * Region3D has no saved cube yet. It intentionally does not touch Minecraft's ChunkMap yet: vanilla chunks remain the
 * compatibility shell while this cache proves the cube lifecycle against Region3D storage and the cubic generator.</p>
 */
public final class ServerCubeCache {
    /**
     * Active player movement must not synchronously generate a whole new cube slab.
     *
     * <p>M13.1 pregen has its own throttle, but player tickets used the older M6 loader directly. Crossing one cube
     * boundary can request a fresh 21x9 slab edge, and loading 128 generated cubes in one server tick is exactly the
     * multi-second freeze observed during M13.1 testing. Keep total active loads modest and cap generated loads harder.
     */
    public static final int MAX_LOADS_PER_TICK = 32;

    /** At most one freshly generated gameplay cube per tick; persisted/loaded cubes may still stream faster. */
    public static final int MAX_GENERATED_LOADS_PER_TICK = 1;

    /** Soft active-load budget. One expensive generated cube can exceed this, but the loader stops immediately after it. */
    public static final int MAX_LOAD_MICROS_PER_TICK = 4_000;

    /** Keep recently unrequested cubes warm for a short time to avoid thrash while tickets refresh or players move. */
    public static final int UNLOAD_GRACE_TICKS = 100;

    /** Safety cap for accidental huge debug cuboids before real async distance propagation exists. */
    public static final int MAX_REQUESTED_CUBES_PER_TICK = 32768;

    /** M9 keeps static block-light rebuilds small and predictable during gameplay ticks. */
    public static final int MAX_LIGHT_REBUILDS_PER_TICK = 32;

    /** M14.1 scans content/index summaries in small batches after block mutation. */
    public static final int MAX_DIRTY_CONTENT_REBUILDS_PER_TICK = 8;

    /** M14.1 saves dirty cubes in batches instead of synchronously writing every block edit. */
    public static final int MAX_DIRTY_SAVES_PER_TICK = 4;

    /** Soft M14.1 save flush budget; one expensive Region3D write may exceed it, then the queue stops. */
    public static final int MAX_DIRTY_SAVE_MICROS_PER_TICK = 4_000;

    /** M10.1 keeps automatic vertical sky-light rebuilds tiny; manual commands may still rebuild large ranges immediately. */
    public static final int MAX_SKY_LIGHT_COLUMNS_PER_TICK = 1;

    /** Debounces automatic sky rebuilds while player tickets are still streaming many cubes into the same columns. */
    public static final int SKY_LIGHT_DIRTY_DELAY_TICKS = 40;

    private final CubicDimensionSettings settings;
    private final CubeRegionStorage storage;
    private final CubicWorldgenPipeline generator;
    private final Map<CubePos, CubeHolder> holders = new ConcurrentHashMap<>();
    private final LinkedHashMap<CubePos, CubeTicketLevel> pendingLoads = new LinkedHashMap<>();
    private final LinkedHashSet<CubePos> lightDirtyQueue = new LinkedHashSet<>();
    private final LinkedHashMap<ColumnPos, Long> skyLightDirtyColumns = new LinkedHashMap<>();
    private final CubeDirtyTracker dirtyTracker = new CubeDirtyTracker(
            MAX_DIRTY_CONTENT_REBUILDS_PER_TICK,
            MAX_DIRTY_SAVES_PER_TICK,
            MAX_DIRTY_SAVE_MICROS_PER_TICK
    );
    private final CubeBlockUpdatePipeline blockUpdatePipeline = new CubeBlockUpdatePipeline();

    private long gameTime;
    private long totalLoaded;
    private long totalUnloaded;
    private long totalSaved;
    private long totalGenerated;
    private int requestedLastTick;
    private int queuedLastTick;
    private int loadedLastTick;
    private int unloadedLastTick;
    private int generatedLastTick;
    private long loadMicrosLastTick;
    private long loadMicrosMax;
    private boolean loadGeneratedBudgetHitLastTick;
    private boolean loadTimeBudgetHitLastTick;
    private boolean requestLimitHitLastTick;
    private long totalLightRebuilt;
    private int lightRebuiltLastTick;
    private int lightDirtyLastTick;
    private long totalSkyLightRebuilt;
    private long totalSkyLightColumnsRebuilt;
    private long totalSkyLightSkippedUnchanged;
    private long totalSkyLightSavedChanged;
    private int skyLightColumnsLastTick;
    private int skyLightDirtyLastTick;
    private int skyLightChangedLastTick;
    private int skyLightSkippedUnchangedLastTick;
    private int skyLightSavedChangedLastTick;
    private long skyLightRebuildMicrosLastTick;
    private long skyLightRebuildMicrosMax;

    public ServerCubeCache(Path storageRoot, CubicDimensionSettings settings, long seed) {
        this(new CubeRegionStorage(storageRoot), settings, seed);
    }

    public ServerCubeCache(CubeRegionStorage storage, CubicDimensionSettings settings, long seed) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.generator = new CubicWorldgenPipeline(settings, seed);
    }

    public Path storageRoot() {
        return storage.rootDirectory();
    }

    public synchronized LevelCube generateTemporary(CubePos cubePos) {
        if (!settings.containsCubeY(cubePos.y())) {
            throw new IllegalArgumentException("Cube Y is outside cubic dimension settings: " + cubePos);
        }
        LevelCube generated = generator.generate(cubePos);
        StaticBlockLightLayer.rebuild(generated);
        SkyLightLayer.rebuildSingleCubeFromOpenSky(generated);
        return generated;
    }

    public CubicDimensionSettings settings() {
        return settings;
    }

    /** Returns the M14.0 cube-first facade that new world-core code should use for block access. */
    public CubicLevelAccess access() {
        return new CubicLevelAccess(this);
    }

    public synchronized CubeMutationSnapshot mutationSnapshot() {
        return blockUpdatePipeline.snapshot();
    }

    public synchronized void resetMutationCounters() {
        blockUpdatePipeline.reset();
    }


    public synchronized CubeLoadingTickResult tick(Collection<CubeTicket> tickets) {
        gameTime++;
        dirtyTracker.beginTick();

        RequiredLevels required = collectRequiredLevels(tickets);
        requestedLastTick = required.levels().size();
        requestLimitHitLastTick = required.limitHit();

        purgePendingNoLongerRequired(required.levels());
        int queuedThisTick = queueMissingAndRefreshLoaded(required.levels());
        int unloadedThisTick = unloadNoLongerRequired(required.levels());
        LoadCounters loadCounters = loadPending(required.levels());
        int rebuiltContentThisTick = rebuildDirtyContentQueue();
        int rebuiltLightThisTick = rebuildDirtyLightQueue();
        SkyLightQueueCounters skyCounters = rebuildDirtySkyLightColumns();
        int savedDirtyThisTick = flushDirtySaveQueue();

        queuedLastTick = queuedThisTick;
        loadedLastTick = loadCounters.loaded();
        generatedLastTick = loadCounters.generated();
        loadMicrosLastTick = loadCounters.elapsedMicros();
        loadMicrosMax = Math.max(loadMicrosMax, loadCounters.elapsedMicros());
        loadGeneratedBudgetHitLastTick = loadCounters.generatedBudgetHit();
        loadTimeBudgetHitLastTick = loadCounters.timeBudgetHit();
        unloadedLastTick = unloadedThisTick;
        lightRebuiltLastTick = rebuiltLightThisTick;
        lightDirtyLastTick = lightDirtyQueue.size();
        skyLightColumnsLastTick = skyCounters.rebuiltColumns();
        skyLightChangedLastTick = skyCounters.changedCubes();
        skyLightSkippedUnchangedLastTick = skyCounters.skippedUnchangedCubes();
        skyLightSavedChangedLastTick = skyCounters.savedChangedCubes();
        skyLightRebuildMicrosLastTick = skyCounters.elapsedMicros();
        skyLightRebuildMicrosMax = Math.max(skyLightRebuildMicrosMax, skyCounters.elapsedMicros());
        skyLightDirtyLastTick = skyLightDirtyColumns.size();

        return new CubeLoadingTickResult(gameTime, requestedLastTick, queuedThisTick, loadCounters.loaded(), loadCounters.generated(), unloadedThisTick, requestLimitHitLastTick);
    }

    public synchronized Optional<CubeHolder> holder(CubePos cubePos) {
        return Optional.ofNullable(holders.get(cubePos));
    }

    public synchronized List<CubeHolder> sortedHolders() {
        List<CubeHolder> result = new ArrayList<>(holders.values());
        result.sort(Comparator
                .comparing((CubeHolder holder) -> holder.cubePos().x())
                .thenComparing(holder -> holder.cubePos().y())
                .thenComparing(holder -> holder.cubePos().z()));
        return result;
    }

    /**
     * M8 edit bridge: writes a physical vanilla block change back into the cube backend.
     *
     * <p>M14.0 routes the legacy bridge through the cube mutation pipeline. Physical player edits now share the same
     * ownership path as commands and later mixins: block pos -&gt; CubePos -&gt; local pos -&gt; holder mutation -&gt; dirty/light/save.
     * Sky light is queued instead of rebuilding the whole column synchronously on every player click.</p>
     */
    public synchronized Optional<CubeHolder> writeBlock(BlockPos worldPos, BlockState state, boolean saveImmediately) {
        CubeMutationResult result = mutateBlock(worldPos, state, CubeMutationContext.playerEdit(saveImmediately));
        if (!result.applied()) {
            return Optional.empty();
        }
        return Optional.ofNullable(holders.get(result.cubePos()));
    }

    /** Reads a block from loaded or persisted cube storage without generating missing terrain. */
    public synchronized Optional<BlockState> readBlock(BlockPos worldPos) {
        CubePos cubePos = CubePos.fromBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        if (!settings.containsCubeY(cubePos.y())) {
            return Optional.empty();
        }

        CubeHolder holder = holders.get(cubePos);
        if (holder != null) {
            return Optional.of(holder.cube().getBlockState(worldPos));
        }

        Optional<LevelCube> persisted = storage.get(cubePos);
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        BlockState state = persisted.get().getBlockState(worldPos);
        storage.unloadFromMemory(cubePos);
        return Optional.of(state);
    }

    /** Reads a block from cube storage, generating a temporary cube if the backend has not materialized it yet. */
    public synchronized Optional<BlockState> readOrGenerateBlock(BlockPos worldPos) {
        Optional<BlockState> stored = readBlock(worldPos);
        if (stored.isPresent()) {
            return stored;
        }
        CubePos cubePos = CubePos.fromBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        if (!settings.containsCubeY(cubePos.y())) {
            return Optional.empty();
        }
        return Optional.of(generateTemporary(cubePos).getBlockState(worldPos));
    }

    /**
     * M14.0 cube-owned block mutation pipeline.
     *
     * <p>This is the first stable API that treats the cube as the owner of a block change. It is still deliberately
     * conservative: vanilla chunks remain the visual/compatibility shell, but every Redline World Core mutation now has
     * one path for holder load/generation, status promotion, dirty marking, light invalidation and Region3D saving.</p>
     */
    public synchronized CubeMutationResult mutateBlock(BlockPos worldPos, BlockState state, CubeMutationContext context) {
        Objects.requireNonNull(worldPos, "worldPos");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(context, "context");

        long startNanos = System.nanoTime();
        CubePos cubePos = CubePos.fromBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        CubeLocalPos localPos = CubeLocalPos.fromBlock(worldPos);
        if (!settings.containsCubeY(cubePos.y())) {
            CubeMutationResult rejected = CubeMutationResult.rejected(worldPos, cubePos, localPos, state, context.origin(),
                    "outside_settings", elapsedMicrosSince(startNanos));
            blockUpdatePipeline.record(rejected);
            return rejected;
        }

        CubeHolder holder = holders.get(cubePos);
        boolean holderLoaded = false;
        boolean holderGenerated = false;
        if (holder == null) {
            if (!context.generateMissingHolder()) {
                CubeMutationResult rejected = CubeMutationResult.rejected(worldPos, cubePos, localPos, state, context.origin(),
                        "missing_holder", elapsedMicrosSince(startNanos));
                blockUpdatePipeline.record(rejected);
                return rejected;
            }
            holder = loadHolder(cubePos, CubeTicketLevel.FULL);
            holders.put(cubePos, holder);
            totalLoaded++;
            holderLoaded = true;
            if (holder.state() == CubeHolderState.GENERATED) {
                totalGenerated++;
                holderGenerated = true;
            }
        }

        BlockState previous = holder.cube().getBlockState(worldPos);
        boolean changed = !previous.equals(state);
        boolean statusPromoted = !holder.cube().status().isAtLeast(CubeStatus.FULL);
        if (changed) {
            holder.cube().setBlockState(worldPos, state);
        }
        if (statusPromoted) {
            holder.cube().setStatus(CubeStatus.FULL);
        }

        boolean dirty = changed || statusPromoted;
        boolean staticLightRebuilt = false;
        boolean skyLightRebuilt = false;
        boolean skyLightQueued = false;
        boolean saved = false;

        if (dirty) {
            holder.markDirty();
            dirtyTracker.mark(cubePos,
                    CubeDirtyFlag.BLOCKS,
                    CubeDirtyFlag.CONTENT_FLAGS,
                    CubeDirtyFlag.COLUMN_INDEX,
                    CubeDirtyFlag.STATIC_LIGHT,
                    CubeDirtyFlag.STORAGE,
                    CubeDirtyFlag.CLIENT_SYNC
            );
            markLightDirty(cubePos);
            if (context.rebuildSkyLightColumnNow() || context.markSkyLightDirty()) {
                dirtyTracker.mark(cubePos, CubeDirtyFlag.SKY_LIGHT);
                skyLightQueued = markSkyLightDirty(cubePos.columnPos());
            }
            // M14.1: even if the caller asks for immediate save/light, gameplay mutations only enqueue the work here.
            // Static light, content flags, column indexes and Region3D save are budgeted later in the server tick.
        }

        CubeMutationResult result = new CubeMutationResult(true, changed, statusPromoted, holderLoaded, holderGenerated, saved,
                staticLightRebuilt, skyLightRebuilt, skyLightQueued, elapsedMicrosSince(startNanos), worldPos, cubePos,
                localPos, previous, state, context.origin(), context.reason());
        blockUpdatePipeline.record(result);
        return result;
    }

    private static long elapsedMicrosSince(long startNanos) {
        return Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
    }

    /** Marks a loaded cube as needing M9 static block-light rebuild. */
    public synchronized boolean markLightDirty(CubePos cubePos) {
        if (!settings.containsCubeY(cubePos.y())) {
            return false;
        }
        if (!holders.containsKey(cubePos)) {
            return false;
        }
        lightDirtyQueue.add(cubePos);
        dirtyTracker.mark(cubePos, CubeDirtyFlag.STATIC_LIGHT, CubeDirtyFlag.STORAGE);
        return true;
    }

    /** Marks an X/Z column as needing delayed M10.1 vertical sky-light rebuild. */
    public synchronized boolean markSkyLightDirty(ColumnPos columnPos) {
        if (!hasLoadedCubeInColumn(columnPos)) {
            return false;
        }
        long dueGameTime = gameTime + SKY_LIGHT_DIRTY_DELAY_TICKS;
        Long previousDue = skyLightDirtyColumns.get(columnPos);
        if (previousDue == null || previousDue < dueGameTime) {
            skyLightDirtyColumns.put(columnPos, dueGameTime);
        }
        return true;
    }

    public synchronized Optional<StaticBlockLightLayer.RebuildResult> rebuildLight(CubePos cubePos, boolean saveImmediately) {
        if (!settings.containsCubeY(cubePos.y())) {
            return Optional.empty();
        }
        CubeHolder holder = holders.get(cubePos);
        if (holder == null) {
            holder = loadHolder(cubePos, CubeTicketLevel.LIGHT_READY);
            holders.put(cubePos, holder);
            totalLoaded++;
            if (holder.state() == CubeHolderState.GENERATED) {
                totalGenerated++;
            }
        }
        StaticBlockLightLayer.RebuildResult result = rebuildLightNow(holder);
        holder.markDirty();
        if (saveImmediately) {
            storage.put(holder.cube());
            holder.markSaved(CubeHolderState.REGION3D_SAVED);
            totalSaved++;
        }
        return Optional.of(result);
    }

    public synchronized Optional<SkyLightLayer.CubeRebuildResult> rebuildSkyLightCube(CubePos cubePos, boolean saveImmediately) {
        if (!settings.containsCubeY(cubePos.y())) {
            return Optional.empty();
        }
        CubeHolder holder = holders.get(cubePos);
        if (holder == null) {
            holder = loadHolder(cubePos, CubeTicketLevel.LIGHT_READY);
            holders.put(cubePos, holder);
            totalLoaded++;
            if (holder.state() == CubeHolderState.GENERATED) {
                totalGenerated++;
            }
        }
        SkyLightLayer.CubeRebuildResult result = SkyLightLayer.rebuildSingleCubeFromOpenSky(holder.cube());
        holder.markDirty();
        totalSkyLightRebuilt++;
        if (saveImmediately) {
            storage.put(holder.cube());
            holder.markSaved(CubeHolderState.REGION3D_SAVED);
            totalSaved++;
        }
        return Optional.of(result);
    }

    public synchronized Optional<SkyLightLayer.ColumnRebuildResult> rebuildSkyLightColumn(ColumnPos columnPos, int minCubeY, int maxCubeY, boolean saveImmediately) {
        int minY = Math.max(settings.minCubeY(), Math.min(minCubeY, maxCubeY));
        int maxY = Math.min(settings.maxCubeY(), Math.max(minCubeY, maxCubeY));
        if (minY > maxY) {
            return Optional.empty();
        }

        for (int cubeY = minY; cubeY <= maxY; cubeY++) {
            CubePos cubePos = new CubePos(columnPos.x(), cubeY, columnPos.z());
            CubeHolder holder = holders.get(cubePos);
            if (holder != null) {
                continue;
            }
            holder = loadHolder(cubePos, CubeTicketLevel.LIGHT_READY);
            holders.put(cubePos, holder);
            totalLoaded++;
            if (holder.state() == CubeHolderState.GENERATED) {
                totalGenerated++;
            }
        }

        return rebuildSkyLightColumnLoaded(columnPos, saveImmediately, true);
    }

    public synchronized Optional<SkyLightLayer.ColumnRebuildResult> rebuildSkyLightColumnLoaded(ColumnPos columnPos, boolean saveImmediately) {
        return rebuildSkyLightColumnLoaded(columnPos, saveImmediately, true);
    }

    private synchronized Optional<SkyLightLayer.ColumnRebuildResult> rebuildSkyLightColumnLoaded(ColumnPos columnPos, boolean saveImmediately, boolean markDirtyChanged) {
        List<CubeHolder> columnHolders = loadedColumnHolders(columnPos);
        if (columnHolders.isEmpty()) {
            skyLightDirtyColumns.remove(columnPos);
            return Optional.empty();
        }

        Map<CubePos, Long> beforeHashes = new LinkedHashMap<>();
        for (CubeHolder holder : columnHolders) {
            beforeHashes.put(holder.cubePos(), SkyLightSummary.from(holder.cube()).hash());
        }

        List<LevelCube> cubes = columnHolders.stream()
                .map(CubeHolder::cube)
                .toList();
        long startNanos = System.nanoTime();
        SkyLightLayer.ColumnRebuildResult rawResult = SkyLightLayer.rebuildColumn(cubes);
        long elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);

        int changed = 0;
        int unchanged = 0;
        for (CubeHolder holder : columnHolders) {
            long beforeHash = beforeHashes.getOrDefault(holder.cubePos(), Long.MIN_VALUE);
            long afterHash = SkyLightSummary.from(holder.cube()).hash();
            if (beforeHash == afterHash) {
                unchanged++;
                continue;
            }
            changed++;
            if (markDirtyChanged) {
                holder.markDirty();
                dirtyTracker.mark(holder.cubePos(), CubeDirtyFlag.SKY_LIGHT, CubeDirtyFlag.STORAGE, CubeDirtyFlag.CLIENT_SYNC);
            }
        }

        int saved = 0;
        if (saveImmediately) {
            for (CubeHolder holder : columnHolders) {
                if (!holder.dirty()) {
                    continue;
                }
                storage.put(holder.cube());
                holder.markSaved(CubeHolderState.REGION3D_SAVED);
                dirtyTracker.recordSaved(holder.cubePos(), 0L);
                saved++;
            }
            totalSaved += saved;
        }

        SkyLightLayer.ColumnRebuildResult result = rawResult.withRuntimeStats(changed, unchanged, saved, elapsedMicros);
        totalSkyLightRebuilt += result.rebuiltCubes();
        totalSkyLightColumnsRebuilt++;
        totalSkyLightSkippedUnchanged += unchanged;
        totalSkyLightSavedChanged += saved;
        skyLightDirtyColumns.remove(columnPos);
        return Optional.of(result);
    }

    public synchronized SkyLightSummary skyLightSummary(CubePos cubePos) {
        Optional<CubeHolder> holder = holder(cubePos);
        if (holder.isPresent()) {
            return SkyLightSummary.from(holder.get().cube());
        }
        Optional<LevelCube> persisted = readPersisted(cubePos);
        if (persisted.isPresent()) {
            return SkyLightSummary.from(persisted.get());
        }
        LevelCube generated = generateTemporary(cubePos);
        SkyLightLayer.rebuildSingleCubeFromOpenSky(generated);
        return SkyLightSummary.from(generated);
    }

    public synchronized Optional<ColumnSkyIndex> columnSkyIndex(ColumnPos columnPos) {
        List<CubeHolder> columnHolders = loadedColumnHolders(columnPos);
        if (columnHolders.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ColumnSkyIndex.build(columnPos, columnHolders));
    }

    public synchronized StaticLightSummary lightSummary(CubePos cubePos) {
        Optional<CubeHolder> holder = holder(cubePos);
        if (holder.isPresent()) {
            return StaticLightSummary.from(holder.get().cube());
        }
        Optional<LevelCube> persisted = readPersisted(cubePos);
        if (persisted.isPresent()) {
            return StaticLightSummary.from(persisted.get());
        }
        LevelCube generated = generateTemporary(cubePos);
        StaticBlockLightLayer.rebuild(generated);
        return StaticLightSummary.from(generated);
    }


    /**
     * M8.1 debug helper: reads a cube from durable Region3D storage without creating a generated holder.
     *
     * <p>This is used by persistence-check commands to prove that edited cubes survived unload/reload and are no
     * longer just deterministic generator output.</p>
     */
    public synchronized Optional<LevelCube> readPersisted(CubePos cubePos) {
        if (!settings.containsCubeY(cubePos.y())) {
            return Optional.empty();
        }
        return storage.get(cubePos);
    }




    /**
     * M13.1 throttle probe: tells the pregen manager whether the next request is likely to run the expensive generator.
     * This is intentionally conservative; dirty loaded cubes are treated as skip so player edits are never overwritten by
     * background pregen.
     */
    public synchronized boolean wouldPregenGenerate(CubePos cubePos, CubeStatus targetStatus) {
        Objects.requireNonNull(cubePos, "cubePos");
        Objects.requireNonNull(targetStatus, "targetStatus");
        if (!settings.containsCubeY(cubePos.y())) {
            return false;
        }
        CubeHolder existing = holders.get(cubePos);
        if (existing != null) {
            return !existing.cube().status().isAtLeast(targetStatus) && !existing.dirty();
        }
        Optional<LevelCube> persisted = storage.get(cubePos);
        if (persisted.isPresent() && persisted.get().status().isAtLeast(targetStatus)) {
            storage.unloadFromMemory(cubePos);
            return false;
        }
        storage.unloadFromMemory(cubePos);
        return targetStatus != CubeStatus.EMPTY;
    }

    /**
     * M13.0 manual pregen entry point.
     *
     * <p>This prepares a single cube in the cube-first backend and saves it to Region3D without creating gameplay
     * tickets, entity ticking, random ticks, mob spawning or block entity logic. If the cube is already loaded or saved at
     * the requested status, it is counted as skipped by the pregen manager.</p>
     */
    public synchronized PregenCubeResult pregenCube(CubePos cubePos, CubeStatus targetStatus, boolean saveImmediately) {
        Objects.requireNonNull(cubePos, "cubePos");
        Objects.requireNonNull(targetStatus, "targetStatus");
        if (!settings.containsCubeY(cubePos.y())) {
            return new PregenCubeResult(cubePos, false, false, false, "outside_settings");
        }

        CubeHolder existing = holders.get(cubePos);
        if (existing != null && existing.cube().status().isAtLeast(targetStatus)) {
            boolean saved = false;
            if (saveImmediately && existing.dirty()) {
                storage.put(existing.cube());
                existing.markSaved(CubeHolderState.REGION3D_SAVED);
                totalSaved++;
                saved = true;
            }
            return new PregenCubeResult(cubePos, false, saved, false, "already_loaded");
        }
        if (existing != null && existing.dirty()) {
            return new PregenCubeResult(cubePos, false, false, false, "loaded_dirty_skip");
        }

        Optional<LevelCube> persisted = storage.get(cubePos);
        if (persisted.isPresent() && persisted.get().status().isAtLeast(targetStatus)) {
            if (existing == null) {
                storage.unloadFromMemory(cubePos);
            }
            return new PregenCubeResult(cubePos, false, false, false, "already_persisted");
        }

        LevelCube prepared = targetStatus == CubeStatus.EMPTY ? new LevelCube(cubePos) : generator.generate(cubePos);
        if (targetStatus == CubeStatus.EMPTY) {
            prepared.setStatus(CubeStatus.EMPTY);
        }
        StaticBlockLightLayer.rebuild(prepared);
        totalLightRebuilt++;
        if (targetStatus.isAtLeast(CubeStatus.LIGHT_READY)) {
            SkyLightLayer.rebuildSingleCubeFromOpenSky(prepared);
            totalSkyLightRebuilt++;
        }

        boolean saved = false;
        if (saveImmediately) {
            storage.put(prepared);
            totalSaved++;
            saved = true;
        }
        totalGenerated++;

        if (existing != null) {
            CubeHolder replacement = new CubeHolder(cubePos, prepared, existing.ticketLevel(), CubeHolderState.REGION3D_SAVED, gameTime);
            replacement.markRequired(existing.ticketLevel(), gameTime);
            holders.put(cubePos, replacement);
        } else {
            storage.unloadFromMemory(cubePos);
        }
        return new PregenCubeResult(cubePos, true, saved, targetStatus.isAtLeast(CubeStatus.LIGHT_READY), "generated");
    }

    public synchronized CubeLoadingSnapshot snapshot() {
        Map<CubeTicketLevel, Integer> byTicketLevel = new EnumMap<>(CubeTicketLevel.class);
        Map<CubeStatus, Integer> byCubeStatus = new EnumMap<>(CubeStatus.class);
        Map<CubeHolderState, Integer> byHolderState = new EnumMap<>(CubeHolderState.class);

        for (CubeHolder holder : holders.values()) {
            byTicketLevel.merge(holder.ticketLevel(), 1, Integer::sum);
            byCubeStatus.merge(holder.cube().status(), 1, Integer::sum);
            byHolderState.merge(holder.state(), 1, Integer::sum);
        }

        return new CubeLoadingSnapshot(
                holders.size(),
                pendingLoads.size(),
                requestedLastTick,
                totalLoaded,
                totalUnloaded,
                totalSaved,
                totalGenerated,
                totalLightRebuilt,
                lightRebuiltLastTick,
                lightDirtyLastTick,
                totalSkyLightRebuilt,
                totalSkyLightColumnsRebuilt,
                totalSkyLightSkippedUnchanged,
                totalSkyLightSavedChanged,
                skyLightColumnsLastTick,
                skyLightDirtyLastTick,
                skyLightChangedLastTick,
                skyLightSkippedUnchangedLastTick,
                skyLightSavedChangedLastTick,
                skyLightRebuildMicrosLastTick,
                skyLightRebuildMicrosMax,
                MAX_SKY_LIGHT_COLUMNS_PER_TICK,
                SKY_LIGHT_DIRTY_DELAY_TICKS,
                loadedLastTick,
                generatedLastTick,
                unloadedLastTick,
                queuedLastTick,
                loadMicrosLastTick,
                loadMicrosMax,
                MAX_LOADS_PER_TICK,
                MAX_GENERATED_LOADS_PER_TICK,
                MAX_LOAD_MICROS_PER_TICK,
                loadGeneratedBudgetHitLastTick,
                loadTimeBudgetHitLastTick,
                requestLimitHitLastTick,
                mutationSnapshot(),
                dirtyTracker.snapshot(),
                Map.copyOf(byTicketLevel),
                Map.copyOf(byCubeStatus),
                Map.copyOf(byHolderState)
        );
    }

    public synchronized int saveAllLoaded() {
        int saved = 0;
        for (CubeHolder holder : holders.values()) {
            if (shouldSkipDebugSave(holder)) {
                continue;
            }
            storage.put(holder.cube());
            holder.markSaved(CubeHolderState.REGION3D_SAVED);
            saved++;
        }
        totalSaved += saved;
        return saved;
    }

    public synchronized int unloadAllLoaded(boolean saveDirty) {
        int unloaded = 0;
        for (CubeHolder holder : new ArrayList<>(holders.values())) {
            unloadHolder(holder, saveDirty);
            unloaded++;
        }
        pendingLoads.clear();
        skyLightDirtyColumns.clear();
        dirtyTracker.clearQueues();
        return unloaded;
    }

    private RequiredLevels collectRequiredLevels(Collection<CubeTicket> tickets) {
        Map<CubePos, CubeTicketLevel> required = new LinkedHashMap<>();
        boolean limitHit = false;

        for (CubeTicket ticket : tickets) {
            if (ticket.level() == CubeTicketLevel.UNLOADED) {
                continue;
            }
            Iterator<CubePos> iterator = ticket.shape().stream().iterator();
            while (iterator.hasNext()) {
                CubePos cubePos = iterator.next();
                if (!settings.containsCubeY(cubePos.y())) {
                    continue;
                }
                required.merge(cubePos, ticket.level(), ServerCubeCache::strongerLevel);
                if (required.size() >= MAX_REQUESTED_CUBES_PER_TICK) {
                    limitHit = true;
                    return new RequiredLevels(required, true);
                }
            }
        }

        return new RequiredLevels(required, limitHit);
    }

    private void purgePendingNoLongerRequired(Map<CubePos, CubeTicketLevel> required) {
        pendingLoads.keySet().removeIf(cubePos -> !required.containsKey(cubePos));
    }

    private int queueMissingAndRefreshLoaded(Map<CubePos, CubeTicketLevel> required) {
        int queued = 0;
        for (Map.Entry<CubePos, CubeTicketLevel> entry : required.entrySet()) {
            CubeHolder holder = holders.get(entry.getKey());
            if (holder != null) {
                holder.markRequired(entry.getValue(), gameTime);
                continue;
            }

            CubeTicketLevel previous = pendingLoads.get(entry.getKey());
            if (previous == null) {
                pendingLoads.put(entry.getKey(), entry.getValue());
                queued++;
            } else {
                pendingLoads.put(entry.getKey(), strongerLevel(previous, entry.getValue()));
            }
        }
        return queued;
    }

    private LoadCounters loadPending(Map<CubePos, CubeTicketLevel> required) {
        int loaded = 0;
        int generated = 0;
        boolean generatedBudgetHit = false;
        boolean timeBudgetHit = false;
        long startNanos = System.nanoTime();
        long elapsedMicros = 0L;
        Set<ColumnPos> touchedColumns = new LinkedHashSet<>();
        Iterator<Map.Entry<CubePos, CubeTicketLevel>> iterator = pendingLoads.entrySet().iterator();
        while (iterator.hasNext() && loaded < MAX_LOADS_PER_TICK) {
            Map.Entry<CubePos, CubeTicketLevel> entry = iterator.next();
            CubeTicketLevel requiredLevel = required.get(entry.getKey());
            if (requiredLevel == null) {
                iterator.remove();
                continue;
            }
            if (holders.containsKey(entry.getKey())) {
                iterator.remove();
                continue;
            }

            CubeHolder holder = loadHolder(entry.getKey(), strongerLevel(entry.getValue(), requiredLevel));
            holders.put(holder.cubePos(), holder);
            touchedColumns.add(holder.cubePos().columnPos());
            iterator.remove();
            loaded++;
            totalLoaded++;
            if (holder.state() == CubeHolderState.GENERATED) {
                generated++;
                totalGenerated++;
            }

            elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
            if (generated >= MAX_GENERATED_LOADS_PER_TICK) {
                generatedBudgetHit = iterator.hasNext();
                break;
            }
            if (elapsedMicros >= MAX_LOAD_MICROS_PER_TICK) {
                timeBudgetHit = iterator.hasNext();
                break;
            }
        }
        if (loaded == 0) {
            elapsedMicros = 0L;
        } else if (elapsedMicros == 0L) {
            elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
        }
        for (ColumnPos columnPos : touchedColumns) {
            markSkyLightDirty(columnPos);
        }
        return new LoadCounters(loaded, generated, elapsedMicros, generatedBudgetHit, timeBudgetHit);
    }

    private CubeHolder loadHolder(CubePos cubePos, CubeTicketLevel level) {
        Optional<LevelCube> loaded = storage.get(cubePos);
        if (loaded.isPresent()) {
            LevelCube cube = loaded.get();
            if (StaticBlockLightLayer.needsBootstrap(cube)) {
                StaticBlockLightLayer.rebuild(cube);
                totalLightRebuilt++;
            }
            return new CubeHolder(cubePos, cube, level, CubeHolderState.REGION3D_LOADED, gameTime);
        }

        if (level.isAtLeast(CubeTicketLevel.GENERATED)) {
            LevelCube generated = generator.generate(cubePos);
            StaticBlockLightLayer.rebuild(generated);
            // M10.1: do not rebuild sky per cube while the loading window streams in.
            // The delayed column queue will compute correct finite-top skylight once the column settles.
            totalLightRebuilt++;
            return new CubeHolder(cubePos, generated, level, CubeHolderState.GENERATED, gameTime);
        }

        LevelCube placeholder = new LevelCube(cubePos);
        placeholder.setStatus(CubeStatus.EMPTY);
        StaticBlockLightLayer.rebuild(placeholder);
        // M10.1: placeholders also wait for the delayed column queue instead of doing per-cube open-sky work.
        totalLightRebuilt++;
        return new CubeHolder(cubePos, placeholder, level, CubeHolderState.PLACEHOLDER, gameTime);
    }

    private int unloadNoLongerRequired(Map<CubePos, CubeTicketLevel> required) {
        int unloaded = 0;
        for (CubeHolder holder : new ArrayList<>(holders.values())) {
            if (required.containsKey(holder.cubePos())) {
                continue;
            }
            if (holder.ticksSinceRequired(gameTime) < UNLOAD_GRACE_TICKS) {
                continue;
            }
            unloadHolder(holder, true);
            unloaded++;
        }
        return unloaded;
    }

    private static boolean shouldSkipDebugSave(CubeHolder holder) {
        // M7 generated cubes are deterministic and clean by default, so debug save_all should not write thousands of
        // untouched terrain cubes into Region3D. Later block edits will mark holders dirty and save normally.
        return !holder.dirty();
    }

    private void unloadHolder(CubeHolder holder, boolean saveDirty) {
        if (saveDirty && holder.dirty()) {
            storage.put(holder.cube());
            holder.markSaved(CubeHolderState.REGION3D_SAVED);
            totalSaved++;
        }
        holders.remove(holder.cubePos());
        lightDirtyQueue.remove(holder.cubePos());
        dirtyTracker.remove(holder.cubePos());
        if (loadedColumnHolders(holder.cubePos().columnPos()).isEmpty()) {
            skyLightDirtyColumns.remove(holder.cubePos().columnPos());
        }
        storage.unloadFromMemory(holder.cubePos());
        totalUnloaded++;
    }

    private int rebuildDirtyLightQueue() {
        int rebuilt = 0;
        Iterator<CubePos> iterator = lightDirtyQueue.iterator();
        while (iterator.hasNext() && rebuilt < MAX_LIGHT_REBUILDS_PER_TICK) {
            CubePos cubePos = iterator.next();
            CubeHolder holder = holders.get(cubePos);
            iterator.remove();
            if (holder == null) {
                continue;
            }
            rebuildLightNow(holder);
            holder.markDirty();
            rebuilt++;
        }
        return rebuilt;
    }

    private SkyLightQueueCounters rebuildDirtySkyLightColumns() {
        int rebuiltColumns = 0;
        int changedCubes = 0;
        int skippedUnchangedCubes = 0;
        int savedChangedCubes = 0;
        long elapsedMicros = 0L;

        Iterator<Map.Entry<ColumnPos, Long>> iterator = skyLightDirtyColumns.entrySet().iterator();
        while (iterator.hasNext() && rebuiltColumns < MAX_SKY_LIGHT_COLUMNS_PER_TICK) {
            Map.Entry<ColumnPos, Long> entry = iterator.next();
            if (entry.getValue() > gameTime) {
                continue;
            }
            ColumnPos columnPos = entry.getKey();
            iterator.remove();
            Optional<SkyLightLayer.ColumnRebuildResult> result = rebuildSkyLightColumnLoaded(columnPos, false, true);
            if (result.isEmpty()) {
                continue;
            }
            SkyLightLayer.ColumnRebuildResult rebuild = result.get();
            rebuiltColumns++;
            changedCubes += rebuild.changedCubes();
            skippedUnchangedCubes += rebuild.unchangedCubes();
            savedChangedCubes += rebuild.savedCubes();
            elapsedMicros += rebuild.elapsedMicros();
        }

        return new SkyLightQueueCounters(rebuiltColumns, changedCubes, skippedUnchangedCubes, savedChangedCubes, elapsedMicros);
    }


    private int rebuildDirtyContentQueue() {
        int rebuilt = 0;
        for (CubePos cubePos : dirtyTracker.pollContentWork()) {
            CubeHolder holder = holders.get(cubePos);
            if (holder == null) {
                dirtyTracker.remove(cubePos);
                continue;
            }
            long startNanos = System.nanoTime();
            CubeContentSummary summary = CubeContentSummary.from(holder.cube());
            long elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
            dirtyTracker.recordContent(cubePos, summary, elapsedMicros);
            rebuilt++;
        }
        return rebuilt;
    }

    private int flushDirtySaveQueue() {
        int saved = 0;
        long startNanos = System.nanoTime();
        List<CubePos> batch = dirtyTracker.pollSaveWork(0L);
        for (int index = 0; index < batch.size(); index++) {
            CubePos cubePos = batch.get(index);
            CubeHolder holder = holders.get(cubePos);
            if (holder == null) {
                dirtyTracker.remove(cubePos);
                continue;
            }
            if (!holder.dirty()) {
                dirtyTracker.clean(cubePos, CubeDirtyFlag.STORAGE);
                continue;
            }
            long saveStartNanos = System.nanoTime();
            storage.put(holder.cube());
            holder.markSaved(CubeHolderState.REGION3D_SAVED);
            long saveMicros = Math.max(1L, (System.nanoTime() - saveStartNanos) / 1_000L);
            totalSaved++;
            dirtyTracker.recordSaved(cubePos, saveMicros);
            saved++;

            long elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
            if (dirtyTracker.shouldStopSaving(elapsedMicros, saved)) {
                for (int remaining = index + 1; remaining < batch.size(); remaining++) {
                    dirtyTracker.requeueSave(batch.get(remaining));
                }
                break;
            }
        }
        return saved;
    }

    private int saveDirtyLoadedColumn(ColumnPos columnPos) {
        int saved = 0;
        for (CubeHolder holder : loadedColumnHolders(columnPos)) {
            if (!holder.dirty()) {
                continue;
            }
            storage.put(holder.cube());
            holder.markSaved(CubeHolderState.REGION3D_SAVED);
            saved++;
        }
        totalSaved += saved;
        return saved;
    }

    private boolean hasLoadedCubeInColumn(ColumnPos columnPos) {
        for (CubePos cubePos : holders.keySet()) {
            if (cubePos.x() == columnPos.x() && cubePos.z() == columnPos.z()) {
                return true;
            }
        }
        return false;
    }

    private List<CubeHolder> loadedColumnHolders(ColumnPos columnPos) {
        return holders.values().stream()
                .filter(holder -> holder.cubePos().x() == columnPos.x() && holder.cubePos().z() == columnPos.z())
                .sorted(Comparator.comparingInt((CubeHolder holder) -> holder.cubePos().y()).reversed())
                .toList();
    }

    private StaticBlockLightLayer.RebuildResult rebuildLightNow(CubeHolder holder) {
        StaticBlockLightLayer.RebuildResult result = StaticBlockLightLayer.rebuild(holder.cube());
        lightDirtyQueue.remove(holder.cubePos());
        dirtyTracker.clean(holder.cubePos(), CubeDirtyFlag.STATIC_LIGHT);
        totalLightRebuilt++;
        return result;
    }

    private static CubeTicketLevel strongerLevel(CubeTicketLevel first, CubeTicketLevel second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }



    public record PregenCubeResult(CubePos cubePos, boolean generated, boolean saved, boolean lightReady, String reason) {
    }

    private record RequiredLevels(Map<CubePos, CubeTicketLevel> levels, boolean limitHit) {
    }

    private record LoadCounters(int loaded, int generated, long elapsedMicros, boolean generatedBudgetHit, boolean timeBudgetHit) {
    }

    private record SkyLightQueueCounters(int rebuiltColumns, int changedCubes, int skippedUnchangedCubes, int savedChangedCubes, long elapsedMicros) {
    }
}
