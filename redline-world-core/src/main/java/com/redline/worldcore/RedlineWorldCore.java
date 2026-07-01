package com.redline.worldcore;

import com.mojang.logging.LogUtils;
import com.redline.worldcore.server.debug.CubeMathSelfTest;
import com.redline.worldcore.server.debug.RedlineWorldCoreCommands;
import com.redline.worldcore.server.ticket.PlayerCubeTicketUpdater;
import com.redline.worldcore.server.ticket.WorldCoreTickets;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(RedlineWorldCore.MOD_ID)
public final class RedlineWorldCore {
    public static final String MOD_ID = "redline_world_core";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RedlineWorldCore(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> RedlineWorldCoreCommands.register(event));
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> PlayerCubeTicketUpdater.onPlayerTick(event));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> WorldCoreTickets.onServerTick(event));

        CubeMathSelfTest.runOrThrow();

        LOGGER.info("Loading Redline World Core");
        LOGGER.info("Cube size: {} blocks, region size: {}x{}x{} cubes", 16, 16, 16, 16);
    }
}
