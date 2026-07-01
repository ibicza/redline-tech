package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import com.redline.worldcore.server.ticket.WorldCoreTickets;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

/** Shared M6 entry point for cube loading. More cubic dimensions can get their own caches later. */
public final class WorldCoreCubeLoading {
    private static final CubicTestDimensionService CUBIC_TEST = new CubicTestDimensionService();
    private static ServerCubeCache cubicTestCache;
    private static Path cubicTestStorageRoot;

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!CUBIC_TEST.isRegistered(event.getServer())) {
            return;
        }
        cubicTest(event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve(com.redline.worldcore.RedlineWorldCore.MOD_ID)
                .resolve("cubic_test"))
                .tick(WorldCoreTickets.MANAGER.allTickets());
    }

    public static synchronized ServerCubeCache cubicTest(Path storageRoot) {
        if (cubicTestCache == null || cubicTestStorageRoot == null || !cubicTestStorageRoot.equals(storageRoot)) {
            cubicTestStorageRoot = storageRoot;
            cubicTestCache = new ServerCubeCache(storageRoot, CubicDimensionSettings.defaults());
        }
        return cubicTestCache;
    }

    public static synchronized ServerCubeCache cubicTestForServer(net.minecraft.server.MinecraftServer server) {
        return cubicTest(CUBIC_TEST.storageRoot(server));
    }

    private WorldCoreCubeLoading() {
    }
}
