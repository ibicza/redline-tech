package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.server.storage.CubeRegionStorage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M6 cube-first server cache.
 *
 * <p>This is the first layer that consumes CubeTicket requests and turns them into loaded cube holders. It intentionally
 * does not touch Minecraft's ChunkMap yet: vanilla chunks remain the compatibility shell while this cache proves the
 * cube lifecycle against Region3D storage.</p>
 */
public final class ServerCubeCache {
    /** Keep one player ticket from loading in one tick; 3969 player cubes need about 31 ticks with this default. */
    public static final int MAX_LOADS_PER_TICK = 128;

    /** Keep recently unrequested cubes warm for a short time to avoid thrash while tickets refresh or players move. */
    public static final int UNLOAD_GRACE_TICKS = 100;

    /** Safety cap for accidental huge debug cuboids before real async distance propagation exists. */
    public static final int MAX_REQUESTED_CUBES_PER_TICK = 32768;

    private final CubicDimensionSettings settings;
    private final CubeRegionStorage storage;
    private final Map<CubePos, CubeHolder> holders = new ConcurrentHashMap<>();
    private final LinkedHashMap<CubePos, CubeTicketLevel> pendingLoads = new LinkedHashMap<>();

    private long gameTime;
    private long totalLoaded;
    private long totalUnloaded;
    private long totalSaved;
    private int requestedLastTick;
    private int queuedLastTick;
    private int loadedLastTick;
    private int unloadedLastTick;
    private boolean requestLimitHitLastTick;

    public ServerCubeCache(Path storageRoot, CubicDimensionSettings settings) {
        this(new CubeRegionStorage(storageRoot), settings);
    }

    public ServerCubeCache(CubeRegionStorage storage, CubicDimensionSettings settings) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Path storageRoot() {
        return storage.rootDirectory();
    }

    public synchronized CubeLoadingTickResult tick(Collection<CubeTicket> tickets) {
        gameTime++;

        RequiredLevels required = collectRequiredLevels(tickets);
        requestedLastTick = required.levels().size();
        requestLimitHitLastTick = required.limitHit();

        int queuedThisTick = queueMissingAndRefreshLoaded(required.levels());
        int unloadedThisTick = unloadNoLongerRequired(required.levels());
        int loadedThisTick = loadPending(required.levels());

        queuedLastTick = queuedThisTick;
        loadedLastTick = loadedThisTick;
        unloadedLastTick = unloadedThisTick;

        return new CubeLoadingTickResult(gameTime, requestedLastTick, queuedThisTick, loadedThisTick, unloadedThisTick, requestLimitHitLastTick);
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
                loadedLastTick,
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

    private int loadPending(Map<CubePos, CubeTicketLevel> required) {
        int loaded = 0;
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
            iterator.remove();
            loaded++;
            totalLoaded++;
        }
        return loaded;
    }

    private CubeHolder loadHolder(CubePos cubePos, CubeTicketLevel level) {
        Optional<LevelCube> loaded = storage.get(cubePos);
        if (loaded.isPresent()) {
            return new CubeHolder(cubePos, loaded.get(), level, CubeHolderState.REGION3D_LOADED, gameTime);
        }

        LevelCube placeholder = new LevelCube(cubePos);
        placeholder.setStatus(CubeStatus.EMPTY);
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

    private void unloadHolder(CubeHolder holder, boolean saveDirty) {
        if (saveDirty && holder.dirty()) {
            storage.put(holder.cube());
            holder.markSaved(CubeHolderState.REGION3D_SAVED);
            totalSaved++;
        }
        holders.remove(holder.cubePos());
        storage.unloadFromMemory(holder.cubePos());
        totalUnloaded++;
    }

    private static CubeTicketLevel strongerLevel(CubeTicketLevel first, CubeTicketLevel second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    private record RequiredLevels(Map<CubePos, CubeTicketLevel> levels, boolean limitHit) {
    }
}
