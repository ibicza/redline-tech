package com.ibicza.redlineatlasworldgen.surface;

import com.ibicza.redlineatlasworldgen.bathymetry.AtlasOpenWaterGuide;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeContext;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeResolver;
import com.ibicza.redlineatlasworldgen.biome.WaterContext;
import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.landcover.LandcoverClass;
import com.ibicza.redlineatlasworldgen.lake.AtlasLakeGuide;
import com.ibicza.redlineatlasworldgen.lake.LakeKind;
import com.ibicza.redlineatlasworldgen.lake.LakeSample;
import com.ibicza.redlineatlasworldgen.profiler.AtlasWorldgenProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
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
                int heightmapTopY = Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ));
                AtlasOpenWaterGuide.OpenWaterSample water = waterForSurface(blockX, blockZ);
                LakeSample lake = lakeForSurface(blockX, blockZ);

                if (AtlasWorldgenConfig.SURFACE_POLISH_FILL_LAKE_WATER.get()
                        && AtlasLakeGuide.isLakeWater(lake.kind())) {
                    changed |= finishLakeColumn(level, mutable, blockX, blockZ, heightmapTopY, minY, maxY, lake);
                    continue;
                }

                if (AtlasWorldgenConfig.SURFACE_POLISH_FILL_LAKE_WATER.get()
                        && lake.kind() == LakeKind.LAKE_SHORE) {
                    changed |= finishLakeShoreColumn(level, mutable, blockX, blockZ, heightmapTopY, minY, maxY, lake);
                    continue;
                }

                if (AtlasWorldgenConfig.SURFACE_POLISH_FILL_OPEN_OCEAN_WATER.get()
                        && isFillableOceanKind(water.kind())) {
                    changed |= finishOceanColumn(level, mutable, blockX, blockZ, heightmapTopY, minY, maxY, seaY, water);
                    continue;
                }

                if (AtlasWorldgenConfig.SURFACE_POLISH_FILL_OPEN_OCEAN_WATER.get()
                        && AtlasWorldgenConfig.SURFACE_POLISH_FILL_COAST_DEPRESSION_WATER.get()
                        && water.kind() == AtlasOpenWaterGuide.OpenWaterKind.COAST
                        && water.distanceToOceanBlocks() <= AtlasWorldgenConfig.SURFACE_POLISH_COAST_WATER_MAX_DISTANCE_BLOCKS.get()) {
                    changed |= finishCoastDepressionColumn(level, mutable, blockX, blockZ, heightmapTopY, minY, seaY, water);
                }

                int terrainY = findTopTerrain(level, mutable, blockX, blockZ, heightmapTopY, Math.max(minY, heightmapTopY - scan));
                if (terrainY == Integer.MIN_VALUE) {
                    continue;
                }

                BlockState replacement = replacementFor(level, blockX, terrainY, blockZ, water, lake);
                if (replacement == null) {
                    continue;
                }

                boolean replaceSoft = shouldReplaceSoftSurface(replacement);
                changed |= applySurfaceCap(level, mutable, blockX, terrainY, blockZ, minY, replacement, replaceSoft);
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
        if (isFillableOceanKind(cached.kind())) {
            return cached;
        }
        return AtlasOpenWaterGuide.sample(blockX, blockZ);
    }

    private static LakeSample lakeForSurface(int blockX, int blockZ) {
        LakeSample cached = AtlasLakeGuide.sampleForBiome(blockX, blockZ);
        if (!AtlasWorldgenConfig.SURFACE_POLISH_EXACT_COAST_SAMPLES.get()) {
            return cached;
        }
        // Surface/water finish runs once per column, so spend the exact sample here.
        // The cached biome cell is intentionally coarse and may miss/overexpand 10m WorldCover shapes.
        LakeSample exact = AtlasLakeGuide.sample(blockX, blockZ);
        return exact.hasLakeData() ? exact : cached;
    }

    private static boolean finishLakeColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                            int heightmapTopY, int minY, int maxY, LakeSample lake) {
        long started = AtlasWorldgenProfiler.start();
        try {
            int terrainY = findTopTerrain(level, pos, x, z, Math.min(maxY, heightmapTopY), minY);
            if (terrainY == Integer.MIN_VALUE) {
                return false;
            }

            int waterY = AtlasCoordinateMapper.metersToWorldY(lake.waterSurfaceMeters());
            int bottomY = lakeBottomY(waterY, lake, minY);
            boolean changed = false;

            // M30.6: exact water-mask columns are water. The lake level comes from the DEM basin fit,
            // while the horizontal shape comes from WorldCover/manual water. Do not reject columns one
            // by one because that tears the mask into strips. Carve the generated terrain down and fill.
            int clearTopY = Math.min(maxY, Math.max(heightmapTopY, terrainY));
            for (int y = waterY + 1; y <= clearTopY; y++) {
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && (state.liquid() || isTerrainSurfaceCandidate(state) || isSoftSurfaceMaterial(state) || isIgnoredSurfaceFeature(state))) {
                    changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }

            changed |= carveAndFillLakeBowl(level, pos, x, z, bottomY, waterY, lake);
            return changed;
        } finally {
            AtlasWorldgenProfiler.recordSince("surfacePolish.lakeColumn", started);
        }
    }


    private static boolean finishLakeShoreColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                                 int heightmapTopY, int minY, int maxY, LakeSample lake) {
        long started = AtlasWorldgenProfiler.start();
        try {
            int terrainY = findTopTerrain(level, pos, x, z, Math.min(maxY, heightmapTopY), minY);
            if (terrainY == Integer.MIN_VALUE) {
                return false;
            }

            int waterY = AtlasCoordinateMapper.metersToWorldY(lake.waterSurfaceMeters());
            int targetY = waterY + Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_SHORE_TARGET_HEIGHT_BLOCKS.get());
            int smoothCarve = Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_SHORE_SMOOTH_CARVE_BLOCKS.get());
            boolean changed = false;

            // Shore columns are land, not water. Always clear old overfilled lake fluid/waterfall walls.
            changed |= clearLiquidsInColumn(level, pos, x, z, Math.max(minY, Math.min(terrainY + 1, targetY + 1)), Math.min(maxY, heightmapTopY));

            // M30.6 containment: if generated terrain around the water mask is too low, build the missing
            // bank up to the fitted water level. This is the piece that prevents floating lake plates.
            if (terrainY < targetY) {
                boolean raised = raiseLakeContainmentBank(level, pos, x, z, terrainY, targetY, lake);
                changed |= raised;
                if (raised) {
                    terrainY = targetY;
                }
            } else if (terrainY > targetY && terrainY <= targetY + smoothCarve) {
                for (int y = targetY + 1; y <= terrainY; y++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && (state.liquid() || isTerrainSurfaceCandidate(state) || isSoftSurfaceMaterial(state) || isIgnoredSurfaceFeature(state))) {
                        changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
                terrainY = targetY;
            }

            BlockState replacement = lakeShoreMaterial(level, x, terrainY, z, lake);
            if (replacement != null) {
                changed |= applySurfaceCap(level, pos, x, terrainY, z, minY, replacement, shouldReplaceSoftSurface(replacement));
            }
            return changed;
        } finally {
            AtlasWorldgenProfiler.recordSince("surfacePolish.lakeShoreColumn", started);
        }
    }

    private static boolean raiseLakeContainmentBank(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                                    int terrainY, int targetY, LakeSample lake) {
        if (!AtlasWorldgenConfig.SURFACE_POLISH_LAKE_BUILD_CONTAINMENT_BANKS.get()) {
            return false;
        }
        int raise = targetY - terrainY;
        if (raise <= 0 || raise > Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_BANK_MAX_RAISE_BLOCKS.get())) {
            return false;
        }
        BlockState topMaterial = lakeShoreMaterial(level, x, targetY, z, lake);
        if (topMaterial == null) {
            topMaterial = Blocks.SAND.defaultBlockState();
        }
        boolean changed = false;
        for (int y = terrainY + 1; y <= targetY; y++) {
            pos.set(x, y, z);
            BlockState current = level.getBlockState(pos);
            if (current.isAir() || current.liquid() || isTerrainSurfaceCandidate(current) || isSoftSurfaceMaterial(current) || isIgnoredSurfaceFeature(current)) {
                BlockState material = layerReplacement(topMaterial, targetY - y);
                if (!current.is(material.getBlock())) {
                    changed |= level.setBlock(pos, material, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }
        return changed;
    }

    private static boolean hasUnsafeLakeLeak(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                               int waterY, int minY, int maxY) {
        int radius = Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_LEAK_GUARD_RADIUS_BLOCKS.get());
        if (radius <= 0) {
            return false;
        }
        int step = Math.max(1, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_LEAK_GUARD_STEP_BLOCKS.get());
        int maxDropoff = Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_LEAK_MAX_DROPOFF_BLOCKS.get());
        int minSupportingTerrainY = waterY - maxDropoff;

        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx * (double) dx + dz * (double) dz > radius * (double) radius) {
                    continue;
                }
                int sx = x + dx;
                int sz = z + dz;
                LakeSample nearbyLake = lakeForSurface(sx, sz);
                if (AtlasLakeGuide.isLakeWater(nearbyLake.kind())) {
                    continue;
                }

                int topY = Math.min(maxY, level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz));
                int terrainY = findTopTerrain(level, pos, sx, sz, topY, minY);
                if (terrainY != Integer.MIN_VALUE && terrainY < minSupportingTerrainY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int lakeBottomY(int waterY, LakeSample lake, int minY) {
        double verticalScale = Math.max(0.001D, AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get());
        int depthBlocksFromSample = Math.max(1, (int) Math.ceil(Math.max(0.0D, lake.depthMeters()) / verticalScale));
        int minDepthBlocks = Math.max(1, AtlasWorldgenConfig.LAKE_SYNTHETIC_MIN_DEPTH_BLOCKS.get());
        int maxDepthBlocks = Math.max(minDepthBlocks, AtlasWorldgenConfig.LAKE_SYNTHETIC_MAX_DEPTH_BLOCKS.get());
        int depthBlocks = Math.max(minDepthBlocks, Math.min(maxDepthBlocks, depthBlocksFromSample));
        int maxFill = Math.max(1, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_MAX_FILL_BLOCKS.get());
        depthBlocks = Math.min(depthBlocks, maxFill);
        return Math.max(minY, waterY - depthBlocks);
    }

    private static boolean carveAndFillLakeBowl(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                                int bottomY, int waterY, LakeSample lake) {
        boolean changed = false;
        for (int y = bottomY; y <= waterY; y++) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (y == bottomY) {
                BlockState bottomMaterial = lakeBottomMaterial(lake, waterY - y);
                if (isReplaceableTerrainMaterial(state, true) || state.isAir() || state.liquid()) {
                    if (!state.is(bottomMaterial.getBlock())) {
                        changed |= level.setBlock(pos, bottomMaterial, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
            } else if (state.isAir() || state.liquid() || isReplaceableWaterColumnBlock(state) || isTerrainSurfaceCandidate(state) || isSoftSurfaceMaterial(state)) {
                if (!state.is(Blocks.WATER)) {
                    changed |= level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }
        return changed;
    }


    private static boolean clearLiquidsInColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, int fromY, int toY) {
        if (toY < fromY) {
            return false;
        }
        boolean changed = false;
        for (int y = fromY; y <= toY; y++) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.liquid()) {
                changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return changed;
    }


    private static int lakeCarveAboveWaterBlocks(LakeSample lake) {
        int shoreCarve = Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_CARVE_ABOVE_WATER_BLOCKS.get());
        if (AtlasLakeGuide.isLakeWater(lake.kind()) && lake.exactWater()) {
            return Math.max(shoreCarve, Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_LAKE_WATER_MASK_CARVE_ABOVE_WATER_BLOCKS.get()));
        }
        return shoreCarve;
    }

    private static boolean finishOceanColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                             int heightmapTopY, int minY, int maxY, int seaY,
                                             AtlasOpenWaterGuide.OpenWaterSample water) {
        long started = AtlasWorldgenProfiler.start();
        try {
            int scanFrom = Math.min(maxY, heightmapTopY);
            int terrainY = findTopTerrain(level, pos, x, z, scanFrom, minY);
            if (terrainY == Integer.MIN_VALUE) {
                return false;
            }

            if (water.kind() == AtlasOpenWaterGuide.OpenWaterKind.OCEAN_FLOOD) {
                return finishCoastalFloodColumn(level, pos, x, z, terrainY, minY, seaY, water);
            }

            // Exact GEBCO ocean can still be wrong at coarse coast edges. Do not carve large land masses here.
            // If terrain is above sea level, treat it as a small island/shore override and only repair surface cap.
            if (terrainY >= seaY) {
                BlockState replacement = replacementFor(level, x, terrainY, z, water, LakeSample.none());
                return replacement != null && applySurfaceCap(level, pos, x, terrainY, z, minY, replacement, shouldReplaceSoftSurface(replacement));
            }

            BlockState bottomMaterial = oceanBottomMaterial(water);
            boolean changed = applySurfaceCap(level, pos, x, terrainY, z, minY, bottomMaterial, true);
            changed |= fillWaterAbove(level, pos, x, z, terrainY, seaY);
            return changed;
        } finally {
            AtlasWorldgenProfiler.recordSince("surfacePolish.oceanColumn", started);
        }
    }

    private static boolean finishCoastalFloodColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                                    int terrainY, int minY, int seaY,
                                                    AtlasOpenWaterGuide.OpenWaterSample water) {
        long started = AtlasWorldgenProfiler.start();
        try {
            int carveAbove = Math.max(0, AtlasWorldgenConfig.SURFACE_POLISH_COASTAL_FLOOD_CARVE_ABOVE_SEA_BLOCKS.get());
            if (terrainY > seaY + carveAbove) {
                // Connected low-water mask says this is likely coarse-coast ocean, but actual vanilla terrain is too high.
                // Keep it as shore/land and only repair the surface material.
                BlockState replacement = replacementFor(level, x, terrainY, z, water, LakeSample.none());
                return replacement != null && applySurfaceCap(level, pos, x, terrainY, z, minY, replacement, shouldReplaceSoftSurface(replacement));
            }

            int depthBlocks = Math.max(1, (int) Math.ceil(water.depthMeters() / Math.max(0.001D, AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get())));
            int bottomY = Math.max(minY, seaY - depthBlocks);
            BlockState bottomMaterial = oceanBottomMaterial(water);
            boolean changed = false;

            // Carve only the tiny lip above sea level; never flatten large terrain.
            for (int y = seaY + 1; y <= terrainY; y++) {
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && (state.liquid() || isTerrainSurfaceCandidate(state) || isSoftSurfaceMaterial(state) || isIgnoredSurfaceFeature(state))) {
                    changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }

            for (int y = bottomY; y <= seaY; y++) {
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (y == bottomY) {
                    if (isReplaceableTerrainMaterial(state, true) || state.isAir() || state.liquid()) {
                        if (!state.is(bottomMaterial.getBlock())) {
                            changed |= level.setBlock(pos, bottomMaterial, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                        }
                    }
                } else if (state.isAir() || state.liquid() || isReplaceableWaterColumnBlock(state) || isTerrainSurfaceCandidate(state) || isSoftSurfaceMaterial(state)) {
                    if (!state.is(Blocks.WATER)) {
                        changed |= level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
            }
            return changed;
        } finally {
            AtlasWorldgenProfiler.recordSince("surfacePolish.coastalFloodColumn", started);
        }
    }

    private static boolean finishCoastDepressionColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                                       int heightmapTopY, int minY, int seaY,
                                                       AtlasOpenWaterGuide.OpenWaterSample water) {
        long started = AtlasWorldgenProfiler.start();
        try {
            int terrainY = findTopTerrain(level, pos, x, z, heightmapTopY, minY);
            if (terrainY == Integer.MIN_VALUE || terrainY >= seaY) {
                return false;
            }
            BlockState bottomMaterial = oceanBottomMaterial(water);
            boolean changed = applySurfaceCap(level, pos, x, terrainY, z, minY, bottomMaterial, true);
            changed |= fillWaterAbove(level, pos, x, z, terrainY, seaY);
            return changed;
        } finally {
            AtlasWorldgenProfiler.recordSince("surfacePolish.coastWaterColumn", started);
        }
    }

    private static boolean fillWaterAbove(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, int terrainY, int seaY) {
        boolean changed = false;
        int maxFill = Math.max(1, AtlasWorldgenConfig.SURFACE_POLISH_OCEAN_MAX_FILL_BLOCKS.get());
        int fillFrom = terrainY + 1;
        int fillTo = Math.min(seaY, terrainY + maxFill);
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
    }

    private static int findTopTerrain(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, int topY, int minY) {
        for (int y = topY; y >= minY; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.liquid() || isIgnoredSurfaceFeature(state)) {
                continue;
            }
            if (isTerrainSurfaceCandidate(state)) {
                return y;
            }
            // Unknown solid feature, not terrain. Keep scanning so trees/structures do not block repair.
        }
        return Integer.MIN_VALUE;
    }

    private static boolean applySurfaceCap(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int topY, int z, int minY,
                                           BlockState replacement, boolean replaceSoftSurfaceToo) {
        int depth = Math.max(1, replaceSoftSurfaceToo
                ? AtlasWorldgenConfig.SURFACE_POLISH_SHORE_SAND_DEPTH_BLOCKS.get()
                : AtlasWorldgenConfig.SURFACE_POLISH_TERRAIN_CAP_DEPTH_BLOCKS.get());
        boolean changed = false;
        for (int dy = 0; dy < depth; dy++) {
            int y = topY - dy;
            if (y < minY) {
                break;
            }
            pos.set(x, y, z);
            BlockState current = level.getBlockState(pos);
            if (current.isAir() || current.liquid() || isIgnoredSurfaceFeature(current)) {
                continue;
            }
            if (!isReplaceableTerrainMaterial(current, replaceSoftSurfaceToo)) {
                break;
            }
            BlockState layerReplacement = layerReplacement(replacement, dy);
            if (!current.is(layerReplacement.getBlock())) {
                changed |= level.setBlock(pos, layerReplacement, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return changed;
    }

    private static BlockState layerReplacement(BlockState topReplacement, int depth) {
        if (depth <= 0) {
            return topReplacement;
        }
        if (topReplacement.is(Blocks.GRASS_BLOCK)) {
            return Blocks.DIRT.defaultBlockState();
        }
        if (topReplacement.is(Blocks.SNOW_BLOCK)) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        return topReplacement;
    }

    private static BlockState replacementFor(ServerLevel level, int blockX, int blockY, int blockZ,
                                             AtlasOpenWaterGuide.OpenWaterSample water, LakeSample lake) {
        ResourceKey<Biome> actualBiome = actualBiomeAt(level, blockX, blockY, blockZ).orElse(null);
        boolean actualOcean = isAnyOceanBiome(actualBiome);

        if (lake.kind() == LakeKind.LAKE_SHORE) {
            BlockState shore = lakeShoreMaterial(level, blockX, blockY, blockZ, lake);
            if (shore != null) {
                return shore;
            }
        }
        if (AtlasLakeGuide.isLakeWater(lake.kind()) && blockY <= AtlasCoordinateMapper.metersToWorldY(lake.waterSurfaceMeters())) {
            return lakeBottomMaterial(lake, Math.max(1, AtlasCoordinateMapper.metersToWorldY(lake.waterSurfaceMeters()) - blockY));
        }

        if (isFillableOceanKind(water.kind()) && blockY < AtlasWorldgenConfig.SEA_LEVEL_Y.get()) {
            return oceanBottomMaterial(water);
        }
        if (actualOcean && blockY < AtlasWorldgenConfig.SEA_LEVEL_Y.get()) {
            return oceanBottomMaterial(water);
        }

        BlockState shoreMaterial = shoreLandMaterial(level, blockX, blockY, blockZ, water);
        if (shoreMaterial != null) {
            return shoreMaterial;
        }

        if (isSandyBiome(actualBiome)) {
            return Blocks.SAND.defaultBlockState();
        }
        if (isSnowBiome(actualBiome)) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (isRockyBiome(actualBiome)) {
            return null;
        }

        Optional<AtlasBiomeContext> context = AtlasBiomeResolver.context(blockX, blockY, blockZ, level.getSeed());
        if (context.isEmpty()) {
            return isNearSeaLevel(blockY) ? Blocks.GRASS_BLOCK.defaultBlockState() : null;
        }

        AtlasBiomeContext ctx = context.get();
        if ((ctx.water().kind() == WaterContext.WaterKind.OPEN_OCEAN || ctx.water().kind() == WaterContext.WaterKind.OPEN_OCEAN_FLOOD) && blockY < AtlasWorldgenConfig.SEA_LEVEL_Y.get()) {
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
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static Optional<ResourceKey<Biome>> actualBiomeAt(ServerLevel level, int blockX, int blockY, int blockZ) {
        return level.getBiome(new BlockPos(blockX, blockY, blockZ)).unwrapKey();
    }

    private static boolean isAnyOceanBiome(ResourceKey<Biome> key) {
        return key == Biomes.OCEAN
                || key == Biomes.DEEP_OCEAN
                || key == Biomes.COLD_OCEAN
                || key == Biomes.DEEP_COLD_OCEAN
                || key == Biomes.FROZEN_OCEAN
                || key == Biomes.DEEP_FROZEN_OCEAN
                || key == Biomes.LUKEWARM_OCEAN
                || key == Biomes.DEEP_LUKEWARM_OCEAN
                || key == Biomes.WARM_OCEAN;
    }

    private static boolean isSandyBiome(ResourceKey<Biome> key) {
        return key == Biomes.BEACH
                || key == Biomes.SNOWY_BEACH
                || key == Biomes.DESERT
                || key == Biomes.BADLANDS
                || key == Biomes.ERODED_BADLANDS
                || key == Biomes.WOODED_BADLANDS;
    }

    private static boolean isSnowBiome(ResourceKey<Biome> key) {
        return key == Biomes.SNOWY_PLAINS
                || key == Biomes.ICE_SPIKES
                || key == Biomes.SNOWY_TAIGA
                || key == Biomes.SNOWY_SLOPES
                || key == Biomes.FROZEN_PEAKS
                || key == Biomes.FROZEN_RIVER;
    }

    private static boolean isRockyBiome(ResourceKey<Biome> key) {
        return key == Biomes.STONY_SHORE
                || key == Biomes.STONY_PEAKS
                || key == Biomes.JAGGED_PEAKS;
    }

    private static boolean shouldReplaceSoftSurface(BlockState replacement) {
        return replacement.is(Blocks.SAND)
                || replacement.is(Blocks.GRAVEL)
                || replacement.is(Blocks.SNOW_BLOCK);
    }

    private static BlockState shoreLandMaterial(ServerLevel level, int blockX, int blockY, int blockZ,
                                                 AtlasOpenWaterGuide.OpenWaterSample water) {
        if (!AtlasWorldgenConfig.SURFACE_POLISH_BUILD_OPEN_OCEAN_SHORES.get()) {
            return null;
        }
        if (water.kind() != AtlasOpenWaterGuide.OpenWaterKind.COAST
                && water.kind() != AtlasOpenWaterGuide.OpenWaterKind.OCEAN
                && water.kind() != AtlasOpenWaterGuide.OpenWaterKind.OCEAN_FLOOD) {
            return null;
        }

        double distance = water.distanceToOceanBlocks();
        if (!Double.isFinite(distance)) {
            distance = isFillableOceanKind(water.kind()) ? 0.0D : Double.POSITIVE_INFINITY;
        }
        if (distance > AtlasWorldgenConfig.SURFACE_POLISH_BEACH_DISTANCE_BLOCKS.get()) {
            return null;
        }

        int seaY = AtlasWorldgenConfig.SEA_LEVEL_Y.get();
        if (blockY > seaY + AtlasWorldgenConfig.SURFACE_POLISH_BEACH_MAX_HEIGHT_ABOVE_SEA_BLOCKS.get()) {
            return null;
        }

        Optional<AtlasBiomeContext> context = AtlasBiomeResolver.context(blockX, blockY, blockZ, level.getSeed());
        if (context.isPresent()) {
            AtlasBiomeContext ctx = context.get();
            if (ctx.slope() > AtlasWorldgenConfig.SURFACE_POLISH_BEACH_MAX_SLOPE.get()) {
                return null;
            }
            if (ctx.slope() >= AtlasWorldgenConfig.OPEN_WATER_STONY_SHORE_SLOPE.get()
                    && blockY > seaY + 2) {
                return null;
            }
        }

        return Blocks.SAND.defaultBlockState();
    }

    private static BlockState lakeShoreMaterial(ServerLevel level, int blockX, int blockY, int blockZ, LakeSample lake) {
        if (!AtlasWorldgenConfig.SURFACE_POLISH_BUILD_OPEN_OCEAN_SHORES.get()) {
            return null;
        }
        if (lake.kind() != LakeKind.LAKE_SHORE && !AtlasLakeGuide.isLakeWater(lake.kind())) {
            return null;
        }
        int waterY = Double.isFinite(lake.waterSurfaceMeters()) ? AtlasCoordinateMapper.metersToWorldY(lake.waterSurfaceMeters()) : AtlasWorldgenConfig.SEA_LEVEL_Y.get();
        if (blockY > waterY + AtlasWorldgenConfig.SURFACE_POLISH_BEACH_MAX_HEIGHT_ABOVE_SEA_BLOCKS.get()) {
            return null;
        }
        Optional<AtlasBiomeContext> context = AtlasBiomeResolver.context(blockX, blockY, blockZ, level.getSeed());
        if (context.isPresent() && context.get().slope() > AtlasWorldgenConfig.SURFACE_POLISH_BEACH_MAX_SLOPE.get()) {
            return null;
        }
        return Blocks.SAND.defaultBlockState();
    }

    private static BlockState lakeBottomMaterial(LakeSample lake, int depthBlocksBelowSurface) {
        double depth = Math.max(Math.max(0.0D, lake.depthMeters()), depthBlocksBelowSurface * Math.max(0.001D, AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get()));
        if (depth <= AtlasWorldgenConfig.SURFACE_POLISH_LAKE_SAND_DEPTH_METERS.get()) {
            return Blocks.SAND.defaultBlockState();
        }
        if (depth <= AtlasWorldgenConfig.SURFACE_POLISH_LAKE_GRAVEL_DEPTH_METERS.get()) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.CLAY.defaultBlockState();
    }

    private static BlockState oceanBottomMaterial(AtlasOpenWaterGuide.OpenWaterSample water) {
        double depth = Math.max(0.0D, water.depthMeters());
        if (depth <= AtlasWorldgenConfig.SURFACE_POLISH_OCEAN_SAND_DEPTH_METERS.get()) {
            return Blocks.SAND.defaultBlockState();
        }
        if (depth <= AtlasWorldgenConfig.SURFACE_POLISH_OCEAN_GRAVEL_DEPTH_METERS.get()) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.GRAVEL.defaultBlockState();
    }

    private static boolean isNearSeaLevel(int blockY) {
        return Math.abs(blockY - AtlasWorldgenConfig.SEA_LEVEL_Y.get()) <= 18;
    }

    private static boolean isTerrainSurfaceCandidate(BlockState state) {
        return isSurfaceStoneLike(state) || isSoftSurfaceMaterial(state);
    }

    private static boolean isReplaceableTerrainMaterial(BlockState state, boolean replaceSoftSurfaceToo) {
        if (isSurfaceStoneLike(state)) {
            return true;
        }
        return replaceSoftSurfaceToo && isSoftSurfaceMaterial(state);
    }

    private static boolean isSoftSurfaceMaterial(BlockState state) {
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

    private static boolean isIgnoredSurfaceFeature(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.SNOW);
    }

    private static boolean isSurfaceStoneLike(BlockState state) {
        if (state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.BASALT)
                || state.is(Blocks.SMOOTH_BASALT)
                || state.is(Blocks.BLACKSTONE)) {
            return true;
        }
        if (!AtlasWorldgenConfig.SURFACE_POLISH_REPLACE_SURFACE_ORES.get()) {
            return false;
        }
        return state.is(Blocks.COAL_ORE)
                || state.is(Blocks.IRON_ORE)
                || state.is(Blocks.COPPER_ORE)
                || state.is(Blocks.GOLD_ORE)
                || state.is(Blocks.REDSTONE_ORE)
                || state.is(Blocks.EMERALD_ORE)
                || state.is(Blocks.LAPIS_ORE)
                || state.is(Blocks.DIAMOND_ORE)
                || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.DEEPSLATE_IRON_ORE)
                || state.is(Blocks.DEEPSLATE_COPPER_ORE)
                || state.is(Blocks.DEEPSLATE_GOLD_ORE)
                || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                || state.is(Blocks.DEEPSLATE_EMERALD_ORE)
                || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);
    }

    private static boolean isFillableOceanKind(AtlasOpenWaterGuide.OpenWaterKind kind) {
        return kind == AtlasOpenWaterGuide.OpenWaterKind.OCEAN
                || kind == AtlasOpenWaterGuide.OpenWaterKind.OCEAN_FLOOD;
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
