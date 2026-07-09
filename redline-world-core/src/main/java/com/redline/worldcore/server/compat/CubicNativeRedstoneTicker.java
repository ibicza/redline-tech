package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Small delayed redstone tick queue for the cube-native outside-shell redstone bridge.
 *
 * <p>Vanilla redstone gets its timing from LevelChunk scheduled ticks. At true cubic heights there is no vanilla
 * LevelChunkSection owner, so repeater delay and button release must be driven by a cube-native queue until scheduled
 * block ticks are executed directly from LevelCube.</p>
 */
public final class CubicNativeRedstoneTicker {
    private static final int MAX_QUEUE = 8192;
    private static final int MAX_STEPS_PER_TICK = 256;

    private static final PriorityQueue<ScheduledRedstoneTick> QUEUE = new PriorityQueue<>(Comparator.comparingLong(ScheduledRedstoneTick::dueTick));
    private static final Map<TickKey, Long> QUEUED = new HashMap<>();

    private static long totalScheduled;
    private static long totalProcessed;
    private static long totalRepeater;
    private static long totalButtonRelease;
    private static long totalRefresh;
    private static int scheduledLastTick;
    private static int processedLastTick;
    private static int repeaterLastTick;
    private static int buttonReleaseLastTick;
    private static int refreshLastTick;
    private static int delayedLastTick;

    private CubicNativeRedstoneTicker() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        scheduledLastTick = 0;
        processedLastTick = 0;
        repeaterLastTick = 0;
        buttonReleaseLastTick = 0;
        refreshLastTick = 0;
        delayedLastTick = 0;
        ServerLevel level = event.getServer().getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL);
        if (level == null) {
            QUEUE.clear();
            QUEUED.clear();
            return;
        }
        tick(level);
    }

    public static void scheduleRefresh(ServerLevel level, BlockPos pos, int delay, String reason) {
        enqueue(level, Kind.REFRESH, pos, false, delay, reason);
    }

    public static void scheduleButtonRelease(ServerLevel level, BlockPos pos, int delay, String reason) {
        enqueue(level, Kind.BUTTON_RELEASE, pos, false, delay, reason);
    }

    public static void scheduleDiode(ServerLevel level, BlockPos pos, boolean powered, int delay, String reason) {
        enqueue(level, Kind.DIODE_POWER, pos, powered, delay, reason);
    }

    public static Snapshot snapshot() {
        return new Snapshot(QUEUE.size(), totalScheduled, totalProcessed, totalRepeater, totalButtonRelease, totalRefresh,
                scheduledLastTick, processedLastTick, repeaterLastTick, buttonReleaseLastTick, refreshLastTick, delayedLastTick);
    }

    private static void tick(ServerLevel level) {
        long now = level.getGameTime();
        int steps = 0;
        while (steps < MAX_STEPS_PER_TICK && !QUEUE.isEmpty()) {
            ScheduledRedstoneTick next = QUEUE.peek();
            if (next.dueTick() > now) {
                delayedLastTick = QUEUE.size();
                break;
            }
            QUEUE.poll();
            TickKey key = new TickKey(next.kind(), next.pos());
            Long due = QUEUED.get(key);
            if (due == null || due.longValue() != next.dueTick()) {
                continue;
            }
            QUEUED.remove(key);
            steps++;
            processedLastTick++;
            totalProcessed++;
            switch (next.kind()) {
                case REFRESH -> {
                    CubicNativeRedstoneBridge.refreshAround(level, next.pos(), next.reason());
                    refreshLastTick++;
                    totalRefresh++;
                }
                case BUTTON_RELEASE -> {
                    if (CubicNativeRedstoneBridge.releaseButton(level, next.pos(), next.reason())) {
                        buttonReleaseLastTick++;
                        totalButtonRelease++;
                    }
                }
                case DIODE_POWER -> {
                    if (CubicNativeRedstoneBridge.applyScheduledDiodePower(level, next.pos(), next.powered(), next.reason())) {
                        repeaterLastTick++;
                        totalRepeater++;
                    }
                }
            }
        }
    }

    private static void enqueue(ServerLevel level, Kind kind, BlockPos pos, boolean powered, int delay, String reason) {
        if (!CubicNativeRedstoneBridge.shouldHandle(level, pos)) {
            return;
        }
        BlockPos immutable = pos.immutable();
        long dueTick = level.getGameTime() + Math.max(1, delay);
        TickKey key = new TickKey(kind, immutable);
        Long previous = QUEUED.get(key);
        if (previous != null && previous <= dueTick) {
            return;
        }
        if (previous == null && QUEUE.size() >= MAX_QUEUE) {
            return;
        }
        QUEUED.put(key, dueTick);
        QUEUE.add(new ScheduledRedstoneTick(kind, immutable, powered, dueTick, reason));
        scheduledLastTick++;
        totalScheduled++;
    }

    private enum Kind {
        REFRESH,
        BUTTON_RELEASE,
        DIODE_POWER
    }

    private record TickKey(Kind kind, BlockPos pos) {
    }

    private record ScheduledRedstoneTick(Kind kind, BlockPos pos, boolean powered, long dueTick, String reason) {
    }

    public record Snapshot(int queue,
                           long totalScheduled,
                           long totalProcessed,
                           long totalRepeater,
                           long totalButtonRelease,
                           long totalRefresh,
                           int scheduledLastTick,
                           int processedLastTick,
                           int repeaterLastTick,
                           int buttonReleaseLastTick,
                           int refreshLastTick,
                           int delayedLastTick) {
    }
}
