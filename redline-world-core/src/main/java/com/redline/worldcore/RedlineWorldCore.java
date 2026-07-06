package com.redline.worldcore;

import com.mojang.logging.LogUtils;
import com.redline.worldcore.client.RedlineWorldCoreClient;
import com.redline.worldcore.network.WorldCoreNetwork;
import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import com.redline.worldcore.server.compat.CubicNativeFluidTicker;
import com.redline.worldcore.server.compat.CubicNativePassiveSpawner;
import com.redline.worldcore.server.compat.CubicTestGameplayGuard;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.debug.CubeMathSelfTest;
import com.redline.worldcore.server.debug.CubeOwnershipAutoTest;
import com.redline.worldcore.server.debug.RedlineWorldCoreCommands;
import com.redline.worldcore.server.entity.EntityCubeTracker;
import com.redline.worldcore.server.pregen.ColumnVisitTracker;
import com.redline.worldcore.server.pregen.CubePregenManager;
import com.redline.worldcore.server.ticket.PlayerCubeTicketUpdater;
import com.redline.worldcore.server.ticket.WorldCoreTickets;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.slf4j.Logger;

@Mod(RedlineWorldCore.MOD_ID)
public final class RedlineWorldCore {
    public static final String MOD_ID = "redline_world_core";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RedlineWorldCore(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(WorldCoreNetwork::registerPayloads);
        if (FMLEnvironment.getDist().isClient()) {
            RedlineWorldCoreClient.init(modEventBus);
        }

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> RedlineWorldCoreCommands.register(event));
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> PlayerCubeTicketUpdater.onPlayerTick(event));
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> ColumnVisitTracker.onPlayerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> WorldCoreTickets.onServerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> WorldCoreCubeLoading.onServerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> EntityCubeTracker.onServerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> CubePregenManager.MANAGER.onServerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> CubicClientSyncBridge.onServerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> CubicNativeFluidTicker.onServerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> CubicNativePassiveSpawner.onServerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> CubeOwnershipAutoTest.onServerTick(event));
        NeoForge.EVENT_BUS.addListener((BreakBlockEvent event) -> CubicClientSyncBridge.onBlockBreak(event));
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent event) -> CubicClientSyncBridge.onBlockPlace(event));
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> CubicTestGameplayGuard.onEntityJoinLevel(event));

        CubeMathSelfTest.runOrThrow();

        LOGGER.info("Loading Redline World Core");
        LOGGER.info("Cube size: {} blocks, region size: {}x{}x{} cubes", 16, 16, 16, 16);
    }
}
