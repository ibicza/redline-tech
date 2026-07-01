package com.redline.worldcore;

import com.mojang.logging.LogUtils;
import com.redline.worldcore.server.debug.CubeMathSelfTest;
import com.redline.worldcore.server.debug.RedlineWorldCoreCommands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(RedlineWorldCore.MOD_ID)
public final class RedlineWorldCore {
    public static final String MOD_ID = "redline_world_core";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RedlineWorldCore(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> RedlineWorldCoreCommands.register(event));

        CubeMathSelfTest.runOrThrow();

        LOGGER.info("Loading Redline World Core");
        LOGGER.info("Cube size: {} blocks, region size: {}x{}x{} cubes", 16, 16, 16, 16);
    }
}
