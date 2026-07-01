package com.redline.worldcore.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers M8 play payloads used by the client sync/debug layer. */
public final class WorldCoreNetwork {
    public static final String NETWORK_VERSION = "8";

    private WorldCoreNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(NETWORK_VERSION)
                .playToClient(CubeClientSyncPayload.TYPE, CubeClientSyncPayload.CODEC);
    }
}
