package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import com.redline.worldcore.server.ticket.WorldCoreTickets;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Method;
import java.nio.file.Path;

/** Shared M6/M15 entry point for cube loading. More cubic dimensions can get their own caches later. */
public final class WorldCoreCubeLoading {
    private static final CubicTestDimensionService CUBIC_TEST = new CubicTestDimensionService();
    private static ServerCubeCache cubicTestCache;
    private static Path cubicTestStorageRoot;
    private static long cubicTestSeed;

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!CUBIC_TEST.isRegistered(event.getServer())) {
            return;
        }
        cubicTest(event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve(com.redline.worldcore.RedlineWorldCore.MOD_ID)
                .resolve("cubic_test"), seedFromServer(event.getServer()))
                .tick(WorldCoreTickets.MANAGER.allTickets(), event.getServer().getLevel(com.redline.worldcore.api.dimension.CubicDimensionKeys.CUBIC_TEST_LEVEL));
    }

    public static synchronized ServerCubeCache cubicTest(Path storageRoot) {
        return cubicTest(storageRoot, 0L);
    }

    public static synchronized ServerCubeCache cubicTest(Path storageRoot, long seed) {
        if (cubicTestCache == null || cubicTestStorageRoot == null || !cubicTestStorageRoot.equals(storageRoot) || cubicTestSeed != seed) {
            cubicTestStorageRoot = storageRoot;
            cubicTestSeed = seed;
            cubicTestCache = new ServerCubeCache(storageRoot, CubicDimensionSettings.defaults(), seed);
        }
        return cubicTestCache;
    }

    public static synchronized ServerCubeCache cubicTestForServer(MinecraftServer server) {
        return cubicTest(CUBIC_TEST.storageRoot(server), seedFromServer(server));
    }

    private static long seedFromServer(MinecraftServer server) {
        try {
            Method method = server.overworld().getClass().getMethod("getSeed");
            Object value = method.invoke(server.overworld());
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keep deterministic fallback for unusual dev/test environments where mapped getSeed is absent.
        }
        return 0L;
    }

    private WorldCoreCubeLoading() {
    }
}
