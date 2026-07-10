package com.ibicza.redlinechunkpriority;

import com.ibicza.redlinechunkpriority.config.ChunkPriorityConfig;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(RedlineChunkPriority.MOD_ID)
public final class RedlineChunkPriority {
    public static final String MOD_ID = "redline_chunk_priority";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RedlineChunkPriority(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, ChunkPriorityConfig.SPEC, MOD_ID + "-server.toml");
        LOGGER.info("Loading Redline Chunk Priority");
    }
}
