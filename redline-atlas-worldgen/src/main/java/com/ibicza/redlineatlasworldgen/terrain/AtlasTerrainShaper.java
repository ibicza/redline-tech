package com.ibicza.redlineatlasworldgen.terrain;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.heightmap.HeightSample;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public final class AtlasTerrainShaper {
    private static final ArrayDeque<QueuedChunk> QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED_KEYS = new HashSet<>();
    private static boolean runtimeEnabled = true;
    private static AtlasTerrainStats lastStats = AtlasTerrainStats.empty(true);

    public static void enqueue(ServerLevel level, ChunkPos pos, boolean automatic) {
        if (!runtimeEnabled || !AtlasWorldgenConfig.ENABLED.get()) {
            return;
        }
        if (automatic && AtlasWorldgenConfig.OVERWORLD_ONLY.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }
        long key = key(level.dimension(), pos);
        if (QUEUED_KEYS.add(key)) {
            QUEUE.addLast(new QueuedChunk(level.dimension(), pos, automatic));
        }
    }

    public static void tick(MinecraftServer server) {
        if (!runtimeEnabled || !AtlasWorldgenConfig.ENABLED.get()) {
            lastStats = new AtlasTerrainStats(runtimeEnabled, QUEUE.size(), lastStats.shapedChunks(), lastStats.shapedColumns(), lastStats.skippedChunks(), lastStats.missingSampleColumns(), lastStats.lastChunk(), lastStats.lastSource());
            return;
        }

        int chunksBudget = AtlasWorldgenConfig.MAX_CHUNKS_PER_TICK.get();
        int columnsBudget = AtlasWorldgenConfig.MAX_COLUMNS_PER_TICK.get();
        long shapedChunks = 0;
        long shapedColumns = 0;
        long skipped = 0;
        long missing = 0;
        String lastChunk = lastStats.lastChunk();
        String lastSource = lastStats.lastSource();

        while (chunksBudget > 0 && columnsBudget > 0 && !QUEUE.isEmpty()) {
            QueuedChunk queued = QUEUE.removeFirst();
            QUEUED_KEYS.remove(key(queued.dimension(), queued.pos()));

            ServerLevel level = server.getLevel(queued.dimension());
            if (level == null) {
                skipped++;
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(queued.pos().x(), queued.pos().z());
            if (chunk == null) {
                skipped++;
                continue;
            }

            ShapeResult result = shapeChunk(level, chunk, columnsBudget);
            columnsBudget -= result.columnsVisited();
            missing += result.missingSamples();
            if (result.columnsChanged() > 0) {
                shapedChunks++;
                shapedColumns += result.columnsChanged();
                lastChunk = queued.dimension().identifier() + " " + queued.pos().x() + "," + queued.pos().z();
                lastSource = result.lastSource();
            }
            chunksBudget--;
        }

        long totalChunks = lastStats.shapedChunks() + shapedChunks;
        long totalColumns = lastStats.shapedColumns() + shapedColumns;
        long totalSkipped = lastStats.skippedChunks() + skipped;
        long totalMissing = lastStats.missingSampleColumns() + missing;
        lastStats = new AtlasTerrainStats(runtimeEnabled, QUEUE.size(), totalChunks, totalColumns, totalSkipped, totalMissing, lastChunk, lastSource);
    }

    public static ShapeResult shapeChunk(ServerLevel level, LevelChunk chunk, int maxColumns) {
        AtlasHeightmapIndex index = AtlasHeightmapIndex.active();
        ChunkPos pos = chunk.getPos();
        int minY = level.getMinY();
        int maxY = level.getMaxY() - 1;
        int columnsVisited = 0;
        int columnsChanged = 0;
        int missingSamples = 0;
        String lastSource = "none";

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                if (columnsVisited >= maxColumns) {
                    return new ShapeResult(columnsVisited, columnsChanged, missingSamples, lastSource);
                }
                columnsVisited++;

                int blockX = pos.getMinBlockX() + localX;
                int blockZ = pos.getMinBlockZ() + localZ;
                GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
                Optional<HeightSample> sample = index.sample(geo.latitude(), geo.longitude());
                if (sample.isEmpty()) {
                    missingSamples++;
                    continue;
                }

                int targetY = clamp(AtlasCoordinateMapper.metersToWorldY(sample.get().meters()), minY + 1, maxY - 1);
                int currentTop = clamp(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1, minY, maxY);
                int limitedTarget = limitDelta(currentTop, targetY, AtlasWorldgenConfig.MAX_TERRAIN_DELTA_PER_PASS.get(), minY + 1, maxY - 1);

                boolean changed = shapeColumn(level, mutable, blockX, blockZ, currentTop, limitedTarget, minY, maxY);
                if (changed) {
                    columnsChanged++;
                    lastSource = sample.get().sourceId();
                }
            }
        }

        if (columnsChanged > 0) {
            chunk.markUnsaved();
            if (AtlasWorldgenConfig.DEBUG_LOG_CHUNKS.get()) {
                RedlineAtlasWorldgen.LOGGER.info("Atlas shaped chunk {},{}: changedColumns={}, missingSamples={}, lastSource={}",
                        pos.x(), pos.z(), columnsChanged, missingSamples, lastSource);
            }
        }
        return new ShapeResult(columnsVisited, columnsChanged, missingSamples, lastSource);
    }

    private static boolean shapeColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int blockX, int blockZ,
                                       int currentTop, int targetY, int minY, int maxY) {
        boolean changed = false;
        int sea = AtlasWorldgenConfig.SEA_LEVEL_Y.get();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState sand = Blocks.SAND.defaultBlockState();

        if (currentTop > targetY) {
            for (int y = targetY + 1; y <= currentTop && y <= maxY; y++) {
                BlockState state = (AtlasWorldgenConfig.FILL_WATER_BELOW_SEA_LEVEL.get() && y <= sea) ? water : air;
                changed |= set(level, pos, blockX, y, blockZ, state);
            }
        } else if (currentTop < targetY) {
            for (int y = currentTop + 1; y <= targetY && y <= maxY; y++) {
                BlockState state = surfaceStateFor(y, targetY, sea, stone, dirt, grass, sand);
                changed |= set(level, pos, blockX, y, blockZ, state);
            }
        }

        for (int y = Math.max(minY, targetY - 4); y <= targetY && y <= maxY; y++) {
            BlockState state = surfaceStateFor(y, targetY, sea, stone, dirt, grass, sand);
            changed |= set(level, pos, blockX, y, blockZ, state);
        }

        if (AtlasWorldgenConfig.FILL_WATER_BELOW_SEA_LEVEL.get() && targetY < sea) {
            for (int y = targetY + 1; y <= sea && y <= maxY; y++) {
                changed |= set(level, pos, blockX, y, blockZ, water);
            }
        }
        return changed;
    }

    private static BlockState surfaceStateFor(int y, int targetY, int sea, BlockState stone, BlockState dirt, BlockState grass, BlockState sand) {
        if (targetY < sea + 1) {
            return y >= targetY - 2 ? sand : stone;
        }
        if (y == targetY) {
            return grass;
        }
        if (y >= targetY - 3) {
            return dirt;
        }
        return stone;
    }

    private static boolean set(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState state) {
        pos.set(x, y, z);
        if (level.getBlockState(pos) == state) {
            return false;
        }
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
        return true;
    }

    private static int limitDelta(int currentTop, int targetY, int maxDelta, int minY, int maxY) {
        if (targetY > currentTop + maxDelta) {
            return clamp(currentTop + maxDelta, minY, maxY);
        }
        if (targetY < currentTop - maxDelta) {
            return clamp(currentTop - maxDelta, minY, maxY);
        }
        return targetY;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long key(ResourceKey<Level> dimension, ChunkPos pos) {
        long dimensionHash = dimension.identifier().hashCode();
        return (dimensionHash << 32) ^ pos.pack();
    }

    public static boolean toggleRuntimeEnabled() {
        runtimeEnabled = !runtimeEnabled;
        return runtimeEnabled;
    }

    public static void setRuntimeEnabled(boolean enabled) {
        runtimeEnabled = enabled;
    }

    public static boolean runtimeEnabled() {
        return runtimeEnabled;
    }

    public static AtlasTerrainStats lastStats() {
        return lastStats;
    }

    public record ShapeResult(int columnsVisited, int columnsChanged, int missingSamples, String lastSource) {
    }

    private AtlasTerrainShaper() {
    }
}
