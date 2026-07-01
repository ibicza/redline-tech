package com.redline.worldcore.client.sync;

import com.redline.worldcore.network.CubeClientSyncPayload;
import net.minecraft.client.Minecraft;

import java.util.Optional;

/** Client-side copy of the latest M8 cube sync payload. */
public final class ClientCubeSyncState {
    private static CubeClientSyncPayload latest;
    private static long receivedAtGameTime;

    private ClientCubeSyncState() {
    }

    public static void accept(CubeClientSyncPayload payload) {
        latest = payload;
        Minecraft minecraft = Minecraft.getInstance();
        receivedAtGameTime = minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }

    public static Optional<CubeClientSyncPayload> latest() {
        return Optional.ofNullable(latest);
    }

    public static long receivedAtGameTime() {
        return receivedAtGameTime;
    }

    public static boolean hasFreshPayload() {
        Minecraft minecraft = Minecraft.getInstance();
        if (latest == null || minecraft.level == null) {
            return false;
        }
        return minecraft.level.getGameTime() - receivedAtGameTime <= 80L;
    }
}
