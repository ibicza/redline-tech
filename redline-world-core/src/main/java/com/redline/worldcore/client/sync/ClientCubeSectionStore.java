package com.redline.worldcore.client.sync;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.network.CubeSectionSnapshotPayload;
import com.redline.worldcore.network.CubeSectionUnloadPayload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side cube-native section cache introduced in M17.4.
 *
 * <p>It does not render yet. M17.5 will use this store as the source for a vanilla render-section bridge, replacing the
 * current setBlock-based shell for more and more of the visible volume.</p>
 */
public final class ClientCubeSectionStore {
    private static final int MAX_SECTIONS = 8192;
    private static final LinkedHashMap<CubePos, ClientCubeSectionSnapshot> SECTIONS = new LinkedHashMap<>(256, 0.75F, true);
    private static long receivedSnapshots;
    private static long replacedSnapshots;
    private static long unloads;
    private static long droppedEldest;
    private static long receivedBytesEstimate;

    private ClientCubeSectionStore() {
    }

    public static synchronized void accept(CubeSectionSnapshotPayload payload) {
        CubePos cubePos = payload.cubePos();
        ClientCubeSectionSnapshot previous = SECTIONS.put(cubePos, new ClientCubeSectionSnapshot(
                cubePos,
                payload.statusOrdinal(),
                payload.hash(),
                payload.decodePalette(),
                payload.paletteIndices(),
                payload.blockLight(),
                payload.skyLight()
        ));
        receivedSnapshots++;
        receivedBytesEstimate += payload.estimatedBytes();
        if (previous != null && previous.hash() != payload.hash()) {
            replacedSnapshots++;
        }
        while (SECTIONS.size() > MAX_SECTIONS) {
            CubePos eldest = SECTIONS.keySet().iterator().next();
            SECTIONS.remove(eldest);
            droppedEldest++;
        }
    }

    public static synchronized void unload(CubeSectionUnloadPayload payload) {
        if (SECTIONS.remove(payload.cubePos()) != null) {
            unloads++;
        }
    }

    public static synchronized Optional<ClientCubeSectionSnapshot> get(CubePos cubePos) {
        return Optional.ofNullable(SECTIONS.get(cubePos));
    }

    public static synchronized SnapshotStats stats() {
        return new SnapshotStats(SECTIONS.size(), receivedSnapshots, replacedSnapshots, unloads, droppedEldest, receivedBytesEstimate);
    }

    public record SnapshotStats(
            int sections,
            long receivedSnapshots,
            long replacedSnapshots,
            long unloads,
            long droppedEldest,
            long receivedBytesEstimate
    ) {
    }
}
