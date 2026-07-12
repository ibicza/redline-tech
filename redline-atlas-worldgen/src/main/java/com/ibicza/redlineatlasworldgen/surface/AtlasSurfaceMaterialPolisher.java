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
import com.ibicza.redlineatlasworldgen.river.AtlasRiverIndex;
import com.ibicza.redlineatlasworldgen.river.RiverKind;
import com.ibicza.redlineatlasworldgen.river.RiverSample;
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
        enqueue(level, pos, newChunk, false, 0);
    }

    public static void enqueueForced(ServerLevel level, ChunkPos pos) {
        enqueue(level, pos, false, true, 0);
    }

    public static void enqueueRiverBoundaryNeighbors(ServerLevel level, ChunkPos pos, boolean newChunk) {
        if (!newChunk || !AtlasWorldgenConfig.SURFACE_POLISH_ENABLED.get()) {
            return;
        }
        if (AtlasWorldgenConfig.OVERWORLD_ONLY.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }
        enqueueRiverBoundaryNeighbor(level, pos, -1, 0);
        enqueueRiverBoundaryNeighbor(level, pos, 1, 0);
        enqueueRiverBoundaryNeighbor(level, pos, 0, -1);
        enqueueRiverBoundaryNeighbor(level, pos, 0, 1);
    }

    private static void enqueueRiverBoundaryNeighbor(ServerLevel level, ChunkPos pos, int dx, int dz) {
        ChunkPos neighbor = new ChunkPos(pos.x() + dx, pos.z() + dz);
        if (level.getChunkSource().getChunkNow(neighbor.x(), neighbor.z()) == null) {
            return;
        }
        if (sharedBoundaryHasRiver(pos, dx, dz)) {
            enqueue(level, neighbor, false, true, 0);
        }
    }

    private static boolean sharedBoundaryHasRiver(ChunkPos pos, int dx, int dz) {
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        for (int i = 0; i < 16; i++) {
            int x;
            int z;
            int neighborX;
            int neighborZ;
            if (dx < 0) {
                x = minX;
                z = minZ + i;
                neighborX = x - 1;
                neighborZ = z;
            } else if (dx > 0) {
                x = minX + 15;
                z = minZ + i;
                neighborX = x + 1;
                neighborZ = z;
            } else if (dz < 0) {
                x = minX + i;
                z = minZ;
                neighborX = x;
                neighborZ = z - 1;
            } else {
                x = minX + i;
                z = minZ + 15;
                neighborX = x;
                neighborZ = z + 1;
            }
            if (AtlasRiverIndex.active().sample(x, z).kind() == RiverKind.CHANNEL
                    || AtlasRiverIndex.active().sample(neighborX, neighborZ).kind() == RiverKind.CHANNEL) {
                return true;
            }
        }
        return false;
    }

    private static void enqueue(ServerLevel level, ChunkPos pos, boolean newChunk, boolean force, int nextColumn) {
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
                QUEUE.addLast(new QueuedSurfaceChunk(level.dimension(), pos, Math.max(0, Math.min(256, nextColumn))));
            }
        }
    }

    private static void enqueueContinuation(ResourceKey<Level> dimension, ChunkPos pos, int nextColumn) {
        long key = key(dimension, pos);
        synchronized (QUEUE) {
            if (QUEUED_KEYS.add(key)) {
                QUEUE.addFirst(new QueuedSurfaceChunk(dimension, pos, Math.max(0, Math.min(256, nextColumn))));
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
            PolishResult result = polishChunk(level, chunk, columnsBudget, queued.nextColumn());
            columnsBudget -= result.visitedColumns();
            if (!result.complete()) {
                enqueueContinuation(queued.dimension(), queued.pos(), result.nextColumn());
            }
            chunksBudget--;
        }
        AtlasWorldgenProfiler.recordSince("surfacePolish.tick", started);
    }

    public static int queueSize() {
        synchronized (QUEUE) {
            return QUEUE.size();
        }
    }

    private static PolishResult polishChunk(ServerLevel level, LevelChunk chunk, int maxColumns, int startColumn) {
        long started = AtlasWorldgenProfiler.start();
        ChunkPos pos = chunk.getPos();
        int minY = level.getMinY();
        int maxY = level.getMaxY() - 1;
        int seaY = AtlasWorldgenConfig.SEA_LEVEL_Y.get();
        int scan = Math.max(1, AtlasWorldgenConfig.SURFACE_POLISH_TOP_SCAN_BLOCKS.get());
        int visited = 0;
        boolean changed = false;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        int start = Math.max(0, Math.min(256, startColumn));
        for (int column = start; column < 256; column++) {
            if (visited >= maxColumns) {
                if (changed) {
                    chunk.markUnsaved();
                }
                AtlasWorldgenProfiler.recordSince("surfacePolish.chunk", started);
                return new PolishResult(visited, column, false);
            }
            visited++;

            int localX = column & 15;
            int localZ = column >>> 4;
            int blockX = pos.getMinBlockX() + localX;
            int blockZ = pos.getMinBlockZ() + localZ;
                int heightmapTopY = Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ));
                AtlasOpenWaterGuide.OpenWaterSample water = waterForSurface(blockX, blockZ);
                LakeSample lake = lakeForSurface(blockX, blockZ);
                RiverSample river = AtlasRiverIndex.active().sample(blockX, blockZ);

                if (AtlasWorldgenConfig.SURFACE_POLISH_FILL_LAKE_WATER.get()
                        && AtlasLakeGuide.isLakeWater(lake.kind())) {
                    changed |= finishLakeColumn(level, mutable, blockX, blockZ, heightmapTopY, minY, maxY, lake);
                    continue;
                }

                if (AtlasWorldgenConfig.SURFACE_POLISH_FILL_RIVER_WATER.get()
                        && river.kind() == RiverKind.CHANNEL) {
                    changed |= finishRiverColumn(level, mutable, blockX, blockZ, heightmapTopY,
                            minY, maxY, river);
                    continue;
                }

                if (AtlasWorldgenConfig.SURFACE_POLISH_FILL_RIVER_WATER.get()
                        && river.kind() == RiverKind.BANK) {
                    changed |= finishRiverBankColumn(level, mutable, blockX, blockZ, heightmapTopY,
                            minY, maxY, river);
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
        if (changed) {
            chunk.markUnsaved();
        }
        AtlasWorldgenProfiler.recordSince("surfacePolish.chunk", started);
        return new PolishResult(visited, 256, true);
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

    private static boolean finishRiverColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                             int heightmapTopY, int minY, int maxY, RiverSample river) {
        long started = AtlasWorldgenProfiler.start();
        try {
            int terrainY = findTopTerrain(level, pos, x, z, Math.min(maxY, heightmapTopY), minY);
            if (terrainY == Integer.MIN_VALUE) {
                return false;
            }

            RiverSectionProfile section = riverSectionProfile(river, minY, maxY);
            int waterY = section.waterY();
            int maximumDepth = riverMaximumDepthBlocks(river);
            int depthBlocks = symmetricRiverDepthBlocks(river, maximumDepth);
            int bottomY = Math.max(minY, waterY - depthBlocks);
            int existingColumnTopY = Math.min(maxY, Math.max(heightmapTopY, terrainY));

            // Features are generated after terrain/surface shaping, so a later river pass can cut
            // the ground out from under trees and plants. Clear the complete river column before
            // carving/filling; otherwise logs, leaves, flowers and grass remain suspended above
            // the new channel even though findTopTerrain intentionally ignores them.
            boolean changed = clearRiverVegetationColumn(level, pos, x, z, waterY + 1, existingColumnTopY);

            // The channel mask is authoritative. Remove all old liquid above the new surface and
            // carve replaceable terrain all the way down to the mirrored bowl. Limiting this to
            // waterY + N left roofs/bridges of sand over deeply lowered sections.
            changed |= clearRiverLiquidColumn(level, pos, x, z, waterY + 1, existingColumnTopY);
            for (int y = bottomY + 1; y <= existingColumnTopY; y++) {
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && (state.liquid() || isReplaceableTerrainMaterial(state, true)
                        || isIgnoredSurfaceFeature(state))) {
                    changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }

            BlockState bed = riverBottomMaterial(river, depthBlocks);
            changed |= buildRiverBed(level, pos, x, z, bottomY, minY, depthBlocks, bed);

            // Fill after the bed and side profile exist. Every placed block is a source block and
            // the complete cross-section shares one section waterY.
            int maxFill = AtlasWorldgenConfig.SURFACE_POLISH_RIVER_MAX_FILL_BLOCKS.get();
            int fillStart = Math.max(bottomY + 1, waterY - maxFill + 1);
            for (int y = fillStart; y <= waterY; y++) {
                pos.set(x, y, z);
                BlockState current = level.getBlockState(pos);
                if (current.isAir() || current.liquid() || isReplaceableTerrainMaterial(current, true)
                        || isIgnoredSurfaceFeature(current)) {
                    changed |= level.setBlock(pos, Blocks.WATER.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
            return changed;
        } finally {
            AtlasWorldgenProfiler.recordSince("surfacePolish.river", started);
        }
    }

    private static boolean finishRiverBankColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                                 int heightmapTopY, int minY, int maxY, RiverSample river) {
        int terrainY = findTopTerrain(level, pos, x, z, Math.min(maxY, heightmapTopY), minY);
        if (terrainY == Integer.MIN_VALUE) {
            return false;
        }

        RiverSectionProfile section = riverSectionProfile(river, minY, maxY);
        int bankWidth = Math.max(1, AtlasWorldgenConfig.RIVER_BANK_WIDTH_BLOCKS.get());
        int lateralBand = Math.max(0, (int) Math.floor(river.distanceToBankBlocks() + 0.5D));
        double progress = Math.min(1.0D, lateralBand / (double) bankWidth);
        int targetY = Math.min(maxY, section.bankTopY() + (int) Math.round(2.0D * smoothstep(progress)));
        int existingColumnTopY = Math.min(maxY, Math.max(heightmapTopY, terrainY));
        boolean changed = clearRiverVegetationColumn(level, pos, x, z, targetY + 1, existingColumnTopY);
        changed |= clearRiverLiquidColumn(level, pos, x, z, targetY + 1, existingColumnTopY);

        // The bank target depends only on the section and |distance from center|. Thus opposite
        // columns get exactly the same Y even when their original terrain heights differ.
        if (terrainY > targetY) {
            for (int y = terrainY; y > targetY; y--) {
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (isReplaceableTerrainMaterial(state, true) || isIgnoredSurfaceFeature(state)) {
                    changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                } else if (!state.isAir() && !state.liquid()) {
                    break;
                }
            }
            terrainY = findTopTerrain(level, pos, x, z, targetY, minY);
        }

        BlockState cap = riverBankMaterial(river, progress);
        int maximumDepth = riverMaximumDepthBlocks(river);
        int sealBottomY = Math.max(minY, section.waterY() - maximumDepth);
        boolean edgeColumn = lateralBand <= 1;
        int looseFoundationDepth = isLooseRiverMaterial(cap)
                ? riverLooseFoundationDepthBlocks(x, z)
                : 0;
        int fillBottom = edgeColumn ? sealBottomY : Math.max(minY, targetY - 3);
        if (looseFoundationDepth > 0) {
            fillBottom = Math.min(fillBottom, Math.max(minY, targetY - looseFoundationDepth));
        }

        // A one/two-column mirrored rim seals the whole water column. This prevents source water
        // from falling through caves or flowing below a missing bank while avoiding tall dams in
        // the outer blend zone.
        for (int y = fillBottom; y <= targetY; y++) {
            pos.set(x, y, z);
            BlockState current = level.getBlockState(pos);
            if (current.isAir() || current.liquid() || isReplaceableTerrainMaterial(current, true)
                    || isIgnoredSurfaceFeature(current)) {
                BlockState replacement;
                if (y == targetY) {
                    replacement = cap;
                } else if (looseFoundationDepth > 0 && y >= targetY - looseFoundationDepth) {
                    replacement = Blocks.CLAY.defaultBlockState();
                } else {
                    replacement = bankFoundationMaterial(cap);
                }
                changed |= level.setBlock(pos, replacement,
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
        }

        // Old builds allowed channel source blocks to spread over solid ledges outside the atlas
        // mask. Repair those waterfall curtains from the outer bank edge, but remove only flowing
        // water: legitimate source water belonging to lakes/oceans is left untouched.
        if (progress >= 0.75D) {
            changed |= clearFlowingRiverOverflow(level, pos, x, z, minY, maxY, river);
        }
        return changed;
    }

    private static boolean clearFlowingRiverOverflow(ServerLevel level, BlockPos.MutableBlockPos pos,
                                                      int x, int z, int minY, int maxY, RiverSample river) {
        int cleanup = AtlasWorldgenConfig.SURFACE_POLISH_RIVER_OVERFLOW_CLEANUP_BLOCKS.get();
        if (cleanup <= 0 || !Double.isFinite(river.centerXBlocks()) || !Double.isFinite(river.centerZBlocks())) {
            return false;
        }
        double normalLength = Math.hypot(river.normalX(), river.normalZ());
        if (normalLength <= 1.0E-9D) {
            return false;
        }
        double normalX = river.normalX() / normalLength;
        double normalZ = river.normalZ() / normalLength;
        double side = ((x + 0.5D) - river.centerXBlocks()) * normalX
                + ((z + 0.5D) - river.centerZBlocks()) * normalZ;
        double outwardX = side >= 0.0D ? normalX : -normalX;
        double outwardZ = side >= 0.0D ? normalZ : -normalZ;

        boolean changed = false;
        long previousKey = Long.MIN_VALUE;
        for (int step = 1; step <= cleanup; step++) {
            int sampleX = (int) Math.round(x + outwardX * step);
            int sampleZ = (int) Math.round(z + outwardZ * step);
            long key = ((long) sampleX << 32) ^ (sampleZ & 0xffffffffL);
            if (key == previousKey) {
                continue;
            }
            previousKey = key;

            RiverSample targetRiver = AtlasRiverIndex.active().sample(sampleX, sampleZ);
            if (targetRiver.kind() == RiverKind.CHANNEL) {
                continue;
            }
            LevelChunk targetChunk = level.getChunkSource().getChunkNow(Math.floorDiv(sampleX, 16), Math.floorDiv(sampleZ, 16));
            if (targetChunk == null) {
                continue;
            }
            int localX = Math.floorMod(sampleX, 16);
            int localZ = Math.floorMod(sampleZ, 16);
            int topY = Math.min(maxY, targetChunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ));
            int terrainY = findTopTerrain(level, pos, sampleX, sampleZ, topY, minY);
            if (terrainY == Integer.MIN_VALUE) {
                continue;
            }
            for (int y = terrainY + 1; y <= topY; y++) {
                pos.set(sampleX, y, sampleZ);
                BlockState state = level.getBlockState(pos);
                if (state.liquid() && !state.getFluidState().isSource()) {
                    changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }
        return changed;
    }

    private static int riverMaximumDepthBlocks(RiverSample river) {
        return Math.max(1, (int) Math.round(river.depthMeters()
                / AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get()));
    }

    private static int symmetricRiverDepthBlocks(RiverSample river, int maximumDepth) {
        int halfWidthBand = Math.max(1, (int) Math.ceil(river.halfWidthBlocks()));
        int lateralBand = Math.min(halfWidthBand,
                Math.max(0, (int) Math.floor(river.distanceToCenterBlocks() + 0.5D)));
        double normalized = lateralBand / (double) halfWidthBand;
        double bowl = 1.0D - smoothstep(normalized);
        return 1 + (int) Math.round(Math.max(0, maximumDepth - 1) * bowl);
    }

    private static boolean buildRiverBed(ServerLevel level, BlockPos.MutableBlockPos pos,
                                         int x, int z, int bottomY, int minY,
                                         int depthBlocks, BlockState bed) {
        boolean changed = false;

        if (isLooseRiverMaterial(bed)) {
            // Falling blocks must never be placed first. Build a deterministic 2-4 block clay
            // footing from the bottom up, then place the visible sand/gravel cap last. Even if a
            // cave opens directly below the footing, clay remains stable and the riverbed cannot
            // collapse into items or leave holes in the water column.
            int foundationDepth = riverLooseFoundationDepthBlocks(x, z);
            int foundationBottomY = Math.max(minY, bottomY - foundationDepth);
            for (int y = foundationBottomY; y < bottomY; y++) {
                pos.set(x, y, z);
                BlockState current = level.getBlockState(pos);
                if (current.isAir() || current.liquid() || isReplaceableTerrainMaterial(current, true)
                        || isIgnoredSurfaceFeature(current)) {
                    changed |= level.setBlock(pos, Blocks.CLAY.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                }
            }

            pos.set(x, bottomY, z);
            BlockState current = level.getBlockState(pos);
            if (current.isAir() || current.liquid() || isReplaceableTerrainMaterial(current, true)
                    || isIgnoredSurfaceFeature(current)) {
                changed |= level.setBlock(pos, bed,
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
            return changed;
        }

        int bedLayers = Math.min(3, Math.max(1, depthBlocks));
        for (int y = bottomY; y >= Math.max(minY, bottomY - bedLayers + 1); y--) {
            pos.set(x, y, z);
            BlockState current = level.getBlockState(pos);
            if (isReplaceableTerrainMaterial(current, true) || current.isAir() || current.liquid()
                    || isIgnoredSurfaceFeature(current)) {
                changed |= level.setBlock(pos, bed,
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return changed;
    }

    private static int riverLooseFoundationDepthBlocks(int x, int z) {
        int configuredMin = AtlasWorldgenConfig.SURFACE_POLISH_RIVER_LOOSE_FOUNDATION_MIN_BLOCKS.get();
        int configuredMax = AtlasWorldgenConfig.SURFACE_POLISH_RIVER_LOOSE_FOUNDATION_MAX_BLOCKS.get();
        int min = Math.max(1, Math.min(configuredMin, configuredMax));
        int max = Math.max(min, Math.max(configuredMin, configuredMax));
        int range = max - min + 1;
        if (range <= 1) {
            return min;
        }

        long mixed = (long) x * 0x9E3779B97F4A7C15L ^ (long) z * 0xC2B2AE3D27D4EB4FL;
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        return min + (int) Math.floorMod(mixed, (long) range);
    }

    private static boolean isLooseRiverMaterial(BlockState state) {
        return state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL);
    }

    private static BlockState bankFoundationMaterial(BlockState cap) {
        if (cap.is(Blocks.SAND)) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        if (cap.is(Blocks.RED_SAND)) {
            return Blocks.RED_SANDSTONE.defaultBlockState();
        }
        if (cap.is(Blocks.GRAVEL)) {
            return Blocks.STONE.defaultBlockState();
        }
        if (cap.is(Blocks.GRASS_BLOCK)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return cap;
    }

    private static boolean clearRiverVegetationColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                                                       int x, int z, int fromY, int toY) {
        if (fromY > toY) {
            return false;
        }
        boolean changed = false;
        for (int y = fromY; y <= toY; y++) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.liquid() && isIgnoredSurfaceFeature(state)) {
                changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return changed;
    }

    private static boolean clearRiverLiquidColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                                                  int x, int z, int fromY, int toY) {
        if (fromY > toY) {
            return false;
        }
        boolean changed = false;
        for (int y = fromY; y <= toY; y++) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.liquid()) {
                changed |= level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return changed;
    }

    private static RiverSectionProfile riverSectionProfile(RiverSample river, int minY, int maxY) {
        int waterY = AtlasCoordinateMapper.metersToWorldY(river.waterSurfaceMeters());
        int clearance = Math.max(1, AtlasWorldgenConfig.RIVER_WATER_BELOW_BANK_BLOCKS.get());
        waterY = Math.max(minY + 1, Math.min(maxY - clearance, waterY));
        return new RiverSectionProfile(waterY, waterY + clearance);
    }

    private record RiverSectionProfile(int waterY, int bankTopY) {
    }

    private static BlockState riverBottomMaterial(RiverSample river, int depthBlocks) {
        double depthMeters = depthBlocks * AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get();
        if (depthMeters <= AtlasWorldgenConfig.SURFACE_POLISH_RIVER_SAND_DEPTH_METERS.get()
                && river.dischargeCms() < 100.0D) {
            return Blocks.SAND.defaultBlockState();
        }
        if (depthMeters <= AtlasWorldgenConfig.SURFACE_POLISH_RIVER_GRAVEL_DEPTH_METERS.get()
                || river.strahlerOrder() >= 5) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.CLAY.defaultBlockState();
    }

    private static BlockState riverBankMaterial(RiverSample river, double progress) {
        if (progress < 0.45D) {
            return river.dischargeCms() >= 250.0D ? Blocks.SAND.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static double smoothstep(double value) {
        double t = Math.max(0.0D, Math.min(1.0D, value));
        return t * t * (3.0D - 2.0D * t);
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
            } else if (state.isAir() || state.liquid() || isReplaceableWaterColumnBlock(state)
                    || isTerrainSurfaceCandidate(state) || isSoftSurfaceMaterial(state) || isIgnoredSurfaceFeature(state)) {
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
                } else if (state.isAir() || state.liquid() || isReplaceableWaterColumnBlock(state)
                        || isTerrainSurfaceCandidate(state) || isSoftSurfaceMaterial(state) || isIgnoredSurfaceFeature(state)) {
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
            if (state.isAir() || state.liquid() || isReplaceableWaterColumnBlock(state) || isIgnoredSurfaceFeature(state)) {
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
        return isIgnoredSurfaceFeature(state)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS);
    }

    private static boolean isIgnoredSurfaceFeature(BlockState state) {
        return state.canBeReplaced()
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.FLOWERS)
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

    private record QueuedSurfaceChunk(ResourceKey<Level> dimension, ChunkPos pos, int nextColumn) {
    }

    private record PolishResult(int visitedColumns, int nextColumn, boolean complete) {
    }

    private AtlasSurfaceMaterialPolisher() {
    }
}
