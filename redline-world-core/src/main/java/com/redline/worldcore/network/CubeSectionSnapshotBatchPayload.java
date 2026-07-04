package com.redline.worldcore.network;

import com.redline.worldcore.RedlineWorldCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * M17.5 batched cube-native section stream.
 *
 * <p>M17.4 proved the custom cube packet path, but sending one play payload per cube produced packet overhead and
 * occasional send spikes.  This payload groups a small byte-budgeted batch of snapshots while keeping the client store
 * logic simple: the client just accepts every contained {@link CubeSectionSnapshotPayload}.</p>
 */
public record CubeSectionSnapshotBatchPayload(List<CubeSectionSnapshotPayload> snapshots) implements CustomPacketPayload {
    public static final Type<CubeSectionSnapshotBatchPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_section_snapshot_batch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CubeSectionSnapshotBatchPayload> CODEC = StreamCodec.ofMember(CubeSectionSnapshotBatchPayload::write, CubeSectionSnapshotBatchPayload::read);

    public CubeSectionSnapshotBatchPayload {
        snapshots = List.copyOf(snapshots);
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("Cube section snapshot batch cannot be empty");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public long estimatedBytes() {
        long bytes = 16L;
        for (CubeSectionSnapshotPayload snapshot : snapshots) {
            bytes += snapshot.estimatedBytes();
        }
        return bytes;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(snapshots.size());
        for (CubeSectionSnapshotPayload snapshot : snapshots) {
            snapshot.writeTo(buffer);
        }
    }

    private static CubeSectionSnapshotBatchPayload read(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size <= 0 || size > 64) {
            throw new IllegalArgumentException("Invalid cube section snapshot batch size: " + size);
        }
        java.util.ArrayList<CubeSectionSnapshotPayload> snapshots = new java.util.ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            snapshots.add(CubeSectionSnapshotPayload.readFrom(buffer));
        }
        return new CubeSectionSnapshotBatchPayload(snapshots);
    }
}
