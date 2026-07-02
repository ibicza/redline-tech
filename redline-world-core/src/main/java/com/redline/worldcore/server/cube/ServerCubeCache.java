package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.ColumnPos;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
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
    /** Keep one player ticket from loading in one tick; 3969 player cubes need about 31 ticks with this default. */
    public static final int MAX_LOADS_PER_TICK = 128;

    /** Keep recently unrequested cubes warm for a short time to avoid thrash while tickets refresh or players move. */
    public static final int UNLOAD_GRACE_TICKS = 100;

    /** Safety cap for accidental huge debug cuboids before real async distance propagation exists. */
    public static final int MAX_REQUESTED_CUBES_PER_TICK = 32768;

    /** M9 keeps static block-light rebuilds small and predictable during gameplay ticks. */
    public static final int MAX_LIGHT_REBUILDS_PER_TICK = 32;

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


    public synchronized CubeLoadingTickResult tick(Collection<CubeTicket> tickets) {
        gameTime++;

        RequiredLevels required = collectRequiredLevels(tickets);
        requestedLastTick = required.levels().size();
        requestLimitHitLastTick = required.limitHit();

        purgePendingNoLongerRequired(required.levels());
        int queuedThisTick = queueMissingAndRefreshLoaded(required.levels());
        int unloadedThisTick = unloadNoLongerRequired(required.levels());
        LoadCounters loadCounters = loadPending(required.levels());
        int rebuiltLightThisTick = rebuildDirtyLightQueue();
        SkyLightQueueCounters skyCounters = rebuildDirtySkyLightColumns();

        queuedLastTick = queuedThisTick;
        loadedLastTick = loadCounters.loaded();
        generatedLastTick = loadCounters.generated();
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
     * <p>If the holder is not currently loaded, the cube is loaded from Region3D or regenerated first. This keeps player
     * edits persistent without forcing the caller to manage tickets.</p>
     */
    public synchronized Optional<CubeHolder> writeBlock(BlockPos worldPos, BlockState state, boolean saveImmediately) {
        CubePos cubePos = CubePos.fromBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        if (!settings.containsCubeY(cubePos.y())) {
            return Optional.empty();
        }

        CubeHolder holder = holders.get(cubePos);
        if (holder == null) {
            holder = loadHolder(cubePos, CubeTicketLevel.FULL);
            holders.put(cubePos, holder);
            totalLoaded++;
            if (holder.state() == CubeHolderState.GENERATED) {
                totalGenerated++;
            }
        }

        holder.cube().setBlockState(worldPos, state);
        markLightDirty(cubePos);
        rebuildLightNow(holder);
        rebuildSkyLightColumnLoaded(cubePos.columnPos(), false, true);
        holder.markDirty();
        if (saveImmediately) {
            int saved = saveDirtyLoadedColumn(cubePos.columnPos());
            if (saved == 0) {
                storage.put(holder.cube());
                holder.markSaved(CubeHolderState.REGION3D_SAVED);
                totalSaved++;
            }
        }
        return Optional.of(holder);
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
                requestLimitHitLastTick,
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
        }
        for (ColumnPos columnPos : touchedColumns) {
            markSkyLightDirty(columnPos);
        }
        return new LoadCounters(loaded, generated);
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
            Optional<SkyLightLayer.ColumnRebuildResult> result = rebuildSkyLightColumnLoaded(columnPos, false, false);
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
        totalLightRebuilt++;
        return result;
    }

    private static CubeTicketLevel strongerLevel(CubeTicketLevel first, CubeTicketLevel second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    private record RequiredLevels(Map<CubePos, CubeTicketLevel> levels, boolean limitHit) {
    }

    private record LoadCounters(int loaded, int generated) {
    }

    private record SkyLightQueueCounters(int rebuiltColumns, int changedCubes, int skippedUnchangedCubes, int savedChangedCubes, long elapsedMicros) {
    }
}
