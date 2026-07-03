package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.network.CubeClientSyncPayload;
import com.redline.worldcore.server.cube.CubeHolder;
import com.redline.worldcore.server.cube.CubeLoadingSnapshot;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import com.redline.worldcore.server.entity.EntityCubeTracker;
import com.redline.worldcore.server.entity.EntityTrackingSnapshot;
import com.redline.worldcore.server.generation.CubeGenerationSummary;
import com.redline.worldcore.server.lighting.SkyLightSummary;
import com.redline.worldcore.server.lighting.StaticLightSummary;
import com.redline.worldcore.server.pregen.CubePregenManager;
import com.redline.worldcore.server.pregen.CubePregenSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * RWC vanilla-client bridge for the cubic test dimension.
 *
 * <p>The final project will eventually stream cube sections through a real client chunk compatibility layer. This MVP is
 * intentionally safer: it keeps vanilla columns as the temporary shell, mirrors a small cube window around the player
 * into the physical test dimension, and syncs metadata to the client overlay. This proves the edit/persistence path
 * without touching ChunkMap packet internals yet.</p>
 */
public final class CubicClientSyncBridge {
    public static final int OVERLAY_HIDDEN = 0;
    public static final int OVERLAY_COMPACT = 1;
    public static final int OVERLAY_FULL = 2;

    public static final int DEFAULT_STREAM_HORIZONTAL_RADIUS = 8;
    public static final int DEFAULT_STREAM_VERTICAL_RADIUS = 2;
    public static final int DEFAULT_MAX_MATERIALIZED_CUBES_PER_TICK = 8;
    public static final int DEFAULT_SYNC_PACKET_INTERVAL_TICKS = 5;

    /**
     * M14.9.3: strict cubic shell must not wait for the normal gameplay pending-load throttle before nearby cubes
     * become visible. The normal player ticket still requests a much larger slab, but that path generates only one
     * fresh cube per tick. This small eager window makes the cubes the player can step/fly into cube-owned before
     * the player crosses the border, without reintroducing a visible vanilla flat fallback.
     */
    public static final int DEFAULT_EAGER_LOAD_HORIZONTAL_RADIUS = 4;
    public static final int DEFAULT_EAGER_LOAD_VERTICAL_RADIUS = 2;
    public static final int DEFAULT_MAX_EAGER_CLIENT_LOADS_PER_TICK = 8;
    public static final int MAX_PACKET_ENTRIES = 96;

    /**
     * Client-only mirror writes must not wake vanilla physics. UPDATE_CLIENTS + UPDATE_KNOWN_SHAPE + UPDATE_SUPPRESS_DROPS.
     * This is a temporary shell flag set until cube section packets replace physical vanilla block mirroring.
     */
    private static final int SET_BLOCK_FLAGS = 2 | 16 | 32;
    private static final int MAX_TRACKED_MATERIALIZED = 2048;
    private static final Map<UUID, PlayerBridgeState> PLAYER_STATES = new HashMap<>();

    private static int streamHorizontalRadius = DEFAULT_STREAM_HORIZONTAL_RADIUS;
    private static int streamVerticalRadius = DEFAULT_STREAM_VERTICAL_RADIUS;
    private static int maxMaterializedCubesPerTick = DEFAULT_MAX_MATERIALIZED_CUBES_PER_TICK;
    private static int syncPacketIntervalTicks = DEFAULT_SYNC_PACKET_INTERVAL_TICKS;
    private static int eagerLoadHorizontalRadius = DEFAULT_EAGER_LOAD_HORIZONTAL_RADIUS;
    private static int eagerLoadVerticalRadius = DEFAULT_EAGER_LOAD_VERTICAL_RADIUS;
    private static int maxEagerClientLoadsPerTick = DEFAULT_MAX_EAGER_CLIENT_LOADS_PER_TICK;
    private static int overlayMode = OVERLAY_FULL;

    private static boolean materializationInProgress;
    private static long materializerWritesIgnored;
    private static long playerWritesSaved;
    private static long commandWritesSaved;
    private static long clientInvalidationsQueued;
    private static long clientMirrorsCleaned;
    private static long forcedClientLoads;
    private static long immediatePlayerCubeMaterializations;
    private static long eagerClientLoads;
    private static long eagerClientGeneratedLoads;
    private static int eagerClientLoadsLastTick;
    private static int eagerClientGeneratedLastTick;

    private CubicClientSyncBridge() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Optional<ServerLevel> maybeCubic = new CubicTestDimensionService().level(event.getServer());
        if (maybeCubic.isEmpty()) {
            PLAYER_STATES.clear();
            return;
        }

        ServerLevel cubicLevel = maybeCubic.get();
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(event.getServer());
        Set<UUID> activePlayers = new HashSet<>();

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel playerLevel) || !isCubicTest(playerLevel)) {
                continue;
            }
            activePlayers.add(player.getUUID());
            PlayerBridgeState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerBridgeState());
            tickPlayer(cubicLevel, player, cache, state);
        }

        // Drop per-player materialized bookkeeping immediately after the player leaves cubic_test. The vanilla shell
        // blocks themselves are intentionally left alone; they are only a temporary compatibility view of the backend.
        PLAYER_STATES.keySet().removeIf(uuid -> !activePlayers.contains(uuid));
    }

    public static int resetAll() {
        int size = PLAYER_STATES.size();
        PLAYER_STATES.clear();
        return size;
    }

    public static void resetCounters() {
        materializerWritesIgnored = 0L;
        playerWritesSaved = 0L;
        commandWritesSaved = 0L;
        clientInvalidationsQueued = 0L;
        clientMirrorsCleaned = 0L;
        forcedClientLoads = 0L;
        immediatePlayerCubeMaterializations = 0L;
        eagerClientLoads = 0L;
        eagerClientGeneratedLoads = 0L;
        eagerClientLoadsLastTick = 0;
        eagerClientGeneratedLastTick = 0;
    }

    public static boolean isMaterializedForAnyPlayer(CubePos cubePos) {
        for (PlayerBridgeState state : PLAYER_STATES.values()) {
            if (state.materializedHashes.containsKey(cubePos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean materializationInProgress() {
        return materializationInProgress;
    }

    public static int trackedPlayers() {
        return PLAYER_STATES.size();
    }

    public static int totalMaterializedCubes() {
        int total = 0;
        for (PlayerBridgeState state : PLAYER_STATES.values()) {
            total += state.materializedHashes.size();
        }
        return total;
    }

    public static int totalQueuedMaterializations() {
        int total = 0;
        for (PlayerBridgeState state : PLAYER_STATES.values()) {
            total += state.materializationQueue.size();
        }
        return total;
    }

    public static long materializerWritesIgnored() {
        return materializerWritesIgnored;
    }

    public static long playerWritesSaved() {
        return playerWritesSaved;
    }

    public static long commandWritesSaved() {
        return commandWritesSaved;
    }

    public static long clientInvalidationsQueued() {
        return clientInvalidationsQueued;
    }

    public static long clientMirrorsCleaned() {
        return clientMirrorsCleaned;
    }

    public static long forcedClientLoads() {
        return forcedClientLoads;
    }

    public static long immediatePlayerCubeMaterializations() {
        return immediatePlayerCubeMaterializations;
    }

    public static long eagerClientLoads() {
        return eagerClientLoads;
    }

    public static long eagerClientGeneratedLoads() {
        return eagerClientGeneratedLoads;
    }

    public static int eagerClientLoadsLastTick() {
        return eagerClientLoadsLastTick;
    }

    public static int eagerClientGeneratedLastTick() {
        return eagerClientGeneratedLastTick;
    }

    public static int eagerLoadHorizontalRadius() {
        return eagerLoadHorizontalRadius;
    }

    public static int eagerLoadVerticalRadius() {
        return eagerLoadVerticalRadius;
    }

    public static int maxEagerClientLoadsPerTick() {
        return maxEagerClientLoadsPerTick;
    }

    public static int streamHorizontalRadius() {
        return streamHorizontalRadius;
    }

    public static int streamVerticalRadius() {
        return streamVerticalRadius;
    }

    public static int maxMaterializedCubesPerTick() {
        return maxMaterializedCubesPerTick;
    }

    public static int syncPacketIntervalTicks() {
        return syncPacketIntervalTicks;
    }

    public static int overlayMode() {
        return overlayMode;
    }

    public static String overlayModeName() {
        return overlayModeName(overlayMode);
    }

    public static String overlayModeName(int mode) {
        return switch (mode) {
            case OVERLAY_HIDDEN -> "hidden";
            case OVERLAY_COMPACT -> "compact";
            case OVERLAY_FULL -> "full";
            default -> "unknown(" + mode + ")";
        };
    }

    public static void setOverlayMode(int mode) {
        if (mode != OVERLAY_HIDDEN && mode != OVERLAY_COMPACT && mode != OVERLAY_FULL) {
            throw new IllegalArgumentException("Unknown M8 overlay mode: " + mode);
        }
        overlayMode = mode;
    }

    public static void configureStream(int horizontalRadius, int verticalRadius, int maxPerTick, int packetIntervalTicks) {
        streamHorizontalRadius = Mth.clamp(horizontalRadius, 0, 10);
        streamVerticalRadius = Mth.clamp(verticalRadius, 0, 4);
        maxMaterializedCubesPerTick = Mth.clamp(maxPerTick, 1, 16);
        syncPacketIntervalTicks = Mth.clamp(packetIntervalTicks, 1, 100);
        eagerLoadHorizontalRadius = Math.min(streamHorizontalRadius, DEFAULT_EAGER_LOAD_HORIZONTAL_RADIUS);
        eagerLoadVerticalRadius = Math.min(streamVerticalRadius, DEFAULT_EAGER_LOAD_VERTICAL_RADIUS);
        maxEagerClientLoadsPerTick = Math.min(maxMaterializedCubesPerTick, DEFAULT_MAX_EAGER_CLIENT_LOADS_PER_TICK);
        for (PlayerBridgeState state : PLAYER_STATES.values()) {
            state.materializationQueue.clear();
            state.queued.clear();
            state.dirtyInvalidationsAccounted.clear();
        }
    }

    public static void resetStreamConfig() {
        configureStream(
                DEFAULT_STREAM_HORIZONTAL_RADIUS,
                DEFAULT_STREAM_VERTICAL_RADIUS,
                DEFAULT_MAX_MATERIALIZED_CUBES_PER_TICK,
                DEFAULT_SYNC_PACKET_INTERVAL_TICKS
        );
    }

    public static void recordCommandWriteSaved() {
        commandWritesSaved++;
    }

    private static void tickPlayer(ServerLevel level, ServerPlayer player, ServerCubeCache cache, PlayerBridgeState state) {
        state.tickCounter++;
        state.materializedLastTick = 0;

        CubePos playerCube = CubePos.fromBlock(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
        ensurePlayerCubeVisible(level, cache, playerCube, state);
        ensureEagerNeighborhoodLoaded(level, cache, playerCube, state);
        queueVisibleLoadedCubes(cache, playerCube, state);
        materializeQueued(level, cache, state);

        if (state.tickCounter % syncPacketIntervalTicks == 0) {
            PacketDistributor.sendToPlayer(player, buildPayload(cache, playerCube, state));
        }
    }

    private static void ensurePlayerCubeVisible(ServerLevel level, ServerCubeCache cache, CubePos playerCube, PlayerBridgeState state) {
        Optional<CubeHolder> holder = cache.ensureLoadedForClient(playerCube, com.redline.worldcore.api.ticket.CubeTicketLevel.FULL);
        if (holder.isEmpty() || !isInsidePhysicalShell(level, playerCube)) {
            return;
        }
        forcedClientLoads++;
        long hash = CubeGenerationSummary.from(holder.get().cube()).hash();
        boolean clientDirty = cache.clientSyncDirty(playerCube);
        if (!clientDirty && state.materializedHashes.getOrDefault(playerCube, Long.MIN_VALUE) == hash) {
            return;
        }
        materializeCube(level, holder.get().cube());
        rememberMaterialized(state, playerCube, hash);
        scheduleWaterTicksWhenNeighborhoodReady(level, holder.get().cube(), state);
        if (clientDirty) {
            cache.recordClientMirrorSynced(playerCube);
            state.dirtyInvalidationsAccounted.remove(playerCube);
            clientMirrorsCleaned++;
        }
        immediatePlayerCubeMaterializations++;
    }

    private static void ensureEagerNeighborhoodLoaded(ServerLevel level, ServerCubeCache cache, CubePos playerCube, PlayerBridgeState state) {
        eagerClientLoadsLastTick = 0;
        eagerClientGeneratedLastTick = 0;

        List<CubePos> eager = new ArrayList<>();
        for (int y = playerCube.y() - eagerLoadVerticalRadius; y <= playerCube.y() + eagerLoadVerticalRadius; y++) {
            for (int z = playerCube.z() - eagerLoadHorizontalRadius; z <= playerCube.z() + eagerLoadHorizontalRadius; z++) {
                for (int x = playerCube.x() - eagerLoadHorizontalRadius; x <= playerCube.x() + eagerLoadHorizontalRadius; x++) {
                    CubePos cubePos = new CubePos(x, y, z);
                    if (!isInsidePhysicalShell(level, cubePos)) {
                        continue;
                    }
                    if (cache.holder(cubePos).isPresent()) {
                        continue;
                    }
                    eager.add(cubePos);
                }
            }
        }
        eager.sort(Comparator
                .comparingInt((CubePos cubePos) -> manhattan(cubePos, playerCube))
                .thenComparingInt(cubePos -> Math.abs(cubePos.y() - playerCube.y()))
                .thenComparingInt(CubePos::x)
                .thenComparingInt(CubePos::z));

        int loaded = 0;
        for (CubePos cubePos : eager) {
            if (loaded >= maxEagerClientLoadsPerTick) {
                break;
            }
            Optional<CubeHolder> holder = cache.ensureLoadedForClient(cubePos, com.redline.worldcore.api.ticket.CubeTicketLevel.FULL);
            if (holder.isEmpty()) {
                continue;
            }
            loaded++;
            eagerClientLoads++;
            eagerClientLoadsLastTick++;
            if (holder.get().state() == com.redline.worldcore.server.cube.CubeHolderState.GENERATED) {
                eagerClientGeneratedLoads++;
                eagerClientGeneratedLastTick++;
            }
        }
    }

    private static void queueVisibleLoadedCubes(ServerCubeCache cache, CubePos playerCube, PlayerBridgeState state) {
        List<CubePos> visible = new ArrayList<>();
        for (int y = playerCube.y() - streamVerticalRadius; y <= playerCube.y() + streamVerticalRadius; y++) {
            for (int z = playerCube.z() - streamHorizontalRadius; z <= playerCube.z() + streamHorizontalRadius; z++) {
                for (int x = playerCube.x() - streamHorizontalRadius; x <= playerCube.x() + streamHorizontalRadius; x++) {
                    visible.add(new CubePos(x, y, z));
                }
            }
        }
        visible.sort(Comparator
                .comparingInt((CubePos cubePos) -> manhattan(cubePos, playerCube))
                .thenComparingInt(CubePos::y));

        for (CubePos cubePos : visible) {
            Optional<CubeHolder> holder = cache.holder(cubePos);
            if (holder.isEmpty()) {
                continue;
            }
            long hash = CubeGenerationSummary.from(holder.get().cube()).hash();
            boolean clientDirty = cache.clientSyncDirty(cubePos);
            if (!clientDirty && state.materializedHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash) {
                continue;
            }
            if (!state.queued.add(cubePos)) {
                continue;
            }
            state.materializationQueue.addLast(cubePos);
            if (clientDirty && state.dirtyInvalidationsAccounted.add(cubePos)) {
                clientInvalidationsQueued++;
            }
        }
    }

    private static void materializeQueued(ServerLevel level, ServerCubeCache cache, PlayerBridgeState state) {
        int materialized = 0;
        while (materialized < maxMaterializedCubesPerTick && !state.materializationQueue.isEmpty()) {
            CubePos cubePos = state.materializationQueue.removeFirst();
            state.queued.remove(cubePos);
            Optional<CubeHolder> holder = cache.holder(cubePos);
            if (holder.isEmpty() || !isInsidePhysicalShell(level, cubePos)) {
                continue;
            }

            long hash = CubeGenerationSummary.from(holder.get().cube()).hash();
            boolean clientDirty = cache.clientSyncDirty(cubePos);
            if (!clientDirty && state.materializedHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash) {
                continue;
            }
            materializeCube(level, holder.get().cube());
            rememberMaterialized(state, cubePos, hash);
            scheduleWaterTicksWhenNeighborhoodReady(level, holder.get().cube(), state);
            if (clientDirty) {
                cache.recordClientMirrorSynced(cubePos);
                state.dirtyInvalidationsAccounted.remove(cubePos);
                clientMirrorsCleaned++;
            } else {
                state.dirtyInvalidationsAccounted.remove(cubePos);
            }
            materialized++;
            state.materializedLastTick++;
        }
    }

    private static void materializeCube(ServerLevel level, LevelCube cube) {
        CubePos cubePos = cube.cubePos();
        materializationInProgress = true;
        try {
            for (int localY = 0; localY < CubePos.SIZE; localY++) {
                int worldY = cubePos.minBlockY() + localY;
                if (level.isOutsideBuildHeight(worldY)) {
                    continue;
                }
                for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                    for (int localX = 0; localX < CubePos.SIZE; localX++) {
                        BlockState state = cube.getBlockState(localX, localY, localZ);
                        BlockPos blockPos = new BlockPos(cubePos.minBlockX() + localX, worldY, cubePos.minBlockZ() + localZ);
                        if (!level.getBlockState(blockPos).equals(state)) {
                            level.setBlock(blockPos, state, SET_BLOCK_FLAGS);
                        }
                    }
                }
            }
        } finally {
            materializationInProgress = false;
        }
    }

    private static void scheduleWaterTicksWhenNeighborhoodReady(ServerLevel level, LevelCube cube, PlayerBridgeState state) {
        CubePos cubePos = cube.cubePos();
        if (!state.materializedHashes.containsKey(new CubePos(cubePos.x() + 1, cubePos.y(), cubePos.z()))
                || !state.materializedHashes.containsKey(new CubePos(cubePos.x() - 1, cubePos.y(), cubePos.z()))
                || !state.materializedHashes.containsKey(new CubePos(cubePos.x(), cubePos.y(), cubePos.z() + 1))
                || !state.materializedHashes.containsKey(new CubePos(cubePos.x(), cubePos.y(), cubePos.z() - 1))) {
            return;
        }

        int scheduled = 0;
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            int worldY = cubePos.minBlockY() + localY;
            if (level.isOutsideBuildHeight(worldY)) {
                continue;
            }
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    BlockState blockState = cube.getBlockState(localX, localY, localZ);
                    FluidState fluidState = blockState.getFluidState();
                    if (!fluidState.getType().isSame(Fluids.WATER)) {
                        continue;
                    }
                    // Cap per cube: this is just a wake-up pass after the neighboring shell is present, not full fluid sim.
                    if (scheduled >= 96) {
                        return;
                    }
                    BlockPos blockPos = new BlockPos(cubePos.minBlockX() + localX, worldY, cubePos.minBlockZ() + localZ);
                    level.scheduleTick(blockPos, fluidState.getType(), Math.max(1, fluidState.getType().getTickDelay(level)));
                    WaterSurfaceSupportDebug.recordScheduledPostMaterializeTick();
                    scheduled++;
                }
            }
        }
    }

    private static void rememberMaterialized(PlayerBridgeState state, CubePos cubePos, long hash) {
        if (state.materializedHashes.size() >= MAX_TRACKED_MATERIALIZED) {
            CubePos first = state.materializedHashes.keySet().iterator().next();
            state.materializedHashes.remove(first);
        }
        state.materializedHashes.put(cubePos, hash);
    }

    private static CubeClientSyncPayload buildPayload(ServerCubeCache cache, CubePos playerCube, PlayerBridgeState state) {
        CubeLoadingSnapshot snapshot = cache.snapshot();
        EntityTrackingSnapshot entitySnapshot = EntityCubeTracker.snapshot(playerCube);
        CubePregenSnapshot pregenSnapshot = CubePregenManager.MANAGER.snapshot();
        CubePos busiestEntityCube = entitySnapshot.busiestCube();
        List<CubeClientSyncPayload.Entry> entries = new ArrayList<>();
        List<CubeHolder> nearby = cache.sortedHolders().stream()
                .sorted(Comparator.comparingInt(holder -> manhattan(holder.cubePos(), playerCube)))
                .limit(MAX_PACKET_ENTRIES)
                .toList();

        for (CubeHolder holder : nearby) {
            CubePos cubePos = holder.cubePos();
            long hash = CubeGenerationSummary.from(holder.cube()).hash();
            boolean materialized = state.materializedHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash;
            StaticLightSummary light = StaticLightSummary.from(holder.cube());
            SkyLightSummary sky = SkyLightSummary.from(holder.cube());
            entries.add(new CubeClientSyncPayload.Entry(
                    cubePos.x(),
                    cubePos.y(),
                    cubePos.z(),
                    holder.cube().status().ordinal(),
                    holder.state().ordinal(),
                    holder.ticketLevel().ordinal(),
                    hash,
                    light.maxLight(),
                    light.litBlocks(),
                    light.emittingBlocks(),
                    light.hash(),
                    sky.maxLight(),
                    sky.litBlocks(),
                    sky.bottomLitBlocks(),
                    sky.hash(),
                    holder.dirty(),
                    materialized
            ));
        }

        return new CubeClientSyncPayload(
                playerCube.x(),
                playerCube.y(),
                playerCube.z(),
                snapshot.loadedCubes(),
                snapshot.pendingLoads(),
                snapshot.requestedCubes(),
                snapshot.loadedLastTick(),
                snapshot.generatedLastTick(),
                snapshot.loadMicrosLastTick(),
                snapshot.loadMicrosMax(),
                snapshot.maxLoadsPerTick(),
                snapshot.maxGeneratedLoadsPerTick(),
                snapshot.maxLoadMicrosPerTick(),
                snapshot.loadGeneratedBudgetHitLastTick(),
                snapshot.loadTimeBudgetHitLastTick(),
                snapshot.totalGenerated(),
                snapshot.totalLightRebuilt(),
                snapshot.lightRebuiltLastTick(),
                snapshot.lightDirtyQueue(),
                snapshot.totalSkyLightRebuilt(),
                snapshot.totalSkyLightColumnsRebuilt(),
                snapshot.totalSkyLightSkippedUnchanged(),
                snapshot.totalSkyLightSavedChanged(),
                snapshot.skyLightColumnsLastTick(),
                snapshot.skyLightDirtyColumns(),
                snapshot.skyLightChangedLastTick(),
                snapshot.skyLightSkippedUnchangedLastTick(),
                snapshot.skyLightSavedChangedLastTick(),
                snapshot.skyLightRebuildMicrosLastTick(),
                snapshot.skyLightRebuildMicrosMax(),
                snapshot.skyLightAutoColumnsPerTick(),
                snapshot.skyLightDirtyDelayTicks(),
                state.materializedHashes.size(),
                state.materializationQueue.size(),
                state.materializedLastTick,
                overlayMode,
                streamHorizontalRadius,
                streamVerticalRadius,
                maxMaterializedCubesPerTick,
                syncPacketIntervalTicks,
                playerWritesSaved,
                materializerWritesIgnored,
                commandWritesSaved,
                entitySnapshot.trackedEntities(),
                entitySnapshot.entitySections(),
                entitySnapshot.entitiesInPlayerCube(),
                entitySnapshot.scannedLastTick(),
                entitySnapshot.addedLastTick(),
                entitySnapshot.movedLastTick(),
                entitySnapshot.removedLastTick(),
                entitySnapshot.totalMoved(),
                busiestEntityCube == null ? 0 : busiestEntityCube.x(),
                busiestEntityCube == null ? 0 : busiestEntityCube.y(),
                busiestEntityCube == null ? 0 : busiestEntityCube.z(),
                entitySnapshot.busiestCubeEntities(),
                entitySnapshot.playerEntities(),
                entitySnapshot.mobEntities(),
                entitySnapshot.itemEntities(),
                entitySnapshot.projectileEntities(),
                entitySnapshot.otherEntities(),
                entitySnapshot.scanMicrosLastTick(),
                entitySnapshot.scanMicrosAverage(),
                entitySnapshot.scanMicrosMax(),
                pregenSnapshot.running(),
                pregenSnapshot.paused(),
                pregenSnapshot.queuedCubes(),
                pregenSnapshot.activeTotalCubes(),
                pregenSnapshot.activeProcessedCubes(),
                pregenSnapshot.totalCompletedJobs(),
                pregenSnapshot.totalProcessedCubes(),
                pregenSnapshot.totalGeneratedCubes(),
                pregenSnapshot.totalSkippedCubes(),
                pregenSnapshot.totalFailedCubes(),
                pregenSnapshot.lastTickProcessed(),
                pregenSnapshot.lastTickGenerated(),
                pregenSnapshot.lastTickSkipped(),
                pregenSnapshot.lastTickFailed(),
                pregenSnapshot.lastTickMicros(),
                pregenSnapshot.maxTickMicros(),
                pregenSnapshot.maxCubesPerTick(),
                pregenSnapshot.maxMillisPerTick(),
                pregenSnapshot.targetStatus().ordinal(),
                pregenSnapshot.activeJobId(),
                pregenSnapshot.lastError(),
                pregenSnapshot.maxSkippedCubesPerTick(),
                pregenSnapshot.maxGeneratedCubesPerSecond(),
                pregenSnapshot.expensiveCubeMillis(),
                pregenSnapshot.cooldownAfterExpensiveTicks(),
                pregenSnapshot.throttleCooldownTicks(),
                pregenSnapshot.generatedThisSecond(),
                pregenSnapshot.throttleReason(),
                pregenSnapshot.visitedColumns(),
                pregenSnapshot.backfillDoneColumns(),
                pregenSnapshot.backfillEnabled(),
                pregenSnapshot.backfillPendingColumns(),
                pregenSnapshot.backfillJobsStarted(),
                pregenSnapshot.backfillMaxVerticalRadius(),
                pregenSnapshot.backfillDelayTicks(),
                pregenSnapshot.backfillTargetStatus(),
                pregenSnapshot.backfillLastReason(),
                pregenSnapshot.afkEnabled(),
                pregenSnapshot.afkTrackedPlayers(),
                pregenSnapshot.afkPlayers(),
                pregenSnapshot.afkJobsStarted(),
                pregenSnapshot.afkAfterTicks(),
                pregenSnapshot.afkRadiusBlocks(),
                pregenSnapshot.afkVerticalRadiusCubes(),
                pregenSnapshot.afkTargetStatus(),
                pregenSnapshot.afkLastReason(),
                entries
        );
    }

    private static int manhattan(CubePos first, CubePos second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.y() - second.y()) + Math.abs(first.z() - second.z());
    }

    private static boolean isInsidePhysicalShell(ServerLevel level, CubePos cubePos) {
        return !level.isOutsideBuildHeight(cubePos.minBlockY()) && !level.isOutsideBuildHeight(cubePos.maxBlockY());
    }

    public static void onBlockBreak(BreakBlockEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !isCubicTest(level)) {
            return;
        }
        if (materializationInProgress) {
            materializerWritesIgnored++;
            return;
        }
        if (writeBlockEdit(level, event.getPos(), Blocks.AIR.defaultBlockState())) {
            playerWritesSaved++;
        }
    }

    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        LevelAccessor accessor = event.getLevel();
        if (!(accessor instanceof ServerLevel level) || !isCubicTest(level)) {
            return;
        }
        if (materializationInProgress) {
            materializerWritesIgnored++;
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multiPlace) {
            for (var snapshot : multiPlace.getReplacedBlockSnapshots()) {
                if (writeBlockEdit(level, snapshot.getPos(), level.getBlockState(snapshot.getPos()))) {
                    playerWritesSaved++;
                }
            }
            return;
        }
        if (writeBlockEdit(level, event.getPos(), event.getPlacedBlock())) {
            playerWritesSaved++;
        }
    }

    private static boolean writeBlockEdit(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.isOutsideBuildHeight(pos)) {
            return false;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        return cache.writeBlock(pos, state, true).isPresent();
    }

    private static boolean isCubicTest(ServerLevel level) {
        return level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL);
    }

    private static final class PlayerBridgeState {
        private final ArrayDeque<CubePos> materializationQueue = new ArrayDeque<>();
        private final Set<CubePos> queued = new HashSet<>();
        private final Set<CubePos> dirtyInvalidationsAccounted = new HashSet<>();
        private final Map<CubePos, Long> materializedHashes = new HashMap<>();
        private int tickCounter;
        private int materializedLastTick;
    }
}
