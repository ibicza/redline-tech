package com.redline.worldcore.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers M10 play payloads used by the client sync/debug layer. */
public final class WorldCoreNetwork {
    public static final String NETWORK_VERSION = "10.0";

    private WorldCoreNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(NETWORK_VERSION)
                .playToClient(CubeClientSyncPayload.TYPE, CubeClientSyncPayload.CODEC);
    }
}
