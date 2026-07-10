package com.ibicza.redlineatlasworldgen.surface;

import com.ibicza.redlineatlasworldgen.bathymetry.AtlasOpenWaterGuide;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeContext;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeResolver;
import com.ibicza.redlineatlasworldgen.biome.WaterContext;
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
        enqueue(level, pos, newChunk, false);
    }

    public static void enqueueForced(ServerLevel level, ChunkPos pos) {
        enqueue(level, pos, false, true);
    }

    private static void enqueue(ServerLevel level, ChunkPos pos, boolean newChunk, boolean force) {
        if (!AtlasWorldgenConfig.SURFACE_POLISH_ENABLED.get()) {
            return;
        }
        if (AtlasWorldgenConfig.OVERWORLD_ONLY.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (!force && AtlasWorldgenConfig.SURFACE_POLISH_ONLY_NEW_CHUNKS.get() && !newChunk) {
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
        int seaY = AtlasWorldgenConfig.SEA_LEVEL_Y.get();
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
                int heightmapTopY = Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1);
                AtlasOpenWaterGuide.OpenWaterSample water = waterForSurface(blockX, blockZ);

                if (water.kind() == AtlasOpenWaterGuide.OpenWaterKind.OCEAN
                        && AtlasWorldgenConfig.SURFACE_POLISH_FILL_OPEN_OCEAN_WATER.get()) {
                    changed |= finishOceanColumn(level, mutable, blockX, blockZ, heightmapTopY, minY, maxY, seaY, water);
                    continue;
                }

                int solidY = findTopSolid(level, mutable, blockX, blockZ, heightmapTopY, Math.max(minY, heightmapTopY - scan));
                if (solidY == Integer.MIN_VALUE) {
                    continue;
                }

                BlockState replacement = replacementFor(level, blockX, solidY, blockZ, water);
                if (replacement == null) {
                    continue;
                }

                boolean shore = water.kind() == AtlasOpenWaterGuide.OpenWaterKind.COAST;
                changed |= applySurfaceCap(level, mutable, blockX, solidY, blockZ, minY, replacement, shore);
            }
        }
        if (changed) {
            chunk.markUnsaved();
        }
        AtlasWorldgenProfiler.recordSince("surfacePolish.chunk", started);
        return visited;
    }

    private static AtlasOpenWaterGuide.OpenWaterSample waterForSurface(int blockX, int blockZ) {
        AtlasOpenWaterGuide.OpenWaterSample cached = AtlasOpenWaterGuide.sampleForBiome(blockX, blockZ);
        if (!AtlasWorldgenConfig.SURFACE_POLISH_EXACT_COAST_SAMPLES.get()) {
            return cached;
        }
        if (cached.kind() == AtlasOpenWaterGuide.OpenWaterKind.OCEAN) {
            return cached;
        }
        return AtlasOpenWaterGuide.sample(blockX, blockZ);
    }

    private static boolean finishOceanColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                             int heightmapTopY, int minY, int maxY, int seaY,
                                             AtlasOpenWaterGuide.OpenWaterSample water) {
        long started = AtlasWorldgenProfiler.start();
        try {
            boolean changed = false;
            int carveAbove = Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_OCEAN_CARVE_ABOVE_SEA_BLOCKS.get());
            int scanFrom = Math.min(maxY, Math.max(heightmapTopY, seaY + carveAbove));
            int solidY = findTopSolid(level, pos, x, z, scanFrom, minY);
            if (solidY == Integer.MIN_VALUE) {
                return false;
            }

            if (solidY > seaY) {
                if (solidY > seaY + carveAbove) {
                    // Probably a real island/land override that the coarse GEBCO cell cannot represent.
                    // Do not force-fill it as ocean.
                    BlockState replacement = replacementFor(level, x, solidY, z, AtlasOpenWaterGuide.OpenWaterSample.none());
                    return replacement != null && applySurfaceCap(level, pos, x, solidY, z, minY, replacement, false);
                }
                for (int y = solidY; y > seaY; y--) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && !state.liquid()) {
                        changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
                solidY = findTopSolid(level, pos, x, z, seaY, minY);
                if (solidY == Integer.MIN_VALUE) {
                    return changed;
                }
            }

            BlockState bottomMaterial = oceanBottomMaterial(water);
            changed |= applySurfaceCap(level, pos, x, solidY, z, minY, bottomMaterial, true);

            int maxFill = Math.max(1, AtlasWorldgenConfig.SURFACE_POLISH_OCEAN_MAX_FILL_BLOCKS.get());
            int fillFrom = solidY + 1;
            int fillTo = Math.min(seaY, solidY + maxFill);
            for (int y = fillFrom; y <= fillTo; y++) {
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || state.liquid() || isReplaceableWaterColumnBlock(state)) {
                    if (!state.is(Blocks.WATER)) {
                        changed |= level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
            }
            if (fillTo >= fillFrom) {
                AtlasWorldgenProfiler.record("surfacePolish.waterColumns", 0L);
            }
            return changed;
        } finally {
            AtlasWorldgenProfiler.recordSince("surfacePolish.oceanColumn", started);
        }
    }

    private static int findTopSolid(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, int topY, int minY) {
        for (int y = topY; y >= minY; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.liquid()) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean applySurfaceCap(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int topY, int z, int minY,
                                           BlockState replacement, boolean replaceSoftSurfaceToo) {
        int depth = Math.max(1, replaceSoftSurfaceToo ? AtlasWorldgenConfig.SURFACE_POLISH_SHORE_SAND_DEPTH_BLOCKS.get() : 1);
        boolean changed = false;
        for (int dy = 0; dy < depth; dy++) {
            int y = topY - dy;
            if (y < minY) {
                break;
            }
            pos.set(x, y, z);
            BlockState current = level.getBlockState(pos);
            if (current.isAir() || current.liquid()) {
                continue;
            }
            if (!isReplaceableSurfaceMaterial(current, replaceSoftSurfaceToo)) {
                break;
            }
            if (!current.is(replacement.getBlock())) {
                changed |= level.setBlock(pos, replacement, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return changed;
    }

    private static BlockState replacementFor(ServerLevel level, int blockX, int blockY, int blockZ,
                                             AtlasOpenWaterGuide.OpenWaterSample water) {
        if (AtlasWorldgenConfig.SURFACE_POLISH_BUILD_OPEN_OCEAN_SHORES.get()
                && water.kind() == AtlasOpenWaterGuide.OpenWaterKind.COAST) {
            Optional<AtlasBiomeContext> context = AtlasBiomeResolver.context(blockX, blockY, blockZ, level.getSeed());
            double slope = context.map(AtlasBiomeContext::slope).orElse(0.0D);
            if (slope >= AtlasWorldgenConfig.OPEN_WATER_STONY_SHORE_SLOPE.get()) {
                return Blocks.STONE.defaultBlockState();
            }
            return Blocks.SAND.defaultBlockState();
        }

        Optional<AtlasBiomeContext> context = AtlasBiomeResolver.context(blockX, blockY, blockZ, level.getSeed());
        if (context.isEmpty()) {
            return isNearSeaLevel(blockY) ? Blocks.GRASS_BLOCK.defaultBlockState() : null;
        }
        AtlasBiomeContext ctx = context.get();
        if (ctx.water().kind() == WaterContext.WaterKind.OPEN_OCEAN) {
            return oceanBottomMaterial(water);
        }
        if (ctx.temperatureC() <= AtlasWorldgenConfig.BIOME_FREEZING_TEMPERATURE_C.get()
                && ctx.elevationMeters() > AtlasWorldgenConfig.BIOME_MONTANE_METERS.get()) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (ctx.landcover() == LandcoverClass.SNOW_ICE) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (ctx.landcover() == LandcoverClass.BARE_SPARSE || ctx.slope() >= AtlasWorldgenConfig.BIOME_CLIFF_SLOPE.get()) {
            return null;
        }
        if (isNearSeaLevel(blockY) && ctx.slope() <= AtlasWorldgenConfig.OPEN_WATER_BEACH_MAX_SLOPE.get()
                && water.distanceToOceanBlocks() <= AtlasWorldgenConfig.OPEN_WATER_COAST_RADIUS_BLOCKS.get()) {
            return Blocks.SAND.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static BlockState oceanBottomMaterial(AtlasOpenWaterGuide.OpenWaterSample water) {
        if (water.depthMeters() <= AtlasWorldgenConfig.OPEN_WATER_SHALLOW_DEPTH_METERS.get() * 2.0D) {
            return Blocks.SAND.defaultBlockState();
        }
        return Blocks.GRAVEL.defaultBlockState();
    }

    private static boolean isNearSeaLevel(int blockY) {
        return Math.abs(blockY - AtlasWorldgenConfig.SEA_LEVEL_Y.get()) <= 12;
    }

    private static boolean isReplaceableSurfaceMaterial(BlockState state, boolean replaceSoftSurfaceToo) {
        if (isSurfaceStoneLike(state)) {
            return true;
        }
        if (!replaceSoftSurfaceToo) {
            return false;
        }
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SNOW_BLOCK);
    }

    private static boolean isReplaceableWaterColumnBlock(BlockState state) {
        return state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS);
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
