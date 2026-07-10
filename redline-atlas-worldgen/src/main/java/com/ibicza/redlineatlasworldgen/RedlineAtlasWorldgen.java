package com.ibicza.redlineatlasworldgen;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(RedlineAtlasWorldgen.MOD_ID)
public final class RedlineAtlasWorldgen {
    public static final String MOD_ID = "redline_atlas_worldgen";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RedlineAtlasWorldgen(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, AtlasWorldgenConfig.SPEC, MOD_ID + "-server.toml");
        LOGGER.info("Loading Redline Atlas Worldgen");
    }
}
