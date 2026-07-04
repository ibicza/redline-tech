package com.redline.worldcore.client.sync;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M17.6/M17.7 client-side cube-native visual bridge.
 *
 * <p>M17.4/M17.5 taught the client to receive {@link ClientCubeSectionSnapshot}s, but the playable image still came
 * from the server-side vanilla-shell materializer.  This bridge is the first practical bypass: it uses the native
 * section store as a render source and mirrors a time-budgeted set of section blocks into the client level only.  It is
 * not the final renderer yet, but it moves medium/far visual fill away from server getBlockState/setBlock and gives the
 * next patch a stable RenderSection/mesh source to replace this temporary client shell.</p>
 */
public final class ClientCubeRenderBridge {
    private static final int HORIZONTAL_RENDER_SCAN_RADIUS = 8;
    private static final int VERTICAL_RENDER_SCAN_RADIUS = 2;
    private static final int IMMEDIATE_RADIUS = 2;
    private static final int CLIENT_SHELL_SKIP_HORIZONTAL_RADIUS = 0;
    private static final int CLIENT_SHELL_SKIP_VERTICAL_RADIUS = 0;
    private static final int MAX_TASKS_TO_DISCOVER_PER_TICK = 384;
    private static final int MAX_VISUAL_MIRROR_SECTIONS_PER_TICK = 6;
    private static final int MAX_VISUAL_MIRROR_BLOCKS_PER_TICK = 16_384;
    private static final int MAX_VISUAL_MIRROR_MICROS_PER_TICK = 5_000;
    private static final int MAX_TRACKED_MIRRORED_SECTIONS = 8192;
    private static final int SET_BLOCK_FLAGS = 2 | 16 | 32;

    private static final int TILE_SIZE = 4;
    private static final int TILE_BITS = 2;
    private static final int TILE_MASK = TILE_SIZE - 1;

    private static final ArrayDeque<CubePos> MIRROR_QUEUE = new ArrayDeque<>();
    private static final Map<CubePos, VisualMirrorTask> TASKS = new HashMap<>();
    private static final Map<CubePos, Long> MIRRORED_HASHES = new HashMap<>();

    private static long scans;
    private static long visualMirrorTasksQueued;
    private static long visualMirrorSectionsCompleted;
    private static long visualMirrorBlocksWritten;
    private static long visualMirrorBudgetHits;
    private static long visualMirrorTimeBudgetHits;
    private static long visualMirrorSkippedImmediate;
    private static long visualMirrorSkippedMissing;
    private static long visualMirrorHashHits;
    private static long visualMirrorInvalidations;
    private static int bridgeReadySections;
    private static int immediateReadySections;
    private static int missingImmediateSections;
    private static int queuedMirrorSections;
    private static int mirroredSections;
    private static int blocksMirroredLastTick;
    private static CubePos lastPlayerCube;

    private ClientCubeRenderBridge() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clearTransientStats();
            return;
        }

        CubePos playerCube = CubePos.fromBlock(
                minecraft.player.blockPosition().getX(),
                minecraft.player.blockPosition().getY(),
                minecraft.player.blockPosition().getZ()
        );
        Map<CubePos, ClientCubeSectionSnapshot> sections = ClientCubeSectionStore.copySections();
        updateAvailabilityStats(playerCube, sections);
        discoverVisualMirrorTasks(playerCube, sections);
        processVisualMirrorQueue(minecraft.level, sections, playerCube);
        trimMirroredHashes();
        lastPlayerCube = playerCube;
        scans++;
    }

    public static synchronized void forget(CubePos cubePos) {
        MIRRORED_HASHES.remove(cubePos);
        TASKS.remove(cubePos);
        MIRROR_QUEUE.remove(cubePos);
    }

    public static synchronized void invalidate(CubePos cubePos) {
        if (MIRRORED_HASHES.remove(cubePos) != null) {
            visualMirrorInvalidations++;
            RuntimeProfiler.addCount("client.render_bridge_delta_invalidations", 1);
        }
        TASKS.remove(cubePos);
        MIRROR_QUEUE.remove(cubePos);
    }


    private static void clearTransientStats() {
        bridgeReadySections = 0;
        immediateReadySections = 0;
        missingImmediateSections = 0;
        queuedMirrorSections = 0;
        mirroredSections = MIRRORED_HASHES.size();
        blocksMirroredLastTick = 0;
        lastPlayerCube = null;
        MIRROR_QUEUE.clear();
        TASKS.clear();
    }

    private static void updateAvailabilityStats(CubePos playerCube, Map<CubePos, ClientCubeSectionSnapshot> sections) {
        int ready = 0;
        int immediateReady = 0;
        int immediateMissing = 0;

        for (int y = playerCube.y() - VERTICAL_RENDER_SCAN_RADIUS; y <= playerCube.y() + VERTICAL_RENDER_SCAN_RADIUS; y++) {
            for (int z = playerCube.z() - HORIZONTAL_RENDER_SCAN_RADIUS; z <= playerCube.z() + HORIZONTAL_RENDER_SCAN_RADIUS; z++) {
                for (int x = playerCube.x() - HORIZONTAL_RENDER_SCAN_RADIUS; x <= playerCube.x() + HORIZONTAL_RENDER_SCAN_RADIUS; x++) {
                    CubePos cubePos = new CubePos(x, y, z);
                    boolean present = sections.containsKey(cubePos);
                    if (present) {
                        ready++;
                    }
                    int horizontal = Math.max(Math.abs(x - playerCube.x()), Math.abs(z - playerCube.z()));
                    int vertical = Math.abs(y - playerCube.y());
                    if (horizontal <= IMMEDIATE_RADIUS && vertical <= 1) {
                        if (present) {
                            immediateReady++;
                        } else {
                            immediateMissing++;
                        }
                    }
                }
            }
        }

        bridgeReadySections = ready;
        immediateReadySections = immediateReady;
        missingImmediateSections = immediateMissing;
    }

    private static void discoverVisualMirrorTasks(CubePos playerCube, Map<CubePos, ClientCubeSectionSnapshot> sections) {
        int scanned = 0;
        List<Map.Entry<CubePos, ClientCubeSectionSnapshot>> sorted = sections.entrySet().stream()
                .sorted((first, second) -> Integer.compare(renderPriority(first.getKey(), playerCube), renderPriority(second.getKey(), playerCube)))
                .toList();
        for (Map.Entry<CubePos, ClientCubeSectionSnapshot> entry : sorted) {
            if (scanned++ >= MAX_TASKS_TO_DISCOVER_PER_TICK) {
                RuntimeProfiler.addCount("client.render_bridge_discovery_budget_hits", 1);
                break;
            }
            CubePos cubePos = entry.getKey();
            ClientCubeSectionSnapshot snapshot = entry.getValue();
            if (!isWithinRenderRadius(cubePos, playerCube)) {
                continue;
            }
            if (isImmediateServerShellArea(cubePos, playerCube)) {
                visualMirrorSkippedImmediate++;
                RuntimeProfiler.addCount("client.render_bridge_skipped_immediate_shell", 1);
                continue;
            }
            if (MIRRORED_HASHES.getOrDefault(cubePos, Long.MIN_VALUE) == snapshot.hash()) {
                visualMirrorHashHits++;
                RuntimeProfiler.addCount("client.render_bridge_hash_hits", 1);
                continue;
            }
            VisualMirrorTask existing = TASKS.get(cubePos);
            if (existing != null && existing.hash == snapshot.hash()) {
                continue;
            }
            TASKS.put(cubePos, new VisualMirrorTask(snapshot.hash()));
            if (!MIRROR_QUEUE.contains(cubePos)) {
                MIRROR_QUEUE.addLast(cubePos);
                visualMirrorTasksQueued++;
                RuntimeProfiler.addCount("client.render_bridge_tasks_queued", 1);
            }
        }
        queuedMirrorSections = MIRROR_QUEUE.size();
        mirroredSections = MIRRORED_HASHES.size();
        RuntimeProfiler.addCount("client.render_bridge_discovery_scanned", scanned);
    }

    private static void processVisualMirrorQueue(ClientLevel level, Map<CubePos, ClientCubeSectionSnapshot> sections, CubePos playerCube) {
        int blocks = 0;
        int completed = 0;
        long start = System.nanoTime();
        while (!MIRROR_QUEUE.isEmpty() && completed < MAX_VISUAL_MIRROR_SECTIONS_PER_TICK && blocks < MAX_VISUAL_MIRROR_BLOCKS_PER_TICK) {
            if (blocks > 0 && elapsedMicrosSince(start) >= MAX_VISUAL_MIRROR_MICROS_PER_TICK) {
                visualMirrorTimeBudgetHits++;
                RuntimeProfiler.addCount("client.render_bridge_time_budget_hits", 1);
                break;
            }
            CubePos cubePos = MIRROR_QUEUE.removeFirst();
            VisualMirrorTask task = TASKS.get(cubePos);
            ClientCubeSectionSnapshot snapshot = sections.get(cubePos);
            if (task == null || snapshot == null || task.hash != snapshot.hash() || !isWithinRenderRadius(cubePos, playerCube)) {
                TASKS.remove(cubePos);
                visualMirrorSkippedMissing++;
                RuntimeProfiler.addCount("client.render_bridge_missing_or_stale", 1);
                continue;
            }
            int written = mirrorSlice(level, snapshot, task, MAX_VISUAL_MIRROR_BLOCKS_PER_TICK - blocks, start);
            blocks += written;
            if (task.cursor >= CubePos.BLOCK_COUNT) {
                TASKS.remove(cubePos);
                MIRRORED_HASHES.put(cubePos, snapshot.hash());
                completed++;
                visualMirrorSectionsCompleted++;
                RuntimeProfiler.addCount("client.render_bridge_sections_completed", 1);
            } else {
                MIRROR_QUEUE.addLast(cubePos);
                visualMirrorBudgetHits++;
                RuntimeProfiler.addCount("client.render_bridge_budget_hits", 1);
                break;
            }
        }
        blocksMirroredLastTick = blocks;
        visualMirrorBlocksWritten += blocks;
        queuedMirrorSections = MIRROR_QUEUE.size();
        mirroredSections = MIRRORED_HASHES.size();
        RuntimeProfiler.addCount("client.render_bridge_blocks_written", blocks);
        RuntimeProfiler.addCount("client.render_bridge_queue", queuedMirrorSections);
    }

    private static int mirrorSlice(ClientLevel level, ClientCubeSectionSnapshot snapshot, VisualMirrorTask task, int maxBlocks, long tickStartNanos) {
        int written = 0;
        CubePos cubePos = snapshot.cubePos();
        while (task.cursor < CubePos.BLOCK_COUNT && written < maxBlocks) {
            if (written > 0 && (written & 63) == 0 && elapsedMicrosSince(tickStartNanos) >= MAX_VISUAL_MIRROR_MICROS_PER_TICK) {
                break;
            }
            int localIndex = tileOrderedLocalIndex(task.cursor);
            int localX = localIndex & CubePos.MASK;
            int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
            int localY = localIndex >> (CubePos.SIZE_BITS * 2);
            int worldY = cubePos.minBlockY() + localY;
            if (!level.isOutsideBuildHeight(worldY)) {
                BlockState state = snapshot.blockStateAtLocalIndex(localIndex);
                BlockPos blockPos = new BlockPos(cubePos.minBlockX() + localX, worldY, cubePos.minBlockZ() + localZ);
                level.setBlock(blockPos, state, SET_BLOCK_FLAGS);
                written++;
            }
            task.cursor++;
        }
        return written;
    }

    private static int tileOrderedLocalIndex(int ordinal) {
        int blockInTile = ordinal & 63;
        int tileIndex = ordinal >> 6;

        int tileX = tileIndex & TILE_MASK;
        int tileZ = (tileIndex >> TILE_BITS) & TILE_MASK;
        int tileY = TILE_MASK - ((tileIndex >> (TILE_BITS * 2)) & TILE_MASK);

        int blockX = blockInTile & TILE_MASK;
        int blockZ = (blockInTile >> TILE_BITS) & TILE_MASK;
        int blockY = TILE_MASK - ((blockInTile >> (TILE_BITS * 2)) & TILE_MASK);

        int localX = (tileX << TILE_BITS) | blockX;
        int localY = (tileY << TILE_BITS) | blockY;
        int localZ = (tileZ << TILE_BITS) | blockZ;
        return localX | (localZ << CubePos.SIZE_BITS) | (localY << (CubePos.SIZE_BITS * 2));
    }

    private static boolean isWithinRenderRadius(CubePos cubePos, CubePos playerCube) {
        return Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z())) <= HORIZONTAL_RENDER_SCAN_RADIUS
                && Math.abs(cubePos.y() - playerCube.y()) <= VERTICAL_RENDER_SCAN_RADIUS;
    }

    private static boolean isImmediateServerShellArea(CubePos cubePos, CubePos playerCube) {
        return Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z())) <= CLIENT_SHELL_SKIP_HORIZONTAL_RADIUS
                && Math.abs(cubePos.y() - playerCube.y()) <= CLIENT_SHELL_SKIP_VERTICAL_RADIUS;
    }

    private static int renderPriority(CubePos cubePos, CubePos playerCube) {
        int dx = cubePos.x() - playerCube.x();
        int dz = cubePos.z() - playerCube.z();
        int horizontal = Math.max(Math.abs(dx), Math.abs(dz));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        return horizontal * 32 + dy * 8 + dx * dx + dz * dz;
    }

    private static void trimMirroredHashes() {
        while (MIRRORED_HASHES.size() > MAX_TRACKED_MIRRORED_SECTIONS) {
            CubePos first = MIRRORED_HASHES.keySet().iterator().next();
            MIRRORED_HASHES.remove(first);
            RuntimeProfiler.addCount("client.render_bridge_mirrored_hash_evictions", 1);
        }
    }

    private static long elapsedMicrosSince(long startNanos) {
        return Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
    }

    public static long scans() {
        return scans;
    }

    public static int bridgeReadySections() {
        return bridgeReadySections;
    }

    public static int immediateReadySections() {
        return immediateReadySections;
    }

    public static int missingImmediateSections() {
        return missingImmediateSections;
    }

    public static int queuedMirrorSections() {
        return queuedMirrorSections;
    }

    public static int mirroredSections() {
        return mirroredSections;
    }

    public static int blocksMirroredLastTick() {
        return blocksMirroredLastTick;
    }

    public static long visualMirrorBlocksWritten() {
        return visualMirrorBlocksWritten;
    }

    public static long visualMirrorSectionsCompleted() {
        return visualMirrorSectionsCompleted;
    }

    public static long visualMirrorBudgetHits() {
        return visualMirrorBudgetHits;
    }

    public static long visualMirrorInvalidations() {
        return visualMirrorInvalidations;
    }

    public static String lastPlayerCubeString() {
        return lastPlayerCube == null ? "-" : lastPlayerCube.x() + " " + lastPlayerCube.y() + " " + lastPlayerCube.z();
    }

    private static final class VisualMirrorTask {
        private final long hash;
        private int cursor;

        private VisualMirrorTask(long hash) {
            this.hash = hash;
        }
    }
}
