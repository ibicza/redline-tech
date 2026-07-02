package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** M13.1 AFK pregen controller. It only starts jobs while the main manager is idle. */
public final class AfkPregenController {
    private static final Map<UUID, AfkPlayerState> PLAYERS = new HashMap<>();
    private static boolean enabled = false;
    private static int afkAfterTicks = 20 * 300;
    private static int radiusBlocks = 96;
    private static int verticalRadiusCubes = 4;
    private static CubeStatus targetStatus = CubeStatus.LIGHT_READY;
    private static long jobsStarted;
    private static String lastReason = "disabled";

    public static synchronized void tick(MinecraftServer server, CubePregenManager manager, CubicDimensionSettings settings) {
        Set<UUID> active = new HashSet<>();
        int afkPlayers = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel) || !CubicDimensionKeys.isCubicTest(player.level())) {
                continue;
            }
            active.add(player.getUUID());
            AfkPlayerState state = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> AfkPlayerState.from(player));
            state.update(player);
            if (state.idleTicks >= afkAfterTicks) {
                afkPlayers++;
            }
        }
        PLAYERS.keySet().removeIf(uuid -> !active.contains(uuid));

        if (!enabled) {
            lastReason = "disabled";
            return;
        }
        if (!manager.canStartBackgroundJob()) {
            lastReason = "pregen_busy";
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AfkPlayerState state = PLAYERS.get(player.getUUID());
            if (state == null || state.idleTicks < afkAfterTicks || !CubicDimensionKeys.isCubicTest(player.level())) {
                continue;
            }
            CubePregenJob job = createJob(player, settings);
            if (manager.startBackground(job, settings)) {
                jobsStarted++;
                lastReason = "started for " + player.getScoreboardName();
                state.idleTicks = 0;
                return;
            }
        }
        lastReason = afkPlayers > 0 ? "waiting_manager" : "no_afk_players";
    }

    private static CubePregenJob createJob(ServerPlayer player, CubicDimensionSettings settings) {
        CubePos center = CubePos.fromBlock(player.blockPosition());
        int minY = Math.max(settings.minCubeY(), center.y() - verticalRadiusCubes);
        int maxY = Math.min(settings.maxCubeY(), center.y() + verticalRadiusCubes);
        Vec3 position = player.position();
        CubePos min = CubePos.fromBlock(Mth.floor(position.x) - radiusBlocks, minY * CubePos.SIZE, Mth.floor(position.z) - radiusBlocks);
        CubePos max = CubePos.fromBlock(Mth.floor(position.x) + radiusBlocks, maxY * CubePos.SIZE + CubePos.MASK, Mth.floor(position.z) + radiusBlocks);
        return CubePregenJob.cuboid(min, max, targetStatus, "afk:" + player.getScoreboardName());
    }

    public static synchronized void configure(boolean newEnabled, int newAfkAfterTicks, int newRadiusBlocks, int newVerticalRadiusCubes, CubeStatus newTargetStatus) {
        enabled = newEnabled;
        afkAfterTicks = Math.max(20, Math.min(20 * 60 * 60, newAfkAfterTicks));
        radiusBlocks = Math.max(16, Math.min(1024, newRadiusBlocks));
        verticalRadiusCubes = Math.max(0, Math.min(64, newVerticalRadiusCubes));
        targetStatus = newTargetStatus == null ? CubeStatus.LIGHT_READY : newTargetStatus;
        lastReason = "configured";
    }

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        lastReason = value ? "enabled" : "disabled";
    }

    public static synchronized AfkPregenSnapshot snapshot() {
        int afkPlayers = 0;
        for (AfkPlayerState state : PLAYERS.values()) {
            if (state.idleTicks >= afkAfterTicks) {
                afkPlayers++;
            }
        }
        return new AfkPregenSnapshot(enabled, PLAYERS.size(), afkPlayers, jobsStarted, afkAfterTicks, radiusBlocks, verticalRadiusCubes, targetStatus.name(), lastReason);
    }

    public static synchronized void load(boolean loadedEnabled, int loadedAfkAfterTicks, int loadedRadiusBlocks, int loadedVerticalRadiusCubes, CubeStatus loadedTargetStatus, long loadedJobsStarted, String loadedLastReason) {
        enabled = loadedEnabled;
        afkAfterTicks = Math.max(20, Math.min(20 * 60 * 60, loadedAfkAfterTicks));
        radiusBlocks = Math.max(16, Math.min(1024, loadedRadiusBlocks));
        verticalRadiusCubes = Math.max(0, Math.min(64, loadedVerticalRadiusCubes));
        targetStatus = loadedTargetStatus == null ? CubeStatus.LIGHT_READY : loadedTargetStatus;
        jobsStarted = Math.max(0L, loadedJobsStarted);
        lastReason = loadedLastReason == null ? "loaded" : loadedLastReason;
    }

    public static synchronized boolean enabled() {
        return enabled;
    }

    public static synchronized int afkAfterTicks() {
        return afkAfterTicks;
    }

    public static synchronized int radiusBlocks() {
        return radiusBlocks;
    }

    public static synchronized int verticalRadiusCubes() {
        return verticalRadiusCubes;
    }

    public static synchronized CubeStatus targetStatus() {
        return targetStatus;
    }

    public static synchronized long jobsStarted() {
        return jobsStarted;
    }

    public static synchronized String lastReason() {
        return lastReason;
    }

    private static final class AfkPlayerState {
        private double x;
        private double y;
        private double z;
        private float yRot;
        private float xRot;
        private int idleTicks;

        private static AfkPlayerState from(ServerPlayer player) {
            AfkPlayerState state = new AfkPlayerState();
            state.x = player.getX();
            state.y = player.getY();
            state.z = player.getZ();
            state.yRot = player.getYRot();
            state.xRot = player.getXRot();
            return state;
        }

        private void update(ServerPlayer player) {
            boolean moved = Math.abs(player.getX() - x) > 0.05D
                    || Math.abs(player.getY() - y) > 0.05D
                    || Math.abs(player.getZ() - z) > 0.05D
                    || Math.abs(player.getYRot() - yRot) > 1.0F
                    || Math.abs(player.getXRot() - xRot) > 1.0F;
            if (moved) {
                idleTicks = 0;
                x = player.getX();
                y = player.getY();
                z = player.getZ();
                yRot = player.getYRot();
                xRot = player.getXRot();
                return;
            }
            idleTicks++;
        }
    }

    private AfkPregenController() {
    }
}
