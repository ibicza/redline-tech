package com.ibicza.redlineatlasworldgen.command;

import com.ibicza.redlineatlasworldgen.bathymetry.AtlasOceanBathymetryIndex;
import com.ibicza.redlineatlasworldgen.bathymetry.AtlasOpenWaterGuide;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeContext;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeHolderLookup;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeResolver;
import com.ibicza.redlineatlasworldgen.landcover.AtlasLandcoverIndex;
import com.ibicza.redlineatlasworldgen.lake.AtlasLakeGuide;
import com.ibicza.redlineatlasworldgen.lake.LakeSample;
import com.ibicza.redlineatlasworldgen.lake.ManualLakeIndex;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;
import com.ibicza.redlineatlasworldgen.surface.AtlasSurfaceMaterialPolisher;
import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseGuide;
import com.ibicza.redlineatlasworldgen.terrain.AtlasTerrainShaper;
import com.ibicza.redlineatlasworldgen.terrain.AtlasTerrainStats;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;

public final class AtlasWorldgenCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("rla")
                        .then(Commands.literal("status").executes(AtlasWorldgenCommands::status))
                        .then(Commands.literal("reload").executes(AtlasWorldgenCommands::reload))
                        .then(Commands.literal("toggle").executes(AtlasWorldgenCommands::toggle))
                        .then(Commands.literal("sample").executes(AtlasWorldgenCommands::sampleHere))
                        .then(Commands.literal("landcover").executes(AtlasWorldgenCommands::landcoverHere))
                        .then(Commands.literal("water_sample").executes(AtlasWorldgenCommands::waterSampleHere))
                        .then(Commands.literal("lake_sample").executes(AtlasWorldgenCommands::lakeSampleHere))
                        .then(Commands.literal("nearest_lake").executes(context -> nearestLake(context, 2048))
                                .then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(16, 32768)).executes(context -> nearestLake(context, IntegerArgumentType.getInteger(context, "radiusBlocks")))))
                        .then(Commands.literal("manual_lakes").executes(context -> listManualLakes(context, 8))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 64)).executes(context -> listManualLakes(context, IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("nearest_ocean").executes(context -> nearestOcean(context, 4096))
                                .then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(16, 32768)).executes(context -> nearestOcean(context, IntegerArgumentType.getInteger(context, "radiusBlocks")))))
                        .then(Commands.literal("profile").executes(context -> profile(context, 16))
                                .then(Commands.literal("reset").executes(AtlasWorldgenCommands::profileReset))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 64)).executes(context -> profile(context, IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("biome_sample").executes(AtlasWorldgenCommands::biomeSampleHere))
                        .then(Commands.literal("landcover_tiles").executes(context -> listLandcoverTiles(context, 8))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 64)).executes(context -> listLandcoverTiles(context, IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("ocean_tiles").executes(context -> listOceanTiles(context, 8))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 64)).executes(context -> listOceanTiles(context, IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("tiles").executes(context -> listTiles(context, 8))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 64)).executes(context -> listTiles(context, IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("shape_here").executes(context -> shapeHere(context, 0))
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 16)).executes(context -> shapeHere(context, IntegerArgumentType.getInteger(context, "radiusChunks")))))
                        .then(Commands.literal("finish_here").executes(context -> finishHere(context, 0))
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 16)).executes(context -> finishHere(context, IntegerArgumentType.getInteger(context, "radiusChunks")))))
        );
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        AtlasTerrainStats stats = AtlasTerrainShaper.lastStats();
        AtlasHeightmapIndex index = AtlasHeightmapIndex.active();
        AtlasOceanBathymetryIndex oceanIndex = AtlasOceanBathymetryIndex.active();
        ManualLakeIndex lakeIndex = ManualLakeIndex.active();
        context.getSource().sendSuccess(() -> Component.literal(
                "RLA runtime=" + stats.runtimeEnabled()
                        + ", config=" + AtlasWorldgenConfig.ENABLED.get()
                        + ", tiles=" + index.tileCount()
                        + ", oceanTiles=" + oceanIndex.tileCount()
                        + ", manualLakes=" + lakeIndex.lakeCount()
                        + ", queue=" + stats.queued()
                        + ", shapedChunks=" + stats.shapedChunks()
                        + ", shapedColumns=" + stats.shapedColumns()
                        + ", skipped=" + stats.skippedChunks()
                        + ", missingSamples=" + stats.missingSampleColumns()
                        + ", lastChunk=" + stats.lastChunk()
                        + ", lastSource=" + stats.lastSource()
        ), false);
        context.getSource().sendSuccess(() -> Component.literal("RLA tileRoot=" + index.root()), false);
        context.getSource().sendSuccess(() -> Component.literal("RLA oceanTileRoot=" + oceanIndex.root()), false);
        context.getSource().sendSuccess(() -> Component.literal("RLA manualLakeRoot=" + lakeIndex.root()), false);
        return index.tileCount() + oceanIndex.tileCount() + lakeIndex.lakeCount();
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        AtlasHeightmapIndex index = AtlasHeightmapIndex.reload(context.getSource().getServer().getServerDirectory());
        AtlasLandcoverIndex landcoverIndex = AtlasLandcoverIndex.reload(context.getSource().getServer().getServerDirectory());
        AtlasOceanBathymetryIndex oceanIndex = AtlasOceanBathymetryIndex.reload(context.getSource().getServer().getServerDirectory());
        ManualLakeIndex lakeIndex = AtlasLakeGuide.reload(context.getSource().getServer().getServerDirectory());
        AtlasNoiseGuide.clearCache();
        AtlasOpenWaterGuide.clearCache();
        AtlasLakeGuide.clearCache();
        AtlasBiomeResolver.clearCache();
        AtlasBiomeHolderLookup.clearCache();
        context.getSource().sendSuccess(() -> Component.literal("RLA reloaded " + index.tileCount() + " height tile(s) from " + index.root()
                + "; " + landcoverIndex.tileCount() + " landcover tile(s) from " + landcoverIndex.root()
                + "; " + oceanIndex.tileCount() + " ocean bathymetry tile(s) from " + oceanIndex.root()
                + "; " + lakeIndex.lakeCount() + " manual lake(s) from " + lakeIndex.root()), true);
        return index.tileCount() + landcoverIndex.tileCount() + oceanIndex.tileCount() + lakeIndex.lakeCount();
    }

    private static int toggle(CommandContext<CommandSourceStack> context) {
        boolean enabled = AtlasTerrainShaper.toggleRuntimeEnabled();
        context.getSource().sendSuccess(() -> Component.literal("RLA debug post-shaper runtime enabled = " + enabled
                + ". Noise-guide is controlled by config noise_guide.enabled."), true);
        return enabled ? 1 : 0;
    }

    private static int sampleHere(CommandContext<CommandSourceStack> context) {
        var pos = context.getSource().getPosition();
        int blockX = (int) Math.floor(pos.x());
        int blockZ = (int) Math.floor(pos.z());
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        var sample = AtlasOpenWaterGuide.compositeHeightSample(blockX, blockZ);
        if (sample.isEmpty()) {
            context.getSource().sendFailure(Component.literal("RLA no composite height/ocean sample at x=" + blockX + ", z=" + blockZ
                    + " -> lat=" + geo.latitude() + ", lon=" + geo.longitude()));
            return 0;
        }
        AtlasOpenWaterGuide.OpenWaterSample water = AtlasOpenWaterGuide.sample(blockX, blockZ);
        int targetY = AtlasCoordinateMapper.metersToWorldY(sample.get().meters());
        AtlasNoiseGuide.GuideColumn guide = AtlasNoiseGuide.preview(blockX, blockZ);
        context.getSource().sendSuccess(() -> Component.literal("RLA sample x=" + blockX + ", z=" + blockZ
                + " -> lat=" + geo.latitude() + ", lon=" + geo.longitude()
                + ", height=" + sample.get().meters() + "m"
                + ", targetY=" + targetY
                + ", noiseGuideY=" + (guide.hasSample() ? guide.atlasY() : "none")
                + ", shift=" + (guide.hasSample() ? guide.verticalShiftBlocks() : 0)
                + ", source=" + sample.get().sourceId()
                + ", res=" + sample.get().nominalResolutionMeters() + "m"), false);
        return targetY;
    }

    private static int listTiles(CommandContext<CommandSourceStack> context, int limit) {
        AtlasHeightmapIndex index = AtlasHeightmapIndex.active();
        context.getSource().sendSuccess(() -> Component.literal("RLA tiles: " + index.tileCount() + ", root=" + index.root()), false);
        for (String line : index.describeTiles(limit)) {
            context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
        }
        return index.tileCount();
    }


    private static int landcoverHere(CommandContext<CommandSourceStack> context) {
        var pos = context.getSource().getPosition();
        int blockX = (int) Math.floor(pos.x());
        int blockZ = (int) Math.floor(pos.z());
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        var sample = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude());
        if (sample.isEmpty()) {
            context.getSource().sendFailure(Component.literal("RLA no landcover sample at x=" + blockX + ", z=" + blockZ
                    + " -> lat=" + geo.latitude() + ", lon=" + geo.longitude()));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("RLA landcover x=" + blockX + ", z=" + blockZ
                + " -> lat=" + geo.latitude() + ", lon=" + geo.longitude()
                + ", class=" + sample.get().landcover().id()
                + ", raw=" + sample.get().rawCode()
                + ", source=" + sample.get().sourceId()
                + ", res=" + sample.get().nominalResolutionMeters() + "m"), false);
        return sample.get().rawCode();
    }

    private static int biomeSampleHere(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        ServerLevel level = source.getLevel();

        var pos = source.getPosition();
        int blockX = (int) Math.floor(pos.x());
        int blockY = (int) Math.floor(pos.y());
        int blockZ = (int) Math.floor(pos.z());
        BlockPos blockPos = new BlockPos(blockX, blockY, blockZ);

        var actualBiomeHolder = level.getBiome(blockPos);
        String actualBiome = actualBiomeHolder.unwrapKey()
                .map(String::valueOf)
                .orElse(actualBiomeHolder.toString());

        // Use level seed here so the debug command matches the biome guide used during new chunk generation.
        var biomeContext = AtlasBiomeResolver.context(blockX, blockY, blockZ, level.getSeed());
        if (biomeContext.isEmpty()) {
            context.getSource().sendFailure(Component.literal("RLA no biome atlas context at x=" + blockX + ", y=" + blockY + ", z=" + blockZ
                    + ", actual=" + actualBiome));
            return 0;
        }

        AtlasBiomeContext ctx = biomeContext.get();
        var selected = AtlasBiomeResolver.resolve(ctx, Biomes.PLAINS);
        context.getSource().sendSuccess(() -> Component.literal("RLA biome_sample x=" + blockX + ", y=" + blockY + ", z=" + blockZ
                + " -> lat=" + ctx.latitude() + ", lon=" + ctx.longitude()
                + ", height=" + ctx.elevationMeters() + "m"
                + ", surfaceY=" + ctx.surfaceY()
                + ", relY=" + ctx.relativeY()
                + ", landcover=" + ctx.landcover().id() + "(" + ctx.landcoverRawCode() + ")"
                + ", lcSource=" + ctx.landcoverSource()
                + ", slope=" + ctx.slope()
                + ", rough=" + ctx.roughness()
                + ", temp=" + ctx.temperatureC() + "C"
                + ", humidity=" + ctx.humidity01()
                + ", water=" + ctx.water().kind()
                + ", distOcean=" + ctx.water().distanceToOceanBlocks()
                + ", depth=" + ctx.water().waterDepthMeters() + "m"
                + ", waterSource=" + ctx.water().sourceId()
                + ", selected=" + selected
                + ", actual=" + actualBiome), false);
        return ctx.surfaceY();
    }

    private static int waterSampleHere(CommandContext<CommandSourceStack> context) {
        var pos = context.getSource().getPosition();
        int blockX = (int) Math.floor(pos.x());
        int blockZ = (int) Math.floor(pos.z());
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        AtlasOpenWaterGuide.OpenWaterSample sample = AtlasOpenWaterGuide.sample(blockX, blockZ);
        var raw = AtlasOceanBathymetryIndex.active().rawSample(geo.latitude(), geo.longitude());
        context.getSource().sendSuccess(() -> Component.literal("RLA water_sample x=" + blockX + ", z=" + blockZ
                + " -> lat=" + geo.latitude() + ", lon=" + geo.longitude()
                + ", kind=" + sample.kind()
                + ", exact=" + sample.exactWater()
                + ", distanceOcean=" + sample.distanceToOceanBlocks()
                + ", bottom=" + sample.bottomMeters() + "m"
                + ", depth=" + sample.depthMeters() + "m"
                + ", surface=" + sample.waterSurfaceMeters() + "m"
                + ", source=" + sample.sourceId()
                + ", raw=" + raw.map(value -> value.bottomMeters() + "m from " + value.sourceId()).orElse("none")), false);
        return sample.hasOpenWaterData() ? 1 : 0;
    }

    private static int nearestOcean(CommandContext<CommandSourceStack> context, int radiusBlocks) {
        var pos = context.getSource().getPosition();
        int originX = (int) Math.floor(pos.x());
        int originZ = (int) Math.floor(pos.z());
        int step = Math.max(8, AtlasWorldgenConfig.OPEN_WATER_COAST_STEP_BLOCKS.get());
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        int bestX = originX;
        int bestZ = originZ;
        AtlasOpenWaterGuide.OpenWaterSample bestSample = AtlasOpenWaterGuide.OpenWaterSample.none();

        for (int dz = -radiusBlocks; dz <= radiusBlocks; dz += step) {
            for (int dx = -radiusBlocks; dx <= radiusBlocks; dx += step) {
                double distanceSq = dx * (double) dx + dz * (double) dz;
                if (distanceSq > radiusBlocks * (double) radiusBlocks || distanceSq >= bestDistanceSq) {
                    continue;
                }
                int x = originX + dx;
                int z = originZ + dz;
                AtlasOpenWaterGuide.OpenWaterSample sample = AtlasOpenWaterGuide.sample(x, z);
                if (sample.kind() == AtlasOpenWaterGuide.OpenWaterKind.OCEAN) {
                    bestDistanceSq = distanceSq;
                    bestX = x;
                    bestZ = z;
                    bestSample = sample;
                }
            }
        }

        if (bestSample.kind() != AtlasOpenWaterGuide.OpenWaterKind.OCEAN) {
            context.getSource().sendFailure(Component.literal("RLA nearest_ocean: no exact open ocean within " + radiusBlocks + " blocks from x=" + originX + ", z=" + originZ));
            return 0;
        }

        GeoPoint geo = AtlasCoordinateMapper.toGeo(bestX, bestZ);
        int finalBestX = bestX;
        int finalBestZ = bestZ;
        AtlasOpenWaterGuide.OpenWaterSample finalBestSample = bestSample;
        double distance = Math.sqrt(bestDistanceSq);
        context.getSource().sendSuccess(() -> Component.literal("RLA nearest_ocean x=" + finalBestX + ", z=" + finalBestZ
                + ", distance=" + distance
                + " -> /tp @p " + finalBestX + " 60 " + finalBestZ
                + ", lat=" + geo.latitude() + ", lon=" + geo.longitude()
                + ", bottom=" + finalBestSample.bottomMeters() + "m"
                + ", depth=" + finalBestSample.depthMeters() + "m"
                + ", source=" + finalBestSample.sourceId()), false);
        return 1;
    }

    private static int lakeSampleHere(CommandContext<CommandSourceStack> context) {
        var pos = context.getSource().getPosition();
        int blockX = (int) Math.floor(pos.x());
        int blockZ = (int) Math.floor(pos.z());
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        LakeSample sample = AtlasLakeGuide.sample(blockX, blockZ);
        context.getSource().sendSuccess(() -> Component.literal("RLA lake_sample x=" + blockX + ", z=" + blockZ
                + " -> lat=" + geo.latitude() + ", lon=" + geo.longitude()
                + ", kind=" + sample.kind()
                + ", exact=" + sample.exactWater()
                + ", distanceLake=" + sample.distanceToLakeBlocks()
                + ", distanceShore=" + sample.distanceToShoreBlocks()
                + ", bottom=" + sample.bottomMeters() + "m"
                + ", depth=" + sample.depthMeters() + "m"
                + ", surface=" + sample.waterSurfaceMeters() + "m"
                + ", lakeId=" + sample.lakeId()
                + ", source=" + sample.sourceId()), false);
        return sample.hasLakeData() ? 1 : 0;
    }

    private static int nearestLake(CommandContext<CommandSourceStack> context, int radiusBlocks) {
        var pos = context.getSource().getPosition();
        int originX = (int) Math.floor(pos.x());
        int originZ = (int) Math.floor(pos.z());
        int step = Math.max(8, AtlasWorldgenConfig.LAKE_WORLDCOVER_WATER_STEP_BLOCKS.get());
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        int bestX = originX;
        int bestZ = originZ;
        LakeSample bestSample = LakeSample.none();

        for (int dz = -radiusBlocks; dz <= radiusBlocks; dz += step) {
            for (int dx = -radiusBlocks; dx <= radiusBlocks; dx += step) {
                double distanceSq = dx * (double) dx + dz * (double) dz;
                if (distanceSq > radiusBlocks * (double) radiusBlocks || distanceSq >= bestDistanceSq) {
                    continue;
                }
                int x = originX + dx;
                int z = originZ + dz;
                LakeSample sample = AtlasLakeGuide.sample(x, z);
                if (!AtlasLakeGuide.isLakeWater(sample.kind())) {
                    continue;
                }
                bestDistanceSq = distanceSq;
                bestX = x;
                bestZ = z;
                bestSample = sample;
            }
        }

        if (!Double.isFinite(bestDistanceSq)) {
            context.getSource().sendFailure(Component.literal("RLA nearest_lake: no inland lake/small waterbody within " + radiusBlocks + " blocks."));
            return 0;
        }

        int finalBestX = bestX;
        int finalBestZ = bestZ;
        LakeSample finalBestSample = bestSample;
        double distance = Math.sqrt(bestDistanceSq);
        GeoPoint geo = AtlasCoordinateMapper.toGeo(finalBestX, finalBestZ);
        int tpY = (int) Math.ceil(AtlasCoordinateMapper.metersToWorldY(finalBestSample.waterSurfaceMeters()) + 20);
        context.getSource().sendSuccess(() -> Component.literal("RLA nearest_lake x=" + finalBestX + ", z=" + finalBestZ
                + ", distance=" + distance
                + " -> /tp @p " + finalBestX + " " + tpY + " " + finalBestZ
                + ", lat=" + geo.latitude() + ", lon=" + geo.longitude()
                + ", surface=" + finalBestSample.waterSurfaceMeters() + "m"
                + ", depth=" + finalBestSample.depthMeters() + "m"
                + ", lakeId=" + finalBestSample.lakeId()
                + ", source=" + finalBestSample.sourceId()), false);
        return 1;
    }

    private static int profile(CommandContext<CommandSourceStack> context, int limit) {
        context.getSource().sendSuccess(() -> Component.literal("RLA profiler, surfacePolishQueue=" + AtlasSurfaceMaterialPolisher.queueSize()
                + ", biomeColumnCache=" + AtlasBiomeResolver.cacheSize()
                + ", waterCellCache=" + AtlasOpenWaterGuide.cacheSize()
                + ", coastalFloodCache=" + AtlasOpenWaterGuide.coastalFloodCacheSize()
                + ", lakeCache=" + AtlasLakeGuide.cacheSize()), false);
        for (String line : AtlasWorldgenProfiler.summaryLines(limit)) {
            context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    private static int profileReset(CommandContext<CommandSourceStack> context) {
        AtlasWorldgenProfiler.reset();
        context.getSource().sendSuccess(() -> Component.literal("RLA profiler reset."), true);
        return 1;
    }

    private static int listManualLakes(CommandContext<CommandSourceStack> context, int limit) {
        ManualLakeIndex index = ManualLakeIndex.active();
        context.getSource().sendSuccess(() -> Component.literal("RLA manual lakes: " + index.lakeCount() + ", root=" + index.root()), false);
        for (String line : index.describeLakes(limit)) {
            context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
        }
        return index.lakeCount();
    }

    private static int listOceanTiles(CommandContext<CommandSourceStack> context, int limit) {
        AtlasOceanBathymetryIndex index = AtlasOceanBathymetryIndex.active();
        context.getSource().sendSuccess(() -> Component.literal("RLA ocean bathymetry tiles: " + index.tileCount() + ", root=" + index.root()), false);
        for (String line : index.describeTiles(limit)) {
            context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
        }
        return index.tileCount();
    }

    private static int listLandcoverTiles(CommandContext<CommandSourceStack> context, int limit) {
        AtlasLandcoverIndex index = AtlasLandcoverIndex.active();
        context.getSource().sendSuccess(() -> Component.literal("RLA landcover tiles: " + index.tileCount() + ", root=" + index.root()), false);
        for (String line : index.describeTiles(limit)) {
            context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
        }
        return index.tileCount();
    }

    private static int shapeHere(CommandContext<CommandSourceStack> context, int radiusChunks) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("/rla shape_here must be executed by a player."));
            return 0;
        }
        ServerLevel level = player.level();
        ChunkPos center = player.chunkPosition();
        int queued = 0;
        for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                AtlasTerrainShaper.enqueue(level, new ChunkPos(center.x() + dx, center.z() + dz), false);
                queued++;
            }
        }
        int finalQueued = queued;
        context.getSource().sendSuccess(() -> Component.literal("RLA queued " + finalQueued + " chunk(s) around " + center.x() + "," + center.z()), true);
        return queued;
    }

    private static int finishHere(CommandContext<CommandSourceStack> context, int radiusChunks) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("/rla finish_here must be executed by a player."));
            return 0;
        }
        ServerLevel level = player.level();
        ChunkPos center = player.chunkPosition();
        int queued = 0;
        for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                AtlasSurfaceMaterialPolisher.enqueueForced(level, new ChunkPos(center.x() + dx, center.z() + dz));
                queued++;
            }
        }
        int finalQueued = queued;
        context.getSource().sendSuccess(() -> Component.literal("RLA queued surface/water finish for " + finalQueued
                + " chunk(s) around " + center.x() + "," + center.z()), true);
        return queued;
    }

    private AtlasWorldgenCommands() {
    }
}
