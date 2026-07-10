package com.ibicza.redlineatlasworldgen.command;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseGuide;
import com.ibicza.redlineatlasworldgen.terrain.AtlasTerrainShaper;
import com.ibicza.redlineatlasworldgen.terrain.AtlasTerrainStats;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public final class AtlasWorldgenCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("rla")
                        .then(Commands.literal("status").executes(AtlasWorldgenCommands::status))
                        .then(Commands.literal("reload").executes(AtlasWorldgenCommands::reload))
                        .then(Commands.literal("toggle").executes(AtlasWorldgenCommands::toggle))
                        .then(Commands.literal("sample").executes(AtlasWorldgenCommands::sampleHere))
                        .then(Commands.literal("tiles").executes(context -> listTiles(context, 8))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 64)).executes(context -> listTiles(context, IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("shape_here").executes(context -> shapeHere(context, 0))
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 16)).executes(context -> shapeHere(context, IntegerArgumentType.getInteger(context, "radiusChunks")))))
        );
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        AtlasTerrainStats stats = AtlasTerrainShaper.lastStats();
        AtlasHeightmapIndex index = AtlasHeightmapIndex.active();
        context.getSource().sendSuccess(() -> Component.literal(
                "RLA runtime=" + stats.runtimeEnabled()
                        + ", config=" + AtlasWorldgenConfig.ENABLED.get()
                        + ", tiles=" + index.tileCount()
                        + ", queue=" + stats.queued()
                        + ", shapedChunks=" + stats.shapedChunks()
                        + ", shapedColumns=" + stats.shapedColumns()
                        + ", skipped=" + stats.skippedChunks()
                        + ", missingSamples=" + stats.missingSampleColumns()
                        + ", lastChunk=" + stats.lastChunk()
                        + ", lastSource=" + stats.lastSource()
        ), false);
        context.getSource().sendSuccess(() -> Component.literal("RLA tileRoot=" + index.root()), false);
        return index.tileCount();
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        AtlasHeightmapIndex index = AtlasHeightmapIndex.reload(context.getSource().getServer().getServerDirectory());
        AtlasNoiseGuide.clearCache();
        context.getSource().sendSuccess(() -> Component.literal("RLA reloaded " + index.tileCount() + " height tile(s) from " + index.root()), true);
        return index.tileCount();
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
        var sample = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
        if (sample.isEmpty()) {
            context.getSource().sendFailure(Component.literal("RLA no height sample at x=" + blockX + ", z=" + blockZ
                    + " -> lat=" + geo.latitude() + ", lon=" + geo.longitude()));
            return 0;
        }
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

    private AtlasWorldgenCommands() {
    }
}
