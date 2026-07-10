package com.ibicza.redlinechunkpriority.core;

import com.ibicza.redlinechunkpriority.RedlineChunkPriority;
import com.ibicza.redlinechunkpriority.config.ChunkPriorityConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChunkPriorityManager {
    private static final ChunkPriorityPlanner PLANNER = new ChunkPriorityPlanner();
    private static TicketType ticketType = createTicketType(40);
    private static int ticketTimeout = 40;
    private static boolean runtimeEnabled = true;
    private static ChunkPriorityStats lastStats = ChunkPriorityStats.empty(true);

    public static void tick(MinecraftServer server, boolean hasTime) {
        if (!runtimeEnabled || !ChunkPriorityConfig.ENABLED.get()) {
            lastStats = ChunkPriorityStats.empty(runtimeEnabled);
            return;
        }
        if (ChunkPriorityConfig.RESPECT_SERVER_HAS_TIME.get() && !hasTime) {
            return;
        }
        long gameTime = server.overworld().getGameTime();
        int interval = ChunkPriorityConfig.TICK_INTERVAL.get();
        if (interval > 1 && gameTime % interval != 0L) {
            return;
        }

        refreshTicketTypeIfNeeded();

        int globalCap = ChunkPriorityConfig.MAX_REQUESTS_PER_TICK.get();
        int perPlayerCap = ChunkPriorityConfig.MAX_REQUESTS_PER_PLAYER.get();
        boolean skipLoaded = ChunkPriorityConfig.SKIP_ALREADY_FULL_CHUNKS.get();
        boolean requestFullStatus = ChunkPriorityConfig.REQUEST_FULL_STATUS.get();

        int planned = 0;
        int requested = 0;
        int skippedLoaded = 0;
        int capped = 0;
        List<ChunkPriorityTarget> sample = new ArrayList<>();
        Map<LevelChunkKey, TargetRequest> globalOrder = new LinkedHashMap<>();

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            List<ChunkPriorityTarget> playerTargets = PLANNER.plan(player, perPlayerCap);
            planned += playerTargets.size();
            ServerLevel level = player.level();
            for (ChunkPriorityTarget target : playerTargets) {
                LevelChunkKey key = new LevelChunkKey(level.dimension(), target.pos().pack());
                globalOrder.putIfAbsent(key, new TargetRequest(level, target));
            }
            if (ChunkPriorityConfig.DEBUG_LOG_TOP_CHUNKS.get()) {
                logPlayerPlan(player, playerTargets);
            }
        }

        for (TargetRequest request : globalOrder.values()) {
            if (requested >= globalCap) {
                capped++;
                continue;
            }
            ChunkPriorityTarget target = request.target();
            ServerLevel level = request.level();
            ServerChunkCache chunkSource = level.getChunkSource();
            ChunkPos pos = target.pos();
            if (skipLoaded && chunkSource.getChunkNow(pos.x(), pos.z()) != null) {
                skippedLoaded++;
                continue;
            }

            chunkSource.addTicketWithRadius(ticketType, pos, 0);
            if (requestFullStatus) {
                chunkSource.getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, true);
            }
            requested++;
            if (sample.size() < ChunkPriorityConfig.DEBUG_LOG_COUNT.get()) {
                sample.add(target);
            }
        }

        lastStats = new ChunkPriorityStats(gameTime, runtimeEnabled, players.size(), planned, requested, skippedLoaded, capped, List.copyOf(sample));
    }

    public static List<ChunkPriorityTarget> preview(ServerPlayer player) {
        return PLANNER.plan(player, ChunkPriorityConfig.MAX_REQUESTS_PER_PLAYER.get());
    }

    public static ChunkPriorityStats lastStats() {
        return lastStats;
    }

    public static boolean runtimeEnabled() {
        return runtimeEnabled;
    }

    public static void setRuntimeEnabled(boolean enabled) {
        runtimeEnabled = enabled;
        lastStats = ChunkPriorityStats.empty(runtimeEnabled);
    }

    public static boolean toggleRuntimeEnabled() {
        runtimeEnabled = !runtimeEnabled;
        lastStats = ChunkPriorityStats.empty(runtimeEnabled);
        return runtimeEnabled;
    }

    private static void refreshTicketTypeIfNeeded() {
        int configuredTimeout = ChunkPriorityConfig.TICKET_TIMEOUT_TICKS.get();
        if (configuredTimeout != ticketTimeout) {
            ticketTimeout = configuredTimeout;
            ticketType = createTicketType(ticketTimeout);
        }
    }

    private static TicketType createTicketType(int timeoutTicks) {
        return new TicketType(timeoutTicks, TicketType.FLAG_LOADING | TicketType.FLAG_CAN_EXPIRE_IF_UNLOADED);
    }

    private static void logPlayerPlan(ServerPlayer player, List<ChunkPriorityTarget> targets) {
        int limit = Math.min(ChunkPriorityConfig.DEBUG_LOG_COUNT.get(), targets.size());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            builder.append(targets.get(i).shortText());
        }
        RedlineChunkPriority.LOGGER.info("Chunk priority plan for {}: {}", player.getScoreboardName(), builder);
    }

    private record LevelChunkKey(ResourceKey<Level> dimension, long chunkPos) {
    }

    private record TargetRequest(ServerLevel level, ChunkPriorityTarget target) {
    }

    private ChunkPriorityManager() {
    }
}
