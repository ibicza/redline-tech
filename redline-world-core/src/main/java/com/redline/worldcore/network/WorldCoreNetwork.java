package com.redline.worldcore.network;

import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers RWC play payloads used by the client sync/debug layer. */
public final class WorldCoreNetwork {
    public static final String NETWORK_VERSION = "18.2.0";

    private WorldCoreNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(CubeClientSyncPayload.TYPE, CubeClientSyncPayload.CODEC);
        registrar.playToClient(CubeSectionSnapshotPayload.TYPE, CubeSectionSnapshotPayload.CODEC);
        registrar.playToClient(CubeSectionSnapshotBatchPayload.TYPE, CubeSectionSnapshotBatchPayload.CODEC);
        registrar.playToClient(CubeSectionDeltaPayload.TYPE, CubeSectionDeltaPayload.CODEC);
        registrar.playToClient(CubeSectionUnloadPayload.TYPE, CubeSectionUnloadPayload.CODEC);
        registrar.playToServer(ClientCubeSectionAckPayload.TYPE, ClientCubeSectionAckPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                CubicClientSyncBridge.handleNativeSectionAck(serverPlayer, payload);
            }
        });
    }
}
