package com.redline.worldcore.client.sync;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.network.ClientCubeSectionAckPayload;
import com.redline.worldcore.network.ClientCubeSectionRequestPayload;
import com.redline.worldcore.network.CubeClientSyncPayload;
import com.redline.worldcore.network.CubeSectionDeltaPayload;
import com.redline.worldcore.network.CubeSectionSnapshotBatchPayload;
import com.redline.worldcore.network.CubeSectionSnapshotPayload;
import com.redline.worldcore.network.CubeSectionUnloadPayload;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
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
    private static final LinkedHashMap<CubePos, Long> PENDING_ACKS = new LinkedHashMap<>(128, 0.75F, true);
    private static final LinkedHashMap<CubePos, Long> PENDING_REQUESTS = new LinkedHashMap<>(128, 0.75F, true);
    private static long receivedSnapshots;
    private static long replacedSnapshots;
    private static long unloads;
    private static long droppedEldest;
    private static long receivedBytesEstimate;
    private static long receivedBatches;
    private static long receivedDeltas;
    private static long appliedDeltas;
    private static long staleDeltas;
    private static long deltaEntries;
    private static long ackPacketsQueued;
    private static long ackEntriesQueued;
    private static long ackPacketsSent;
    private static long ackEntriesSent;
    private static long requestPacketsQueued;
    private static long requestEntriesQueued;
    private static long requestPacketsSent;
    private static long requestEntriesSent;

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
        enqueueAck(cubePos, payload.hash());
        // Full snapshots are authoritative repair points.  Even when the hash is the same, the previous visual mirror may
        // have completed while the temporary vanilla client shell was not writable yet.  Always drop the visual/native
        // mirror bookkeeping so the render bridge verifies and rewrites the shell from this snapshot instead of trusting a
        // stale MIRRORED_HASHES entry.
        if (previous != null && previous.hash() != payload.hash()) {
            replacedSnapshots++;
        }
        ClientCubeRenderBridge.invalidate(cubePos);
        ClientCubeRenderBridge.forceDirtySection(cubePos);
        ClientCubeNativeMeshBridge.invalidate(cubePos);
        while (SECTIONS.size() > MAX_SECTIONS) {
            CubePos eldest = SECTIONS.keySet().iterator().next();
            SECTIONS.remove(eldest);
            PENDING_ACKS.remove(eldest);
            PENDING_REQUESTS.remove(eldest);
            ClientCubeRenderBridge.forget(eldest);
            ClientCubeNativeMeshBridge.forget(eldest);
            droppedEldest++;
        }
    }

    public static synchronized void acceptBatch(CubeSectionSnapshotBatchPayload payload) {
        receivedBatches++;
        for (CubeSectionSnapshotPayload snapshot : payload.snapshots()) {
            accept(snapshot);
        }
    }


    public static synchronized void applyDelta(CubeSectionDeltaPayload payload) {
        receivedDeltas++;
        deltaEntries += payload.localIndices().length;
        CubePos cubePos = payload.cubePos();
        ClientCubeSectionSnapshot previous = SECTIONS.get(cubePos);
        if (previous == null) {
            staleDeltas++;
            return;
        }
        // Prefer exact hash chaining, but accept the delta as a repair if the server says this is the latest hash.
        // Full snapshots still replace the section if this optimistic delta was not enough.
        if (payload.baseHash() != Long.MIN_VALUE && previous.hash() != payload.baseHash()) {
            staleDeltas++;
        }
        ArrayList<BlockState> states = new ArrayList<>(payload.stateIds().length);
        for (int index = 0; index < payload.stateIds().length; index++) {
            states.add(payload.stateAt(index));
        }
        ClientCubeSectionSnapshot updated = previous.withBlockChanges(payload.newHash(), payload.localIndices(), states);
        SECTIONS.put(cubePos, updated);
        enqueueAck(cubePos, payload.newHash());
        appliedDeltas++;
        ClientCubeRenderBridge.invalidate(cubePos);
        ClientCubeRenderBridge.applyImmediateDelta(previous, updated, payload.localIndices());
        ClientCubeNativeMeshBridge.invalidate(cubePos);
    }

    public static synchronized void unload(CubeSectionUnloadPayload payload) {
        CubePos cubePos = payload.cubePos();
        if (SECTIONS.remove(cubePos) != null) {
            unloads++;
        }
        PENDING_ACKS.remove(cubePos);
        PENDING_REQUESTS.remove(cubePos);
        ClientCubeRenderBridge.forget(cubePos);
        ClientCubeNativeMeshBridge.forget(cubePos);
    }

    private static void enqueueAck(CubePos cubePos, long hash) {
        PENDING_ACKS.put(cubePos, hash);
        ackEntriesQueued++;
    }

    public static synchronized Optional<ClientCubeSectionAckPayload> pollAckPayload(int maxEntries) {
        if (PENDING_ACKS.isEmpty()) {
            return Optional.empty();
        }
        int count = Math.min(Math.max(1, maxEntries), Math.min(ClientCubeSectionAckPayload.MAX_ENTRIES, PENDING_ACKS.size()));
        ArrayList<ClientCubeSectionAckPayload.Entry> entries = new ArrayList<>(count);
        var iterator = PENDING_ACKS.entrySet().iterator();
        while (iterator.hasNext() && entries.size() < count) {
            Map.Entry<CubePos, Long> entry = iterator.next();
            CubePos cubePos = entry.getKey();
            entries.add(new ClientCubeSectionAckPayload.Entry(cubePos.x(), cubePos.y(), cubePos.z(), entry.getValue()));
            iterator.remove();
        }
        ackPacketsQueued++;
        return Optional.of(new ClientCubeSectionAckPayload(entries));
    }

    public static synchronized void recordAckSent(ClientCubeSectionAckPayload payload) {
        ackPacketsSent++;
        ackEntriesSent += payload.entries().size();
    }

    /**
     * M19.2.5: repair native-section holes detected from the metadata sync packet.
     *
     * <p>If the server says a nearby cube exists/native-ready but this client has no matching snapshot, local collision and
     * render can both fall back to the empty vanilla shell.  Queue a compact resend request instead of waiting until a
     * player block update accidentally causes a full refresh.</p>
     */
    public static synchronized void enqueueMissingRequestsFromSync(CubeClientSyncPayload payload) {
        int queued = 0;
        for (CubeClientSyncPayload.Entry entry : payload.entries()) {
            if (!entry.nativeReady()) {
                continue;
            }
            CubePos cubePos = new CubePos(entry.cubeX(), entry.cubeY(), entry.cubeZ());
            ClientCubeSectionSnapshot snapshot = SECTIONS.get(cubePos);
            if (snapshot != null && snapshot.hash() == entry.hash()) {
                PENDING_REQUESTS.remove(cubePos);
                continue;
            }
            PENDING_REQUESTS.put(cubePos, entry.hash());
            requestEntriesQueued++;
            queued++;
            if (queued >= 16) {
                break;
            }
        }
        if (queued > 0) {
            requestPacketsQueued++;
        }
    }

    public static synchronized Optional<ClientCubeSectionRequestPayload> pollRequestPayload(int maxEntries) {
        if (PENDING_REQUESTS.isEmpty()) {
            return Optional.empty();
        }
        int count = Math.min(Math.max(1, maxEntries), Math.min(ClientCubeSectionRequestPayload.MAX_ENTRIES, PENDING_REQUESTS.size()));
        ArrayList<ClientCubeSectionRequestPayload.Entry> entries = new ArrayList<>(count);
        var iterator = PENDING_REQUESTS.entrySet().iterator();
        while (iterator.hasNext() && entries.size() < count) {
            Map.Entry<CubePos, Long> entry = iterator.next();
            CubePos cubePos = entry.getKey();
            entries.add(new ClientCubeSectionRequestPayload.Entry(cubePos.x(), cubePos.y(), cubePos.z(), entry.getValue()));
            iterator.remove();
        }
        return Optional.of(new ClientCubeSectionRequestPayload(entries));
    }

    public static synchronized void recordRequestSent(ClientCubeSectionRequestPayload payload) {
        requestPacketsSent++;
        requestEntriesSent += payload.entries().size();
    }

    public static synchronized boolean hasHash(CubePos cubePos, long hash) {
        ClientCubeSectionSnapshot snapshot = SECTIONS.get(cubePos);
        return snapshot != null && snapshot.hash() == hash;
    }

    public static synchronized Optional<ClientCubeSectionSnapshot> get(CubePos cubePos) {
        return Optional.ofNullable(SECTIONS.get(cubePos));
    }

    /** Returns a small copy for client render-bridge bookkeeping without exposing the LRU map itself. */
    public static synchronized Map<CubePos, ClientCubeSectionSnapshot> copySections() {
        return Map.copyOf(SECTIONS);
    }

    public static synchronized SnapshotStats stats() {
        return new SnapshotStats(SECTIONS.size(), receivedSnapshots, receivedBatches, replacedSnapshots, unloads, droppedEldest, receivedBytesEstimate, receivedDeltas, appliedDeltas, staleDeltas, deltaEntries, PENDING_ACKS.size(), ackPacketsQueued, ackEntriesQueued, ackPacketsSent, ackEntriesSent);
    }

    public record SnapshotStats(
            int sections,
            long receivedSnapshots,
            long receivedBatches,
            long replacedSnapshots,
            long unloads,
            long droppedEldest,
            long receivedBytesEstimate,
            long receivedDeltas,
            long appliedDeltas,
            long staleDeltas,
            long deltaEntries,
            int pendingAcks,
            long ackPacketsQueued,
            long ackEntriesQueued,
            long ackPacketsSent,
            long ackEntriesSent
    ) {
    }
}
