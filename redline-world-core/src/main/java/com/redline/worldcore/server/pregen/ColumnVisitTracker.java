package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.ColumnPos;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tracks X/Z columns visited by players in cubic_test. This feeds M13.1 vertical backfill. */
public final class ColumnVisitTracker {
    private static final int MAX_VISITED_COLUMNS = 16_384;
    private static final Map<ColumnPos, ColumnVisitEntry> VISITED = new LinkedHashMap<>();
    private static boolean dirty;

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().isClientSide() || !CubicDimensionKeys.isCubicTest(player.level())) {
            return;
        }
        CubePos cubePos = CubePos.fromBlock(player.blockPosition());
        markVisited(cubePos, player.level().getGameTime());
    }

    public static synchronized void markVisited(CubePos cubePos, long gameTime) {
        ColumnPos columnPos = cubePos.columnPos();
        ColumnVisitEntry entry = VISITED.get(columnPos);
        if (entry == null) {
            if (VISITED.size() >= MAX_VISITED_COLUMNS) {
                ColumnPos first = VISITED.keySet().iterator().next();
                VISITED.remove(first);
            }
            VISITED.put(columnPos, new ColumnVisitEntry(columnPos, cubePos.y(), gameTime));
            dirty = true;
            return;
        }
        int beforeMin = entry.minVisitedCubeY();
        int beforeMax = entry.maxVisitedCubeY();
        int beforeLast = entry.lastVisitedCubeY();
        entry.markVisited(cubePos.y(), gameTime);
        dirty = dirty || beforeMin != entry.minVisitedCubeY() || beforeMax != entry.maxVisitedCubeY() || beforeLast != entry.lastVisitedCubeY();
    }

    public static synchronized Optional<ColumnVisitEntry> current(CubePos playerCube) {
        return Optional.ofNullable(VISITED.get(playerCube.columnPos()));
    }

    public static synchronized List<ColumnVisitEntry> orderedForBackfill() {
        return VISITED.values().stream()
                .filter(entry -> !entry.backfillDone())
                .sorted(Comparator.comparingLong(ColumnVisitEntry::lastVisitedGameTime).reversed())
                .toList();
    }

    public static synchronized List<ColumnVisitEntry> recentEntries(int limit) {
        return VISITED.values().stream()
                .sorted(Comparator.comparingLong(ColumnVisitEntry::lastVisitedGameTime).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    public static synchronized ColumnVisitSnapshot snapshot(CubePos playerCube) {
        ColumnVisitEntry current = playerCube == null ? null : VISITED.get(playerCube.columnPos());
        int done = 0;
        for (ColumnVisitEntry entry : VISITED.values()) {
            if (entry.backfillDone()) {
                done++;
            }
        }
        return new ColumnVisitSnapshot(
                VISITED.size(),
                done,
                current == null ? null : current.columnPos(),
                current == null ? 0 : current.minVisitedCubeY(),
                current == null ? 0 : current.maxVisitedCubeY(),
                current == null ? 0 : current.lastVisitedCubeY(),
                current == null ? 0 : current.visits()
        );
    }

    public static synchronized Map<ColumnPos, ColumnVisitEntry> copyEntries() {
        return new LinkedHashMap<>(VISITED);
    }

    public static synchronized void replaceEntries(List<ColumnVisitEntry> entries) {
        VISITED.clear();
        for (ColumnVisitEntry entry : entries) {
            VISITED.put(entry.columnPos(), entry);
        }
        dirty = false;
    }

    public static synchronized int clear() {
        int size = VISITED.size();
        VISITED.clear();
        dirty = true;
        return size;
    }

    public static synchronized boolean consumeDirty() {
        boolean result = dirty;
        dirty = false;
        return result;
    }

    public static synchronized void markDirty() {
        dirty = true;
    }

    private ColumnVisitTracker() {
    }
}
