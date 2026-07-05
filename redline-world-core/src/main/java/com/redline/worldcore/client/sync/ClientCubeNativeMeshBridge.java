package com.redline.worldcore.client.sync;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M18.2 native mesh prototype.
 *
 * <p>This is deliberately not the final renderer hook yet.  It proves the next layer after the M17 render mirror by
 * building a small native mesh descriptor directly from {@link ClientCubeSectionStore} without writing to the vanilla
 * client level.  The descriptor keeps counts/hashes/faces and is kept time-budgeted so M18.3 can replace the temporary
 * visual mirror with real RenderSection upload code.</p>
 */
public final class ClientCubeNativeMeshBridge {
    private static final int HORIZONTAL_SCAN_RADIUS = 8;
    private static final int VERTICAL_SCAN_RADIUS = 2;
    private static final boolean SURFACE_PROJECTION_MESH_ENABLED = true;
    private static final int MAX_DISCOVERY_SCAN_PER_TICK = 384;
    private static final int MAX_MESH_SECTIONS_PER_TICK = 4;
    private static final int MAX_MESH_BLOCKS_PER_TICK = 12_288;
    private static final int MAX_MESH_MICROS_PER_TICK = 3_500;
    private static final int MAX_TRACKED_MESHES = 8192;

    private static final ArrayDeque<CubePos> MESH_QUEUE = new ArrayDeque<>();
    private static final Map<CubePos, MeshBuildTask> TASKS = new HashMap<>();
    private static final Map<CubePos, NativeSectionMesh> MESHES = new HashMap<>();

    private static long scans;
    private static long tasksQueued;
    private static long sectionsBuilt;
    private static long blocksScanned;
    private static long solidBlocks;
    private static long facesEstimated;
    private static long hashHits;
    private static long invalidations;
    private static long budgetHits;
    private static long timeBudgetHits;
    private static int queueSize;
    private static int meshCount;
    private static int blocksLastTick;
    private static int readyAroundPlayer;

    private ClientCubeNativeMeshBridge() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clearTransientStats();
            return;
        }
        CubePos playerCube = CubePos.fromBlock(minecraft.player.blockPosition());
        Map<CubePos, ClientCubeSectionSnapshot> sections = ClientCubeSectionStore.copySections();
        discoverMeshTasks(playerCube, sections);
        processMeshQueue(sections, playerCube);
        trimMeshes();
        updateReadyStats(playerCube);
        scans++;
    }

    public static synchronized void forget(CubePos cubePos) {
        MESHES.remove(cubePos);
        TASKS.remove(cubePos);
        MESH_QUEUE.remove(cubePos);
    }

    public static synchronized void invalidate(CubePos cubePos) {
        if (MESHES.remove(cubePos) != null) {
            invalidations++;
            RuntimeProfiler.addCount("client.native_mesh_invalidations", 1);
        }
        TASKS.remove(cubePos);
        MESH_QUEUE.remove(cubePos);
    }

    private static void clearTransientStats() {
        queueSize = 0;
        meshCount = MESHES.size();
        blocksLastTick = 0;
        readyAroundPlayer = 0;
        MESH_QUEUE.clear();
        TASKS.clear();
    }

    private static void discoverMeshTasks(CubePos playerCube, Map<CubePos, ClientCubeSectionSnapshot> sections) {
        int scanned = 0;
        List<Map.Entry<CubePos, ClientCubeSectionSnapshot>> sorted = sections.entrySet().stream()
                .sorted((first, second) -> Integer.compare(meshPriority(first.getKey(), playerCube), meshPriority(second.getKey(), playerCube)))
                .toList();
        for (Map.Entry<CubePos, ClientCubeSectionSnapshot> entry : sorted) {
            if (scanned++ >= MAX_DISCOVERY_SCAN_PER_TICK) {
                RuntimeProfiler.addCount("client.native_mesh_discovery_budget_hits", 1);
                break;
            }
            CubePos cubePos = entry.getKey();
            ClientCubeSectionSnapshot snapshot = entry.getValue();
            if (!isWithinScanRadius(cubePos, playerCube, snapshot)) {
                continue;
            }
            NativeSectionMesh mesh = MESHES.get(cubePos);
            if (mesh != null && mesh.hash == snapshot.hash()) {
                hashHits++;
                RuntimeProfiler.addCount("client.native_mesh_hash_hits", 1);
                continue;
            }
            MeshBuildTask existing = TASKS.get(cubePos);
            if (existing != null && existing.hash == snapshot.hash()) {
                continue;
            }
            TASKS.put(cubePos, new MeshBuildTask(snapshot.hash()));
            if (!MESH_QUEUE.contains(cubePos)) {
                MESH_QUEUE.addLast(cubePos);
                tasksQueued++;
                RuntimeProfiler.addCount("client.native_mesh_tasks_queued", 1);
            }
        }
        queueSize = MESH_QUEUE.size();
        meshCount = MESHES.size();
        RuntimeProfiler.addCount("client.native_mesh_discovery_scanned", scanned);
    }

    private static void processMeshQueue(Map<CubePos, ClientCubeSectionSnapshot> sections, CubePos playerCube) {
        int blocks = 0;
        int completed = 0;
        long start = System.nanoTime();
        while (!MESH_QUEUE.isEmpty() && completed < MAX_MESH_SECTIONS_PER_TICK && blocks < MAX_MESH_BLOCKS_PER_TICK) {
            if (blocks > 0 && elapsedMicrosSince(start) >= MAX_MESH_MICROS_PER_TICK) {
                timeBudgetHits++;
                RuntimeProfiler.addCount("client.native_mesh_time_budget_hits", 1);
                break;
            }
            CubePos cubePos = MESH_QUEUE.removeFirst();
            MeshBuildTask task = TASKS.get(cubePos);
            ClientCubeSectionSnapshot snapshot = sections.get(cubePos);
            if (task == null || snapshot == null || task.hash != snapshot.hash() || !isWithinScanRadius(cubePos, playerCube, snapshot)) {
                TASKS.remove(cubePos);
                RuntimeProfiler.addCount("client.native_mesh_missing_or_stale", 1);
                continue;
            }
            int scanned = meshSlice(snapshot, task, MAX_MESH_BLOCKS_PER_TICK - blocks, start);
            blocks += scanned;
            if (task.cursor >= CubePos.BLOCK_COUNT) {
                TASKS.remove(cubePos);
                MESHES.put(cubePos, new NativeSectionMesh(snapshot.hash(), task.solidBlocks, task.facesEstimated));
                completed++;
                sectionsBuilt++;
                RuntimeProfiler.addCount("client.native_mesh_sections_built", 1);
            } else {
                MESH_QUEUE.addLast(cubePos);
                budgetHits++;
                RuntimeProfiler.addCount("client.native_mesh_budget_hits", 1);
                break;
            }
        }
        blocksLastTick = blocks;
        blocksScanned += blocks;
        queueSize = MESH_QUEUE.size();
        meshCount = MESHES.size();
        RuntimeProfiler.addCount("client.native_mesh_blocks_scanned", blocks);
        RuntimeProfiler.addCount("client.native_mesh_queue", queueSize);
    }

    private static int meshSlice(ClientCubeSectionSnapshot snapshot, MeshBuildTask task, int maxBlocks, long tickStartNanos) {
        int scanned = 0;
        while (task.cursor < CubePos.BLOCK_COUNT && scanned < maxBlocks) {
            if (scanned > 0 && (scanned & 63) == 0 && elapsedMicrosSince(tickStartNanos) >= MAX_MESH_MICROS_PER_TICK) {
                break;
            }
            int localIndex = task.cursor++;
            if (!snapshot.blockStateAtLocalIndex(localIndex).isAir()) {
                task.solidBlocks++;
                int faces = estimateVisibleFaces(snapshot, localIndex);
                task.facesEstimated += faces;
                solidBlocks++;
                facesEstimated += faces;
            }
            scanned++;
        }
        return scanned;
    }

    private static int estimateVisibleFaces(ClientCubeSectionSnapshot snapshot, int localIndex) {
        int x = localIndex & CubePos.MASK;
        int z = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
        int y = localIndex >> (CubePos.SIZE_BITS * 2);
        int faces = 0;
        if (x == 0 || snapshot.blockStateAtLocalIndex(localIndex - 1).isAir()) {
            faces++;
        }
        if (x == CubePos.MASK || snapshot.blockStateAtLocalIndex(localIndex + 1).isAir()) {
            faces++;
        }
        if (z == 0 || snapshot.blockStateAtLocalIndex(localIndex - CubePos.SIZE).isAir()) {
            faces++;
        }
        if (z == CubePos.MASK || snapshot.blockStateAtLocalIndex(localIndex + CubePos.SIZE).isAir()) {
            faces++;
        }
        if (y == 0 || snapshot.blockStateAtLocalIndex(localIndex - CubePos.SIZE * CubePos.SIZE).isAir()) {
            faces++;
        }
        if (y == CubePos.MASK || snapshot.blockStateAtLocalIndex(localIndex + CubePos.SIZE * CubePos.SIZE).isAir()) {
            faces++;
        }
        return faces;
    }

    private static boolean isWithinScanRadius(CubePos cubePos, CubePos playerCube, ClientCubeSectionSnapshot snapshot) {
        int horizontal = Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z()));
        if (horizontal > HORIZONTAL_SCAN_RADIUS) {
            return false;
        }
        if (Math.abs(cubePos.y() - playerCube.y()) <= VERTICAL_SCAN_RADIUS) {
            return true;
        }
        if (SURFACE_PROJECTION_MESH_ENABLED && snapshot != null && snapshot.hasVisibleBlocks()) {
            RuntimeProfiler.addCount("client.surface_projection_native_mesh_accept", 1);
            return true;
        }
        return false;
    }

    private static boolean isWithinScanRadius(CubePos cubePos, CubePos playerCube) {
        return Math.max(Math.abs(cubePos.x() - playerCube.x()), Math.abs(cubePos.z() - playerCube.z())) <= HORIZONTAL_SCAN_RADIUS
                && Math.abs(cubePos.y() - playerCube.y()) <= VERTICAL_SCAN_RADIUS;
    }

    private static int meshPriority(CubePos cubePos, CubePos playerCube) {
        int dx = cubePos.x() - playerCube.x();
        int dz = cubePos.z() - playerCube.z();
        int horizontal = Math.max(Math.abs(dx), Math.abs(dz));
        int dy = Math.abs(cubePos.y() - playerCube.y());
        return horizontal * 32 + dy * 8 + dx * dx + dz * dz;
    }

    private static void trimMeshes() {
        while (MESHES.size() > MAX_TRACKED_MESHES) {
            CubePos first = MESHES.keySet().iterator().next();
            MESHES.remove(first);
            RuntimeProfiler.addCount("client.native_mesh_evictions", 1);
        }
    }

    private static void updateReadyStats(CubePos playerCube) {
        int ready = 0;
        for (CubePos cubePos : MESHES.keySet()) {
            if (isWithinScanRadius(cubePos, playerCube)) {
                ready++;
            }
        }
        readyAroundPlayer = ready;
    }

    private static long elapsedMicrosSince(long startNanos) {
        return Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
    }

    public static long scans() {
        return scans;
    }

    public static int queueSize() {
        return queueSize;
    }

    public static int meshCount() {
        return meshCount;
    }

    public static int blocksLastTick() {
        return blocksLastTick;
    }

    public static int readyAroundPlayer() {
        return readyAroundPlayer;
    }

    public static long tasksQueued() {
        return tasksQueued;
    }

    public static long sectionsBuilt() {
        return sectionsBuilt;
    }

    public static long blocksScanned() {
        return blocksScanned;
    }

    public static long solidBlocks() {
        return solidBlocks;
    }

    public static long facesEstimated() {
        return facesEstimated;
    }

    public static long hashHits() {
        return hashHits;
    }

    public static long invalidations() {
        return invalidations;
    }

    public static long budgetHits() {
        return budgetHits;
    }

    public static long timeBudgetHits() {
        return timeBudgetHits;
    }

    private static final class MeshBuildTask {
        private final long hash;
        private int cursor;
        private int solidBlocks;
        private int facesEstimated;

        private MeshBuildTask(long hash) {
            this.hash = hash;
        }
    }

    private record NativeSectionMesh(long hash, int solidBlocks, int facesEstimated) {
    }
}
