package com.redline.worldcore.client;

import com.redline.worldcore.client.debug.CubeDebugOverlay;
import com.redline.worldcore.client.lighting.ClientDynamicLightLayer;
import com.redline.worldcore.client.sync.ClientCubeSectionStore;
import com.redline.worldcore.client.sync.ClientCubeSyncState;
import com.redline.worldcore.network.CubeClientSyncPayload;
import com.redline.worldcore.network.CubeSectionSnapshotPayload;
import com.redline.worldcore.network.CubeSectionUnloadPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only bootstrap for M8 debug/sync rendering and M11 dynamic light. */
public final class RedlineWorldCoreClient {
    private RedlineWorldCoreClient() {
    }

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(RedlineWorldCoreClient::registerClientPayloads);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientDynamicLightLayer.onClientTick(event));
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> CubeDebugOverlay.render(event));
    }

    private static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(CubeClientSyncPayload.TYPE, (payload, context) -> ClientCubeSyncState.accept(payload));
        event.register(CubeSectionSnapshotPayload.TYPE, (payload, context) -> ClientCubeSectionStore.accept(payload));
        event.register(CubeSectionUnloadPayload.TYPE, (payload, context) -> ClientCubeSectionStore.unload(payload));
    }
}
