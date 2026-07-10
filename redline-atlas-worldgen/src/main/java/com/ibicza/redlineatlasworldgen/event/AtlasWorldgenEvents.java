package com.ibicza.redlineatlasworldgen.event;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.command.AtlasWorldgenCommands;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseGuide;
import com.ibicza.redlineatlasworldgen.terrain.AtlasTerrainShaper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = RedlineAtlasWorldgen.MOD_ID)
public final class AtlasWorldgenEvents {
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            AtlasHeightmapIndex.reload(level.getServer().getServerDirectory());
            AtlasNoiseGuide.clearCache();
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!AtlasWorldgenConfig.AUTO_POST_SHAPE_CHUNKS.get()) {
            return;
        }
        if (AtlasWorldgenConfig.SHAPE_ONLY_NEW_CHUNKS.get() && !event.isNewChunk()) {
            return;
        }
        // Debug-only legacy pass. The real atlas integration is the NoiseChunk mixin,
        // which runs during vanilla noise generation before carvers/surface/features.
        AtlasTerrainShaper.enqueue(level, event.getChunk().getPos(), true);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        AtlasTerrainShaper.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AtlasWorldgenCommands.register(event.getDispatcher());
    }

    private AtlasWorldgenEvents() {
    }
}
