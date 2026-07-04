package com.redline.worldcore.server.sync;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M17.2 bounded server-side cache for cube-native section snapshots.
 *
 * <p>The cache is intentionally not wired into the visible hot path yet.  M17.2 creates the stable DTO and cache API;
 * M17.3 can start feeding it into packet serialization without touching the vanilla shell materializer.</p>
 */
public final class CubeSectionSnapshotCache {
    public static final int DEFAULT_MAX_ENTRIES = 4096;

    private final int maxEntries;
    private final LinkedHashMap<CubePos, CubeSectionSnapshot> snapshots;

    public CubeSectionSnapshotCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public CubeSectionSnapshotCache(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
        this.snapshots = new LinkedHashMap<>(128, 0.75F, true);
    }

    public synchronized Optional<CubeSectionSnapshot> get(CubePos cubePos, long hash) {
        CubeSectionSnapshot snapshot = snapshots.get(cubePos);
        if (snapshot == null || snapshot.hash() != hash) {
            RuntimeProfiler.addCount("client.native_section_cache_misses", 1);
            return Optional.empty();
        }
        RuntimeProfiler.addCount("client.native_section_cache_hits", 1);
        return Optional.of(snapshot);
    }

    public synchronized CubeSectionSnapshot getOrBuild(LevelCube cube, long hash) {
        Optional<CubeSectionSnapshot> cached = get(cube.cubePos(), hash);
        if (cached.isPresent()) {
            return cached.get();
        }
        long start = RuntimeProfiler.markStart();
        CubeSectionSnapshot snapshot = build(cube, hash);
        put(snapshot);
        RuntimeProfiler.recordSince("client.native_section_snapshot_build", start);
        return snapshot;
    }

    public synchronized void put(CubeSectionSnapshot snapshot) {
        snapshots.put(snapshot.cubePos(), snapshot);
        while (snapshots.size() > maxEntries) {
            CubePos eldest = snapshots.keySet().iterator().next();
            snapshots.remove(eldest);
            RuntimeProfiler.addCount("client.native_section_cache_evictions", 1);
        }
    }

    public synchronized void invalidate(CubePos cubePos) {
        if (snapshots.remove(cubePos) != null) {
            RuntimeProfiler.addCount("client.native_section_cache_invalidations", 1);
        }
    }

    public synchronized int size() {
        return snapshots.size();
    }

    private static CubeSectionSnapshot build(LevelCube cube, long hash) {
        Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
        List<BlockState> palette = new ArrayList<>();
        int[] indices = new int[CubePos.BLOCK_COUNT];
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    int localIndex = CubePos.localIndex(localX, localY, localZ);
                    BlockState state = cube.getBlockState(localX, localY, localZ);
                    Integer paletteId = paletteIndex.get(state);
                    if (paletteId == null) {
                        paletteId = palette.size();
                        paletteIndex.put(state, paletteId);
                        palette.add(state);
                    }
                    indices[localIndex] = paletteId;
                }
            }
        }
        RuntimeProfiler.addCount("client.native_section_snapshot_blocks", CubePos.BLOCK_COUNT);
        RuntimeProfiler.addCount("client.native_section_snapshot_palette_entries", palette.size());
        return new CubeSectionSnapshot(cube.cubePos(), cube.status(), hash, palette, indices, cube.copyBlockLight(), cube.copySkyLight());
    }
}
