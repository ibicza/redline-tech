package com.redline.worldcore.network;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M17.8 cube-native section delta payload.
 *
 * <p>This is the first small update packet for the native section store. Full snapshots still bootstrap a section, but
 * normal player/block edits can now update a few local positions without forcing a whole 16x16x16 re-send or a server
 * vanilla-shell remirror.</p>
 */
public record CubeSectionDeltaPayload(
        int cubeX,
        int cubeY,
        int cubeZ,
        long baseHash,
        long newHash,
        int[] localIndices,
        int[] stateIds
) implements CustomPacketPayload {
    public static final Type<CubeSectionDeltaPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_section_delta"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CubeSectionDeltaPayload> CODEC = StreamCodec.ofMember(CubeSectionDeltaPayload::write, CubeSectionDeltaPayload::read);

    public CubeSectionDeltaPayload {
        localIndices = localIndices.clone();
        stateIds = stateIds.clone();
        if (localIndices.length != stateIds.length) {
            throw new IllegalArgumentException("localIndices/stateIds length mismatch");
        }
        if (localIndices.length > 1024) {
            throw new IllegalArgumentException("too many cube delta entries: " + localIndices.length);
        }
        for (int index : localIndices) {
            if (index < 0 || index >= CubePos.BLOCK_COUNT) {
                throw new IllegalArgumentException("local index outside cube: " + index);
            }
        }
    }

    public static CubeSectionDeltaPayload single(CubePos cubePos, long baseHash, long newHash, int localIndex, BlockState state) {
        return new CubeSectionDeltaPayload(
                cubePos.x(),
                cubePos.y(),
                cubePos.z(),
                baseHash,
                newHash,
                new int[]{localIndex},
                new int[]{Block.getId(state)}
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public CubePos cubePos() {
        return new CubePos(cubeX, cubeY, cubeZ);
    }

    public BlockState stateAt(int index) {
        return Block.stateById(stateIds[index]);
    }

    public long estimatedBytes() {
        return 40L + localIndices.length * 10L;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(cubeX);
        buffer.writeVarInt(cubeY);
        buffer.writeVarInt(cubeZ);
        buffer.writeLong(baseHash);
        buffer.writeLong(newHash);
        buffer.writeVarInt(localIndices.length);
        for (int index = 0; index < localIndices.length; index++) {
            buffer.writeVarInt(localIndices[index]);
            buffer.writeVarInt(stateIds[index]);
        }
    }

    private static CubeSectionDeltaPayload read(RegistryFriendlyByteBuf buffer) {
        int cubeX = buffer.readVarInt();
        int cubeY = buffer.readVarInt();
        int cubeZ = buffer.readVarInt();
        long baseHash = buffer.readLong();
        long newHash = buffer.readLong();
        int count = buffer.readVarInt();
        int[] localIndices = new int[count];
        int[] stateIds = new int[count];
        for (int index = 0; index < count; index++) {
            localIndices[index] = buffer.readVarInt();
            stateIds[index] = buffer.readVarInt();
        }
        return new CubeSectionDeltaPayload(cubeX, cubeY, cubeZ, baseHash, newHash, localIndices, stateIds);
    }
}
