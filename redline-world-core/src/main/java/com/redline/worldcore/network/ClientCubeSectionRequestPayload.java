package com.redline.worldcore.network;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> server repair request for cube-native section snapshots.
 *
 * <p>The server may already believe a cube section was sent/acked, while the temporary client section store can still be
 * missing it after a race with loading, unload/reload, or a stale native-render handoff.  The client sends this compact
 * request when the debug/sync metadata says a nearby cube exists but {@code ClientCubeSectionStore} has no matching
 * snapshot.  The server then requeues a full snapshot instead of waiting for a block edit to accidentally repair it.</p>
 */
public record ClientCubeSectionRequestPayload(List<Entry> entries) implements CustomPacketPayload {
    public static final Type<ClientCubeSectionRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_section_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientCubeSectionRequestPayload> CODEC = StreamCodec.ofMember(ClientCubeSectionRequestPayload::write, ClientCubeSectionRequestPayload::read);
    public static final int MAX_ENTRIES = 64;

    public ClientCubeSectionRequestPayload {
        entries = List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Cube section request batch cannot be empty");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Cube section request batch is too large: " + entries.size());
        }
    }

    public static ClientCubeSectionRequestPayload single(CubePos cubePos, long hash) {
        return new ClientCubeSectionRequestPayload(List.of(new Entry(cubePos.x(), cubePos.y(), cubePos.z(), hash)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public long estimatedBytes() {
        return 4L + entries.size() * 20L;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeVarInt(entry.cubeX());
            buffer.writeVarInt(entry.cubeY());
            buffer.writeVarInt(entry.cubeZ());
            buffer.writeLong(entry.hash());
        }
    }

    private static ClientCubeSectionRequestPayload read(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size <= 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid cube section request batch size: " + size);
        }
        ArrayList<Entry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new Entry(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readLong()));
        }
        return new ClientCubeSectionRequestPayload(entries);
    }

    public record Entry(int cubeX, int cubeY, int cubeZ, long hash) {
        public CubePos cubePos() {
            return new CubePos(cubeX, cubeY, cubeZ);
        }
    }
}
