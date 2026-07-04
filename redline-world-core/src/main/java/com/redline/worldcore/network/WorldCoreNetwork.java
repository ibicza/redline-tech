package com.redline.worldcore.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers RWC play payloads used by the client sync/debug layer. */
public final class WorldCoreNetwork {
    public static final String NETWORK_VERSION = "17.4.0";

    private WorldCoreNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(CubeClientSyncPayload.TYPE, CubeClientSyncPayload.CODEC);
        registrar.playToClient(CubeSectionSnapshotPayload.TYPE, CubeSectionSnapshotPayload.CODEC);
        registrar.playToClient(CubeSectionUnloadPayload.TYPE, CubeSectionUnloadPayload.CODEC);
    }
}
