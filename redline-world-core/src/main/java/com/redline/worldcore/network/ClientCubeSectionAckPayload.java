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
 * M18.1 client -> server hash acknowledgement for cube-native section snapshots.
 *
 * <p>M17.10 already skipped sections remembered as sent by the server-side bridge.  This payload closes the loop: once
 * the client accepted a snapshot/delta into {@code ClientCubeSectionStore}, it acks the cube/hash pair.  The server can
 * then keep treating that hash as present even if packet batching/requeue logic sees the cube again, and profiles can
 * distinguish blind resend skips from real client-confirmed hash skips.</p>
 */
public record ClientCubeSectionAckPayload(List<Entry> entries) implements CustomPacketPayload {
    public static final Type<ClientCubeSectionAckPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_section_ack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientCubeSectionAckPayload> CODEC = StreamCodec.ofMember(ClientCubeSectionAckPayload::write, ClientCubeSectionAckPayload::read);
    public static final int MAX_ENTRIES = 64;

    public ClientCubeSectionAckPayload {
        entries = List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Cube section ack batch cannot be empty");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Cube section ack batch is too large: " + entries.size());
        }
    }

    public static ClientCubeSectionAckPayload single(CubePos cubePos, long hash) {
        return new ClientCubeSectionAckPayload(List.of(new Entry(cubePos.x(), cubePos.y(), cubePos.z(), hash)));
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

    private static ClientCubeSectionAckPayload read(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size <= 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid cube section ack batch size: " + size);
        }
        ArrayList<Entry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new Entry(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readLong()));
        }
        return new ClientCubeSectionAckPayload(entries);
    }

    public record Entry(int cubeX, int cubeY, int cubeZ, long hash) {
        public CubePos cubePos() {
            return new CubePos(cubeX, cubeY, cubeZ);
        }
    }
}
