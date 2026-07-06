package com.redline.worldcore.client.sync;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.client.query.ClientCubeWorldQuery;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private static final boolean SURFACE_PROJECTION_RENDER_ENABLED = true;
    private static final int IMMEDIATE_RADIUS = 2;
    // M19.2.3: the current player cube must no longer be skipped from the client mirror.
    // The server-side vanilla shell is only a temporary compatibility layer; if the active cube is excluded here,
    // block-break deltas can update the cube store while the already-built render mesh stays stale until the player
    // crosses a CubePos border. A disabled radius keeps every dirty/changed cube eligible for native-store mirroring.
    private static final int CLIENT_SHELL_SKIP_HORIZONTAL_RADIUS = -1;
    private static final int CLIENT_SHELL_SKIP_VERTICAL_RADIUS = -1;
    private static final int MAX_TASKS_TO_DISCOVER_PER_TICK = 384;
    private static final int MAX_VISUAL_MIRROR_SECTIONS_PER_TICK = 6;
    private static final int MAX_VISUAL_MIRROR_BLOCKS_PER_TICK = 16_384;
    private static final int MAX_VISUAL_MIRROR_MICROS_PER_TICK = 5_000;
    private static final int MAX_TRACKED_MIRRORED_SECTIONS = 8192;
    private static final int MAX_VISUAL_SHELL_PROBE_BLOCKS = 96;
    private static final int SET_BLOCK_FLAGS = 2 | 16 | 32;

    private static final int TILE_SIZE = 4;
    private static final int TILE_BITS = 2;
    private static final int TILE_MASK = TILE_SIZE - 1;

    private static final ArrayDeque<CubePos> MIRROR_QUEUE = new ArrayDeque<>();
    private static final Map<CubePos, VisualMirrorTask> TASKS = new HashMap<>();
    private static final Map<CubePos, Long> MIRRORED_HASHES = new HashMap<>();
    private static final ArrayDeque<ImmediateDeltaTask> IMMEDIATE_DELTA_QUEUE = new ArrayDeque<>();
    private static final ArrayDeque<CubePos> FORCED_DIRTY_QUEUE = new ArrayDeque<>();

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

        blocksMirroredLastTick = 0;
        CubePos playerCube = CubePos.fromBlock(
                minecraft.player.blockPosition().getX(),
                minecraft.player.blockPosition().getY(),
                minecraft.player.blockPosition().getZ()
        );
        Map<CubePos, ClientCubeSectionSnapshot> sections = ClientCubeSectionStore.copySections();
        processImmediateDeltaQueue(minecraft.level);
        processForcedDirtyQueue(minecraft.level);
        updateAvailabilityStats(playerCube, sections);
        discoverVisualMirrorTasks(minecraft.level, playerCube, sections);
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

    /**
     * M19.2.1: immediately pushes small server deltas into the temporary client visual shell.
     *
     * <p>The client cube query layer is already updated when this method runs. If we only invalidate the whole cube and wait
     * for the normal mirror queue, the player cube can be skipped as an "immediate server shell" area and the old render
     * section mesh keeps drawing a removed generated block. Writing the changed positions into the client chunk storage and
     * explicitly dirtying the render section makes block breaking disappear on the same client tick while the cube store stays
     * authoritative.</p>
     */
    public static synchronized void applyImmediateDelta(ClientCubeSectionSnapshot previous, ClientCubeSectionSnapshot updated, int[] localIndices) {
        if (previous == null || updated == null || localIndices.length == 0) {
            return;
        }

        // Client payload handlers are not the right place to touch ClientLevel/renderer state.  In the broken current-cube
        // case the only attempted visual update happened from the payload path, while the normal mirror path skipped the
        // player's own cube as an immediate server-shell area.  Queue exact block deltas and apply them from ClientTick.Post,
        // then the render extractor sees a normal main-thread section dirty mark.
        ArrayList<ImmediateBlockDelta> blocks = new ArrayList<>(localIndices.length);
        CubePos cubePos = updated.cubePos();
        for (int localIndex : localIndices) {
            if (localIndex < 0 || localIndex >= CubePos.BLOCK_COUNT) {
                continue;
            }
            blocks.add(new ImmediateBlockDelta(
                    localIndex,
                    previous.blockStateAtLocalIndex(localIndex),
                    updated.blockStateAtLocalIndex(localIndex)
            ));
        }
        if (blocks.isEmpty()) {
            return;
        }
        IMMEDIATE_DELTA_QUEUE.addLast(new ImmediateDeltaTask(cubePos, updated.hash(), blocks));
        RuntimeProfiler.addCount("client.render_bridge_immediate_delta_queued", 1);
        RuntimeProfiler.addCount("client.render_bridge_immediate_delta_blocks_queued", blocks.size());
    }

    private static synchronized int processImmediateDeltaQueue(ClientLevel level) {
        int appliedBlocks = 0;
        int appliedSections = 0;
        while (!IMMEDIATE_DELTA_QUEUE.isEmpty()) {
            ImmediateDeltaTask task = IMMEDIATE_DELTA_QUEUE.removeFirst();
            int sectionBlocks = 0;
            for (ImmediateBlockDelta delta : task.blocks) {
                int localIndex = delta.localIndex;
                int localX = localIndex & CubePos.MASK;
                int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
                int localY = localIndex >> (CubePos.SIZE_BITS * 2);
                int worldY = task.cubePos.minBlockY() + localY;
                if (level.isOutsideBuildHeight(worldY)) {
                    continue;
                }

                BlockPos blockPos = new BlockPos(task.cubePos.minBlockX() + localX, worldY, task.cubePos.minBlockZ() + localZ);

                // The cube store has already accepted the server state. Suppress cube-backed reads while mutating the
                // temporary vanilla client shell, otherwise Level#setBlock can compare against the already-new cube state
                // and leave the current render section with its old mesh.
                ClientCubeWorldQuery.runWithoutCubeQueryForVisualShellWrite(() -> level.setBlock(blockPos, delta.newState, SET_BLOCK_FLAGS));
                level.setBlocksDirty(blockPos, delta.oldState, delta.newState);
                level.sendBlockUpdated(blockPos, delta.oldState, delta.newState, SET_BLOCK_FLAGS);
                sectionBlocks++;
            }
            if (sectionBlocks > 0) {
                level.setSectionDirtyWithNeighbors(task.cubePos.x(), task.cubePos.y(), task.cubePos.z());
                appliedBlocks += sectionBlocks;
                appliedSections++;

                // Keep the normal mirror bookkeeping coherent.  The current player cube is now also eligible for a full
                // mirror pass, so any missed client-prediction edge case is repaired by the next high-priority mirror slice.
                if (MIRRORED_HASHES.containsKey(task.cubePos)) {
                    MIRRORED_HASHES.put(task.cubePos, task.hash);
                }
            }
        }
        if (appliedBlocks > 0) {
            visualMirrorBlocksWritten += appliedBlocks;
            blocksMirroredLastTick += appliedBlocks;
            RuntimeProfiler.addCount("client.render_bridge_immediate_delta_blocks", appliedBlocks);
            RuntimeProfiler.addCount("client.render_bridge_immediate_delta_sections", appliedSections);
        }
        return appliedBlocks;
    }

    public static synchronized void forceDirtySection(CubePos cubePos) {
        // Payload handlers can run outside the normal client tick/render extraction path.  Queue the dirty mark and touch
        // ClientLevel only from ClientTick.Post, same as immediate block deltas.
        FORCED_DIRTY_QUEUE.addLast(cubePos);
    }

    private static synchronized void processForcedDirtyQueue(ClientLevel level) {
        int dirty = 0;
        while (!FORCED_DIRTY_QUEUE.isEmpty() && dirty < MAX_VISUAL_MIRROR_SECTIONS_PER_TICK * 4) {
            CubePos cubePos = FORCED_DIRTY_QUEUE.removeFirst();
            dirtyVisualSection(level, cubePos);
            dirty++;
        }
        if (dirty > 0) {
            RuntimeProfiler.addCount("client.render_bridge_forced_dirty_sections", dirty);
        }
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
        IMMEDIATE_DELTA_QUEUE.clear();
        FORCED_DIRTY_QUEUE.clear();
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

    private static void discoverVisualMirrorTasks(ClientLevel level, CubePos playerCube, Map<CubePos, ClientCubeSectionSnapshot> sections) {
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
            if (!isWithinRenderRadius(cubePos, playerCube, snapshot)) {
                continue;
            }
            if (isImmediateServerShellArea(cubePos, playerCube)) {
                visualMirrorSkippedImmediate++;
                RuntimeProfiler.addCount("client.render_bridge_skipped_immediate_shell", 1);
                continue;
            }
            if (MIRRORED_HASHES.getOrDefault(cubePos, Long.MIN_VALUE) == snapshot.hash()) {
                if (!visualShellNeedsRepair(level, snapshot, playerCube)) {
                    visualMirrorHashHits++;
                    RuntimeProfiler.addCount("client.render_bridge_hash_hits", 1);
                    continue;
                }
                MIRRORED_HASHES.remove(cubePos);
                TASKS.remove(cubePos);
                RuntimeProfiler.addCount("client.render_bridge_hash_hit_shell_repairs", 1);
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
        int queueAtStart = MIRROR_QUEUE.size();
        int attempted = 0;
        while (!MIRROR_QUEUE.isEmpty()
                && attempted < queueAtStart
                && completed < MAX_VISUAL_MIRROR_SECTIONS_PER_TICK
                && blocks < MAX_VISUAL_MIRROR_BLOCKS_PER_TICK) {
            if (blocks > 0 && elapsedMicrosSince(start) >= MAX_VISUAL_MIRROR_MICROS_PER_TICK) {
                visualMirrorTimeBudgetHits++;
                RuntimeProfiler.addCount("client.render_bridge_time_budget_hits", 1);
                break;
            }
            attempted++;
            CubePos cubePos = MIRROR_QUEUE.removeFirst();
            VisualMirrorTask task = TASKS.get(cubePos);
            ClientCubeSectionSnapshot snapshot = sections.get(cubePos);
            if (task == null || snapshot == null || task.hash != snapshot.hash() || !isWithinRenderRadius(cubePos, playerCube, snapshot)) {
                TASKS.remove(cubePos);
                visualMirrorSkippedMissing++;
                RuntimeProfiler.addCount("client.render_bridge_missing_or_stale", 1);
                continue;
            }
            MirrorSliceResult result = mirrorSlice(level, snapshot, task, MAX_VISUAL_MIRROR_BLOCKS_PER_TICK - blocks, start);
            blocks += result.written();
            if (result.dirty()) {
                dirtyVisualSection(level, cubePos);
            }
            if (task.cursor >= CubePos.BLOCK_COUNT) {
                TASKS.remove(cubePos);
                MIRRORED_HASHES.put(cubePos, snapshot.hash());
                completed++;
                visualMirrorSectionsCompleted++;
                RuntimeProfiler.addCount("client.render_bridge_sections_completed", 1);
                if (result.written() == 0) {
                    // Even an already-equal shell can have an old compiled render mesh (the classic black-hole case).
                    // Mark the section dirty once when a cube becomes mirror-complete so vanilla rebuilds from the shell.
                    dirtyVisualSection(level, cubePos);
                }
            } else {
                MIRROR_QUEUE.addLast(cubePos);
                visualMirrorBudgetHits++;
                if (result.waitingForShell()) {
                    RuntimeProfiler.addCount("client.render_bridge_waiting_for_shell", 1);
                    // Do not break the whole queue on one column whose vanilla client shell is not ready yet.
                    // It can be retried on the next tick while other visible cube sections continue mirroring.
                    continue;
                }
                RuntimeProfiler.addCount("client.render_bridge_budget_hits", 1);
                break;
            }
        }
        blocksMirroredLastTick += blocks;
        visualMirrorBlocksWritten += blocks;
        queuedMirrorSections = MIRROR_QUEUE.size();
        mirroredSections = MIRRORED_HASHES.size();
        RuntimeProfiler.addCount("client.render_bridge_blocks_written", blocks);
        RuntimeProfiler.addCount("client.render_bridge_queue", queuedMirrorSections);
    }

    private static MirrorSliceResult mirrorSlice(ClientLevel level, ClientCubeSectionSnapshot snapshot, VisualMirrorTask task, int maxBlocks, long tickStartNanos) {
        int processed = 0;
        boolean dirty = false;
        boolean waitingForShell = false;
        CubePos cubePos = snapshot.cubePos();
        while (task.cursor < CubePos.BLOCK_COUNT && processed < maxBlocks) {
            if (processed > 0 && (processed & 63) == 0 && elapsedMicrosSince(tickStartNanos) >= MAX_VISUAL_MIRROR_MICROS_PER_TICK) {
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

                // The render bridge is still backed by vanilla RenderSection storage. A chunk-column can exist while the
                // concrete client section is not writable yet. Level#setBlock then returns false, but the old M19.2.4 code
                // still advanced the cursor and recorded this cube hash as mirrored. The result was exactly the new symptom:
                // cube-native reads/collision were solid, while the vanilla render section stayed empty until any manual
                // block edit dirtied and populated it. From now on a cursor advances only after the vanilla shell is already
                // equal to the snapshot or after a write is verified by reading the shell back with cube-query suppressed.
                BlockState shellState = vanillaShellState(level, blockPos);
                if (!shellState.equals(state)) {
                    if (!level.hasChunkAt(blockPos)) {
                        waitingForShell = true;
                        break;
                    }
                    boolean wrote = ClientCubeWorldQuery.callWithoutCubeQueryForVisualShellWrite(() -> level.setBlock(blockPos, state, SET_BLOCK_FLAGS));
                    BlockState afterWrite = vanillaShellState(level, blockPos);
                    if (!afterWrite.equals(state)) {
                        waitingForShell = true;
                        RuntimeProfiler.addCount(wrote ? "client.render_bridge_write_unverified" : "client.render_bridge_write_failed", 1);
                        break;
                    }
                    level.setBlocksDirty(blockPos, shellState, state);
                    level.sendBlockUpdated(blockPos, shellState, state, SET_BLOCK_FLAGS);
                    dirty = true;
                }
                processed++;
            }
            task.cursor++;
        }
        return new MirrorSliceResult(processed, dirty, waitingForShell);
    }

    private static void dirtyVisualSection(ClientLevel level, CubePos cubePos) {
        level.setSectionDirtyWithNeighbors(cubePos.x(), cubePos.y(), cubePos.z());
        RuntimeProfiler.addCount("client.render_bridge_dirty_visual_sections", 1);
    }

    /**
     * M19.2.6: lightweight guard against stale MIRRORED_HASHES.
     *
     * <p>Manual block edits repair invisible-but-solid cubes because they force vanilla shell writes. If a previous mirror
     * attempt marked the hash as done before the shell really contained the non-air blocks, normal discovery would skip the
     * section forever. Probe a few non-air sample positions before trusting a hash hit and requeue the cube if the temporary
     * vanilla shell is still air/stale.</p>
     */
    private static boolean visualShellNeedsRepair(ClientLevel level, ClientCubeSectionSnapshot snapshot, CubePos playerCube) {
        if (!snapshot.hasVisibleBlocks()) {
            return false;
        }
        CubePos cubePos = snapshot.cubePos();
        int checked = 0;
        int start = (int) ((scans * 97L) & (CubePos.BLOCK_COUNT - 1));
        for (int step = 0; step < CubePos.BLOCK_COUNT && checked < MAX_VISUAL_SHELL_PROBE_BLOCKS; step++) {
            int localIndex = tileOrderedLocalIndex((start + step) & (CubePos.BLOCK_COUNT - 1));
            BlockState expected = snapshot.blockStateAtLocalIndex(localIndex);
            if (expected.isAir()) {
                continue;
            }
            int localX = localIndex & CubePos.MASK;
            int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
            int localY = localIndex >> (CubePos.SIZE_BITS * 2);
            int worldY = cubePos.minBlockY() + localY;
            if (level.isOutsideBuildHeight(worldY)) {
                continue;
            }
            BlockPos blockPos = new BlockPos(cubePos.minBlockX() + localX, worldY, cubePos.minBlockZ() + localZ);
            if (!level.hasChunkAt(blockPos)) {
                return true;
            }
            BlockState shellState = vanillaShellState(level, blockPos);
            if (!shellState.equals(expected)) {
                RuntimeProfiler.addCount("client.render_bridge_visual_shell_probe_misses", 1);
                return true;
            }
            checked++;
        }
        return false;
    }

    private static BlockState vanillaShellState(ClientLevel level, BlockPos blockPos) {
        return ClientCubeWorldQuery.callWithoutCubeQueryForVisualShellWrite(() -> level.getBlockState(blockPos));
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

    private static boolean isWithinRenderRadius(CubePos cubePos, CubePos playerCube, ClientCubeSectionSnapshot snapshot) {
        int horizontal = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        if (horizontal > HORIZONTAL_RENDER_SCAN_RADIUS) {
            return false;
        }
        if (Math.abs(cubePos.y() - playerCube.y()) <= VERTICAL_RENDER_SCAN_RADIUS) {
            return true;
        }
        if (SURFACE_PROJECTION_RENDER_ENABLED && snapshot.hasVisibleBlocks()) {
            RuntimeProfiler.addCount("client.surface_projection_render_bridge_accept", 1);
            return true;
        }
        return false;
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

    private record MirrorSliceResult(int written, boolean dirty, boolean waitingForShell) {
    }

    private record ImmediateDeltaTask(CubePos cubePos, long hash, List<ImmediateBlockDelta> blocks) {
    }

    private record ImmediateBlockDelta(int localIndex, BlockState oldState, BlockState newState) {
    }

    private static final class VisualMirrorTask {
        private final long hash;
        private int cursor;

        private VisualMirrorTask(long hash) {
            this.hash = hash;
        }
    }
}
