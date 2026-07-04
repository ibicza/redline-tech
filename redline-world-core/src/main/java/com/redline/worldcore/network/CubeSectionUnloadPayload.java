package com.redline.worldcore.network;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-bound M17.4 packet that removes a cube-native section from the client-side store. */
public record CubeSectionUnloadPayload(int cubeX, int cubeY, int cubeZ) implements CustomPacketPayload {
    public static final Type<CubeSectionUnloadPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_section_unload"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CubeSectionUnloadPayload> CODEC = StreamCodec.ofMember(CubeSectionUnloadPayload::write, CubeSectionUnloadPayload::read);

    public static CubeSectionUnloadPayload of(CubePos cubePos) {
        return new CubeSectionUnloadPayload(cubePos.x(), cubePos.y(), cubePos.z());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public CubePos cubePos() {
        return new CubePos(cubeX, cubeY, cubeZ);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(cubeX);
        buffer.writeVarInt(cubeY);
        buffer.writeVarInt(cubeZ);
    }

    private static CubeSectionUnloadPayload read(RegistryFriendlyByteBuf buffer) {
        return new CubeSectionUnloadPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }
}
