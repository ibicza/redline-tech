package com.ibicza.redlineatlasworldgen.event;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.bathymetry.AtlasOceanBathymetryIndex;
import com.ibicza.redlineatlasworldgen.command.AtlasWorldgenCommands;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.landcover.AtlasLandcoverIndex;
import com.ibicza.redlineatlasworldgen.lake.AtlasLakeGuide;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeHolderLookup;
import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseGuide;
import com.ibicza.redlineatlasworldgen.terrain.AtlasTerrainShaper;
import com.ibicza.redlineatlasworldgen.surface.AtlasSurfaceMaterialPolisher;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;
import com.ibicza.redlineatlasworldgen.profiler.ChunkProfilePlanRunner;
import com.ibicza.redlineatlasworldgen.river.AtlasRiverIndex;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = RedlineAtlasWorldgen.MOD_ID)
public final class AtlasWorldgenEvents {
    private static long serverTickStartedNanos;

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            AtlasHeightmapIndex.reload(level.getServer().getServerDirectory());
            AtlasLandcoverIndex.reload(level.getServer().getServerDirectory());
            AtlasOceanBathymetryIndex.reload(level.getServer().getServerDirectory());
            AtlasLakeGuide.reload(level.getServer().getServerDirectory());
            AtlasRiverIndex.reload(level.getServer().getServerDirectory());
            AtlasNoiseGuide.clearCache();
            AtlasBiomeHolderLookup.clearCache();
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        AtlasWorldgenProfiler.chunkLoaded(level, event.getChunk().getPos(), event.isNewChunk());
        AtlasSurfaceMaterialPolisher.enqueue(level, event.getChunk().getPos(), event.isNewChunk());
        AtlasSurfaceMaterialPolisher.enqueueRiverBoundaryNeighbors(level, event.getChunk().getPos(), event.isNewChunk());

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
    public static void onServerTick(ServerTickEvent.Pre event) {
        serverTickStartedNanos = AtlasWorldgenProfiler.serverTickStarted();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        AtlasTerrainShaper.tick(event.getServer());
        AtlasSurfaceMaterialPolisher.tick(event.getServer());
        AtlasWorldgenProfiler.serverTick(event.getServer(), serverTickStartedNanos);
        ChunkProfilePlanRunner.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ChunkProfilePlanRunner.serverStopping(event.getServer());
    }


    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AtlasWorldgenCommands.register(event.getDispatcher());
    }

    private AtlasWorldgenEvents() {
    }
}
