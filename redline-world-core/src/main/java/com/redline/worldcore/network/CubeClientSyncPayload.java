package com.redline.worldcore.network;

import com.redline.worldcore.RedlineWorldCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * M8 client-bound debug/sync payload for the cubic test dimension.
 *
 * <p>This is intentionally metadata-first: it gives the client a stable view of the cube backend before the later risky
 * packet/mixin work starts replacing vanilla chunk packets. The vanilla column shell is still the compatibility boundary.</p>
 */
public record CubeClientSyncPayload(
        int playerCubeX,
        int playerCubeY,
        int playerCubeZ,
        int loadedCubes,
        int pendingLoads,
        int requestedCubes,
        long totalGenerated,
        int materializedCubes,
        int queuedMaterializations,
        int materializedLastTick,
        List<Entry> entries
) implements CustomPacketPayload {
    public static final Type<CubeClientSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_client_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CubeClientSyncPayload> CODEC = StreamCodec.ofMember(CubeClientSyncPayload::write, CubeClientSyncPayload::read);

    public CubeClientSyncPayload {
        entries = List.copyOf(entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(playerCubeX);
        buffer.writeVarInt(playerCubeY);
        buffer.writeVarInt(playerCubeZ);
        buffer.writeVarInt(loadedCubes);
        buffer.writeVarInt(pendingLoads);
        buffer.writeVarInt(requestedCubes);
        buffer.writeLong(totalGenerated);
        buffer.writeVarInt(materializedCubes);
        buffer.writeVarInt(queuedMaterializations);
        buffer.writeVarInt(materializedLastTick);
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            entry.write(buffer);
        }
    }

    private static CubeClientSyncPayload read(RegistryFriendlyByteBuf buffer) {
        int playerCubeX = buffer.readVarInt();
        int playerCubeY = buffer.readVarInt();
        int playerCubeZ = buffer.readVarInt();
        int loadedCubes = buffer.readVarInt();
        int pendingLoads = buffer.readVarInt();
        int requestedCubes = buffer.readVarInt();
        long totalGenerated = buffer.readLong();
        int materializedCubes = buffer.readVarInt();
        int queuedMaterializations = buffer.readVarInt();
        int materializedLastTick = buffer.readVarInt();
        int count = buffer.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.min(count, 256));
        for (int i = 0; i < count; i++) {
            entries.add(Entry.read(buffer));
        }
        return new CubeClientSyncPayload(
                playerCubeX,
                playerCubeY,
                playerCubeZ,
                loadedCubes,
                pendingLoads,
                requestedCubes,
                totalGenerated,
                materializedCubes,
                queuedMaterializations,
                materializedLastTick,
                entries
        );
    }

    public record Entry(
            int cubeX,
            int cubeY,
            int cubeZ,
            int statusOrdinal,
            int holderStateOrdinal,
            int ticketLevelOrdinal,
            long hash,
            boolean dirty,
            boolean materialized
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(cubeX);
            buffer.writeVarInt(cubeY);
            buffer.writeVarInt(cubeZ);
            buffer.writeVarInt(statusOrdinal);
            buffer.writeVarInt(holderStateOrdinal);
            buffer.writeVarInt(ticketLevelOrdinal);
            buffer.writeLong(hash);
            buffer.writeBoolean(dirty);
            buffer.writeBoolean(materialized);
        }

        private static Entry read(RegistryFriendlyByteBuf buffer) {
            return new Entry(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readBoolean(),
                    buffer.readBoolean()
            );
        }
    }
}
