package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.network.CubeClientSyncPayload;
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
    private static VanillaShellMode vanillaShellMode = VanillaShellMode.FULL;
    private static int nearShellHorizontalRadius = DEFAULT_NEAR_SHELL_HORIZONTAL_RADIUS;
    private static int nearShellVerticalRadius = DEFAULT_NEAR_SHELL_VERTICAL_RADIUS;

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
            case "NATIVE", "NATIVE_ONLY" -> VanillaShellMode.NATIVE_ONLY;
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
        for (PlayerBridgeState state : PLAYER_STATES.values()) {
            state.materializationQueue.clear();
            state.queued.clear();
            state.materializationTasks.clear();
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
        vanillaShellMode = VanillaShellMode.FULL;
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
        queueVisibleLoadedCubes(cache, playerCube, state);
        RuntimeProfiler.recordSince("client.queue_visible_loaded", phaseStart);
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

    private static void queueVisibleLoadedCubes(ServerCubeCache cache, CubePos playerCube, PlayerBridgeState state) {
        List<CubePos> visible = visibleOrder(playerCube, state);
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
            if (!clientDirty && holder.get().vanillaShellReady(hash)) {
                rememberMaterialized(state, cubePos, hash);
                vanillaShellGlobalReadyHits++;
                RuntimeProfiler.addCount("client.vanilla_shell_global_ready_hits", 1);
                continue;
            }
            if (!shouldMaterializeVanillaShell(cubePos, playerCube, false)) {
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

    private static List<CubePos> visibleOrder(CubePos playerCube, PlayerBridgeState state) {
        int configHash = streamHorizontalRadius * 31 + streamVerticalRadius;
        if (playerCube.equals(state.visibleOrderCenter) && state.visibleOrderConfigHash == configHash && !state.visibleOrder.isEmpty()) {
            return state.visibleOrder;
        }
        List<CubePos> visible = new ArrayList<>((streamHorizontalRadius * 2 + 1) * (streamHorizontalRadius * 2 + 1) * (streamVerticalRadius * 2 + 1));
        for (int y = playerCube.y() - streamVerticalRadius; y <= playerCube.y() + streamVerticalRadius; y++) {
            for (int z = playerCube.z() - streamHorizontalRadius; z <= playerCube.z() + streamHorizontalRadius; z++) {
                for (int x = playerCube.x() - streamHorizontalRadius; x <= playerCube.x() + streamHorizontalRadius; x++) {
                    visible.add(new CubePos(x, y, z));
                }
            }
        }
        visible.sort(Comparator
                .comparingInt((CubePos cubePos) -> materializationPriority(cubePos, playerCube))
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
            boolean priorityFullSlice = nearPlayer && task.cursor == 0 && priorityWholeCubes < MAX_PRIORITY_WHOLE_CUBES_PER_TICK;
            int sliceBudget = priorityFullSlice
                    ? Math.max(blockBudget, CubePos.BLOCK_COUNT)
                    : Math.min(blockBudget, farMaterializeSliceBudget(queueAtStart, cubePos, playerCube));
            MaterializeSliceResult result = materializeCubeSlice(
                    level,
                    holder.get().cube(),
                    task,
                    sliceBudget,
                    startNanos,
                    timeBudgetMicros,
                    priorityFullSlice
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
                }
                break;
            }

            if (priorityFullSlice) {
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

    private static int farMaterializeSliceBudget(int queueAtStart, CubePos cubePos, CubePos playerCube) {
        if (isNearPlayerMaterialization(cubePos, playerCube)) {
            return CubePos.BLOCK_COUNT;
        }
        int horizontalCheb = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        if (horizontalCheb <= 3 && dy <= 1) {
            return CubePos.BLOCK_COUNT;
        }
        int base = queueAtStart > 128 ? CubePos.BLOCK_COUNT : CubePos.BLOCK_COUNT / 2;
        return Math.max(512, base);
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
            boolean allowPriorityOverrun
    ) {
        long profileStart = RuntimeProfiler.markStart();
        CubePos cubePos = cube.cubePos();
        int scanned = 0;
        int changed = 0;
        materializationInProgress = true;
        try {
            while (task.cursor < CubePos.BLOCK_COUNT && scanned < maxBlocks) {
                if (scanned > 0 && (scanned & 63) == 0) {
                    long elapsed = elapsedMicrosSince(tickStartNanos);
                    if (elapsed >= HARD_MATERIALIZE_MICROS_PER_TICK) {
                        RuntimeProfiler.addCount("client.materialize_hard_budget_hits", 1);
                        break;
                    }
                    if (elapsed >= timeBudgetMicros) {
                        // Priority cubes may overrun a little so the player does not see a hollow shell, but never force
                        // an entire expensive cube through one tick. M17.0 treats vanilla shell mirroring as fallback,
                        // so native-ready cubes can wait instead of freezing the server to finish a whole shell.
                        if (!allowPriorityOverrun || scanned >= CubePos.BLOCK_COUNT / 3) {
                            break;
                        }
                    }
                }
                int localIndex = task.cursor++;
                int localX = localIndex & CubePos.MASK;
                int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
                int localY = localIndex >> (CubePos.SIZE_BITS * 2);
                int worldY = cubePos.minBlockY() + localY;
                if (level.isOutsideBuildHeight(worldY)) {
                    continue;
                }
                scanned++;
                BlockState state = cube.getBlockState(localX, localY, localZ);
                BlockPos blockPos = new BlockPos(cubePos.minBlockX() + localX, worldY, cubePos.minBlockZ() + localZ);
                if (!level.getBlockState(blockPos).equals(state)) {
                    level.setBlock(blockPos, state, SET_BLOCK_FLAGS);
                    changed++;
                }
            }
        } finally {
            materializationInProgress = false;
            RuntimeProfiler.addCount("client.materialized_blocks_scanned", scanned);
            RuntimeProfiler.addCount("client.materialized_blocks_changed", changed);
            RuntimeProfiler.addCount("client.materialize_slices", 1);
            RuntimeProfiler.recordSince("client.materialize_cube", profileStart);
        }
        return new MaterializeSliceResult(task.cursor >= CubePos.BLOCK_COUNT, scanned, changed);
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

    private static boolean shouldMaterializeVanillaShell(CubePos cubePos, CubePos playerCube, boolean priority) {
        if (priority) {
            return true;
        }
        return switch (vanillaShellMode) {
            case FULL -> true;
            case NEAR -> Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z())) <= nearShellHorizontalRadius
                    && Math.abs(cubePos.y() - playerCube.y()) <= nearShellVerticalRadius;
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

    private static int materializationPriority(CubePos cubePos, CubePos playerCube) {
        int horizontalCheb = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        if (horizontalCheb <= 1 && dy <= 1) {
            return horizontalCheb * 10 + dy;
        }
        if (dy <= 1) {
            return 1_000 + horizontalDistanceSquared(cubePos, playerCube);
        }
        return 2_000 + manhattan(cubePos, playerCube) * 8;
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
                double dot = dx * lookX + dz * lookZ;
                if (dot > distance * 0.35D) {
                    return 1_000 + horizontalDistanceSquared(cubePos, playerCube) + dy * 8;
                }
            }
        }
        return 2_000 + manhattan(cubePos, playerCube) * 16;
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
        return cache.writeBlock(pos, state, true).isPresent();
    }

    private static boolean isCubicTest(ServerLevel level) {
        return level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL);
    }

    private enum VanillaShellMode {
        FULL,
        NEAR,
        NATIVE_ONLY
    }

    private static final class PlayerBridgeState {
        private final ArrayDeque<CubePos> materializationQueue = new ArrayDeque<>();
        private final Set<CubePos> queued = new HashSet<>();
        private final Set<CubePos> dirtyInvalidationsAccounted = new HashSet<>();
        private final Map<CubePos, Long> nativeReadyHashes = new HashMap<>();
        private final Map<CubePos, Long> materializedHashes = new HashMap<>();
        private final Map<CubePos, MaterializationTask> materializationTasks = new HashMap<>();
        private CubePos visibleOrderCenter;
        private List<CubePos> visibleOrder = List.of();
        private int visibleOrderConfigHash;
        private int visibleCursor;
        private int tickCounter;
        private int materializedLastTick;
    }

    private static final class MaterializationTask {
        private final long hash;
        private int cursor;

        private MaterializationTask(long hash) {
            this.hash = hash;
        }
    }

    private record MaterializeSliceResult(boolean completed, int scanned, int changed) {
    }
}
