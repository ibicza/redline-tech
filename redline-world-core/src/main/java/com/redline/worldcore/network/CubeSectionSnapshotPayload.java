package com.redline.worldcore.network;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.sync.CubeSectionSnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * M17.4 cube-native section snapshot payload.
 *
 * <p>This is the first real server-to-client data path that does not depend on the temporary vanilla shell. The client
 * stores these sections in {@code ClientCubeSectionStore}; rendering still uses the legacy hybrid shell until M17.5.</p>
 */
public record CubeSectionSnapshotPayload(
        int cubeX,
        int cubeY,
        int cubeZ,
        int statusOrdinal,
        long hash,
        int[] paletteStateIds,
        int[] paletteIndices,
        byte[] blockLight,
        byte[] skyLight
) implements CustomPacketPayload {
    public static final Type<CubeSectionSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_section_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CubeSectionSnapshotPayload> CODEC = StreamCodec.ofMember(CubeSectionSnapshotPayload::write, CubeSectionSnapshotPayload::read);

    public CubeSectionSnapshotPayload {
        paletteStateIds = paletteStateIds.clone();
        paletteIndices = paletteIndices.clone();
        blockLight = blockLight.clone();
        skyLight = skyLight.clone();
        if (paletteIndices.length != CubePos.BLOCK_COUNT) {
            throw new IllegalArgumentException("paletteIndices must contain " + CubePos.BLOCK_COUNT + " entries");
        }
        if (blockLight.length != CubePos.BLOCK_COUNT || skyLight.length != CubePos.BLOCK_COUNT) {
            throw new IllegalArgumentException("light arrays must contain " + CubePos.BLOCK_COUNT + " entries");
        }
    }

    public static CubeSectionSnapshotPayload from(CubeSectionSnapshot snapshot) {
        List<BlockState> palette = snapshot.palette();
        int[] ids = new int[palette.size()];
        for (int index = 0; index < palette.size(); index++) {
            ids[index] = Block.getId(palette.get(index));
        }
        return new CubeSectionSnapshotPayload(
                snapshot.cubePos().x(),
                snapshot.cubePos().y(),
                snapshot.cubePos().z(),
                snapshot.status().ordinal(),
                snapshot.hash(),
                ids,
                snapshot.copyPaletteIndices(),
                snapshot.copyBlockLight(),
                snapshot.copySkyLight()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public CubePos cubePos() {
        return new CubePos(cubeX, cubeY, cubeZ);
    }

    public long estimatedBytes() {
        int bits = bitsPerPaletteIndex(paletteStateIds.length);
        long packedIndexBytes = ((long) paletteIndices.length * bits + 7L) / 8L;
        return 48L + paletteStateIds.length * 5L + packedIndexBytes + blockLight.length + skyLight.length;
    }

    public List<BlockState> decodePalette() {
        List<BlockState> palette = new ArrayList<>(paletteStateIds.length);
        for (int id : paletteStateIds) {
            palette.add(Block.stateById(id));
        }
        return palette;
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        writeTo(buffer);
    }

    public void writeTo(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(cubeX);
        buffer.writeVarInt(cubeY);
        buffer.writeVarInt(cubeZ);
        buffer.writeVarInt(statusOrdinal);
        buffer.writeLong(hash);
        buffer.writeVarInt(paletteStateIds.length);
        for (int id : paletteStateIds) {
            buffer.writeVarInt(id);
        }
        writePackedPaletteIndices(buffer);
        buffer.writeByteArray(blockLight);
        buffer.writeByteArray(skyLight);
    }

    /**
     * M17.5 compact section index stream.  The in-memory DTO still exposes int[4096] for simple render-bridge code,
     * but the wire format uses 4/8/12/16 bit packed palette indices instead of one VarInt per block.
     */
    private void writePackedPaletteIndices(RegistryFriendlyByteBuf buffer) {
        int bits = bitsPerPaletteIndex(paletteStateIds.length);
        buffer.writeVarInt(bits);
        int valuesPerLong = Math.max(1, 64 / bits);
        int longCount = (paletteIndices.length + valuesPerLong - 1) / valuesPerLong;
        buffer.writeVarInt(paletteIndices.length);
        buffer.writeVarInt(longCount);
        long mask = (1L << bits) - 1L;
        int cursor = 0;
        for (int word = 0; word < longCount; word++) {
            long packed = 0L;
            for (int slot = 0; slot < valuesPerLong && cursor < paletteIndices.length; slot++, cursor++) {
                packed |= ((long) paletteIndices[cursor] & mask) << (slot * bits);
            }
            buffer.writeLong(packed);
        }
    }

    private static CubeSectionSnapshotPayload read(RegistryFriendlyByteBuf buffer) {
        int cubeX = buffer.readVarInt();
        int cubeY = buffer.readVarInt();
        int cubeZ = buffer.readVarInt();
        int statusOrdinal = buffer.readVarInt();
        long hash = buffer.readLong();
        int paletteSize = buffer.readVarInt();
        int[] paletteStateIds = new int[paletteSize];
        for (int index = 0; index < paletteSize; index++) {
            paletteStateIds[index] = buffer.readVarInt();
        }
        int[] paletteIndices = readPackedPaletteIndices(buffer);
        byte[] blockLight = buffer.readByteArray(CubePos.BLOCK_COUNT);
        byte[] skyLight = buffer.readByteArray(CubePos.BLOCK_COUNT);
        return new CubeSectionSnapshotPayload(cubeX, cubeY, cubeZ, statusOrdinal, hash, paletteStateIds, paletteIndices, blockLight, skyLight);
    }

    public static CubeSectionSnapshotPayload readFrom(RegistryFriendlyByteBuf buffer) {
        return read(buffer);
    }

    private static int[] readPackedPaletteIndices(RegistryFriendlyByteBuf buffer) {
        int bits = buffer.readVarInt();
        int indicesSize = buffer.readVarInt();
        int longCount = buffer.readVarInt();
        int[] paletteIndices = new int[indicesSize];
        int valuesPerLong = Math.max(1, 64 / bits);
        long mask = (1L << bits) - 1L;
        int cursor = 0;
        for (int word = 0; word < longCount; word++) {
            long packed = buffer.readLong();
            for (int slot = 0; slot < valuesPerLong && cursor < indicesSize; slot++, cursor++) {
                paletteIndices[cursor] = (int) ((packed >>> (slot * bits)) & mask);
            }
        }
        return paletteIndices;
    }

    public static int bitsPerPaletteIndex(int paletteSize) {
        if (paletteSize <= 16) {
            return 4;
        }
        if (paletteSize <= 256) {
            return 8;
        }
        if (paletteSize <= 4096) {
            return 12;
        }
        return 16;
    }
}
