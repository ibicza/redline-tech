package com.redline.worldcore.client;

import com.redline.worldcore.client.debug.CubeDebugOverlay;
import com.redline.worldcore.client.lighting.ClientDynamicLightLayer;
import com.redline.worldcore.client.sync.ClientCubeNativeMeshBridge;
import com.redline.worldcore.client.sync.ClientCubeRenderBridge;
import com.redline.worldcore.client.sync.ClientCubeSectionStore;
import com.redline.worldcore.client.sync.ClientCubeSyncState;
import com.redline.worldcore.network.ClientCubeSectionAckPayload;
import com.redline.worldcore.network.CubeClientSyncPayload;
import com.redline.worldcore.network.CubeSectionDeltaPayload;
import com.redline.worldcore.network.CubeSectionSnapshotBatchPayload;
import com.redline.worldcore.network.CubeSectionSnapshotPayload;
import com.redline.worldcore.network.CubeSectionUnloadPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
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
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientCubeRenderBridge.onClientTick(event));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientCubeNativeMeshBridge.onClientTick(event));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> flushSectionAcks());
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> CubeDebugOverlay.render(event));
    }

    private static void flushSectionAcks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        ClientCubeSectionStore.pollAckPayload(ClientCubeSectionAckPayload.MAX_ENTRIES).ifPresent(payload -> {
            minecraft.getConnection().send(new ServerboundCustomPayloadPacket(payload));
            ClientCubeSectionStore.recordAckSent(payload);
        });
    }

    private static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(CubeClientSyncPayload.TYPE, (payload, context) -> ClientCubeSyncState.accept(payload));
        event.register(CubeSectionSnapshotPayload.TYPE, (payload, context) -> ClientCubeSectionStore.accept(payload));
        event.register(CubeSectionSnapshotBatchPayload.TYPE, (payload, context) -> ClientCubeSectionStore.acceptBatch(payload));
        event.register(CubeSectionDeltaPayload.TYPE, (payload, context) -> ClientCubeSectionStore.applyDelta(payload));
        event.register(CubeSectionUnloadPayload.TYPE, (payload, context) -> ClientCubeSectionStore.unload(payload));
    }
}
