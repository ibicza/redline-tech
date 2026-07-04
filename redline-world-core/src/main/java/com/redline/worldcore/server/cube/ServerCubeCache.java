package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.cube.CubeScheduledTickData;
import com.redline.worldcore.api.cube.CubeScheduledTickKind;
import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.generation.CubeGenerationContext;
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
import com.redline.worldcore.server.cube.blockentity.CubeBlockEntityRef;
import com.redline.worldcore.server.cube.blockentity.CubeBlockEntitySnapshot;
import com.redline.worldcore.server.cube.blockentity.CubeBlockEntityTracker;
import com.redline.worldcore.server.cube.dirty.CubeAsyncSaveWorker;
import com.redline.worldcore.server.cube.dirty.CubeContentSummary;
import com.redline.worldcore.server.cube.dirty.CubeDirtyFlag;
import com.redline.worldcore.server.cube.dirty.CubeDirtyTracker;
import com.redline.worldcore.server.cube.dirty.CubeSaveWork;
import com.redline.worldcore.server.cube.tick.CubeScheduledTickSnapshot;
import com.redline.worldcore.server.cube.tick.CubeScheduledTickTracker;
import com.redline.worldcore.server.generation.CubicWorldgenPipeline;
import com.redline.worldcore.server.lighting.ColumnSkyIndex;
import com.redline.worldcore.server.lighting.SkyLightLayer;
import com.redline.worldcore.server.lighting.SkyLightSummary;
import com.redline.worldcore.server.lighting.StaticBlockLightLayer;
import com.redline.worldcore.server.lighting.StaticLightSummary;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import com.redline.worldcore.server.storage.CubeRegionStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    /** Soft M14.1 save submit budget. Actual Region3D IO is done by the M14.1.1 worker. */
    public static final int MAX_DIRTY_SAVE_MICROS_PER_TICK = 4_000;

    /** If a finished async write took longer than this, idle save submission cools down. */
    public static final int EXPENSIVE_DIRTY_SAVE_MICROS = 20_000;

    /** Cooldown after an expensive Region3D write, measured in server ticks. */
    public static final int DIRTY_SAVE_COOLDOWN_TICKS = 20;

    /** Maximum async Region3D writes allowed in flight from the gameplay dirty pipeline. */
    public static final int MAX_ASYNC_DIRTY_SAVES_IN_FLIGHT = 2;

    /** M14.5.1 caps server-thread processing of completed async saves so completions cannot burst-freeze a tick. */
    public static final int MAX_ASYNC_SAVE_COMPLETIONS_PER_TICK = 8;

    /** Soft budget for processing async save completions on the server thread. */
    public static final int MAX_ASYNC_COMPLETION_MICROS_PER_TICK = 2_000;

    /** M14.9.1 keeps manual debug save_all from freezing the integrated server for minutes. */
    public static final int DEBUG_SAVE_ALL_MAX_SYNC_CUBES_PER_CALL = 16;

    /** M10.1 keeps automatic vertical sky-light rebuilds tiny; manual commands may still rebuild large ranges immediately. */
    public static final int MAX_SKY_LIGHT_COLUMNS_PER_TICK = 1;

    /** Bounded negative cache for deterministic generated cubes that have no Region3D entry yet. */
    public static final int STORAGE_MISS_CACHE_LIMIT = 65536;

    /** Debounces automatic sky rebuilds while player tickets are still streaming many cubes into the same columns. */
    public static final int SKY_LIGHT_DIRTY_DELAY_TICKS = 40;

    private final CubicDimensionSettings settings;
    private final CubeRegionStorage storage;
    private final CubicWorldgenPipeline generator;
    private final Map<CubePos, CubeHolder> holders = new ConcurrentHashMap<>();
    private final LinkedHashMap<CubePos, CubeTicketLevel> pendingLoads = new LinkedHashMap<>();
    private final LinkedHashSet<CubePos> storageMissCache = new LinkedHashSet<>();
    private RequiredLevels cachedRequiredLevels;
    private long cachedRequiredTicketSignature;
    private int cachedRequiredTicketCount;
    private final LinkedHashSet<CubePos> lightDirtyQueue = new LinkedHashSet<>();
    private final LinkedHashMap<ColumnPos, Long> skyLightDirtyColumns = new LinkedHashMap<>();
    private final CubeDirtyTracker dirtyTracker = new CubeDirtyTracker(
            MAX_DIRTY_CONTENT_REBUILDS_PER_TICK,
            MAX_DIRTY_SAVES_PER_TICK,
            MAX_DIRTY_SAVE_MICROS_PER_TICK,
            MAX_ASYNC_SAVE_COMPLETIONS_PER_TICK,
            MAX_ASYNC_COMPLETION_MICROS_PER_TICK
    );
    private final CubeBlockUpdatePipeline blockUpdatePipeline = new CubeBlockUpdatePipeline();
    private final CubeAsyncSaveWorker dirtySaveWorker = new CubeAsyncSaveWorker("RedlineWorldCore-DirtyCubeIO");
    private final CubeBlockEntityTracker blockEntityTracker = new CubeBlockEntityTracker();
    private final CubeScheduledTickTracker scheduledTickTracker = new CubeScheduledTickTracker();

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

    public CubeGenerationContext generationContext() {
        return generator.context();
    }

    /** Returns the M14.0 cube-first facade that new world-core code should use for block access. */
    public CubicLevelAccess access() {
        return new CubicLevelAccess(this);
    }

    public synchronized CubeMutationSnapshot mutationSnapshot() {
        return blockUpdatePipeline.snapshot();
    }

    public synchronized CubeBlockEntitySnapshot blockEntitySnapshot() {
        return blockEntityTracker.snapshot();
    }

    public synchronized CubeScheduledTickSnapshot scheduledTickSnapshot() {
        return scheduledTickTracker.snapshot();
    }

    public synchronized Optional<CubeBlockEntityRef> blockEntityAt(BlockPos worldPos) {
        CubePos cubePos = CubePos.fromBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        int localIndex = CubePos.localIndex(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()));
        return blockEntityTracker.get(cubePos, localIndex);
    }

    public synchronized boolean captureBlockEntityFromVanilla(ServerLevel level, BlockPos worldPos, String reason) {
        Objects.requireNonNull(worldPos, "worldPos");
        CubePos cubePos = CubePos.fromBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        CubeHolder holder = holders.get(cubePos);
        if (holder == null) {
            return false;
        }
        boolean captured = blockEntityTracker.captureVanilla(holder.cube(), level, worldPos, reason);
        if (captured) {
            holder.markDirty();
            dirtyTracker.mark(cubePos, CubeDirtyFlag.CONTENT_FLAGS, CubeDirtyFlag.STORAGE, CubeDirtyFlag.CLIENT_SYNC);
        }
        return captured;
    }

    public synchronized boolean clientSyncDirty(CubePos cubePos) {
        return dirtyTracker.clientSyncDirty(cubePos);
    }

    public synchronized void recordClientMirrorSynced(CubePos cubePos) {
        dirtyTracker.recordClientSyncClean(cubePos);
    }

    public synchronized Optional<Map<Integer, net.minecraft.nbt.CompoundTag>> blockEntityTags(CubePos cubePos) {
        CubeHolder holder = holders.get(cubePos);
        if (holder != null) {
            return Optional.of(holder.cube().copyBlockEntityData());
        }
        Optional<LevelCube> persisted = readPersisted(cubePos);
        return persisted.map(LevelCube::copyBlockEntityData);
    }

    public synchronized boolean addScheduledTick(BlockPos worldPos, CubeScheduledTickKind kind, String targetId, int delayTicks, int priority, String reason) {
        Objects.requireNonNull(worldPos, "worldPos");
        Objects.requireNonNull(kind, "kind");
        CubePos cubePos = CubePos.fromBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        if (!settings.containsCubeY(cubePos.y())) {
            return false;
        }
        CubeHolder holder = holders.get(cubePos);
        if (holder == null) {
            holder = loadHolder(cubePos, CubeTicketLevel.BLOCK_TICKING);
            holders.put(cubePos, holder);
            totalLoaded++;
            if (holder.state() == CubeHolderState.GENERATED) {
                totalGenerated++;
            }
        }
        CubeScheduledTickData tick = CubeScheduledTickData.create(kind, cubePos, worldPos, targetId, gameTime + Math.max(0, delayTicks), priority, reason);
        holder.cube().addScheduledTick(tick);
        holder.markDirty();
        dirtyTracker.mark(cubePos, CubeDirtyFlag.CONTENT_FLAGS, CubeDirtyFlag.STORAGE, CubeDirtyFlag.CLIENT_SYNC);
        scheduledTickTracker.recordAdded(cubePos, kind, reason);
        return true;
    }

    public synchronized int clearScheduledTicks(CubePos cubePos) {
        CubeHolder holder = holders.get(cubePos);
        if (holder == null) {
            return 0;
        }
        int removed = holder.cube().clearScheduledTicks();
        if (removed > 0) {
            holder.markDirty();
            dirtyTracker.mark(cubePos, CubeDirtyFlag.CONTENT_FLAGS, CubeDirtyFlag.STORAGE, CubeDirtyFlag.CLIENT_SYNC);
            scheduledTickTracker.recordRemoved(cubePos, removed, "clear_cube");
        }
        return removed;
    }

    public synchronized List<CubeScheduledTickData> scheduledTicks(CubePos cubePos) {
        CubeHolder holder = holders.get(cubePos);
        LevelCube cube = holder == null ? null : holder.cube();
        if (cube == null) {
            Optional<LevelCube> persisted = readPersisted(cubePos);
            if (persisted.isEmpty()) {
                return List.of();
            }
            cube = persisted.get();
        }
        List<CubeScheduledTickData> ticks = new ArrayList<>();
        ticks.addAll(cube.copyScheduledBlockTicks());
        ticks.addAll(cube.copyScheduledFluidTicks());
        return ticks;
    }

    public synchronized void resetMutationCounters() {
        blockUpdatePipeline.reset();
    }


    public synchronized CubeLoadingTickResult tick(Collection<CubeTicket> tickets) {
        return tick(tickets, null);
    }

    public synchronized CubeLoadingTickResult tick(Collection<CubeTicket> tickets, ServerLevel level) {
        long tickProfileStart = RuntimeProfiler.markStart();
        gameTime++;

        long phaseStart = RuntimeProfiler.markStart();
        dirtyTracker.beginTick();
        blockEntityTracker.beginTick();
        scheduledTickTracker.beginTick();
        drainAsyncDirtySaveCompletions(false);
        RuntimeProfiler.recordSince("cube_loading.tick_begin", phaseStart);

        phaseStart = RuntimeProfiler.markStart();
        RequiredLevels required = collectRequiredLevelsCached(tickets);
        RuntimeProfiler.recordSince("cube_loading.collect_required", phaseStart);
        requestedLastTick = required.levels().size();
        requestLimitHitLastTick = required.limitHit();
        RuntimeProfiler.addCount("cube_loading.requested_cubes", requestedLastTick);
        RuntimeProfiler.addCount("cube_loading.input_tickets", tickets.size());

        phaseStart = RuntimeProfiler.markStart();
        purgePendingNoLongerRequired(required.levels());
        RuntimeProfiler.recordSince("cube_loading.purge_pending", phaseStart);

        phaseStart = RuntimeProfiler.markStart();
        int queuedThisTick = queueMissingAndRefreshLoaded(required.levels());
        RuntimeProfiler.recordSince("cube_loading.queue_missing", phaseStart);

        phaseStart = RuntimeProfiler.markStart();
        reprioritizePendingLoads(required.levels(), level);
        RuntimeProfiler.recordSince("cube_loading.reprioritize_pending", phaseStart);

        phaseStart = RuntimeProfiler.markStart();
        int unloadedThisTick = unloadNoLongerRequired(required.levels());
        RuntimeProfiler.recordSince("cube_loading.unload_no_longer_required", phaseStart);

        LoadCounters loadCounters = loadPending(required.levels());

        phaseStart = RuntimeProfiler.markStart();
        int capturedBlockEntities = captureVanillaBlockEntities(level);
        RuntimeProfiler.recordSince("cube_loading.capture_block_entities", phaseStart);
        RuntimeProfiler.addCount("cube_loading.captured_block_entities", capturedBlockEntities);
        if (capturedBlockEntities > 0) {
            // Capturing real vanilla NBT changes CubeNBT-owned BE data and must be persisted/indexed.
        }

        phaseStart = RuntimeProfiler.markStart();
        updateBlockEntityTickGates();
        updateScheduledTickGates();
        RuntimeProfiler.recordSince("cube_loading.update_tick_gates", phaseStart);

        phaseStart = RuntimeProfiler.markStart();
        int rebuiltContentThisTick = rebuildDirtyContentQueue();
        RuntimeProfiler.recordSince("cube_loading.rebuild_dirty_content", phaseStart);

        phaseStart = RuntimeProfiler.markStart();
        int rebuiltLightThisTick = rebuildDirtyLightQueue();
        RuntimeProfiler.recordSince("cube_loading.rebuild_dirty_light", phaseStart);

        phaseStart = RuntimeProfiler.markStart();
        SkyLightQueueCounters skyCounters = rebuildDirtySkyLightColumns();
        RuntimeProfiler.recordSince("cube_loading.rebuild_dirty_sky", phaseStart);

        phaseStart = RuntimeProfiler.markStart();
        int savedDirtyThisTick = flushDirtySaveQueue(loadCounters, rebuiltContentThisTick, rebuiltLightThisTick, skyCounters);
        RuntimeProfiler.recordSince("cube_loading.flush_dirty_save", phaseStart);

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

        RuntimeProfiler.addCount("cube_loading.queued", queuedThisTick);
        RuntimeProfiler.addCount("cube_loading.loaded", loadCounters.loaded());
        RuntimeProfiler.addCount("cube_loading.generated", loadCounters.generated());
        RuntimeProfiler.addCount("cube_loading.unloaded", unloadedThisTick);
        RuntimeProfiler.addCount("cube_loading.rebuilt_content", rebuiltContentThisTick);
        RuntimeProfiler.addCount("cube_loading.rebuilt_static_light", rebuiltLightThisTick);
        RuntimeProfiler.addCount("cube_loading.rebuilt_sky_columns", skyCounters.rebuiltColumns());
        RuntimeProfiler.addCount("cube_loading.saved_dirty", savedDirtyThisTick);
        RuntimeProfiler.addCount("cube_loading.pending_after_tick", pendingLoads.size());
        RuntimeProfiler.recordSince("cube_loading.tick_total", tickProfileStart);

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

        Optional<LevelCube> persisted = storageGet(cubePos);
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
            blockEntityTracker.observeMutation(holder.cube(), worldPos, previous, state, context.reason());
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
            rememberStoragePresent(holder.cubePos());
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
            rememberStoragePresent(holder.cubePos());
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
                rememberStoragePresent(holder.cubePos());
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
        return storageGet(cubePos);
    }

    private Optional<LevelCube> storageGet(CubePos cubePos) {
        if (storageMissCache.remove(cubePos)) {
            // Reinsert to keep simple LRU behaviour for recently checked generated-missing cubes.
            storageMissCache.add(cubePos);
            RuntimeProfiler.addCount("cube_io.storage_miss_cache_hits", 1);
            return Optional.empty();
        }

        long indexStart = RuntimeProfiler.markStart();
        if (!storage.hasCube(cubePos)) {
            RuntimeProfiler.recordSince("cube_io.storage_index_probe", indexStart);
            RuntimeProfiler.addCount("cube_io.storage_index_misses", 1);
            rememberStorageMiss(cubePos);
            return Optional.empty();
        }
        RuntimeProfiler.recordSince("cube_io.storage_index_probe", indexStart);
        RuntimeProfiler.addCount("cube_io.storage_index_hits", 1);

        Optional<LevelCube> loaded = storage.get(cubePos);
        if (loaded.isPresent()) {
            storageMissCache.remove(cubePos);
            return loaded;
        }
        rememberStorageMiss(cubePos);
        return Optional.empty();
    }

    private void rememberStorageMiss(CubePos cubePos) {
        storageMissCache.add(cubePos);
        if (storageMissCache.size() > STORAGE_MISS_CACHE_LIMIT) {
            Iterator<CubePos> iterator = storageMissCache.iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        RuntimeProfiler.addCount("cube_io.storage_misses_cached", 1);
    }

    private void rememberStoragePresent(CubePos cubePos) {
        storageMissCache.remove(cubePos);
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
        Optional<LevelCube> persisted = storageGet(cubePos);
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
                rememberStoragePresent(existing.cubePos());
                existing.markSaved(CubeHolderState.REGION3D_SAVED);
                totalSaved++;
                saved = true;
            }
            return new PregenCubeResult(cubePos, false, saved, false, "already_loaded");
        }
        if (existing != null && existing.dirty()) {
            return new PregenCubeResult(cubePos, false, false, false, "loaded_dirty_skip");
        }

        Optional<LevelCube> persisted = storageGet(cubePos);
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
            rememberStoragePresent(cubePos);
            totalSaved++;
            saved = true;
        }
        totalGenerated++;

        if (existing != null) {
            CubeHolder replacement = new CubeHolder(cubePos, prepared, existing.ticketLevel(), CubeHolderState.REGION3D_SAVED, gameTime);
            replacement.markRequired(existing.ticketLevel(), gameTime);
            holders.put(cubePos, replacement);
            blockEntityTracker.rebuildCube(prepared, "pregen_replace");
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
                blockEntityTracker.snapshot(),
                scheduledTickTracker.snapshot(),
                Map.copyOf(byTicketLevel),
                Map.copyOf(byCubeStatus),
                Map.copyOf(byHolderState)
        );
    }

    public synchronized int saveAllLoaded() {
        return saveAllLoaded(DEBUG_SAVE_ALL_MAX_SYNC_CUBES_PER_CALL);
    }

    /**
     * M14.9.1: debug save_all is budgeted. It no longer writes thousands of dirty cubes in one server tick.
     * Run the command repeatedly, wait for async dirty IO, or use ownership auto_test for a bounded persistence check.
     */
    public synchronized int saveAllLoaded(int maxSyncSaves) {
        drainAsyncDirtySaveCompletions(false);
        int limit = Math.max(1, maxSyncSaves);
        int saved = 0;
        for (CubeHolder holder : new ArrayList<>(holders.values())) {
            if (saved >= limit) {
                break;
            }
            if (shouldSkipDebugSave(holder)) {
                continue;
            }
            if (saveCubeNow(holder)) {
                saved++;
            }
        }
        return saved;
    }

    public synchronized int debugSaveBacklog() {
        int dirtyLoaded = 0;
        for (CubeHolder holder : holders.values()) {
            if (holder.dirty()) {
                dirtyLoaded++;
            }
        }
        return dirtyLoaded;
    }

    public synchronized int unloadAllLoaded(boolean saveDirty) {
        drainAsyncDirtySaveCompletions(false);
        int unloaded = 0;
        for (CubeHolder holder : new ArrayList<>(holders.values())) {
            if (unloadHolder(holder, saveDirty)) {
                unloaded++;
            }
        }
        pendingLoads.clear();
        if (holders.isEmpty()) {
            skyLightDirtyColumns.clear();
        }
        return unloaded;
    }

    public synchronized boolean forceSaveCube(CubePos cubePos) {
        CubeHolder holder = holders.get(cubePos);
        if (holder == null) {
            return false;
        }
        return saveCubeNow(holder);
    }

    public synchronized boolean debugUnloadCube(CubePos cubePos, boolean forceSave) {
        CubeHolder holder = holders.get(cubePos);
        if (holder == null) {
            storage.unloadFromMemory(cubePos);
            return true;
        }
        if (forceSave && holder.dirty()) {
            saveCubeNow(holder);
        }
        return unloadHolder(holder, false);
    }

    public synchronized boolean debugLoadCube(CubePos cubePos, CubeTicketLevel level) {
        return ensureLoadedForClient(cubePos, level).isPresent();
    }

    /**
     * M14.9.2 strict cube-shell helper.
     *
     * <p>The vanilla chunk generator is no longer allowed to be the visible source of truth for cubic_test.
     * The client bridge calls this for the player cube before materializing, so the physical chunk shell is filled
     * from cube-owned terrain instead of showing a temporary flat vanilla column until normal pending-load order catches up.</p>
     */
    public synchronized Optional<CubeHolder> ensureLoadedForClient(CubePos cubePos, CubeTicketLevel level) {
        long profileStart = RuntimeProfiler.markStart();
        if (!settings.containsCubeY(cubePos.y())) {
            RuntimeProfiler.recordSince("client.ensure_loaded", profileStart);
            return Optional.empty();
        }
        CubeHolder holder = holders.get(cubePos);
        if (holder != null) {
            holder.markRequired(level, gameTime);
            RuntimeProfiler.addCount("client.ensure_loaded_hits", 1);
            RuntimeProfiler.recordSince("client.ensure_loaded", profileStart);
            return Optional.of(holder);
        }
        CubeHolder loaded = loadHolder(cubePos, level);
        holders.put(cubePos, loaded);
        if (pendingLoads.remove(cubePos) != null) {
            RuntimeProfiler.addCount("cube_loading.pending_removed_by_client_ensure", 1);
        }
        totalLoaded++;
        RuntimeProfiler.addCount("client.ensure_loaded_loads", 1);
        if (loaded.state() == CubeHolderState.GENERATED) {
            totalGenerated++;
            RuntimeProfiler.addCount("client.ensure_loaded_generated", 1);
        }
        markSkyLightDirty(cubePos.columnPos());
        RuntimeProfiler.recordSince("client.ensure_loaded", profileStart);
        return Optional.of(loaded);
    }

    private boolean saveCubeNow(CubeHolder holder) {
        long startNanos = System.nanoTime();
        storage.put(holder.cube());
        rememberStoragePresent(holder.cubePos());
        holder.markSaved(CubeHolderState.REGION3D_SAVED);
        dirtyTracker.recordSaved(new CubeSaveWork(holder.cubePos(), Long.MAX_VALUE), Math.max(1L, (System.nanoTime() - startNanos) / 1_000L));
        totalSaved++;
        return true;
    }

    private RequiredLevels collectRequiredLevelsCached(Collection<CubeTicket> tickets) {
        TicketSignature signature = ticketSignature(tickets);
        if (cachedRequiredLevels != null
                && cachedRequiredTicketSignature == signature.signature()
                && cachedRequiredTicketCount == signature.count()) {
            RuntimeProfiler.addCount("cube_loading.required_cache_hits", 1);
            RuntimeProfiler.addCount("cube_loading.requested_cubes_reused", cachedRequiredLevels.levels().size());
            return cachedRequiredLevels;
        }
        RuntimeProfiler.addCount("cube_loading.required_cache_misses", 1);
        RequiredLevels computed = collectRequiredLevels(tickets);
        cachedRequiredLevels = computed;
        cachedRequiredTicketSignature = signature.signature();
        cachedRequiredTicketCount = signature.count();
        RuntimeProfiler.addCount("cube_loading.requested_cubes_rebuilt", computed.levels().size());
        return computed;
    }

    private static TicketSignature ticketSignature(Collection<CubeTicket> tickets) {
        long signature = 0xCBF29CE484222325L;
        int count = 0;
        for (CubeTicket ticket : tickets) {
            if (ticket.level() == CubeTicketLevel.UNLOADED) {
                continue;
            }
            long hash = ticket.id().getMostSignificantBits() ^ Long.rotateLeft(ticket.id().getLeastSignificantBits(), 17);
            hash ^= ((long) ticket.type().ordinal()) * 0x9E3779B97F4A7C15L;
            hash ^= ((long) ticket.level().ordinal()) * 0xC2B2AE3D27D4EB4FL;
            hash ^= ticket.shape().type().ordinal() * 0x165667B19E3779F9L;
            hash ^= mixTicketCoord(ticket.shape().min().x(), ticket.shape().min().y(), ticket.shape().min().z());
            hash ^= Long.rotateLeft(mixTicketCoord(ticket.shape().max().x(), ticket.shape().max().y(), ticket.shape().max().z()), 29);
            signature ^= mix64(hash + count * 0xD6E8FEB86659FD93L);
            signature *= 0x100000001B3L;
            count++;
        }
        signature ^= ((long) count) * 0x9E3779B97F4A7C15L;
        return new TicketSignature(signature, count);
    }

    private static long mixTicketCoord(int x, int y, int z) {
        long value = ((long) x * 0x9E3779B97F4A7C15L)
                ^ ((long) y * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) z * 0x165667B19E3779F9L);
        return mix64(value);
    }

    private static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return value;
    }

    private RequiredLevels collectRequiredLevels(Collection<CubeTicket> tickets) {
        Map<CubePos, CubeTicketLevel> required = new LinkedHashMap<>();

        for (CubeTicket ticket : tickets) {
            if (ticket.level() == CubeTicketLevel.UNLOADED) {
                continue;
            }
            CubePos min = ticket.shape().min();
            CubePos max = ticket.shape().max();
            for (int y = min.y(); y <= max.y(); y++) {
                if (!settings.containsCubeY(y)) {
                    continue;
                }
                for (int z = min.z(); z <= max.z(); z++) {
                    for (int x = min.x(); x <= max.x(); x++) {
                        CubePos cubePos = new CubePos(x, y, z);
                        required.merge(cubePos, ticket.level(), ServerCubeCache::strongerLevel);
                        if (required.size() >= MAX_REQUESTED_CUBES_PER_TICK) {
                            return new RequiredLevels(required, true);
                        }
                    }
                }
            }
        }

        return new RequiredLevels(required, false);
    }

    /**
     * M16.8 gameplay loading priority.
     *
     * <p>The ticket shape still requests a cuboid, but the actual pending stream is ordered for playability:
     * immediate radius around the player first, then the look-ahead corridor with +/-2 cubeY, then surface cubes,
     * underground cubes, and finally air cubes.  This does not raise the generation budget; it only spends the budget on
     * cubes the player is most likely to see/enter next.</p>
     */
    private void reprioritizePendingLoads(Map<CubePos, CubeTicketLevel> required, ServerLevel level) {
        if (level == null || pendingLoads.size() <= 1) {
            return;
        }
        List<PlayerLoadFocus> focuses = activePlayerFocuses(level);
        if (focuses.isEmpty()) {
            return;
        }
        List<Map.Entry<CubePos, CubeTicketLevel>> entries = new ArrayList<>(pendingLoads.entrySet());
        Map<CubePos, Integer> priorities = new LinkedHashMap<>();
        Map<CubePos, PlayerLoadFocus> nearest = new LinkedHashMap<>();
        for (Map.Entry<CubePos, CubeTicketLevel> entry : entries) {
            CubePos cubePos = entry.getKey();
            priorities.put(cubePos, loadPriority(cubePos, focuses));
            nearest.put(cubePos, nearestFocus(cubePos, focuses));
        }
        entries.sort(Comparator
                .comparingInt((Map.Entry<CubePos, CubeTicketLevel> entry) -> priorities.getOrDefault(entry.getKey(), Integer.MAX_VALUE))
                .thenComparingInt(entry -> Math.abs(entry.getKey().y() - nearest.get(entry.getKey()).cube().y()))
                .thenComparingInt(entry -> horizontalDistanceSquared(entry.getKey(), nearest.get(entry.getKey()).cube()))
                .thenComparingInt(entry -> entry.getKey().x())
                .thenComparingInt(entry -> entry.getKey().z())
                .thenComparingInt(entry -> entry.getKey().y()));
        pendingLoads.clear();
        for (Map.Entry<CubePos, CubeTicketLevel> entry : entries) {
            if (required.containsKey(entry.getKey())) {
                pendingLoads.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private List<PlayerLoadFocus> activePlayerFocuses(ServerLevel level) {
        List<PlayerLoadFocus> focuses = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            CubePos cube = CubePos.fromBlock(player.blockPosition());
            double lookX = player.getLookAngle().x;
            double lookZ = player.getLookAngle().z;
            double len = Math.sqrt(lookX * lookX + lookZ * lookZ);
            if (len < 1.0E-5D) {
                lookX = 0.0D;
                lookZ = 1.0D;
            } else {
                lookX /= len;
                lookZ /= len;
            }
            focuses.add(new PlayerLoadFocus(cube, lookX, lookZ));
        }
        return focuses;
    }

    private int loadPriority(CubePos cubePos, List<PlayerLoadFocus> focuses) {
        int best = Integer.MAX_VALUE;
        for (PlayerLoadFocus focus : focuses) {
            int horizontalCheb = Math.max(Math.abs(cubePos.x() - focus.cube().x()), Math.abs(cubePos.z() - focus.cube().z()));
            int dy = Math.abs(cubePos.y() - focus.cube().y());
            if (horizontalCheb <= 1 && dy <= 1) {
                best = Math.min(best, 0 + horizontalCheb * 10 + dy);
                continue;
            }
            if (isLookAhead(cubePos, focus)) {
                best = Math.min(best, 1_000 + horizontalDistanceSquared(cubePos, focus.cube()) + dy * 8);
                continue;
            }
            int surfaceClass = verticalTerrainClass(cubePos);
            int base = switch (surfaceClass) {
                case 0 -> 2_000; // surface and near-surface
                case 1 -> 3_000; // underground
                default -> 4_000; // air/high empty cubes last
            };
            best = Math.min(best, base + horizontalDistanceSquared(cubePos, focus.cube()) + dy * 16);
        }
        return best;
    }

    private boolean isLookAhead(CubePos cubePos, PlayerLoadFocus focus) {
        int dy = Math.abs(cubePos.y() - focus.cube().y());
        if (dy > 2) {
            return false;
        }
        double dx = cubePos.x() - focus.cube().x();
        double dz = cubePos.z() - focus.cube().z();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0D || distance > 12.0D) {
            return false;
        }
        double dot = dx * focus.lookX() + dz * focus.lookZ();
        return dot > distance * 0.35D;
    }

    /** 0 = surface, 1 = underground, 2 = air/high empty. */
    private int verticalTerrainClass(CubePos cubePos) {
        int centerX = cubePos.minBlockX() + 8;
        int centerZ = cubePos.minBlockZ() + 8;
        int surfaceCubeY = CubePos.blockToCube(com.redline.worldcore.server.generation.M15TerrainModel.surfaceHeightDry(generationContext(), centerX, centerZ));
        if (cubePos.y() >= surfaceCubeY - 1 && cubePos.y() <= surfaceCubeY + 1) {
            return 0;
        }
        return cubePos.y() < surfaceCubeY - 1 ? 1 : 2;
    }

    private static PlayerLoadFocus nearestFocus(CubePos cubePos, List<PlayerLoadFocus> focuses) {
        PlayerLoadFocus best = focuses.getFirst();
        int bestDistance = horizontalDistanceSquared(cubePos, best.cube()) + Math.abs(cubePos.y() - best.cube().y()) * 16;
        for (int index = 1; index < focuses.size(); index++) {
            PlayerLoadFocus focus = focuses.get(index);
            int distance = horizontalDistanceSquared(cubePos, focus.cube()) + Math.abs(cubePos.y() - focus.cube().y()) * 16;
            if (distance < bestDistance) {
                best = focus;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int horizontalDistanceSquared(CubePos a, CubePos b) {
        int dx = a.x() - b.x();
        int dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private void purgePendingNoLongerRequired(Map<CubePos, CubeTicketLevel> required) {
        pendingLoads.keySet().removeIf(cubePos -> !required.containsKey(cubePos));
    }

    private int queueMissingAndRefreshLoaded(Map<CubePos, CubeTicketLevel> required) {
        int queued = 0;
        int loadedRefreshes = 0;
        int pendingDuplicateSkips = 0;
        int pendingLevelPromotions = 0;
        for (Map.Entry<CubePos, CubeTicketLevel> entry : required.entrySet()) {
            CubeHolder holder = holders.get(entry.getKey());
            if (holder != null) {
                holder.markRequired(entry.getValue(), gameTime);
                loadedRefreshes++;
                continue;
            }

            CubeTicketLevel previous = pendingLoads.get(entry.getKey());
            if (previous == null) {
                pendingLoads.put(entry.getKey(), entry.getValue());
                queued++;
            } else {
                CubeTicketLevel stronger = strongerLevel(previous, entry.getValue());
                if (stronger == previous) {
                    pendingDuplicateSkips++;
                    continue;
                }
                pendingLoads.put(entry.getKey(), stronger);
                pendingLevelPromotions++;
            }
        }
        RuntimeProfiler.addCount("cube_loading.loaded_request_refreshes", loadedRefreshes);
        RuntimeProfiler.addCount("cube_loading.pending_duplicate_skips", pendingDuplicateSkips);
        RuntimeProfiler.addCount("cube_loading.pending_level_promotions", pendingLevelPromotions);
        return queued;
    }

    private LoadCounters loadPending(Map<CubePos, CubeTicketLevel> required) {
        long profileStart = RuntimeProfiler.markStart();
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

            RuntimeProfiler.addCount("cube_loading.pending_iterations", 1);
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
        RuntimeProfiler.recordSince("cube_loading.load_pending", profileStart);
        return new LoadCounters(loaded, generated, elapsedMicros, generatedBudgetHit, timeBudgetHit);
    }

    private CubeHolder loadHolder(CubePos cubePos, CubeTicketLevel level) {
        long profileStart = RuntimeProfiler.markStart();
        long phaseStart = RuntimeProfiler.markStart();
        Optional<LevelCube> loaded = storageGet(cubePos);
        RuntimeProfiler.recordSince("cube_io.storage_get", phaseStart);
        if (loaded.isPresent()) {
            LevelCube cube = loaded.get();
            if (StaticBlockLightLayer.needsBootstrap(cube)) {
                phaseStart = RuntimeProfiler.markStart();
                StaticBlockLightLayer.rebuild(cube);
                RuntimeProfiler.recordSince("lighting.static_rebuild_on_load", phaseStart);
                totalLightRebuilt++;
            }
            CubeHolder holder = new CubeHolder(cubePos, cube, level, CubeHolderState.REGION3D_LOADED, gameTime);
            phaseStart = RuntimeProfiler.markStart();
            blockEntityTracker.rebuildCube(cube, "holder_loaded");
            RuntimeProfiler.recordSince("cube_loading.block_entity_rebuild", phaseStart);
            RuntimeProfiler.addCount("cube_loading.region3d_loaded", 1);
            RuntimeProfiler.recordSince("cube_loading.load_holder_total", profileStart);
            return holder;
        }

        if (level.isAtLeast(CubeTicketLevel.GENERATED)) {
            phaseStart = RuntimeProfiler.markStart();
            LevelCube generated = generator.generate(cubePos);
            RuntimeProfiler.recordSince("cube_loading.generate_holder", phaseStart);
            phaseStart = RuntimeProfiler.markStart();
            StaticBlockLightLayer.rebuild(generated);
            RuntimeProfiler.recordSince("lighting.static_rebuild_generated", phaseStart);
            // M10.1: do not rebuild sky per cube while the loading window streams in.
            // The delayed column queue will compute correct finite-top skylight once the column settles.
            totalLightRebuilt++;
            CubeHolder holder = new CubeHolder(cubePos, generated, level, CubeHolderState.GENERATED, gameTime);
            phaseStart = RuntimeProfiler.markStart();
            blockEntityTracker.rebuildCube(generated, "holder_generated");
            RuntimeProfiler.recordSince("cube_loading.block_entity_rebuild", phaseStart);
            RuntimeProfiler.addCount("cube_loading.generated_holders", 1);
            RuntimeProfiler.recordSince("cube_loading.load_holder_total", profileStart);
            return holder;
        }

        LevelCube placeholder = new LevelCube(cubePos);
        placeholder.setStatus(CubeStatus.EMPTY);
        phaseStart = RuntimeProfiler.markStart();
        StaticBlockLightLayer.rebuild(placeholder);
        RuntimeProfiler.recordSince("lighting.static_rebuild_placeholder", phaseStart);
        // M10.1: placeholders also wait for the delayed column queue instead of doing per-cube open-sky work.
        totalLightRebuilt++;
        CubeHolder holder = new CubeHolder(cubePos, placeholder, level, CubeHolderState.PLACEHOLDER, gameTime);
        phaseStart = RuntimeProfiler.markStart();
        blockEntityTracker.rebuildCube(placeholder, "holder_placeholder");
        RuntimeProfiler.recordSince("cube_loading.block_entity_rebuild", phaseStart);
        RuntimeProfiler.addCount("cube_loading.placeholder_holders", 1);
        RuntimeProfiler.recordSince("cube_loading.load_holder_total", profileStart);
        return holder;
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
            if (unloadHolder(holder, true)) {
                unloaded++;
            }
        }
        return unloaded;
    }

    private static boolean shouldSkipDebugSave(CubeHolder holder) {
        // M7 generated cubes are deterministic and clean by default, so debug save_all should not write thousands of
        // untouched terrain cubes into Region3D. Later block edits will mark holders dirty and save normally.
        return !holder.dirty();
    }

    private boolean unloadHolder(CubeHolder holder, boolean saveDirty) {
        if (saveDirty && holder.dirty()) {
            // M14.9.1: never perform large sync IO from the automatic unload path.
            // Keep dirty holders resident until async/idle dirty IO or an explicit bounded debug save persists them.
            dirtyTracker.mark(holder.cubePos(), CubeDirtyFlag.STORAGE);
            return false;
        }
        holders.remove(holder.cubePos());
        lightDirtyQueue.remove(holder.cubePos());
        dirtyTracker.remove(holder.cubePos());
        blockEntityTracker.removeCube(holder.cubePos());
        if (loadedColumnHolders(holder.cubePos().columnPos()).isEmpty()) {
            skyLightDirtyColumns.remove(holder.cubePos().columnPos());
        }
        storage.unloadFromMemory(holder.cubePos());
        totalUnloaded++;
        return true;
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


    private int captureVanillaBlockEntities(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        Map<CubePos, LevelCube> loaded = new LinkedHashMap<>();
        for (CubeHolder holder : holders.values()) {
            loaded.put(holder.cubePos(), holder.cube());
        }
        int captured = blockEntityTracker.captureVanillaForLoaded(level, loaded);
        if (captured > 0) {
            for (CubeHolder holder : holders.values()) {
                if (holder.cube().blockEntityCount() > 0) {
                    holder.markDirty();
                    dirtyTracker.mark(holder.cubePos(), CubeDirtyFlag.CONTENT_FLAGS, CubeDirtyFlag.STORAGE, CubeDirtyFlag.CLIENT_SYNC);
                }
            }
        }
        return captured;
    }

    private void updateBlockEntityTickGates() {
        Map<CubePos, CubeTicketLevel> levels = new LinkedHashMap<>();
        for (CubeHolder holder : holders.values()) {
            levels.put(holder.cubePos(), holder.ticketLevel());
        }
        blockEntityTracker.evaluateTicking(levels);
    }

    private void updateScheduledTickGates() {
        Map<CubePos, LevelCube> cubes = new LinkedHashMap<>();
        Map<CubePos, CubeTicketLevel> levels = new LinkedHashMap<>();
        for (CubeHolder holder : holders.values()) {
            cubes.put(holder.cubePos(), holder.cube());
            levels.put(holder.cubePos(), holder.ticketLevel());
        }
        scheduledTickTracker.evaluate(cubes, levels, gameTime);
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

    private int flushDirtySaveQueue(LoadCounters loadCounters, int rebuiltContentThisTick, int rebuiltLightThisTick, SkyLightQueueCounters skyCounters) {
        drainAsyncDirtySaveCompletions(false);

        if (dirtyTracker.saveCooldownActive()) {
            dirtyTracker.recordSaveIdleSkip("save_cooldown");
            return 0;
        }
        if (dirtySaveWorker.inFlight() >= MAX_ASYNC_DIRTY_SAVES_IN_FLIGHT) {
            dirtyTracker.recordSaveIdleSkip("async_inflight_full");
            return 0;
        }
        if (loadCounters.generated() > 0) {
            dirtyTracker.recordSaveIdleSkip("generated_this_tick");
            return 0;
        }
        if (loadCounters.elapsedMicros() >= MAX_LOAD_MICROS_PER_TICK) {
            dirtyTracker.recordSaveIdleSkip("load_budget_busy");
            return 0;
        }
        if (rebuiltLightThisTick > 0 || skyCounters.rebuiltColumns() > 0) {
            dirtyTracker.recordSaveIdleSkip("light_busy");
            return 0;
        }

        long startNanos = System.nanoTime();
        int submitted = 0;
        List<CubeSaveWork> batch = dirtyTracker.pollSaveWork(0L);
        for (int index = 0; index < batch.size(); index++) {
            CubeSaveWork work = batch.get(index);
            CubePos cubePos = work.cubePos();
            CubeHolder holder = holders.get(cubePos);
            if (holder == null) {
                dirtyTracker.cancelSaveWork(work);
                dirtyTracker.remove(cubePos);
                continue;
            }
            if (!holder.dirty()) {
                dirtyTracker.cancelSaveWork(work);
                dirtyTracker.clean(cubePos, CubeDirtyFlag.STORAGE);
                continue;
            }
            LevelCube snapshot = holder.cube().copy();
            dirtySaveWorker.submit(storage, work, snapshot);
            dirtyTracker.recordSaveSubmitted(work);
            submitted++;

            long elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
            if (dirtyTracker.shouldStopSaving(elapsedMicros, submitted)
                    || dirtySaveWorker.inFlight() >= MAX_ASYNC_DIRTY_SAVES_IN_FLIGHT) {
                for (int remaining = index + 1; remaining < batch.size(); remaining++) {
                    dirtyTracker.requeueSave(batch.get(remaining));
                }
                break;
            }
        }
        return 0;
    }

    private void drainAsyncDirtySaveCompletions(boolean forceAll) {
        long startNanos = System.nanoTime();
        int drained = 0;
        boolean budgetHit = false;
        CubeAsyncSaveWorker.Completion completion;
        while ((completion = dirtySaveWorker.pollCompletion()) != null) {
            CubeSaveWork work = completion.work();
            if (!completion.success()) {
                dirtyTracker.recordSaveFailed(work, completion.error());
            } else {
                totalSaved++;
                rememberStoragePresent(work.cubePos());
                boolean clean = dirtyTracker.recordSaved(work, completion.elapsedMicros());
                CubeHolder holder = holders.get(work.cubePos());
                if (holder != null && clean) {
                    holder.markSaved(CubeHolderState.REGION3D_SAVED);
                }
                if (completion.elapsedMicros() >= EXPENSIVE_DIRTY_SAVE_MICROS) {
                    dirtyTracker.startSaveCooldown(DIRTY_SAVE_COOLDOWN_TICKS, "expensive_async_save");
                }
            }
            drained++;
            long elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
            if (!forceAll && (drained >= MAX_ASYNC_SAVE_COMPLETIONS_PER_TICK || elapsedMicros >= MAX_ASYNC_COMPLETION_MICROS_PER_TICK)) {
                budgetHit = dirtySaveWorker.pendingCompletions() > 0;
                break;
            }
        }
        long elapsedMicros = drained == 0 ? 0L : Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
        dirtyTracker.recordCompletionDrain(drained, elapsedMicros, budgetHit);
    }

    private int forceFlushDirtySaveQueue() {
        drainAsyncDirtySaveCompletions(true);
        int saved = 0;
        int guard = 0;
        while (guard++ < 100000) {
            List<CubeSaveWork> batch = dirtyTracker.pollSaveWork(0L);
            if (batch.isEmpty()) {
                break;
            }
            for (CubeSaveWork work : batch) {
                CubeHolder holder = holders.get(work.cubePos());
                if (holder == null) {
                    dirtyTracker.cancelSaveWork(work);
                    dirtyTracker.remove(work.cubePos());
                    continue;
                }
                if (!holder.dirty()) {
                    dirtyTracker.cancelSaveWork(work);
                    dirtyTracker.clean(work.cubePos(), CubeDirtyFlag.STORAGE);
                    continue;
                }
                long startNanos = System.nanoTime();
                storage.put(holder.cube());
                rememberStoragePresent(holder.cubePos());
                holder.markSaved(CubeHolderState.REGION3D_SAVED);
                long saveMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
                totalSaved++;
                dirtyTracker.recordSaved(work, saveMicros);
                saved++;
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
            long startNanos = System.nanoTime();
            storage.put(holder.cube());
            rememberStoragePresent(holder.cubePos());
            holder.markSaved(CubeHolderState.REGION3D_SAVED);
            dirtyTracker.recordSaved(new CubeSaveWork(holder.cubePos(), Long.MAX_VALUE), Math.max(1L, (System.nanoTime() - startNanos) / 1_000L));
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

    private record PlayerLoadFocus(CubePos cube, double lookX, double lookZ) {
    }

    private record RequiredLevels(Map<CubePos, CubeTicketLevel> levels, boolean limitHit) {
    }

    private record TicketSignature(long signature, int count) {
    }

    private record LoadCounters(int loaded, int generated, long elapsedMicros, boolean generatedBudgetHit, boolean timeBudgetHit) {
    }

    private record SkyLightQueueCounters(int rebuiltColumns, int changedCubes, int skippedUnchangedCubes, int savedChangedCubes, long elapsedMicros) {
    }
}
