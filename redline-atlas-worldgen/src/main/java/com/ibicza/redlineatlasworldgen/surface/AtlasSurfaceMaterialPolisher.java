package com.ibicza.redlineatlasworldgen.surface;

import com.ibicza.redlineatlasworldgen.bathymetry.AtlasOpenWaterGuide;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeContext;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeResolver;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.landcover.LandcoverClass;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;
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

public final class AtlasSurfaceMaterialPolisher {
    private static final ArrayDeque<QueuedSurfaceChunk> QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED_KEYS = new HashSet<>();

    public static void enqueue(ServerLevel level, ChunkPos pos, boolean newChunk) {
        if (!AtlasWorldgenConfig.SURFACE_POLISH_ENABLED.get()) {
            return;
        }
        if (AtlasWorldgenConfig.OVERWORLD_ONLY.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (AtlasWorldgenConfig.SURFACE_POLISH_ONLY_NEW_CHUNKS.get() && !newChunk) {
            return;
        }
        long key = key(level.dimension(), pos);
        synchronized (QUEUE) {
            if (QUEUED_KEYS.add(key)) {
                QUEUE.addLast(new QueuedSurfaceChunk(level.dimension(), pos));
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (!AtlasWorldgenConfig.SURFACE_POLISH_ENABLED.get()) {
            return;
        }
        long started = AtlasWorldgenProfiler.start();
        int chunksBudget = Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_CHUNKS_PER_TICK.get());
        int columnsBudget = Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_COLUMNS_PER_TICK.get());
        while (chunksBudget > 0 && columnsBudget > 0) {
            QueuedSurfaceChunk queued;
            synchronized (QUEUE) {
                queued = QUEUE.pollFirst();
                if (queued == null) {
                    break;
                }
                QUEUED_KEYS.remove(key(queued.dimension(), queued.pos()));
            }
            ServerLevel level = server.getLevel(queued.dimension());
            if (level == null) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(queued.pos().x(), queued.pos().z());
            if (chunk == null) {
                continue;
            }
            columnsBudget -= polishChunk(level, chunk, columnsBudget);
            chunksBudget--;
        }
        AtlasWorldgenProfiler.recordSince("surfacePolish.tick", started);
    }

    public static int queueSize() {
        synchronized (QUEUE) {
            return QUEUE.size();
        }
    }

    private static int polishChunk(ServerLevel level, LevelChunk chunk, int maxColumns) {
        long started = AtlasWorldgenProfiler.start();
        ChunkPos pos = chunk.getPos();
        int minY = level.getMinY();
        int maxY = level.getMaxY() - 1;
        int scan = Math.max(1, AtlasWorldgenConfig.SURFACE_POLISH_TOP_SCAN_BLOCKS.get());
        int visited = 0;
        boolean changed = false;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                if (visited >= maxColumns) {
                    if (changed) {
                        chunk.markUnsaved();
                    }
                    AtlasWorldgenProfiler.recordSince("surfacePolish.chunk", started);
                    return visited;
                }
                visited++;
                int blockX = pos.getMinBlockX() + localX;
                int blockZ = pos.getMinBlockZ() + localZ;
                int topY = Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1);
                int solidY = findTopReplaceableSolid(level, mutable, blockX, blockZ, topY, Math.max(minY, topY - scan));
                if (solidY == Integer.MIN_VALUE) {
                    continue;
                }
                BlockState replacement = replacementFor(level, blockX, solidY, blockZ);
                if (replacement == null) {
                    continue;
                }
                mutable.set(blockX, solidY, blockZ);
                BlockState current = level.getBlockState(mutable);
                if (!current.is(replacement.getBlock())) {
                    changed |= level.setBlock(mutable, replacement, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }
        if (changed) {
            chunk.markUnsaved();
        }
        AtlasWorldgenProfiler.recordSince("surfacePolish.chunk", started);
        return visited;
    }

    private static int findTopReplaceableSolid(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, int topY, int minY) {
        for (int y = topY; y >= minY; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.liquid()) {
                continue;
            }
            if (isSurfaceStoneLike(state)) {
                return y;
            }
            return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    private static BlockState replacementFor(ServerLevel level, int blockX, int blockY, int blockZ) {
        AtlasOpenWaterGuide.OpenWaterSample water = AtlasOpenWaterGuide.sample(blockX, blockZ);
        if (water.kind() == AtlasOpenWaterGuide.OpenWaterKind.OCEAN) {
            if (water.depthMeters() <= AtlasWorldgenConfig.OPEN_WATER_SHALLOW_DEPTH_METERS.get() * 2.0D) {
                return Blocks.SAND.defaultBlockState();
            }
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (water.kind() == AtlasOpenWaterGuide.OpenWaterKind.COAST) {
            if (water.depthMeters() <= AtlasWorldgenConfig.OPEN_WATER_SHALLOW_DEPTH_METERS.get() * 2.0D) {
                return Blocks.SAND.defaultBlockState();
            }
            return Blocks.STONE.defaultBlockState();
        }

        Optional<AtlasBiomeContext> context = AtlasBiomeResolver.context(blockX, blockY, blockZ, level.getSeed());
        if (context.isEmpty()) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        AtlasBiomeContext ctx = context.get();
        if (ctx.temperatureC() <= AtlasWorldgenConfig.BIOME_FREEZING_TEMPERATURE_C.get() && ctx.elevationMeters() > AtlasWorldgenConfig.BIOME_MONTANE_METERS.get()) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (ctx.landcover() == LandcoverClass.SNOW_ICE) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (ctx.landcover() == LandcoverClass.BARE_SPARSE || ctx.slope() >= AtlasWorldgenConfig.BIOME_CLIFF_SLOPE.get()) {
            return null;
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static boolean isSurfaceStoneLike(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.CALCITE);
    }

    private static long key(ResourceKey<Level> dimension, ChunkPos pos) {
        long dimensionHash = dimension.identifier().hashCode();
        return (dimensionHash << 32) ^ pos.pack();
    }

    private record QueuedSurfaceChunk(ResourceKey<Level> dimension, ChunkPos pos) {
    }

    private AtlasSurfaceMaterialPolisher() {
    }
}
