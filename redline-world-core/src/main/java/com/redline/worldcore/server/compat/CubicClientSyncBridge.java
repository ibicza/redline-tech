package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.network.CubeClientSyncPayload;
import com.redline.worldcore.network.CubeSectionDeltaPayload;
import com.redline.worldcore.network.CubeSectionSnapshotBatchPayload;
import com.redline.worldcore.network.ClientCubeSectionAckPayload;
import com.redline.worldcore.network.CubeSectionSnapshotPayload;
import com.redline.worldcore.network.CubeSectionUnloadPayload;
import com.redline.worldcore.server.cube.CubeHolder;
import com.redline.worldcore.server.cube.CubeClientStage;
import com.redline.worldcore.server.cube.CubeLoadingSnapshot;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import com.redline.worldcore.server.entity.EntityCubeTracker;
import com.redline.worldcore.server.entity.EntityTrackingSnapshot;
import com.redline.worldcore.server.lighting.SkyLightSummary;
import com.redline.worldcore.server.lighting.StaticLightSummary;
import com.redline.worldcore.server.pregen.CubePregenManager;
import com.redline.worldcore.server.pregen.CubePregenSnapshot;
import com.redline.worldcore.server.sync.CubeSectionSnapshot;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
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
import java.util.LinkedHashSet;
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
    public static final int DEFAULT_MAX_MATERIALIZED_CUBES_PER_TICK = 10;
    public static final int DEFAULT_SYNC_PACKET_INTERVAL_TICKS = 5;

    /**
     * M14.9.3: strict cubic shell must not wait for the normal gameplay pending-load throttle before nearby cubes
     * become visible. The normal player ticket still requests a much larger slab, but that path generates only one
     * fresh cube per tick. This small eager window makes the cubes the player can step/fly into cube-owned before
     * the player crosses the border, without reintroducing a visible vanilla flat fallback.
     */
    public static final int DEFAULT_EAGER_LOAD_HORIZONTAL_RADIUS = 3;
    public static final int DEFAULT_EAGER_LOAD_VERTICAL_RADIUS = 1;
    public static final int DEFAULT_MAX_EAGER_CLIENT_LOADS_PER_TICK = 4;
    public static final int DEFAULT_MAX_EAGER_CLIENT_GENERATED_LOADS_PER_TICK = 1;
    public static final int DEFAULT_MAX_EAGER_CLIENT_LOAD_MICROS_PER_TICK = 2_500;
    public static final int MAX_PACKET_ENTRIES = 96;
    public static final int DEFAULT_MAX_VISIBLE_QUEUE_SCANS_PER_TICK = 384;
    public static final int DEFAULT_MAX_MATERIALIZE_BLOCKS_PER_TICK = 32_768;
    public static final int DEFAULT_MAX_MATERIALIZE_MICROS_PER_TICK = 12_000;
    private static final int MAX_ADAPTIVE_MATERIALIZE_BLOCKS_PER_TICK = 98_304;
    private static final int MAX_ADAPTIVE_MATERIALIZE_MICROS_PER_TICK = 28_000;
    private static final int MAX_ADAPTIVE_MATERIALIZED_CUBES_PER_TICK = 32;
    private static final int MAX_PRIORITY_WHOLE_CUBES_PER_TICK = 1;
    private static final int HARD_MATERIALIZE_MICROS_PER_TICK = 18_000;
    private static final int DEFAULT_NEAR_SHELL_HORIZONTAL_RADIUS = 4;
    private static final int DEFAULT_NEAR_SHELL_VERTICAL_RADIUS = 1;
    private static final int DEFAULT_HYBRID_SHELL_HORIZONTAL_RADIUS = 5;
    private static final int DEFAULT_HYBRID_SHELL_VERTICAL_RADIUS = 1;
    private static final int DEFAULT_HYBRID_ALWAYS_SHELL_HORIZONTAL_RADIUS = 2;
    private static final int DEFAULT_HYBRID_FAST_SHELL_HORIZONTAL_RADIUS = 1;
    private static final int DEFAULT_HYBRID_FAST_SHELL_VERTICAL_RADIUS = 1;
    private static final int DEFAULT_NATIVE_RENDER_SHELL_HORIZONTAL_RADIUS = 0;
    private static final int DEFAULT_NATIVE_RENDER_SHELL_VERTICAL_RADIUS = 0;
    private static final int DEFAULT_NATIVE_SECTION_SNAPSHOTS_PER_TICK = 8;
    private static final int DEFAULT_NATIVE_SECTION_SNAPSHOT_MICROS_PER_TICK = 3_000;
    private static final int DEFAULT_NATIVE_SECTION_PACKET_BYTES_PER_TICK = 96 * 1024;
    private static final int DEFAULT_NATIVE_SECTION_BATCH_MAX_SNAPSHOTS = 8;
    private static final int MAX_NATIVE_SECTION_QUEUE = 4096;
    private static final int MAX_NATIVE_SECTION_UNLOADS_PER_TICK = 48;
    private static final int MAX_NATIVE_SECTION_UNLOAD_SCAN_PER_TICK = 256;
    private static final int NATIVE_SECTION_RETAIN_EXTRA_HORIZONTAL = 2;
    private static final int NATIVE_SECTION_RETAIN_EXTRA_VERTICAL = 1;
    private static final boolean SURFACE_PROJECTION_ENABLED = true;
    private static final int SURFACE_PROJECTION_BAND_BELOW = 1;
    private static final int SURFACE_PROJECTION_BAND_ABOVE = 1;
    private static final int SURFACE_PROJECTION_UNDERGROUND_RADIUS_CAP = 3;
    private static final int SURFACE_PROJECTION_MAX_COLUMNS = 112;
    private static final int SURFACE_PROJECTION_FAST_MAX_COLUMNS = 176;
    private static final double SURFACE_PROJECTION_TRAJECTORY_SPEED_SQ = 0.000025D;
    private static final double SURFACE_PROJECTION_FAST_SPEED_SQ = 0.0025D;
    private static final int SURFACE_PROJECTION_FAST_EXTRA_RADIUS = 4;
    private static final int SURFACE_PROJECTION_HIGH_ALTITUDE_EXTRA_RADIUS = 6;
    private static final int SURFACE_PROJECTION_HIGH_ALTITUDE_CUBE_DELTA = 2;
    private static final int SURFACE_PROJECTION_TRAJECTORY_MAX_COLUMNS = 224;
    private static final int SURFACE_PROJECTION_TRAJECTORY_SPINE_COLUMNS = 128;
    private static final double SURFACE_PROJECTION_YAW_FALLBACK_HORIZONTAL_LOOK_LEN = 0.20D;
    /*
     * M17.5.1: the M17.5 anti-spike guard cut the vanilla shell into very small linear slices.
     * Visually this looked like a printer/progress-bar because local index order is a straight X/Z line.
     * Use larger time-budgeted slices again, but keep strict intra-cube yielding and a non-linear 4x4x4 tile order.
     */
    private static final int MAX_MATERIALIZE_BLOCKS_PER_SLICE = CubePos.BLOCK_COUNT;
    private static final int MAX_PRIORITY_MATERIALIZE_BLOCKS_PER_SLICE = CubePos.BLOCK_COUNT;
    private static final int MATERIALIZE_CHECK_INTERVAL_BLOCKS = 8;
    private static final int MATERIALIZE_TILE_SIZE = 4;
    private static final int MATERIALIZE_TILE_BITS = 2;
    private static final int MATERIALIZE_TILE_MASK = MATERIALIZE_TILE_SIZE - 1;
    private static final int MATERIALIZE_SETBLOCK_SLOW_MICROS = 1_000;
    private static final int MATERIALIZE_BLOCK_ACCESS_SLOW_MICROS = 1_000;
    private static final int MATERIALIZE_SLICE_SOFT_MICROS = 1_500;
    private static final int MATERIALIZE_SLICE_HARD_MICROS = 3_000;
    private static final int MATERIALIZE_BACKOFF_TICKS_AFTER_SLOW_BLOCK = 2;

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
    private static int maxEagerClientGeneratedLoadsPerTick = DEFAULT_MAX_EAGER_CLIENT_GENERATED_LOADS_PER_TICK;
    private static int maxVisibleQueueScansPerTick = DEFAULT_MAX_VISIBLE_QUEUE_SCANS_PER_TICK;
    private static int maxMaterializeBlocksPerTick = DEFAULT_MAX_MATERIALIZE_BLOCKS_PER_TICK;
    private static int maxMaterializeMicrosPerTick = DEFAULT_MAX_MATERIALIZE_MICROS_PER_TICK;
    private static int overlayMode = OVERLAY_FULL;
    private static VanillaShellMode vanillaShellMode = VanillaShellMode.CUBE_ONLY;
    private static int nearShellHorizontalRadius = DEFAULT_NEAR_SHELL_HORIZONTAL_RADIUS;
    private static int nearShellVerticalRadius = DEFAULT_NEAR_SHELL_VERTICAL_RADIUS;
    private static int hybridShellHorizontalRadius = DEFAULT_HYBRID_SHELL_HORIZONTAL_RADIUS;
    private static int hybridShellVerticalRadius = DEFAULT_HYBRID_SHELL_VERTICAL_RADIUS;
    private static int hybridAlwaysShellHorizontalRadius = DEFAULT_HYBRID_ALWAYS_SHELL_HORIZONTAL_RADIUS;

    private static boolean materializationInProgress;
    private static long materializerWritesIgnored;
    private static long playerWritesSaved;
    private static long commandWritesSaved;
    private static long clientInvalidationsQueued;
    private static long clientMirrorsCleaned;
    private static long forcedClientLoads;
    private static long immediatePlayerCubeMaterializations;
    private static long nativeReadyRecorded;
    private static long vanillaShellSkippedNativeReady;
    private static long vanillaShellGlobalReadyHits;
    private static long eagerClientLoads;
    private static long eagerClientGeneratedLoads;
    private static long nativeSectionSnapshotsPrepared;
    private static long nativeSectionSnapshotBuildRequests;
    private static long nativeSectionDeltaPacketsSent;
    private static long nativeSectionDeltaEntriesSent;
    private static long nativeSectionDeltaBytesSent;
    private static long nativeSectionAcksReceived;
    private static long nativeSectionAckEntriesReceived;
    private static long nativeSectionAckHashSkips;
    private static long nativeSectionPacketsSent;
    private static long nativeSectionUnloadPacketsSent;
    private static long nativeSectionBytesSent;
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
        nativeReadyRecorded = 0L;
        vanillaShellSkippedNativeReady = 0L;
        vanillaShellGlobalReadyHits = 0L;
        eagerClientLoads = 0L;
        eagerClientGeneratedLoads = 0L;
        nativeSectionSnapshotsPrepared = 0L;
        nativeSectionSnapshotBuildRequests = 0L;
        nativeSectionPacketsSent = 0L;
        nativeSectionUnloadPacketsSent = 0L;
        nativeSectionBytesSent = 0L;
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

    public static int totalNativeReadyCubes() {
        int total = 0;
        for (PlayerBridgeState state : PLAYER_STATES.values()) {
            total += state.nativeReadyHashes.size();
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

    public static long nativeReadyRecorded() {
        return nativeReadyRecorded;
    }

    public static long vanillaShellSkippedNativeReady() {
        return vanillaShellSkippedNativeReady;
    }

    public static long vanillaShellGlobalReadyHits() {
        return vanillaShellGlobalReadyHits;
    }

    public static String vanillaShellModeName() {
        return vanillaShellMode.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static void setVanillaShellMode(String modeName) {
        String normalized = modeName == null ? "" : modeName.trim().toUpperCase(java.util.Locale.ROOT);
        vanillaShellMode = switch (normalized) {
            case "FULL" -> VanillaShellMode.FULL;
            case "NEAR" -> VanillaShellMode.NEAR;
            case "HYBRID" -> VanillaShellMode.HYBRID;
            case "HYBRID_FAST", "FAST" -> VanillaShellMode.HYBRID_FAST;
            case "NATIVE_RENDER", "NATIVE_VISUAL", "RENDER_NATIVE" -> VanillaShellMode.NATIVE_RENDER;
            case "CUBE_ONLY", "NATIVE_RENDER_ONLY", "CUBE_ONLY_RENDER" -> VanillaShellMode.CUBE_ONLY;
            case "NATIVE", "NATIVE_ONLY", "DEBUG_NATIVE" -> VanillaShellMode.NATIVE_ONLY;
            default -> throw new IllegalArgumentException("Unknown RWC vanilla shell mode: " + modeName);
        };
        for (PlayerBridgeState state : PLAYER_STATES.values()) {
            state.visibleOrder = List.of();
            state.visibleOrderCenter = null;
            state.visibleCursor = 0;
        }
    }

    public static long eagerClientLoads() {
        return eagerClientLoads;
    }

    public static long eagerClientGeneratedLoads() {
        return eagerClientGeneratedLoads;
    }

    public static long nativeSectionSnapshotsPrepared() {
        return nativeSectionSnapshotsPrepared;
    }

    public static long nativeSectionSnapshotBuildRequests() {
        return nativeSectionSnapshotBuildRequests;
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
        maxEagerClientGeneratedLoadsPerTick = Math.min(maxEagerClientLoadsPerTick, DEFAULT_MAX_EAGER_CLIENT_GENERATED_LOADS_PER_TICK);
        maxVisibleQueueScansPerTick = Mth.clamp(maxMaterializedCubesPerTick * 64, 192, DEFAULT_MAX_VISIBLE_QUEUE_SCANS_PER_TICK * 2);
        nearShellHorizontalRadius = Math.min(streamHorizontalRadius, DEFAULT_NEAR_SHELL_HORIZONTAL_RADIUS);
        nearShellVerticalRadius = Math.min(streamVerticalRadius, DEFAULT_NEAR_SHELL_VERTICAL_RADIUS);
        hybridShellHorizontalRadius = Math.min(streamHorizontalRadius, DEFAULT_HYBRID_SHELL_HORIZONTAL_RADIUS);
        hybridShellVerticalRadius = Math.min(streamVerticalRadius, DEFAULT_HYBRID_SHELL_VERTICAL_RADIUS);
        hybridAlwaysShellHorizontalRadius = Math.min(hybridShellHorizontalRadius, DEFAULT_HYBRID_ALWAYS_SHELL_HORIZONTAL_RADIUS);
        for (PlayerBridgeState state : PLAYER_STATES.values()) {
            state.materializationQueue.clear();
            state.queued.clear();
            state.materializationTasks.clear();
            state.nativeSectionQueue.clear();
            state.nativeSectionQueued.clear();
            state.nativeSectionHashes.clear();
            state.pendingNativeSectionPayloads.clear();
            state.pendingNativeSectionPayloadBytes = 0L;
            state.materializeBackoffTicks = 0;
            state.sentNativeSectionHashes.clear();
            state.ackedNativeSectionHashes.clear();
            state.unackedNativeSectionHashes.clear();
            state.dirtyInvalidationsAccounted.clear();
            state.visibleOrder = List.of();
            state.visibleOrderCenter = null;
            state.visibleCursor = 0;
        }
    }

    public static void resetStreamConfig() {
        configureStream(
                DEFAULT_STREAM_HORIZONTAL_RADIUS,
                DEFAULT_STREAM_VERTICAL_RADIUS,
                DEFAULT_MAX_MATERIALIZED_CUBES_PER_TICK,
                DEFAULT_SYNC_PACKET_INTERVAL_TICKS
        );
        vanillaShellMode = VanillaShellMode.CUBE_ONLY;
    }

    public static void recordCommandWriteSaved() {
        commandWritesSaved++;
    }

    private static void tickPlayer(ServerLevel level, ServerPlayer player, ServerCubeCache cache, PlayerBridgeState state) {
        long profileStart = RuntimeProfiler.markStart();
        state.tickCounter++;
        state.materializedLastTick = 0;

        CubePos playerCube = CubePos.fromBlock(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
        long phaseStart = RuntimeProfiler.markStart();
        ensurePlayerCubeVisible(level, cache, playerCube, state);
        RuntimeProfiler.recordSince("client.ensure_player_cube_visible", phaseStart);
        phaseStart = RuntimeProfiler.markStart();
        ensureEagerNeighborhoodLoaded(level, player, cache, playerCube, state);
        RuntimeProfiler.recordSince("client.eager_neighborhood", phaseStart);
        phaseStart = RuntimeProfiler.markStart();
        queueVisibleLoadedCubes(cache, player, playerCube, state);
        RuntimeProfiler.recordSince("client.queue_visible_loaded", phaseStart);
        phaseStart = RuntimeProfiler.markStart();
        prepareNativeSectionSnapshots(player, cache, state);
        unloadFarNativeSections(player, cache, state, playerCube);
        RuntimeProfiler.recordSince("client.native_section_prepare_total", phaseStart);
        phaseStart = RuntimeProfiler.markStart();
        materializeQueued(level, cache, state, playerCube);
        RuntimeProfiler.recordSince("client.materialize_queued_total", phaseStart);

        if (state.tickCounter % syncPacketIntervalTicks == 0) {
            phaseStart = RuntimeProfiler.markStart();
            PacketDistributor.sendToPlayer(player, buildPayload(cache, playerCube, state));
            RuntimeProfiler.recordSince("client.sync_packet_build_send", phaseStart);
        }
        RuntimeProfiler.recordSince("client.tick_player_total", profileStart);
    }

    private static void ensurePlayerCubeVisible(ServerLevel level, ServerCubeCache cache, CubePos playerCube, PlayerBridgeState state) {
        Optional<CubeHolder> holder = cache.ensureLoadedForClient(playerCube, com.redline.worldcore.api.ticket.CubeTicketLevel.FULL);
        if (holder.isEmpty() || !isInsidePhysicalShell(level, playerCube)) {
            return;
        }
        forcedClientLoads++;
        long hash = holder.get().generationHash();
        recordNativeReady(state, holder.get(), hash);
        boolean clientDirty = cache.clientSyncDirty(playerCube);
        if (!clientDirty && holder.get().vanillaShellReady(hash)) {
            rememberMaterialized(state, playerCube, hash);
            vanillaShellGlobalReadyHits++;
            RuntimeProfiler.addCount("client.vanilla_shell_global_ready_hits", 1);
            return;
        }
        // The player cube is the only mandatory vanilla-shell fallback even in native-only mode. It keeps the current
        // prototype playable until the real M17.x cube-section packet path replaces physical shell blocks.
        if (!clientDirty && state.materializedHashes.getOrDefault(playerCube, Long.MIN_VALUE) == hash) {
            return;
        }
        if (enqueueMaterialization(state, playerCube, true)) {
            holder.get().markVanillaShellQueued();
            RuntimeProfiler.addCount("client.immediate_player_cube_materialize_enqueued", 1);
        }
        immediatePlayerCubeMaterializations++;
    }

    private static void ensureEagerNeighborhoodLoaded(ServerLevel level, ServerPlayer player, ServerCubeCache cache, CubePos playerCube, PlayerBridgeState state) {
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
        double lookX = player.getLookAngle().x;
        double lookZ = player.getLookAngle().z;
        double lookLen = Math.sqrt(lookX * lookX + lookZ * lookZ);
        if (lookLen < 1.0E-5D) {
            lookX = 0.0D;
            lookZ = 1.0D;
        } else {
            lookX /= lookLen;
            lookZ /= lookLen;
        }
        final double finalLookX = lookX;
        final double finalLookZ = lookZ;
        eager.sort(Comparator
                .comparingInt((CubePos cubePos) -> eagerPriority(cubePos, playerCube, finalLookX, finalLookZ))
                .thenComparingInt(cubePos -> Math.abs(cubePos.y() - playerCube.y()))
                .thenComparingInt(cubePos -> horizontalDistanceSquared(cubePos, playerCube))
                .thenComparingInt(CubePos::x)
                .thenComparingInt(CubePos::z));

        int loaded = 0;
        int generated = 0;
        long startNanos = System.nanoTime();
        for (CubePos cubePos : eager) {
            if (loaded >= maxEagerClientLoadsPerTick || generated >= maxEagerClientGeneratedLoadsPerTick) {
                break;
            }
            if (loaded > 0 && (System.nanoTime() - startNanos) / 1_000L >= DEFAULT_MAX_EAGER_CLIENT_LOAD_MICROS_PER_TICK) {
                RuntimeProfiler.addCount("client.eager_time_budget_hits", 1);
                break;
            }
            Optional<CubeHolder> holder = cache.ensureLoadedForClient(cubePos, com.redline.worldcore.api.ticket.CubeTicketLevel.FULL);
            if (holder.isEmpty()) {
                continue;
            }
            loaded++;
            eagerClientLoads++;
            eagerClientLoadsLastTick++;
            RuntimeProfiler.addCount("client.eager_loaded", 1);
            if (holder.get().state() == com.redline.worldcore.server.cube.CubeHolderState.GENERATED) {
                generated++;
                eagerClientGeneratedLoads++;
                eagerClientGeneratedLastTick++;
                RuntimeProfiler.addCount("client.eager_generated", 1);
            }
        }
    }

    private static void queueVisibleLoadedCubes(ServerCubeCache cache, ServerPlayer player, CubePos playerCube, PlayerBridgeState state) {
        PlayerViewFocus viewFocus = playerViewFocus(cache, player, playerCube);
        List<CubePos> visible = visibleOrder(cache, playerCube, viewFocus, state);
        if (visible.isEmpty()) {
            return;
        }
        int scanned = 0;
        int index = Math.min(state.visibleCursor, visible.size() - 1);
        while (scanned < maxVisibleQueueScansPerTick && scanned < visible.size()) {
            CubePos cubePos = visible.get(index);
            index++;
            if (index >= visible.size()) {
                index = 0;
            }
            scanned++;

            Optional<CubeHolder> holder = cache.holder(cubePos);
            if (holder.isEmpty()) {
                continue;
            }
            long hash = holder.get().generationHash();
            recordNativeReady(state, holder.get(), hash);
            boolean clientDirty = cache.clientSyncDirty(cubePos);
            boolean vanillaShellRequired = shouldMaterializeVanillaShell(cubePos, playerCube, false);
            if (shouldSendNativeSectionSnapshot(cache, cubePos, playerCube, vanillaShellRequired)) {
                enqueueNativeSectionSnapshot(state, cubePos, hash);
            }
            if (!clientDirty && holder.get().vanillaShellReady(hash)) {
                rememberMaterialized(state, cubePos, hash);
                vanillaShellGlobalReadyHits++;
                RuntimeProfiler.addCount("client.vanilla_shell_global_ready_hits", 1);
                continue;
            }
            if (!vanillaShellRequired) {
                vanillaShellSkippedNativeReady++;
                RuntimeProfiler.addCount("client.vanilla_shell_skipped_native_ready", 1);
                continue;
            }
            if (!clientDirty && state.materializedHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash) {
                continue;
            }
            if (!enqueueMaterialization(state, cubePos, false)) {
                continue;
            }
            holder.get().markVanillaShellQueued();
            RuntimeProfiler.addCount("client.vanilla_shell_queued", 1);
            if (clientDirty && state.dirtyInvalidationsAccounted.add(cubePos)) {
                clientInvalidationsQueued++;
            }
        }
        state.visibleCursor = index;
        RuntimeProfiler.addCount("client.visible_queue_scanned", scanned);
    }

    private static List<CubePos> visibleOrder(ServerCubeCache cache, CubePos playerCube, PlayerViewFocus focus, PlayerBridgeState state) {
        int dirBucketX = focus.dirX() > 0.35D ? 1 : focus.dirX() < -0.35D ? -1 : 0;
        int dirBucketZ = focus.dirZ() > 0.35D ? 1 : focus.dirZ() < -0.35D ? -1 : 0;
        int surfaceBucketX = focus.surfaceDirX() > 0.35D ? 1 : focus.surfaceDirX() < -0.35D ? -1 : 0;
        int surfaceBucketZ = focus.surfaceDirZ() > 0.35D ? 1 : focus.surfaceDirZ() < -0.35D ? -1 : 0;
        int configHash = streamHorizontalRadius * 31 + streamVerticalRadius * 17
                + (dirBucketX + 2) * 7 + (dirBucketZ + 2) * 13
                + (surfaceBucketX + 2) * 113 + (surfaceBucketZ + 2) * 127
                + (focus.underground() ? 101 : 0) + (focus.fastFlight() ? 1009 : 0)
                + (focus.highAltitude() ? 2039 : 0) + (focus.trajectoryFocus() ? 4093 : 0);
        if (playerCube.equals(state.visibleOrderCenter) && state.visibleOrderConfigHash == configHash && !state.visibleOrder.isEmpty()) {
            RuntimeProfiler.addCount("client.visible_order_reused", 1);
            return state.visibleOrder;
        }
        LinkedHashSet<CubePos> visibleSet = new LinkedHashSet<>((streamHorizontalRadius * 2 + 1) * (streamHorizontalRadius * 2 + 1) * (streamVerticalRadius * 2 + 1));
        for (int y = playerCube.y() - streamVerticalRadius; y <= playerCube.y() + streamVerticalRadius; y++) {
            for (int z = playerCube.z() - streamHorizontalRadius; z <= playerCube.z() + streamHorizontalRadius; z++) {
                for (int x = playerCube.x() - streamHorizontalRadius; x <= playerCube.x() + streamHorizontalRadius; x++) {
                    visibleSet.add(new CubePos(x, y, z));
                }
            }
        }
        int projected = addSurfaceProjectionVisible(cache, playerCube, focus, visibleSet);
        if (projected > 0) {
            RuntimeProfiler.addCount("client.surface_projection_visible_queued", projected);
        }
        List<CubePos> visible = new ArrayList<>(visibleSet);
        visible.sort(Comparator
                .comparingInt((CubePos cubePos) -> materializationPriority(cache, cubePos, playerCube, focus))
                .thenComparingInt(cubePos -> Math.abs(cubePos.y() - playerCube.y()))
                .thenComparingInt(cubePos -> horizontalDistanceSquared(cubePos, playerCube)));
        state.visibleOrderCenter = playerCube;
        state.visibleOrderConfigHash = configHash;
        state.visibleOrder = visible;
        state.visibleCursor = 0;
        RuntimeProfiler.addCount("client.visible_order_rebuilt", 1);
        return visible;
    }

    private static void materializeQueued(ServerLevel level, ServerCubeCache cache, PlayerBridgeState state, CubePos playerCube) {
        if (state.materializeBackoffTicks > 0) {
            state.materializeBackoffTicks--;
            RuntimeProfiler.addCount("client.materialize_backoff_ticks", 1);
            return;
        }
        int queueAtStart = state.materializationQueue.size();
        int completedCubes = 0;
        int priorityWholeCubes = 0;
        int blockBudget = adaptiveMaterializeBlockBudget(queueAtStart);
        int cubeBudget = adaptiveMaterializeCubeBudget(queueAtStart);
        int timeBudgetMicros = adaptiveMaterializeMicrosBudget(queueAtStart);
        long startNanos = System.nanoTime();

        RuntimeProfiler.addCount("client.materialize_adaptive_block_budget", blockBudget);
        RuntimeProfiler.addCount("client.materialize_adaptive_cube_budget", cubeBudget);

        while (completedCubes < cubeBudget && blockBudget > 0 && !state.materializationQueue.isEmpty()) {
            // M16.13: M16.12 proved that tiny fixed slices remove the worst pauses but murder visual throughput.
            // Keep a soft global time budget, but let a few near-player cubes complete whole so the shell does not
            // visibly lag behind the player. Far cubes still use slices and yield when the adaptive budget is spent.
            if (completedCubes > 0 && elapsedMicrosSince(startNanos) >= timeBudgetMicros) {
                RuntimeProfiler.addCount("client.materialize_time_budget_hits", 1);
                break;
            }

            CubePos cubePos = state.materializationQueue.removeFirst();
            Optional<CubeHolder> holder = cache.holder(cubePos);
            if (holder.isEmpty() || !isInsidePhysicalShell(level, cubePos)) {
                finishMaterializationTask(state, cubePos);
                continue;
            }

            long hash = holder.get().generationHash();
            recordNativeReady(state, holder.get(), hash);
            boolean clientDirty = cache.clientSyncDirty(cubePos);
            boolean vanillaShellRequired = shouldMaterializeVanillaShell(cubePos, playerCube, false);
            if (shouldSendNativeSectionSnapshot(cache, cubePos, playerCube, vanillaShellRequired)) {
                enqueueNativeSectionSnapshot(state, cubePos, hash);
            }
            if (!clientDirty && holder.get().vanillaShellReady(hash)) {
                rememberMaterialized(state, cubePos, hash);
                vanillaShellGlobalReadyHits++;
                RuntimeProfiler.addCount("client.vanilla_shell_global_ready_hits", 1);
                finishMaterializationTask(state, cubePos);
                continue;
            }
            if (!clientDirty && state.materializedHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash) {
                finishMaterializationTask(state, cubePos);
                continue;
            }

            holder.get().markVanillaShellMaterializing();
            MaterializationTask task = state.materializationTasks.compute(cubePos, (ignored, existing) -> {
                if (existing == null || existing.hash != hash) {
                    return new MaterializationTask(hash);
                }
                return existing;
            });

            boolean nearPlayer = isNearPlayerMaterialization(cubePos, playerCube);
            boolean prioritySlice = nearPlayer && priorityWholeCubes < MAX_PRIORITY_WHOLE_CUBES_PER_TICK;
            int sliceBudget = Math.min(blockBudget, materializeSliceBudget(queueAtStart, cubePos, playerCube, prioritySlice));
            MaterializeSliceResult result = materializeCubeSlice(
                    level,
                    holder.get().cube(),
                    task,
                    sliceBudget,
                    startNanos,
                    timeBudgetMicros,
                    prioritySlice
            );
            blockBudget -= result.scanned();

            if (!result.completed()) {
                if (nearPlayer) {
                    state.materializationQueue.addFirst(cubePos);
                    RuntimeProfiler.addCount("client.materialize_priority_requeued", 1);
                } else {
                    state.materializationQueue.addLast(cubePos);
                }
                if (blockBudget <= 0) {
                    RuntimeProfiler.addCount("client.materialize_block_budget_hits", 1);
                    break;
                }
                if (result.slowBlock()) {
                    state.materializeBackoffTicks = MATERIALIZE_BACKOFF_TICKS_AFTER_SLOW_BLOCK;
                    RuntimeProfiler.addCount("client.materialize_slow_block_backoff", 1);
                }
                if (result.forcedYield()) {
                    RuntimeProfiler.addCount("client.materialize_budget_forced_yield", 1);
                    break;
                }
                if (elapsedMicrosSince(startNanos) >= timeBudgetMicros) {
                    RuntimeProfiler.addCount("client.materialize_time_budget_hits", 1);
                    break;
                }
                continue;
            }

            if (prioritySlice) {
                priorityWholeCubes++;
                RuntimeProfiler.addCount("client.materialize_priority_full_slices", 1);
            }
            RuntimeProfiler.addCount("client.materialized_cubes", 1);
            holder.get().markVanillaShellReady(hash);
            rememberMaterialized(state, cubePos, hash);
            scheduleWaterTicksWhenNeighborhoodReady(level, holder.get().cube(), state);
            if (clientDirty) {
                cache.recordClientMirrorSynced(cubePos);
                clientMirrorsCleaned++;
            }
            state.dirtyInvalidationsAccounted.remove(cubePos);
            finishMaterializationTask(state, cubePos);
            completedCubes++;
            state.materializedLastTick++;
        }
    }

    private static int adaptiveMaterializeBlockBudget(int queueSize) {
        int extra = Math.min(MAX_ADAPTIVE_MATERIALIZE_BLOCKS_PER_TICK - maxMaterializeBlocksPerTick, Math.max(0, queueSize - 16) * 512);
        return Mth.clamp(maxMaterializeBlocksPerTick + extra, CubePos.BLOCK_COUNT, MAX_ADAPTIVE_MATERIALIZE_BLOCKS_PER_TICK);
    }

    private static int adaptiveMaterializeCubeBudget(int queueSize) {
        int extra = Math.max(0, queueSize - 32) / 24;
        return Mth.clamp(maxMaterializedCubesPerTick + extra, maxMaterializedCubesPerTick, MAX_ADAPTIVE_MATERIALIZED_CUBES_PER_TICK);
    }

    private static int adaptiveMaterializeMicrosBudget(int queueSize) {
        int extra = Math.min(MAX_ADAPTIVE_MATERIALIZE_MICROS_PER_TICK - maxMaterializeMicrosPerTick, Math.max(0, queueSize - 24) * 96);
        return Mth.clamp(maxMaterializeMicrosPerTick + extra, maxMaterializeMicrosPerTick, MAX_ADAPTIVE_MATERIALIZE_MICROS_PER_TICK);
    }

    private static int materializeSliceBudget(int queueAtStart, CubePos cubePos, CubePos playerCube, boolean prioritySlice) {
        if (prioritySlice) {
            return MAX_PRIORITY_MATERIALIZE_BLOCKS_PER_SLICE;
        }
        int horizontalCheb = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        if (horizontalCheb <= 3 && dy <= 1) {
            // Nearby shell cubes should appear as coherent patches/cubes, not as a block-by-block scanline.
            return MAX_MATERIALIZE_BLOCKS_PER_SLICE;
        }
        if (queueAtStart > 128) {
            return MAX_MATERIALIZE_BLOCKS_PER_SLICE;
        }
        return CubePos.BLOCK_COUNT / 2;
    }

    private static boolean isNearPlayerMaterialization(CubePos cubePos, CubePos playerCube) {
        return Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z())) <= 1
                && Math.abs(cubePos.y() - playerCube.y()) <= 1;
    }

    private static void finishMaterializationTask(PlayerBridgeState state, CubePos cubePos) {
        state.queued.remove(cubePos);
        state.materializationTasks.remove(cubePos);
    }

    private static boolean enqueueMaterialization(PlayerBridgeState state, CubePos cubePos, boolean priority) {
        if (state.queued.contains(cubePos)) {
            if (priority) {
                state.materializationQueue.remove(cubePos);
                state.materializationQueue.addFirst(cubePos);
            }
            return false;
        }
        state.queued.add(cubePos);
        if (priority) {
            state.materializationQueue.addFirst(cubePos);
        } else {
            state.materializationQueue.addLast(cubePos);
        }
        RuntimeProfiler.addCount("client.materialization_enqueued", 1);
        return true;
    }

    private static MaterializeSliceResult materializeCubeSlice(
            ServerLevel level,
            LevelCube cube,
            MaterializationTask task,
            int maxBlocks,
            long tickStartNanos,
            int timeBudgetMicros,
            boolean prioritySlice
    ) {
        long profileStart = RuntimeProfiler.markStart();
        long sliceStartNanos = System.nanoTime();
        CubePos cubePos = cube.cubePos();
        int scanned = 0;
        int changed = 0;
        boolean forcedYield = false;
        boolean slowBlock = false;
        materializationInProgress = true;
        try {
            while (task.cursor < CubePos.BLOCK_COUNT && scanned < maxBlocks) {
                if (scanned > 0 && (scanned & (MATERIALIZE_CHECK_INTERVAL_BLOCKS - 1)) == 0) {
                    long tickElapsed = elapsedMicrosSince(tickStartNanos);
                    long sliceElapsed = elapsedMicrosSince(sliceStartNanos);
                    if (tickElapsed >= HARD_MATERIALIZE_MICROS_PER_TICK || sliceElapsed >= MATERIALIZE_SLICE_HARD_MICROS) {
                        RuntimeProfiler.addCount("client.materialize_hard_budget_hits", 1);
                        RuntimeProfiler.addCount("client.materialize_slice_over_budget", 1);
                        forcedYield = true;
                        break;
                    }
                    if (tickElapsed >= timeBudgetMicros || sliceElapsed >= MATERIALIZE_SLICE_SOFT_MICROS) {
                        // M17.4: vanilla shell is only fallback. Do not let priority cubes punch through the hard budget;
                        // they can resume next tick from the same cursor instead of creating 100-200ms stalls.
                        RuntimeProfiler.addCount("client.materialize_slice_over_budget", 1);
                        forcedYield = true;
                        break;
                    }
                }
                int localIndex = materializeLocalIndex(task.cursor);
                int localX = localIndex & CubePos.MASK;
                int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
                int localY = localIndex >> (CubePos.SIZE_BITS * 2);
                int worldY = cubePos.minBlockY() + localY;
                if (level.isOutsideBuildHeight(worldY)) {
                    task.cursor++;
                    continue;
                }
                scanned++;
                BlockState state = cube.getBlockState(localX, localY, localZ);
                BlockPos blockPos = new BlockPos(cubePos.minBlockX() + localX, worldY, cubePos.minBlockZ() + localZ);
                // M17.6 shell bypass: the old fallback shell compared every vanilla block before writing.
                // Profiling showed single getBlockState calls stalling for 300ms, which no slice budget can interrupt
                // once the call has started. Cube hashes already skip completed shells, so the fallback path now writes
                // blindly and lets vanilla's own setBlock short-circuit equal states when it can.
                RuntimeProfiler.addCount("client.materialize_getblock_compare_skipped", 1);
                long setStart = RuntimeProfiler.markStart();
                level.setBlock(blockPos, state, SET_BLOCK_FLAGS);
                long setMicros = elapsedMicrosSince(setStart);
                if (setMicros >= MATERIALIZE_SETBLOCK_SLOW_MICROS) {
                    RuntimeProfiler.addCount("client.materialize_setblock_slow", 1);
                    RuntimeProfiler.recordMicros("client.materialize_setblock_slow_time", setMicros);
                    slowBlock = true;
                    forcedYield = true;
                }
                changed++;
                task.cursor++;
                if (forcedYield) {
                    break;
                }
            }
        } finally {
            materializationInProgress = false;
            RuntimeProfiler.addCount("client.materialized_blocks_scanned", scanned);
            RuntimeProfiler.addCount("client.materialized_blocks_changed", changed);
            RuntimeProfiler.addCount("client.materialize_slice_blocks", scanned);
            RuntimeProfiler.addCount("client.materialize_slices", 1);
            RuntimeProfiler.recordSince("client.materialize_cube", profileStart);
        }
        return new MaterializeSliceResult(task.cursor >= CubePos.BLOCK_COUNT, scanned, changed, forcedYield, slowBlock);
    }

    /**
     * Maps the linear materialization cursor to a top-down 4x4x4 tile order.
     *
     * <p>The old order was the normal local block index, so partial fallback-shell slices were visible as a clean
     * X/Z scanline. This order keeps the same one-pass 4096 block coverage but materializes small spatial chunks
     * across the cube. If a slice yields, the user sees chunky patches instead of a one-block-wide progress bar.</p>
     */
    private static int materializeLocalIndex(int ordinal) {
        int blockInTile = ordinal & 63;
        int tileIndex = ordinal >> 6;

        int tileX = tileIndex & MATERIALIZE_TILE_MASK;
        int tileZ = (tileIndex >> MATERIALIZE_TILE_BITS) & MATERIALIZE_TILE_MASK;
        int tileY = MATERIALIZE_TILE_MASK - ((tileIndex >> (MATERIALIZE_TILE_BITS * 2)) & MATERIALIZE_TILE_MASK);

        int blockX = blockInTile & MATERIALIZE_TILE_MASK;
        int blockZ = (blockInTile >> MATERIALIZE_TILE_BITS) & MATERIALIZE_TILE_MASK;
        int blockY = MATERIALIZE_TILE_MASK - ((blockInTile >> (MATERIALIZE_TILE_BITS * 2)) & MATERIALIZE_TILE_MASK);

        int localX = (tileX << MATERIALIZE_TILE_BITS) | blockX;
        int localY = (tileY << MATERIALIZE_TILE_BITS) | blockY;
        int localZ = (tileZ << MATERIALIZE_TILE_BITS) | blockZ;
        return localX | (localZ << CubePos.SIZE_BITS) | (localY << (CubePos.SIZE_BITS * 2));
    }

    private static void scheduleWaterTicksWhenNeighborhoodReady(ServerLevel level, LevelCube cube, PlayerBridgeState state) {
        if (!enableGeneratedWaterWakeupTicks()) {
            return;
        }
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
                    if (!fluidState.getType().isSame(Fluids.WATER) || fluidState.isSource()) {
                        continue;
                    }
                    // Cap per cube: this is just a wake-up pass after the neighboring shell is present, not full fluid sim.
                    // M16.3 deliberately wakes only generated flowing river/waterfall surfaces. Ocean/lake sources stay
                    // static until a real player/block update touches them, preventing shelf lakes and ocean rims from
                    // spilling over not-yet-sealed terrain.
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

    private static void recordNativeReady(PlayerBridgeState state, CubeHolder holder, long hash) {
        CubePos cubePos = holder.cubePos();
        if (state.nativeReadyHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash && holder.clientNativeReady(hash)) {
            RuntimeProfiler.addCount("client.native_ready_hits", 1);
            return;
        }
        holder.markClientNativeReady(hash);
        rememberNativeReady(state, cubePos, hash);
        nativeReadyRecorded++;
        RuntimeProfiler.addCount("client.native_ready_recorded", 1);
    }

    private static void rememberNativeReady(PlayerBridgeState state, CubePos cubePos, long hash) {
        if (state.nativeReadyHashes.size() >= MAX_TRACKED_MATERIALIZED * 2) {
            CubePos first = state.nativeReadyHashes.keySet().iterator().next();
            state.nativeReadyHashes.remove(first);
        }
        state.nativeReadyHashes.put(cubePos, hash);
    }

    private static void enqueueNativeSectionSnapshot(PlayerBridgeState state, CubePos cubePos, long hash) {
        if (state.nativeSectionHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash
                && state.sentNativeSectionHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash) {
            RuntimeProfiler.addCount("client.native_section_snapshot_ready_hits", 1);
            return;
        }
        if (!state.nativeSectionQueued.add(cubePos)) {
            RuntimeProfiler.addCount("client.native_section_snapshot_queue_hits", 1);
            return;
        }
        if (state.nativeSectionQueue.size() >= MAX_NATIVE_SECTION_QUEUE) {
            CubePos dropped = state.nativeSectionQueue.pollFirst();
            if (dropped != null) {
                state.nativeSectionQueued.remove(dropped);
                RuntimeProfiler.addCount("client.native_section_snapshot_queue_dropped", 1);
            }
        }
        state.nativeSectionQueue.addLast(cubePos);
        RuntimeProfiler.addCount("client.native_section_snapshot_enqueued", 1);
    }

    private static void prepareNativeSectionSnapshots(ServerPlayer player, ServerCubeCache cache, PlayerBridgeState state) {
        int prepared = 0;
        int scanned = 0;
        long bytesPrepared = state.pendingNativeSectionPayloadBytes;
        long startNanos = System.nanoTime();
        while (prepared < DEFAULT_NATIVE_SECTION_SNAPSHOTS_PER_TICK && !state.nativeSectionQueue.isEmpty()) {
            if (prepared > 0 && elapsedMicrosSince(startNanos) >= DEFAULT_NATIVE_SECTION_SNAPSHOT_MICROS_PER_TICK) {
                RuntimeProfiler.addCount("client.native_section_snapshot_time_budget_hits", 1);
                break;
            }
            CubePos cubePos = state.nativeSectionQueue.removeFirst();
            state.nativeSectionQueued.remove(cubePos);
            scanned++;
            Optional<CubeHolder> holder = cache.holder(cubePos);
            if (holder.isEmpty()) {
                state.nativeSectionHashes.remove(cubePos);
                state.sentNativeSectionHashes.remove(cubePos);
                RuntimeProfiler.addCount("client.native_section_snapshot_missing_holder", 1);
                continue;
            }
            long hash = holder.get().generationHash();
            if (state.sentNativeSectionHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash
                    || state.ackedNativeSectionHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash) {
                state.nativeSectionHashes.put(cubePos, hash);
                if (state.ackedNativeSectionHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash) {
                    nativeSectionAckHashSkips++;
                    RuntimeProfiler.addCount("client.native_section_ack_hash_skips", 1);
                } else {
                    RuntimeProfiler.addCount("client.native_section_packet_skipped_cache_hit", 1);
                }
                RuntimeProfiler.addCount("client.native_section_snapshot_ready_hits", 1);
                continue;
            }
            if (cache.clientSyncDirty(cubePos)) {
                RuntimeProfiler.addCount("client.native_section_snapshot_dirty_skips", 1);
                continue;
            }
            nativeSectionSnapshotBuildRequests++;
            RuntimeProfiler.addCount("client.native_section_snapshot_requests", 1);
            Optional<CubeSectionSnapshot> snapshot = cache.cubeSectionSnapshot(cubePos);
            if (snapshot.isEmpty()) {
                continue;
            }
            CubeSectionSnapshotPayload payload = CubeSectionSnapshotPayload.from(snapshot.get());
            long estimatedBytes = payload.estimatedBytes();
            if (!state.pendingNativeSectionPayloads.isEmpty()
                    && bytesPrepared + estimatedBytes > DEFAULT_NATIVE_SECTION_PACKET_BYTES_PER_TICK) {
                state.nativeSectionQueue.addFirst(cubePos);
                state.nativeSectionQueued.add(cubePos);
                RuntimeProfiler.addCount("client.native_section_byte_budget_hits", 1);
                break;
            }
            state.nativeSectionHashes.put(cubePos, hash);
            trimNativeSectionPreparedHashes(state);
            state.pendingNativeSectionPayloads.add(payload);
            state.pendingNativeSectionPayloadBytes += estimatedBytes;
            bytesPrepared += estimatedBytes;
            prepared++;
            nativeSectionSnapshotsPrepared++;
            RuntimeProfiler.addCount("client.native_section_snapshot_prepared", 1);
            if (state.pendingNativeSectionPayloads.size() >= DEFAULT_NATIVE_SECTION_BATCH_MAX_SNAPSHOTS) {
                break;
            }
        }
        flushNativeSectionBatch(player, state);
        RuntimeProfiler.addCount("client.native_section_snapshot_queue", state.nativeSectionQueue.size());
        RuntimeProfiler.addCount("client.native_section_snapshot_scanned", scanned);
    }

    private static void flushNativeSectionBatch(ServerPlayer player, PlayerBridgeState state) {
        if (state.pendingNativeSectionPayloads.isEmpty()) {
            return;
        }
        CubeSectionSnapshotBatchPayload batchPayload = new CubeSectionSnapshotBatchPayload(state.pendingNativeSectionPayloads);
        PacketDistributor.sendToPlayer(player, batchPayload);
        for (CubeSectionSnapshotPayload snapshot : state.pendingNativeSectionPayloads) {
            state.sentNativeSectionHashes.put(snapshot.cubePos(), snapshot.hash());
            state.unackedNativeSectionHashes.put(snapshot.cubePos(), snapshot.hash());
        }
        trimNativeSectionSentHashes(state);
        long estimatedBytes = batchPayload.estimatedBytes();
        int count = state.pendingNativeSectionPayloads.size();
        nativeSectionPacketsSent += count;
        nativeSectionBytesSent += estimatedBytes;
        RuntimeProfiler.addCount("client.native_section_packets_sent", count);
        RuntimeProfiler.addCount("client.native_section_batches_sent", 1);
        RuntimeProfiler.addCount("client.native_section_batch_entries", count);
        RuntimeProfiler.addCount("client.native_section_bytes_sent", estimatedBytes);
        state.pendingNativeSectionPayloads.clear();
        state.pendingNativeSectionPayloadBytes = 0L;
    }

    private static void unloadFarNativeSections(ServerPlayer player, ServerCubeCache cache, PlayerBridgeState state, CubePos playerCube) {
        if (state.sentNativeSectionHashes.isEmpty()) {
            return;
        }
        int scanned = 0;
        int sent = 0;
        List<CubePos> toUnload = new ArrayList<>();
        for (CubePos cubePos : state.sentNativeSectionHashes.keySet()) {
            if (scanned++ >= MAX_NATIVE_SECTION_UNLOAD_SCAN_PER_TICK || sent >= MAX_NATIVE_SECTION_UNLOADS_PER_TICK) {
                break;
            }
            if (!isWithinNativeSectionRetainRadius(cache, cubePos, playerCube)) {
                toUnload.add(cubePos);
                sent++;
            }
        }
        for (CubePos cubePos : toUnload) {
            state.sentNativeSectionHashes.remove(cubePos);
            state.ackedNativeSectionHashes.remove(cubePos);
            state.unackedNativeSectionHashes.remove(cubePos);
            state.nativeSectionHashes.remove(cubePos);
            PacketDistributor.sendToPlayer(player, CubeSectionUnloadPayload.of(cubePos));
            nativeSectionUnloadPacketsSent++;
            RuntimeProfiler.addCount("client.native_section_unload_packets_sent", 1);
        }
        RuntimeProfiler.addCount("client.native_section_unload_scanned", scanned);
    }

    private static boolean isWithinNativeSectionRetainRadius(ServerCubeCache cache, CubePos cubePos, CubePos playerCube) {
        int horizontal = streamHorizontalRadius + NATIVE_SECTION_RETAIN_EXTRA_HORIZONTAL;
        int vertical = streamVerticalRadius + NATIVE_SECTION_RETAIN_EXTRA_VERTICAL;
        if (Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z())) > horizontal) {
            return false;
        }
        if (Math.abs(cubePos.y() - playerCube.y()) <= vertical) {
            return true;
        }
        if (isSurfaceProjectionCube(cache, cubePos)) {
            RuntimeProfiler.addCount("client.surface_projection_retain_native", 1);
            return true;
        }
        return false;
    }

    private static boolean isWithinNativeSectionRetainRadius(CubePos cubePos, CubePos playerCube) {
        int horizontal = streamHorizontalRadius + NATIVE_SECTION_RETAIN_EXTRA_HORIZONTAL;
        int vertical = streamVerticalRadius + NATIVE_SECTION_RETAIN_EXTRA_VERTICAL;
        return Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z())) <= horizontal
                && Math.abs(cubePos.y() - playerCube.y()) <= vertical;
    }

    private static void trimNativeSectionPreparedHashes(PlayerBridgeState state) {
        while (state.nativeSectionHashes.size() > MAX_TRACKED_MATERIALIZED * 2) {
            CubePos first = state.nativeSectionHashes.keySet().iterator().next();
            state.nativeSectionHashes.remove(first);
        }
    }

    private static void trimNativeSectionSentHashes(PlayerBridgeState state) {
        while (state.sentNativeSectionHashes.size() > MAX_TRACKED_MATERIALIZED * 2) {
            CubePos first = state.sentNativeSectionHashes.keySet().iterator().next();
            state.sentNativeSectionHashes.remove(first);
            state.ackedNativeSectionHashes.remove(first);
            state.unackedNativeSectionHashes.remove(first);
            state.nativeSectionHashes.remove(first);
            RuntimeProfiler.addCount("client.native_section_sent_hash_evictions", 1);
        }
    }

    private static boolean shouldSendNativeSectionSnapshot(ServerCubeCache cache, CubePos cubePos, CubePos playerCube, boolean vanillaShellRequired) {
        int horizontalCheb = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        boolean projectedSurface = horizontalCheb <= streamHorizontalRadius && isSurfaceProjectionCube(cache, cubePos);
        if (projectedSurface) {
            RuntimeProfiler.addCount("client.surface_projection_snapshot_candidate", 1);
        }
        return switch (vanillaShellMode) {
            case FULL -> false;
            case NEAR -> (!vanillaShellRequired && dy <= streamVerticalRadius) || projectedSurface;
            case HYBRID -> {
                if (!vanillaShellRequired) {
                    yield true;
                }
                yield projectedSurface || (dy == 0 && horizontalCheb <= hybridShellHorizontalRadius + 1);
            }
            case HYBRID_FAST -> dy <= streamVerticalRadius || projectedSurface;
            case NATIVE_RENDER -> dy <= streamVerticalRadius || projectedSurface;
            case CUBE_ONLY -> dy <= streamVerticalRadius || projectedSurface;
            case NATIVE_ONLY -> true;
        };
    }

    private static boolean shouldMaterializeVanillaShell(CubePos cubePos, CubePos playerCube, boolean priority) {
        if (priority) {
            return true;
        }
        int horizontalCheb = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        return switch (vanillaShellMode) {
            case FULL -> true;
            case NEAR -> horizontalCheb <= nearShellHorizontalRadius && dy <= nearShellVerticalRadius;
            case HYBRID -> {
                // M17.1 playable cube-first bridge: the immediate volume is guaranteed vanilla-visible, while the
                // wider same-level band is materialized lazily so distant cubes are not invisible like debug-native.
                if (horizontalCheb <= hybridAlwaysShellHorizontalRadius && dy <= hybridShellVerticalRadius) {
                    RuntimeProfiler.addCount("client.hybrid_shell_required", 1);
                    yield true;
                }
                if (dy == 0 && horizontalCheb <= hybridShellHorizontalRadius) {
                    RuntimeProfiler.addCount("client.hybrid_shell_required", 1);
                    yield true;
                }
                RuntimeProfiler.addCount("client.hybrid_shell_skipped_far", 1);
                yield false;
            }
            case HYBRID_FAST -> {
                if (horizontalCheb <= DEFAULT_HYBRID_FAST_SHELL_HORIZONTAL_RADIUS && dy <= DEFAULT_HYBRID_FAST_SHELL_VERTICAL_RADIUS) {
                    RuntimeProfiler.addCount("client.hybrid_fast_shell_required", 1);
                    yield true;
                }
                RuntimeProfiler.addCount("client.hybrid_fast_shell_skipped_native_render", 1);
                yield false;
            }
            case NATIVE_RENDER -> {
                if (horizontalCheb <= DEFAULT_NATIVE_RENDER_SHELL_HORIZONTAL_RADIUS && dy <= DEFAULT_NATIVE_RENDER_SHELL_VERTICAL_RADIUS) {
                    RuntimeProfiler.addCount("client.native_render_shell_required", 1);
                    yield true;
                }
                RuntimeProfiler.addCount("client.native_render_shell_skipped", 1);
                yield false;
            }
            case CUBE_ONLY -> {
                RuntimeProfiler.addCount("client.cube_only_shell_skipped", 1);
                yield false;
            }
            case NATIVE_ONLY -> false;
        };
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
            long hash = holder.generationHash();
            boolean materialized = holder.vanillaShellReady(hash);
            boolean nativeReady = holder.clientNativeReady(hash) || state.nativeReadyHashes.getOrDefault(cubePos, Long.MIN_VALUE) == hash;
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
                    nativeReady,
                    materialized,
                    holder.clientStage().ordinal()
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

    private static boolean enableGeneratedWaterWakeupTicks() {
        // M16.10 rivers are static vanilla-style source water. Keeping the old wake-up scan costs up to 4096
        // fluid checks per materialized cube and is only useful for the removed waterfall/flowing-water prototypes.
        return false;
    }


    private static int addSurfaceProjectionVisible(ServerCubeCache cache, CubePos playerCube, PlayerViewFocus focus, Set<CubePos> visible) {
        if (!SURFACE_PROJECTION_ENABLED) {
            return 0;
        }
        int radius = streamHorizontalRadius;
        if (focus.underground()) {
            radius = Math.min(radius, SURFACE_PROJECTION_UNDERGROUND_RADIUS_CAP);
        } else if (focus.trajectoryFocus()) {
            radius += focus.highAltitude() ? SURFACE_PROJECTION_HIGH_ALTITUDE_EXTRA_RADIUS : SURFACE_PROJECTION_FAST_EXTRA_RADIUS;
        }
        int addedFromSpine = addTrajectorySpineSurfaceVisible(cache, playerCube, focus, visible, radius);
        List<SurfaceProjectionColumn> columns = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        int skippedRear = 0;
        for (int z = playerCube.z() - radius; z <= playerCube.z() + radius; z++) {
            for (int x = playerCube.x() - radius; x <= playerCube.x() + radius; x++) {
                int dx = x - playerCube.x();
                int dz = z - playerCube.z();
                int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                if (cheb > radius) {
                    continue;
                }
                double dot = horizontalDot(dx, dz, focus.surfaceDirX(), focus.surfaceDirZ());
                boolean trajectoryLead = focus.trajectoryFocus() || focus.highAltitude();
                if (trajectoryLead && cheb > 2 && dot < -0.10D) {
                    skippedRear++;
                    continue;
                }
                if (trajectoryLead && cheb > Math.max(4, streamHorizontalRadius / 2) && dot < 0.18D) {
                    skippedRear++;
                    continue;
                }
                int band = projectionBand(dot);
                int priorityScore = surfaceProjectionPriorityScore(dx, dz, cheb, dot, focus.surfaceDirX(), focus.surfaceDirZ(), band, trajectoryLead);
                if (trajectoryLead && band == 0 && cheb > streamHorizontalRadius) {
                    RuntimeProfiler.addCount("client.surface_projection_front_lead", 1);
                }
                columns.add(new SurfaceProjectionColumn(x, z, priorityScore, band));
            }
        }
        columns.sort(Comparator
                .comparingInt(SurfaceProjectionColumn::priorityScore)
                .thenComparingInt(SurfaceProjectionColumn::x)
                .thenComparingInt(SurfaceProjectionColumn::z));
        int added = 0;
        int scanned = 0;
        int maxColumns = Math.min(focus.underground()
                ? 32
                : ((focus.trajectoryFocus() || focus.highAltitude()) ? SURFACE_PROJECTION_TRAJECTORY_MAX_COLUMNS : (focus.fastFlight() ? SURFACE_PROJECTION_FAST_MAX_COLUMNS : SURFACE_PROJECTION_MAX_COLUMNS)), columns.size());
        for (int i = 0; i < maxColumns; i++) {
            SurfaceProjectionColumn column = columns.get(i);
            scanned++;
            int surfaceY = visibleSurfaceCube(cache, column.x(), column.z());
            for (int y = surfaceY - SURFACE_PROJECTION_BAND_BELOW; y <= surfaceY + SURFACE_PROJECTION_BAND_ABOVE; y++) {
                CubePos cubePos = new CubePos(column.x(), y, column.z());
                if (visible.add(cubePos)) {
                    added++;
                    switch (column.band()) {
                        case 0 -> RuntimeProfiler.addCount("client.surface_projection_front", 1);
                        case 1 -> RuntimeProfiler.addCount("client.surface_projection_peripheral", 1);
                        default -> RuntimeProfiler.addCount("client.surface_projection_rear", 1);
                    }
                }
            }
        }
        RuntimeProfiler.addCount("client.surface_projection_columns_scanned", scanned);
        if (skippedRear > 0) {
            RuntimeProfiler.addCount("client.surface_projection_rear_skipped", skippedRear);
        }
        if (focus.fastFlight()) {
            RuntimeProfiler.addCount("client.surface_projection_fast_flight_visible", 1);
        }
        if (columns.size() > maxColumns) {
            RuntimeProfiler.addCount("client.surface_projection_budget_hits", 1);
        }
        return added + addedFromSpine;
    }

    private static int addTrajectorySpineSurfaceVisible(ServerCubeCache cache, CubePos playerCube, PlayerViewFocus focus, Set<CubePos> visible, int radius) {
        if (focus.underground() || (!focus.trajectoryFocus() && !focus.highAltitude())) {
            return 0;
        }
        double dirX = focus.surfaceDirX();
        double dirZ = focus.surfaceDirZ();
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0E-5D) {
            return 0;
        }
        dirX /= len;
        dirZ /= len;
        double perpX = -dirZ;
        double perpZ = dirX;
        int maxStep = Math.min(radius, streamHorizontalRadius + (focus.highAltitude() ? SURFACE_PROJECTION_HIGH_ALTITUDE_EXTRA_RADIUS : SURFACE_PROJECTION_FAST_EXTRA_RADIUS));
        LinkedHashSet<Long> seenColumns = new LinkedHashSet<>();
        int added = 0;
        int scanned = 0;
        for (int step = 1; step <= maxStep && scanned < SURFACE_PROJECTION_TRAJECTORY_SPINE_COLUMNS; step++) {
            int halfWidth = Math.min(focus.highAltitude() ? 5 : 3, 1 + step / 4);
            for (int lateral = -halfWidth; lateral <= halfWidth && scanned < SURFACE_PROJECTION_TRAJECTORY_SPINE_COLUMNS; lateral++) {
                int x = playerCube.x() + (int) Math.round(dirX * step + perpX * lateral);
                int z = playerCube.z() + (int) Math.round(dirZ * step + perpZ * lateral);
                long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
                if (!seenColumns.add(key)) {
                    continue;
                }
                scanned++;
                int surfaceY = visibleSurfaceCube(cache, x, z);
                for (int y = surfaceY - SURFACE_PROJECTION_BAND_BELOW; y <= surfaceY + SURFACE_PROJECTION_BAND_ABOVE; y++) {
                    CubePos cubePos = new CubePos(x, y, z);
                    if (visible.add(cubePos)) {
                        added++;
                    }
                }
            }
        }
        if (scanned > 0) {
            RuntimeProfiler.addCount("client.surface_projection_trajectory_spine_columns", scanned);
        }
        if (added > 0) {
            RuntimeProfiler.addCount("client.surface_projection_trajectory_spine_cubes", added);
        }
        return added;
    }

    private static int surfaceProjectionPriorityScore(int dx, int dz, int cheb, double dot, double dirX, double dirZ, int band, boolean trajectoryLead) {
        double along = dx * dirX + dz * dirZ;
        double distSq = (double) dx * dx + (double) dz * dz;
        double lateralSq = Math.max(0.0D, distSq - along * along);
        if (trajectoryLead && band == 0 && along > 0.0D) {
            return (int) Math.round(lateralSq * 512.0D) + (int) Math.round(Math.max(0.0D, along) * 4.0D) + cheb;
        }
        if (band == 0 && along > 0.0D) {
            return band * 1_000_000 + (int) Math.round(lateralSq * 256.0D) + (int) Math.round(along * 16.0D) + cheb;
        }
        int anglePenalty = dot > 0.35D ? 0 : dot > -0.25D ? 2_000 : 8_000;
        return band * 1_000_000 + cheb * cheb * 64 + anglePenalty + (int) Math.round((1.0D - dot) * 128.0D);
    }

    private static int materializationPriority(ServerCubeCache cache, CubePos cubePos, CubePos playerCube, PlayerViewFocus focus) {
        int horizontalCheb = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        int band = viewPriorityBand(cache, cubePos, playerCube, focus, horizontalCheb, dy);
        switch (band) {
            case 0 -> RuntimeProfiler.addCount("client.priority_player_cube", 1);
            case 1 -> RuntimeProfiler.addCount("client.priority_immediate", 1);
            case 2 -> RuntimeProfiler.addCount("client.priority_front_surface", 1);
            case 3 -> RuntimeProfiler.addCount("client.priority_peripheral_surface", 1);
            case 4 -> RuntimeProfiler.addCount("client.priority_rear_surface", 1);
            case 5 -> RuntimeProfiler.addCount("client.priority_air", 1);
            default -> RuntimeProfiler.addCount("client.priority_deep", 1);
        }
        return band * 10_000 + horizontalDistanceSquared(cubePos, playerCube) * 8 + dy * 64 + viewAnglePenalty(cubePos, playerCube, focus);
    }

    private static int eagerPriority(CubePos cubePos, CubePos playerCube, double lookX, double lookZ) {
        int horizontalCheb = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        if (horizontalCheb <= 1 && dy <= 1) {
            return horizontalCheb * 10 + dy;
        }
        if (dy <= 2) {
            double dx = cubePos.x() - playerCube.x();
            double dz = cubePos.z() - playerCube.z();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > 0.5D) {
                double dot = (dx * lookX + dz * lookZ) / distance;
                if (dot > 0.55D) {
                    return 1_000 + horizontalDistanceSquared(cubePos, playerCube) + dy * 8;
                }
                if (dot > -0.15D) {
                    return 1_400 + horizontalDistanceSquared(cubePos, playerCube) + dy * 8;
                }
            }
        }
        return 2_000 + manhattan(cubePos, playerCube) * 16;
    }

    private static PlayerViewFocus playerViewFocus(ServerCubeCache cache, ServerPlayer player, CubePos playerCube) {
        double yawRad = Math.toRadians(player.getYRot());
        double yawX = -Math.sin(yawRad);
        double yawZ = Math.cos(yawRad);
        double dirX = player.getLookAngle().x;
        double dirZ = player.getLookAngle().z;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < SURFACE_PROJECTION_YAW_FALLBACK_HORIZONTAL_LOOK_LEN) {
            dirX = yawX;
            dirZ = yawZ;
            RuntimeProfiler.addCount("client.surface_projection_yaw_fallback", 1);
        } else {
            dirX /= len;
            dirZ /= len;
        }
        int surfaceCubeY = terrainSurfaceCube(cache, playerCube);
        boolean highAltitude = playerCube.y() > surfaceCubeY + SURFACE_PROJECTION_HIGH_ALTITUDE_CUBE_DELTA;
        PlayerBridgeState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerBridgeState());
        double moveX = player.getDeltaMovement().x;
        double moveZ = player.getDeltaMovement().z;
        double moveLenSq = moveX * moveX + moveZ * moveZ;
        TrajectoryEstimate trajectory = estimatePlayerTrajectory(state, player.getX(), player.getZ(), moveX, moveZ, moveLenSq, highAltitude);
        double surfaceDirX = dirX;
        double surfaceDirZ = dirZ;
        boolean fastFlight = trajectory.speedSq() >= SURFACE_PROJECTION_FAST_SPEED_SQ || (highAltitude && trajectory.trajectoryFocus());
        if (trajectory.trajectoryFocus()) {
            surfaceDirX = trajectory.dirX();
            surfaceDirZ = trajectory.dirZ();
            dirX = dirX * 0.65D + surfaceDirX * 0.35D;
            dirZ = dirZ * 0.65D + surfaceDirZ * 0.35D;
            double mixedLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (mixedLen > 1.0E-5D) {
                dirX /= mixedLen;
                dirZ /= mixedLen;
            }
            RuntimeProfiler.addCount("client.surface_projection_trajectory_focus", 1);
            if (trajectory.positionMotion()) {
                RuntimeProfiler.addCount("client.surface_projection_position_motion_focus", 1);
            }
        }
        return new PlayerViewFocus(dirX, dirZ, surfaceDirX, surfaceDirZ, playerCube.y() < surfaceCubeY - 1, fastFlight, highAltitude, trajectory.trajectoryFocus());
    }

    private static TrajectoryEstimate estimatePlayerTrajectory(PlayerBridgeState state, double playerX, double playerZ, double velocityX, double velocityZ, double velocityLenSq, boolean highAltitude) {
        double positionDx = 0.0D;
        double positionDz = 0.0D;
        double positionLenSq = 0.0D;
        if (state.trajectoryInitialized) {
            positionDx = playerX - state.lastTrajectoryX;
            positionDz = playerZ - state.lastTrajectoryZ;
            positionLenSq = positionDx * positionDx + positionDz * positionDz;
        }
        state.lastTrajectoryX = playerX;
        state.lastTrajectoryZ = playerZ;
        state.trajectoryInitialized = true;

        double rawX = velocityX;
        double rawZ = velocityZ;
        double rawLenSq = velocityLenSq;
        boolean positionMotion = false;
        if (positionLenSq > rawLenSq) {
            rawX = positionDx;
            rawZ = positionDz;
            rawLenSq = positionLenSq;
            positionMotion = true;
        }

        if (rawLenSq >= SURFACE_PROJECTION_TRAJECTORY_SPEED_SQ) {
            double rawLen = Math.sqrt(rawLenSq);
            double dirX = rawX / rawLen;
            double dirZ = rawZ / rawLen;
            if (state.trajectoryHasDirection) {
                dirX = state.trajectoryDirX * 0.55D + dirX * 0.45D;
                dirZ = state.trajectoryDirZ * 0.55D + dirZ * 0.45D;
                double mixedLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
                if (mixedLen > 1.0E-5D) {
                    dirX /= mixedLen;
                    dirZ /= mixedLen;
                }
            }
            state.trajectoryDirX = dirX;
            state.trajectoryDirZ = dirZ;
            state.trajectorySpeedSq = rawLenSq;
            state.trajectoryHasDirection = true;
            state.trajectoryStaleTicks = 0;
            return new TrajectoryEstimate(dirX, dirZ, rawLenSq, true, positionMotion);
        }

        if (highAltitude && state.trajectoryHasDirection && state.trajectoryStaleTicks < 40) {
            state.trajectoryStaleTicks++;
            state.trajectorySpeedSq *= 0.92D;
            RuntimeProfiler.addCount("client.surface_projection_trajectory_retained", 1);
            return new TrajectoryEstimate(state.trajectoryDirX, state.trajectoryDirZ, Math.max(state.trajectorySpeedSq, SURFACE_PROJECTION_TRAJECTORY_SPEED_SQ), true, false);
        }

        state.trajectoryStaleTicks++;
        return new TrajectoryEstimate(state.trajectoryDirX, state.trajectoryDirZ, rawLenSq, false, false);
    }

    private static int viewPriorityBand(ServerCubeCache cache, CubePos cubePos, CubePos playerCube, PlayerViewFocus focus, int horizontalCheb, int dy) {
        if (cubePos.equals(playerCube)) {
            return 0;
        }
        if (horizontalCheb <= 1 && dy <= 1) {
            return 1;
        }
        int terrainClass = verticalTerrainClass(cache, cubePos);
        double dot = viewDot(cubePos, playerCube, focus);
        if (focus.underground() && dy <= 1) {
            if (dot > 0.55D) {
                return 2;
            }
            if (dot > -0.15D) {
                return 3;
            }
            return 4;
        }
        if (terrainClass == 0) {
            if (dot > 0.55D) {
                return 2;
            }
            if (dot > -0.15D) {
                return 3;
            }
            return 4;
        }
        return terrainClass == 2 ? 5 : 6;
    }

    private static int viewAnglePenalty(CubePos cubePos, CubePos playerCube, PlayerViewFocus focus) {
        double dot = viewDot(cubePos, playerCube, focus);
        if (dot > 0.75D) {
            return 0;
        }
        if (dot > 0.25D) {
            return 64;
        }
        if (dot > -0.25D) {
            return 192;
        }
        return 384;
    }

    private static double viewDot(CubePos cubePos, CubePos playerCube, PlayerViewFocus focus) {
        double dx = cubePos.x() - playerCube.x();
        double dz = cubePos.z() - playerCube.z();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-5D) {
            return 1.0D;
        }
        return (dx * focus.dirX() + dz * focus.dirZ()) / distance;
    }

    private static int verticalTerrainClass(ServerCubeCache cache, CubePos cubePos) {
        int surfaceCubeY = terrainSurfaceCube(cache, cubePos);
        if (cubePos.y() >= surfaceCubeY - 1 && cubePos.y() <= surfaceCubeY + 1) {
            return 0;
        }
        return cubePos.y() < surfaceCubeY - 1 ? 1 : 2;
    }

    private static int terrainSurfaceCube(ServerCubeCache cache, CubePos cubePos) {
        int centerX = cubePos.minBlockX() + 8;
        int centerZ = cubePos.minBlockZ() + 8;
        return CubePos.blockToCube(com.redline.worldcore.server.generation.M15TerrainModel.surfaceHeightDry(cache.generationContext(), centerX, centerZ));
    }


    private static boolean isSurfaceProjectionCube(ServerCubeCache cache, CubePos cubePos) {
        int surfaceY = visibleSurfaceCube(cache, cubePos.x(), cubePos.z());
        return cubePos.y() >= surfaceY - SURFACE_PROJECTION_BAND_BELOW && cubePos.y() <= surfaceY + SURFACE_PROJECTION_BAND_ABOVE;
    }

    private static int visibleSurfaceCube(ServerCubeCache cache, int cubeX, int cubeZ) {
        int centerX = (cubeX << CubePos.SIZE_BITS) + 8;
        int centerZ = (cubeZ << CubePos.SIZE_BITS) + 8;
        int surfaceY = com.redline.worldcore.server.generation.M15TerrainModel.surfaceHeightDry(cache.generationContext(), centerX, centerZ);
        try {
            com.redline.worldcore.server.generation.M16WaterSample water = com.redline.worldcore.server.generation.M16WaterModel.sample(cache.generationContext(), centerX, centerZ);
            if (water.hasWater()) {
                surfaceY = Math.max(surfaceY, water.waterSurfaceY());
            }
            RuntimeProfiler.addCount("client.surface_projection_surface_hint_hits", 1);
        } catch (RuntimeException ignored) {
            RuntimeProfiler.addCount("client.surface_projection_surface_hint_misses", 1);
        }
        return CubePos.blockToCube(surfaceY);
    }

    private static int projectionBand(double dot) {
        if (dot > 0.35D) {
            return 0;
        }
        if (dot > -0.25D) {
            return 1;
        }
        return 2;
    }

    private static double horizontalDot(int dx, int dz, double dirX, double dirZ) {
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (distance < 1.0E-5D) {
            return 1.0D;
        }
        return (dx * dirX + dz * dirZ) / distance;
    }

    private static int horizontalDistanceSquared(CubePos first, CubePos second) {
        int dx = first.x() - second.x();
        int dz = first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private static int manhattan(CubePos first, CubePos second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.y() - second.y()) + Math.abs(first.z() - second.z());
    }

    private static long elapsedMicrosSince(long startNanos) {
        return Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
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
        CubePos cubePos = CubePos.fromBlock(pos);
        long baseHash = cache.holder(cubePos).map(CubeHolder::generationHash).orElse(Long.MIN_VALUE);
        Optional<CubeHolder> holder = cache.writeBlock(pos, state, true);
        if (holder.isEmpty()) {
            return false;
        }
        sendNativeSectionDelta(level, cubePos, baseHash, holder.get().generationHash(), CubePos.localIndexFromBlock(pos.getX(), pos.getY(), pos.getZ()), state);
        return true;
    }

    private static void sendNativeSectionDelta(ServerLevel level, CubePos cubePos, long baseHash, long newHash, int localIndex, BlockState state) {
        CubeSectionDeltaPayload payload = CubeSectionDeltaPayload.single(cubePos, baseHash, newHash, localIndex, state);
        int sent = 0;
        for (ServerPlayer target : level.players()) {
            CubePos targetCube = CubePos.fromBlock(target.blockPosition());
            if (!isWithinNativeSectionRetainRadius(cubePos, targetCube)) {
                continue;
            }
            PacketDistributor.sendToPlayer(target, payload);
            sent++;
        }
        if (sent > 0) {
            nativeSectionDeltaPacketsSent += sent;
            nativeSectionDeltaEntriesSent += sent;
            nativeSectionDeltaBytesSent += payload.estimatedBytes() * sent;
            RuntimeProfiler.addCount("client.native_section_delta_packets_sent", sent);
            RuntimeProfiler.addCount("client.native_section_delta_entries_sent", sent);
            RuntimeProfiler.addCount("client.native_section_delta_bytes_sent", payload.estimatedBytes() * sent);
        }
    }

    public static void handleNativeSectionAck(ServerPlayer player, ClientCubeSectionAckPayload payload) {
        PlayerBridgeState state = PLAYER_STATES.get(player.getUUID());
        if (state == null) {
            return;
        }
        nativeSectionAcksReceived++;
        nativeSectionAckEntriesReceived += payload.entries().size();
        RuntimeProfiler.addCount("client.native_section_ack_packets_received", 1);
        RuntimeProfiler.addCount("client.native_section_ack_entries_received", payload.entries().size());
        for (ClientCubeSectionAckPayload.Entry entry : payload.entries()) {
            CubePos cubePos = entry.cubePos();
            state.ackedNativeSectionHashes.put(cubePos, entry.hash());
            state.unackedNativeSectionHashes.remove(cubePos);
        }
        trimNativeSectionAckHashes(state);
    }

    private static void trimNativeSectionAckHashes(PlayerBridgeState state) {
        while (state.ackedNativeSectionHashes.size() > MAX_TRACKED_MATERIALIZED * 2) {
            CubePos first = state.ackedNativeSectionHashes.keySet().iterator().next();
            state.ackedNativeSectionHashes.remove(first);
            RuntimeProfiler.addCount("client.native_section_ack_hash_evictions", 1);
        }
    }

    private static boolean isCubicTest(ServerLevel level) {
        return level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL);
    }

    private enum VanillaShellMode {
        FULL,
        NEAR,
        HYBRID,
        HYBRID_FAST,
        NATIVE_RENDER,
        CUBE_ONLY,
        NATIVE_ONLY
    }

    private static final class PlayerBridgeState {
        private final ArrayDeque<CubePos> materializationQueue = new ArrayDeque<>();
        private final Set<CubePos> queued = new HashSet<>();
        private final Set<CubePos> dirtyInvalidationsAccounted = new HashSet<>();
        private final Map<CubePos, Long> nativeReadyHashes = new HashMap<>();
        private final Map<CubePos, Long> materializedHashes = new HashMap<>();
        private final Map<CubePos, MaterializationTask> materializationTasks = new HashMap<>();
        private final ArrayDeque<CubePos> nativeSectionQueue = new ArrayDeque<>();
        private final Set<CubePos> nativeSectionQueued = new HashSet<>();
        private final Map<CubePos, Long> nativeSectionHashes = new HashMap<>();
        private final Map<CubePos, Long> sentNativeSectionHashes = new HashMap<>();
        private final Map<CubePos, Long> ackedNativeSectionHashes = new HashMap<>();
        private final Map<CubePos, Long> unackedNativeSectionHashes = new HashMap<>();
        private final ArrayList<CubeSectionSnapshotPayload> pendingNativeSectionPayloads = new ArrayList<>();
        private long pendingNativeSectionPayloadBytes;
        private int materializeBackoffTicks;
        private CubePos visibleOrderCenter;
        private List<CubePos> visibleOrder = List.of();
        private int visibleOrderConfigHash;
        private int visibleCursor;
        private int tickCounter;
        private int materializedLastTick;
        private boolean trajectoryInitialized;
        private double lastTrajectoryX;
        private double lastTrajectoryZ;
        private boolean trajectoryHasDirection;
        private double trajectoryDirX;
        private double trajectoryDirZ = 1.0D;
        private double trajectorySpeedSq;
        private int trajectoryStaleTicks;
    }

    private static final class MaterializationTask {
        private final long hash;
        private int cursor;

        private MaterializationTask(long hash) {
            this.hash = hash;
        }
    }

    private record SurfaceProjectionColumn(int x, int z, int priorityScore, int band) {
    }

    private record PlayerViewFocus(double dirX, double dirZ, double surfaceDirX, double surfaceDirZ, boolean underground, boolean fastFlight, boolean highAltitude, boolean trajectoryFocus) {
    }

    private record TrajectoryEstimate(double dirX, double dirZ, double speedSq, boolean trajectoryFocus, boolean positionMotion) {
    }

    private record MaterializeSliceResult(boolean completed, int scanned, int changed, boolean forcedYield, boolean slowBlock) {
    }
}
