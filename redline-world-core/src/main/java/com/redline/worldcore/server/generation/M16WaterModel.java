package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.generation.CubeGenerationContext;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.util.Mth;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * M16 seed-only static hydrology layer.
 *
 * <p>The generated blocks are real vanilla water/sand blocks, but this layer deliberately treats them as worldgen-static
 * terrain. The vanilla shell materializer writes them without neighbor updates; active cubic fluid simulation is a later
 * milestone. This prevents M15.0 style water leaks into not-yet-materialized cube borders.</p>
 */
public final class M16WaterModel {
    public static final String VERSION = "M16.14 request-dedupe + shore tile hints v1";

    private static final long OCEAN_SEED = 0x4F4345414E4D4150L;
    private static final long RIVER_SEED = 0x5249564552533031L;
    private static final long GREAT_RIVER_SEED = 0x4752454154524956L;
    private static final long LAKE_SEED = 0x4C414B45534D3136L;
    private static final long WATERFALL_SEED = 0x574154455246414CL;
    private static final long LAKE_SHAPE_SEED = 0x4C414B4553484150L;
    private static final long LAKE_SHORE_SEED = 0x4C414B4553484F52L;
    private static final long RIVER_BED_SEED = 0x5249564552424544L;

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState CLAY = Blocks.CLAY.defaultBlockState();

    private static final int SAMPLE_CACHE_LIMIT = 65536;
    private static final int RIVER_FIELD_CACHE_LIMIT = 65536;
    private static final int OCEAN_SHORE_CACHE_LIMIT = 16384;
    private static final int LAKE_BASIN_CACHE_LIMIT = 8192;
    private static final int SHORE_SHAPE_CACHE_LIMIT = 65536;
    private static final int SHORE_TILE_HINT_CACHE_LIMIT = 32768;
    private static final int SHORE_TILE_SHIFT = 4;
    private static final int SHORE_TILE_SIZE = 1 << SHORE_TILE_SHIFT;

    private static final ThreadLocal<LinkedHashMap<Long, M16WaterSample>> SAMPLE_CACHE = ThreadLocal.withInitial(() -> new LinkedHashMap<>(512, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, M16WaterSample> eldest) {
            return size() > SAMPLE_CACHE_LIMIT;
        }
    });

    private static final ThreadLocal<LinkedHashMap<Long, RiverField>> RIVER_FIELD_CACHE = ThreadLocal.withInitial(() -> new LinkedHashMap<>(512, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, RiverField> eldest) {
            return size() > RIVER_FIELD_CACHE_LIMIT;
        }
    });

    private static final ThreadLocal<LinkedHashMap<Long, Double>> OCEAN_SHORE_CACHE = ThreadLocal.withInitial(() -> new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Double> eldest) {
            return size() > OCEAN_SHORE_CACHE_LIMIT;
        }
    });

    private static final ThreadLocal<LinkedHashMap<Long, Boolean>> LAKE_BASIN_CACHE = ThreadLocal.withInitial(() -> new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > LAKE_BASIN_CACHE_LIMIT;
        }
    });

    private static final ThreadLocal<LinkedHashMap<Long, ShoreShape>> SHORE_SHAPE_CACHE = ThreadLocal.withInitial(() -> new LinkedHashMap<>(1024, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ShoreShape> eldest) {
            return size() > SHORE_SHAPE_CACHE_LIMIT;
        }
    });

    private static final ThreadLocal<LinkedHashMap<Long, ShoreTileHint>> SHORE_TILE_HINT_CACHE = ThreadLocal.withInitial(() -> new LinkedHashMap<>(512, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ShoreTileHint> eldest) {
            return size() > SHORE_TILE_HINT_CACHE_LIMIT;
        }
    });

    public static void beginCubeGeneration() {
        // M16.11: keep bounded per-thread caches warm across adjacent cube generation. Every key includes the seed
        // and dimension settings, so nearby client/eager generation can reuse river/ocean/shore samples safely.
    }

    public static void endCubeGeneration() {
        // Bounded LRU maps evict old entries; clearing after every cube was responsible for millions of repeated
        // river/water samples around shore scans.
    }

    public static M16WaterSample sample(CubeGenerationContext context, M15TerrainSample terrain) {
        long key = sampleKey(context, terrain.x(), terrain.z());
        LinkedHashMap<Long, M16WaterSample> cache = SAMPLE_CACHE.get();
        M16WaterSample cached = cache.get(key);
        if (cached != null) {
            M16WaterPerfDebug.recordSampleCacheHit();
            RuntimeProfiler.addCount("water.sample_cache_hits", 1);
            return cached;
        }
        long start = System.nanoTime();
        M16WaterSample sample = sampleUncached(context, terrain);
        cache.put(key, sample);
        long micros = (System.nanoTime() - start) / 1_000L;
        M16WaterPerfDebug.recordSample(micros);
        RuntimeProfiler.recordMicros("water.sample_uncached", micros);
        RuntimeProfiler.addCount("water.sample_uncached_calls", 1);
        return sample;
    }

    private static M16WaterSample sampleUncached(CubeGenerationContext context, M15TerrainSample terrain) {
        M15WorldgenProfile profile = M15TerrainModel.profile(context);
        int x = terrain.x();
        int z = terrain.z();
        int drySurfaceY = terrain.surfaceY();

        long oceanStart = RuntimeProfiler.markStart();
        M16WaterSample ocean = oceanSample(context, profile, terrain);
        RuntimeProfiler.recordSince("water.sample.ocean", oceanStart);
        // M16.1 ownership: oceans own their volume first. Rivers may lead into oceans later, but they must not
        // rasterize a separate river/lake/waterfall body inside an ocean cell.
        if (ocean.hasWater()) {
            return ocean;
        }

        long riverStart = RuntimeProfiler.markStart();
        M16WaterSample river = riverSample(context, profile, terrain);
        RuntimeProfiler.recordSince("water.sample.river", riverStart);
        long lakeStart = RuntimeProfiler.markStart();
        M16WaterSample lake = lakeSample(context, profile, terrain, river);
        RuntimeProfiler.recordSince("water.sample.lake", lakeStart);

        M16WaterSample best = chooseBest(ocean, river, lake);
        if (best.waterType() == M16WaterType.RIVER && isWaterfallCandidate(context, profile, terrain, best)) {
            int fallCarveDepth = Mth.clamp(best.carveDepth() + 18 + M15Noise.hashToRange(context.seed() ^ WATERFALL_SEED, x >> 4, drySurfaceY >> 3, z >> 4, 48),
                    best.carveDepth() + 12,
                    128);
            int effective = Math.max(profile.minY() + 16, drySurfaceY - fallCarveDepth);
            int waterSurface = Math.min(drySurfaceY - 1, effective + Math.max(6, best.waterDepth()));
            best = new M16WaterSample(
                    x,
                    z,
                    drySurfaceY,
                    effective,
                    waterSurface,
                    Math.max(6, best.waterDepth()),
                    fallCarveDepth,
                    Math.max(best.valleyWidth(), best.riverWidth() * 2),
                    best.oceanMask(),
                    best.shoreDistance(),
                    best.riverDistance(),
                    best.riverWidth(),
                    M16WaterType.WATERFALL,
                    best.riverProfile(),
                    best.greatRiver(),
                    true,
                    true
            );
        }
        return best;
    }

    private static long sampleKey(CubeGenerationContext context, int x, int z) {
        long key = (((long) x) << 32) ^ (z & 0xFFFF_FFFFL);
        key ^= context.seed() * 0x9E3779B97F4A7C15L;
        key ^= ((long) context.settings().minCubeY() << 48);
        key ^= ((long) context.settings().maxCubeY() << 32);
        key ^= ((long) context.settings().seaLevel() << 16);
        return key;
    }

    public static M16WaterSample sample(CubeGenerationContext context, int x, int z) {
        return sample(context, M15TerrainModel.sampleDry(context, x, z));
    }

    public static M16WaterColumnShape columnShape(CubeGenerationContext context, M15TerrainSample terrain) {
        long start = System.nanoTime();
        M15WorldgenProfile profile = M15TerrainModel.profile(context);
        M16WaterSample water = sample(context, terrain);
        if (water.hasWater()) {
            long micros = (System.nanoTime() - start) / 1_000L;
            M16WaterPerfDebug.recordColumnShape(micros);
            RuntimeProfiler.recordMicros("water.column_shape", micros);
            return M16WaterColumnShape.waterBody(water);
        }
        ShoreTileHint shoreHint = shoreTileHint(context, profile, terrain);
        if (!mightNeedDryShoreShape(profile, terrain, shoreHint)) {
            long micros = (System.nanoTime() - start) / 1_000L;
            M16WaterPerfDebug.recordColumnShape(micros);
            RuntimeProfiler.recordMicros("water.column_shape", micros);
            RuntimeProfiler.addCount("water.dry_shore_fast_skips", 1);
            return M16WaterColumnShape.dry(water, false, Integer.MAX_VALUE, 0, false, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }
        M16WaterPerfDebug.recordDryShoreScan();
        RuntimeProfiler.addCount("water.dry_shore_scans", 1);
        long shoreStart = RuntimeProfiler.markStart();
        ShoreShape shore = nearestShoreShape(context, profile, terrain, shoreHint);
        RuntimeProfiler.recordSince("water.nearest_shore_shape", shoreStart);
        long micros = (System.nanoTime() - start) / 1_000L;
        M16WaterPerfDebug.recordColumnShape(micros);
        RuntimeProfiler.recordMicros("water.column_shape", micros);
        return M16WaterColumnShape.dry(water, shore.active(), shore.surfaceY(), shore.softDepth(), shore.raise(), shore.topState(), shore.subState());
    }

    private static boolean mightNeedDryShoreShape(M15WorldgenProfile profile, M15TerrainSample terrain, ShoreTileHint hint) {
        // M16.14: most dry columns do not need any shore solver at all.  A conservative tile hint rejects whole 16x16
        // areas that have no nearby river valley, lake rim or ocean coast, preventing hundreds of thousands of tiny
        // direct shore probes during normal flight.
        if (!hint.mayHaveShore()) {
            return false;
        }
        if (terrain.surfaceY() > profile.seaLevel() + 40 && !hint.mayLake()) {
            return false;
        }
        if (terrain.surfaceY() < profile.seaLevel() - 24) {
            return hint.mayOcean();
        }
        return true;
    }

    public static BlockState overrideState(CubeGenerationContext context, M15TerrainSample terrain, int y, BlockState baseState) {
        return overrideState(context, terrain, columnShape(context, terrain), y, baseState);
    }

    public static BlockState overrideState(CubeGenerationContext context, M15TerrainSample terrain, M16WaterColumnShape column, int y, BlockState baseState) {
        M16WaterSample water = column.water();
        if (!water.hasWater()) {
            return adjustedDryShoreState(terrain, column, y, baseState);
        }

        // M16.5: water bodies are no longer painted over the finished terrain.  The hydrology owner first
        // defines the local bed/surface, then this pass carves or infills the terrain to that shape and only
        // finally places water.  This prevents floating lakes, vertical river cuts and ocean shelves with air
        // gaps between the dry shore and the water.
        if (y > water.waterSurfaceY()) {
            return Blocks.AIR.defaultBlockState();
        }
        if (y > water.effectiveSurfaceY()) {
            return waterState(water, y);
        }
        if (y == water.effectiveSurfaceY()) {
            return bedState(water, y);
        }
        if (y > water.effectiveSurfaceY() - supportDepth(water)) {
            return supportState(water, y);
        }
        if (water.effectiveSurfaceY() > terrain.surfaceY() && y > terrain.surfaceY() - infillDepth(water)) {
            return supportState(water, y);
        }
        return baseState;
    }

    public static boolean isSafeDrySpawn(M16WaterSample water, M15WorldgenProfile profile) {
        return water.waterType() == M16WaterType.NONE && water.drySurfaceY() >= profile.seaLevel() + 4;
    }

    public static boolean mayContainWater(CubeGenerationContext context, M15WorldgenProfile profile, int cubeX, int cubeY, int cubeZ) {
        int minY = cubeY << 4;
        int maxY = minY + 15;
        if (minY > profile.highestSurfaceY() + 16 || maxY < profile.lowestSurfaceY() - 160) {
            return false;
        }
        // Conservative cheap path for deep solid fast-fill checks. Do not call full water sample here; the fast path
        // itself must remain fast. Detailed surface cubes still go through the full 16x16 column planner.
        int baseX = cubeX << 4;
        int baseZ = cubeZ << 4;
        for (int dz = 0; dz <= 16; dz += 8) {
            for (int dx = 0; dx <= 16; dx += 8) {
                M15TerrainSample terrain = M15TerrainModel.sampleDry(context, baseX + Math.min(dx, 15), baseZ + Math.min(dz, 15));
                if (rawOceanCandidate(context, profile, terrain) && maxY >= profile.seaLevel() - 40 && minY <= profile.seaLevel()) {
                    return true;
                }
                RiverField field = riverField(context, terrain.x(), terrain.z(), terrain);
                if (field.profile != M16RiverProfile.NONE
                        && field.distance <= riverWaterRadius(field)
                        && terrain.surfaceY() <= profile.seaLevel() + 32
                        && maxY >= profile.seaLevel() - Math.max(2, field.baseWaterDepth + 2)
                        && minY <= profile.seaLevel()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static LocateResult locateNearest(CubeGenerationContext context, M16WaterType type, int centerX, int centerZ, int radiusBlocks) {
        int step = type == M16WaterType.WATERFALL ? 16 : 32;
        M16WaterSample best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int radius = 0; radius <= radiusBlocks; radius += step) {
            if (radius == 0) {
                M16WaterSample sample = sample(context, centerX, centerZ);
                if (matches(sample, type)) {
                    return new LocateResult(true, sample, 0);
                }
                continue;
            }
            for (int x = centerX - radius; x <= centerX + radius; x += step) {
                best = consider(context, type, x, centerZ - radius, centerX, centerZ, best, bestDistance);
                if (best != null) {
                    bestDistance = distance(centerX, centerZ, best.x(), best.z());
                }
                best = consider(context, type, x, centerZ + radius, centerX, centerZ, best, bestDistance);
                if (best != null) {
                    bestDistance = distance(centerX, centerZ, best.x(), best.z());
                }
            }
            for (int z = centerZ - radius + step; z <= centerZ + radius - step; z += step) {
                best = consider(context, type, centerX - radius, z, centerX, centerZ, best, bestDistance);
                if (best != null) {
                    bestDistance = distance(centerX, centerZ, best.x(), best.z());
                }
                best = consider(context, type, centerX + radius, z, centerX, centerZ, best, bestDistance);
                if (best != null) {
                    bestDistance = distance(centerX, centerZ, best.x(), best.z());
                }
            }
            if (best != null && bestDistance <= radius + step) {
                return new LocateResult(true, best, bestDistance);
            }
        }
        return new LocateResult(false, best, bestDistance == Integer.MAX_VALUE ? -1 : bestDistance);
    }

    private static M16WaterSample oceanSample(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        int x = terrain.x();
        int z = terrain.z();
        double oceanMask = rawOceanMask(context, terrain);
        boolean ocean = (terrain.surfaceY() <= profile.seaLevel() - 3 && oceanMask > 0.50D)
                || (terrain.surfaceY() <= profile.seaLevel() + 1 && oceanMask > 0.38D)
                || (terrain.surfaceY() <= profile.seaLevel() && nearStrongOceanCell(context, profile, x, z));
        if (!ocean) {
            return none(terrain, oceanMask, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()));
        }

        // M16.6: ocean floor must be a coastal shelf, not a full-depth wall right next to dry land.
        // Depth is driven first by distance to the nearest non-ocean/dry shore, then only by deep-ocean mask.
        double shoreDistance = oceanShoreDistance(context, profile, x, z);
        double shoreN = Mth.clamp(shoreDistance / 112.0D, 0.0D, 1.0D);
        // M16.8: coastline floor is explicitly shelf-shaped.  Close to land it must stay shallow; depth grows
        // gradually with shore distance, then ocean mask can add deeper water farther away.
        int shelfDepth = 1 + (int) Math.round(shoreN * shoreN * 20.0D + shoreN * 6.0D);
        int deepDepth = 5 + (int) Math.round(oceanMask * 22.0D)
                + M15Noise.hashToRange(context.seed() ^ OCEAN_SEED, x >> 4, 0, z >> 4, 6);
        int depth = Mth.clamp(Math.min(deepDepth, shelfDepth), 1, 36);
        int bedY = Math.max(profile.minY() + 16, profile.seaLevel() - depth);
        return new M16WaterSample(x, z, terrain.surfaceY(), bedY, profile.seaLevel(), profile.seaLevel() - bedY, profile.seaLevel() - bedY,
                64 + (int) Math.round(oceanMask * 128.0D), oceanMask, shoreDistance, Double.POSITIVE_INFINITY, 0,
                M16WaterType.OCEAN, M16RiverProfile.NONE, false, false, false);
    }

    private static M16WaterSample lakeSample(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain, M16WaterSample river) {
        int x = terrain.x();
        int z = terrain.z();
        if (terrain.surfaceY() <= profile.seaLevel() + 4 || terrain.surfaceY() > profile.highMountainStartY() + 96) {
            return none(terrain, 0.0D, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()));
        }
        // Avoid lake/river double ownership. A river corridor can cut or feed a lake later, but M16.1 does not allow a
        // lake body to be rasterized on top of an existing river water cell.
        if (river.hasWater() && river.riverDistance() < Math.max(8.0D, river.riverWidth() * 0.65D)) {
            return none(terrain, 0.0D, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()));
        }
        M16WaterSample best = null;
        double bestStrength = 0.0D;
        int cellX = Math.floorDiv(x, 256);
        int cellZ = Math.floorDiv(z, 256);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                if (M15Noise.hashToRange(context.seed() ^ LAKE_SEED, cx, 0, cz, 5) != 0) {
                    continue;
                }
                int centerX = cx * 256 + 48 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 11L), cx, 0, cz, 160);
                int centerZ = cz * 256 + 48 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 29L), cx, 0, cz, 160);
                int rx = 14 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 47L), cx, 0, cz, 82);
                int rz = 12 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 61L), cx, 0, cz, 76);
                double nx = (x - centerX) / (double) rx;
                double nz = (z - centerZ) / (double) rz;
                double ellipseDist = nx * nx + nz * nz;
                double lobes = M15Noise.fbm2D(context.seed() ^ LAKE_SHAPE_SEED, x, z, 48, 3) * 0.18D
                        + M15Noise.fbm2D(context.seed() ^ (LAKE_SHAPE_SEED + 19L), x + centerX, z - centerZ, 21, 2) * 0.08D;
                double edgeScale = Mth.clamp(1.0D + lobes, 0.72D, 1.28D);
                double dist = ellipseDist / (edgeScale * edgeScale);
                if (dist > 1.0D) {
                    continue;
                }
                M15TerrainSample centerTerrain = M15TerrainModel.sampleDry(context, centerX, centerZ);
                M16WaterSample centerOcean = oceanSample(context, profile, centerTerrain);
                if (centerOcean.hasWater()) {
                    continue;
                }
                // Flat-lake invariant: every cell of one lake uses a single center-derived water level.
                // The terrain may be carved down to it, but the lake surface itself never slopes.
                int lakeSurface = centerTerrain.surfaceY() - 1;
                if (lakeSurface <= profile.seaLevel() + 3 || lakeSurface > terrain.surfaceY() + 8) {
                    continue;
                }
                if (!lakeBasinLooksValid(context, profile, centerX, centerZ, rx, rz, lakeSurface)) {
                    continue;
                }
                double edge = Math.sqrt(dist);
                int maxUnsupportedDrop = edge > 0.78D ? 2 : edge > 0.52D ? 7 : 16;
                if (lakeSurface - terrain.surfaceY() > maxUnsupportedDrop) {
                    // Do not let a lake spill as a floating slab over an open valley/cliff.  If a basin cannot be
                    // supported by the local terrain, this cell is not owned by the lake.
                    continue;
                }
                double strength = 1.0D - dist;
                int baseDepth = 3 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 79L), cx, 0, cz, 13);
                double bedRoughness = M15Noise.fbm2D(context.seed() ^ (LAKE_SHAPE_SEED + 41L), x, z, 19, 2) * 1.4D;
                int waterDepth = Math.max(1, 1 + (int) Math.round(Math.sqrt(strength) * baseDepth + bedRoughness));
                int carveDepth = waterDepth + 1 + (int) Math.round(strength * 6.0D);
                int targetBedY = Math.max(profile.minY() + 16, lakeSurface - waterDepth);
                int bedY = Math.min(terrain.surfaceY(), targetBedY);
                if (lakeSurface - bedY > 18) {
                    continue;
                }
                M16WaterSample sample = new M16WaterSample(x, z, terrain.surfaceY(), bedY, lakeSurface, lakeSurface - bedY, carveDepth,
                        Math.max(rx, rz), 0.0D, Math.sqrt(dist) * Math.max(rx, rz), Double.POSITIVE_INFINITY, 0,
                        M16WaterType.LAKE, M16RiverProfile.NONE, false, false, false);
                if (strength > bestStrength) {
                    bestStrength = strength;
                    best = sample;
                }
            }
        }
        return best == null ? none(terrain, 0.0D, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel())) : best;
    }

    private static boolean lakeBasinLooksValid(CubeGenerationContext context, M15WorldgenProfile profile, int centerX, int centerZ, int rx, int rz, int lakeSurface) {
        long key = basinKey(context, centerX, centerZ, rx, rz, lakeSurface);
        LinkedHashMap<Long, Boolean> cache = LAKE_BASIN_CACHE.get();
        Boolean cached = cache.get(key);
        if (cached != null) {
            RuntimeProfiler.addCount("water.lake_basin_cache_hits", 1);
            return cached;
        }
        long start = RuntimeProfiler.markStart();
        boolean result = lakeBasinLooksValidUncached(context, profile, centerX, centerZ, rx, rz, lakeSurface);
        RuntimeProfiler.recordSince("water.lake_basin_validation", start);
        RuntimeProfiler.addCount("water.lake_basin_validations", 1);
        cache.put(key, result);
        return result;
    }

    private static boolean lakeBasinLooksValidUncached(CubeGenerationContext context, M15WorldgenProfile profile, int centerX, int centerZ, int rx, int rz, int lakeSurface) {
        // M16.3 basin guard: a lake is allowed to fill a shallow depression, but it must not become a floating
        // saucer glued to a cliff. We sample the rim and a few inner points before accepting the lake owner.
        int validRim = 0;
        int validInner = 0;
        int[][] dirs = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int[] dir : dirs) {
            int rimX = centerX + (int) Math.round(dir[0] * rx * 1.08D);
            int rimZ = centerZ + (int) Math.round(dir[1] * rz * 1.08D);
            M15TerrainSample rim = M15TerrainModel.sampleDry(context, rimX, rimZ);
            if (!oceanSample(context, profile, rim).hasWater() && rim.surfaceY() >= lakeSurface + 2) {
                validRim++;
            }

            int innerX = centerX + (int) Math.round(dir[0] * rx * 0.55D);
            int innerZ = centerZ + (int) Math.round(dir[1] * rz * 0.55D);
            M15TerrainSample inner = M15TerrainModel.sampleDry(context, innerX, innerZ);
            int delta = lakeSurface - inner.surfaceY();
            if (!oceanSample(context, profile, inner).hasWater() && delta >= -10 && delta <= 12) {
                validInner++;
            }
        }
        return validRim >= 7 && validInner >= 6;
    }

    private static long basinKey(CubeGenerationContext context, int centerX, int centerZ, int rx, int rz, int lakeSurface) {
        long key = (((long) centerX) << 32) ^ (centerZ & 0xFFFF_FFFFL);
        key ^= context.seed() * 0x9E3779B97F4A7C15L;
        key ^= ((long) rx & 0xFFFFL) << 48;
        key ^= ((long) rz & 0xFFFFL) << 32;
        key ^= ((long) lakeSurface & 0xFFFFL) << 16;
        key ^= ((long) context.settings().seaLevel() & 0xFFFFL);
        return key;
    }

    private static M16WaterSample riverSample(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        int x = terrain.x();
        int z = terrain.z();
        RiverField field = riverField(context, x, z, terrain);
        double waterRadius = riverWaterRadius(field);
        if (field.distance > waterRadius) {
            return none(terrain, 0.0D, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()));
        }

        // M16.9: vanilla-style rivers are flat water bodies.  Vanilla overworld rivers are not a sloped fluid
        // network; their water is effectively a sea-level cut through terrain.  Keeping one water level removes
        // waterfall-like steps and also deletes the previous expensive centreline smoothing/straightness probes.
        int waterSurfaceY = profile.seaLevel();
        int maxCut = switch (field.profile) {
            case GREAT_RIVER -> 11;
            case PLAINS_RIVER -> 9;
            case SMALL_RIVER -> 7;
            case STREAM -> 5;
            default -> 7;
        };
        if (terrain.surfaceY() > waterSurfaceY + maxCut) {
            return none(terrain, 0.0D, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()));
        }

        double t = Mth.clamp(field.distance / Math.max(1.0D, waterRadius), 0.0D, 1.0D);
        double bowl = Math.max(0.12D, 1.0D - t * t);
        double roughness = M15Noise.fbm2D(context.seed() ^ RIVER_BED_SEED, x, z, 13, 2) * 0.9D;
        int waterDepth = Math.max(1, (int) Math.round(field.baseWaterDepth * bowl + roughness));
        int carveDepth = Math.max(waterDepth + 1, (int) Math.round(field.baseCarveDepth * (0.28D + 0.72D * bowl)));
        int valleyWidth = Math.max(field.width + 8, (int) Math.round(field.width * field.valleyScale));
        int bedY = Math.max(profile.minY() + 16, waterSurfaceY - waterDepth);
        return new M16WaterSample(x, z, terrain.surfaceY(), bedY, waterSurfaceY, waterSurfaceY - bedY, carveDepth, valleyWidth,
                0.0D, field.distance, field.distance, field.width,
                M16WaterType.RIVER, field.profile, field.profile == M16RiverProfile.GREAT_RIVER,
                false, false);
    }

    private static RiverField riverField(CubeGenerationContext context, int x, int z, M15TerrainSample terrain) {
        long key = sampleKey(context, x, z);
        LinkedHashMap<Long, RiverField> cache = RIVER_FIELD_CACHE.get();
        RiverField cached = cache.get(key);
        if (cached != null) {
            RuntimeProfiler.addCount("water.river_field_cache_hits", 1);
            return cached;
        }
        long start = RuntimeProfiler.markStart();
        RiverField field = riverFieldUncached(context, x, z, terrain);
        RuntimeProfiler.recordSince("water.river_field_uncached", start);
        RuntimeProfiler.addCount("water.river_field_uncached_calls", 1);
        cache.put(key, field);
        return field;
    }

    private static RiverField riverFieldUncached(CubeGenerationContext context, int x, int z, M15TerrainSample terrain) {
        M15WorldgenProfile world = M15TerrainModel.profile(context);
        double signedGreat = riverLineValue(context, true, x, z);
        double signedNormal = riverLineValue(context, false, x, z);
        double greatLine = Math.abs(signedGreat);
        double normalLine = Math.abs(signedNormal);
        double wet = M15Noise.smoothstep(-0.30D, 0.70D, terrain.humidity());
        int coarseX = Math.floorDiv(x, 512);
        int coarseZ = Math.floorDiv(z, 512);

        // M16.9: no mountain/canyon/waterfall river profiles.  Rivers are intentionally lowland, flat,
        // vanilla-like sea-level channels.  Width/depth still vary per coarse cell, but water level does not.
        boolean great = greatLine < 0.022D
                && terrain.continentalness() > -0.36D
                && terrain.surfaceY() <= world.seaLevel() + 24
                && wet > 0.18D;

        M16RiverProfile profile;
        int width;
        int depth;
        int carve;
        double distance;
        double valleyScale;
        double signedLine;
        double distanceScale;
        boolean greatLineOwner;
        if (great) {
            profile = M16RiverProfile.GREAT_RIVER;
            width = 36 + M15Noise.hashToRange(context.seed() ^ GREAT_RIVER_SEED, coarseX, 0, coarseZ, 42);
            depth = 4 + M15Noise.hashToRange(context.seed() ^ (GREAT_RIVER_SEED + 31L), coarseX, 0, coarseZ, 10);
            carve = depth + 3 + M15Noise.hashToRange(context.seed() ^ (GREAT_RIVER_SEED + 47L), coarseX, 0, coarseZ, 12);
            distance = greatLine * 1480.0D;
            valleyScale = 2.0D;
            signedLine = signedGreat;
            distanceScale = 1480.0D;
            greatLineOwner = true;
        } else {
            if (normalLine > 0.066D || terrain.continentalness() < -0.52D || wet < 0.18D || terrain.surfaceY() > world.seaLevel() + 32) {
                return new RiverField(M16RiverProfile.NONE, Integer.MAX_VALUE / 4, Double.POSITIVE_INFINITY, 0, 0, 1.0D,
                        0.0D, 620.0D, false);
            }
            int roll = M15Noise.hashToRange(context.seed() ^ RIVER_SEED, coarseX, 0, coarseZ, 100);
            if (roll < 16) {
                profile = M16RiverProfile.STREAM;
                width = 4 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 37L), coarseX, 0, coarseZ, 5);
                depth = 1 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 41L), coarseX, 0, coarseZ, 3);
                carve = depth + 2;
                valleyScale = 1.9D;
            } else if (roll < 50) {
                profile = M16RiverProfile.SMALL_RIVER;
                width = 8 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 43L), coarseX, 0, coarseZ, 13);
                depth = 2 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 53L), coarseX, 0, coarseZ, 6);
                carve = depth + 2 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 59L), coarseX, 0, coarseZ, 7);
                valleyScale = 2.15D;
            } else {
                profile = M16RiverProfile.PLAINS_RIVER;
                width = 16 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 61L), coarseX, 0, coarseZ, 26);
                depth = 3 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 67L), coarseX, 0, coarseZ, 9);
                carve = depth + 3 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 71L), coarseX, 0, coarseZ, 12);
                valleyScale = 2.55D;
            }
            distance = normalLine * 620.0D;
            signedLine = signedNormal;
            distanceScale = 620.0D;
            greatLineOwner = false;
        }
        return new RiverField(profile, width, distance, depth, carve, valleyScale, signedLine, distanceScale, greatLineOwner);
    }

    private static int smoothedRiverSurface(CubeGenerationContext context, int x, int z) {
        int total = M15TerrainModel.surfaceHeightDry(context, x, z) * 4;
        int weight = 4;
        int[] offsets = {24, 48, 72};
        for (int offset : offsets) {
            int w = offset == 24 ? 3 : offset == 48 ? 2 : 1;
            total += M15TerrainModel.surfaceHeightDry(context, x + offset, z) * w;
            total += M15TerrainModel.surfaceHeightDry(context, x - offset, z) * w;
            total += M15TerrainModel.surfaceHeightDry(context, x, z + offset) * w;
            total += M15TerrainModel.surfaceHeightDry(context, x, z - offset) * w;
            weight += w * 4;
        }
        return Math.round(total / (float) weight);
    }

    private static int quantizeRiverWaterSurface(CubeGenerationContext context, M16RiverProfile profile, int x, int z, int rawY, boolean straight) {
        int segment = switch (profile) {
            case GREAT_RIVER -> straight ? 112 : 176;
            case PLAINS_RIVER -> straight ? 80 : 144;
            case SMALL_RIVER -> straight ? 56 : 112;
            case STREAM, MOUNTAIN_RIVER -> straight ? 40 : 96;
            case CANYON_RIVER -> straight ? 56 : 128;
            case NONE -> 64;
        };
        int cellX = Math.floorDiv(x, segment);
        int cellZ = Math.floorDiv(z, segment);
        int localBias = straight ? M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 101L), cellX, rawY >> 3, cellZ, 3) - 1 : 0;
        int step = switch (profile) {
            case GREAT_RIVER, PLAINS_RIVER -> 1;
            case SMALL_RIVER, STREAM -> 2;
            case MOUNTAIN_RIVER, CANYON_RIVER -> straight ? 3 : 4;
            case NONE -> 1;
        };
        return Math.floorDiv(rawY + localBias, step) * step;
    }

    private static int bendSafeWaterSurface(CubeGenerationContext context, RiverField field, int centerX, int centerZ, int rawY) {
        int safe = quantizeRiverWaterSurface(context, field.profile, centerX, centerZ, rawY, false);
        int radius = Math.max(24, field.width);
        safe = Math.max(safe, quantizeRiverWaterSurface(context, field.profile, centerX + radius, centerZ, rawY, false));
        safe = Math.max(safe, quantizeRiverWaterSurface(context, field.profile, centerX - radius, centerZ, rawY, false));
        safe = Math.max(safe, quantizeRiverWaterSurface(context, field.profile, centerX, centerZ + radius, rawY, false));
        safe = Math.max(safe, quantizeRiverWaterSurface(context, field.profile, centerX, centerZ - radius, rawY, false));
        return safe;
    }

    private static double riverWaterRadius(RiverField field) {
        return switch (field.profile) {
            case GREAT_RIVER -> field.width * 0.43D;
            case CANYON_RIVER -> field.width * 0.38D;
            case MOUNTAIN_RIVER -> field.width * 0.40D;
            case STREAM -> field.width * 0.42D;
            case SMALL_RIVER, PLAINS_RIVER -> field.width * 0.44D;
            case NONE -> 0.0D;
        };
    }

    private static double riverLineValue(CubeGenerationContext context, boolean great, int x, int z) {
        return M15Noise.fbm2D(context.seed() ^ (great ? GREAT_RIVER_SEED : RIVER_SEED), x, z, great ? 2048 : 768, great ? 5 : 4);
    }

    private static int[] projectedRiverCenter(CubeGenerationContext context, RiverField field, int x, int z) {
        if (field.profile == M16RiverProfile.NONE || !Double.isFinite(field.distance) || field.distance <= 0.5D) {
            return new int[] {x, z};
        }
        int step = field.greatLine ? 32 : 16;
        double gx = riverLineValue(context, field.greatLine, x + step, z) - riverLineValue(context, field.greatLine, x - step, z);
        double gz = riverLineValue(context, field.greatLine, x, z + step) - riverLineValue(context, field.greatLine, x, z - step);
        double len = Math.sqrt(gx * gx + gz * gz);
        if (len < 1.0E-6D) {
            return new int[] {x, z};
        }
        double signedOffset = field.signedLine * field.distanceScale;
        int centerX = (int) Math.round(x - (gx / len) * signedOffset);
        int centerZ = (int) Math.round(z - (gz / len) * signedOffset);
        return new int[] {centerX, centerZ};
    }

    private static boolean isStraightRiverSegment(CubeGenerationContext context, RiverField field, int x, int z) {
        if (field.profile == M16RiverProfile.GREAT_RIVER || field.profile == M16RiverProfile.PLAINS_RIVER) {
            return true;
        }
        double a = riverTangentAngle(context, field, x, z);
        double b = riverTangentAngle(context, field, x + 48, z);
        double c = riverTangentAngle(context, field, x - 48, z);
        double d = riverTangentAngle(context, field, x, z + 48);
        double e = riverTangentAngle(context, field, x, z - 48);
        double diff = Math.max(Math.max(angleDiff(a, b), angleDiff(a, c)), Math.max(angleDiff(a, d), angleDiff(a, e)));
        return diff < 0.85D;
    }

    private static double riverTangentAngle(CubeGenerationContext context, RiverField field, int x, int z) {
        int step = field.greatLine ? 32 : 16;
        double gx = riverLineValue(context, field.greatLine, x + step, z) - riverLineValue(context, field.greatLine, x - step, z);
        double gz = riverLineValue(context, field.greatLine, x, z + step) - riverLineValue(context, field.greatLine, x, z - step);
        return Math.atan2(gx, -gz);
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % (Math.PI * 2.0D);
        return d > Math.PI ? Math.PI * 2.0D - d : d;
    }

    private static boolean waterfallsEnabled() {
        return false;
    }

    private static boolean isWaterfallCandidate(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain, M16WaterSample river) {
        // M16.4: disable procedural waterfalls until river sources/downstream pools get a strict 3D support pass.
        // This prevents floating water curtains on cliffs. Waterfalls return in the dedicated cleanup pass.
        if (!waterfallsEnabled()) {
            return false;
        }
        if (!river.isRiverLike() || river.waterType() != M16WaterType.RIVER) {
            return false;
        }
        if (river.riverProfile() != M16RiverProfile.MOUNTAIN_RIVER && river.riverProfile() != M16RiverProfile.CANYON_RIVER) {
            return false;
        }
        int x = terrain.x();
        int z = terrain.z();
        if (terrain.surfaceY() <= profile.seaLevel() + 24) {
            return false;
        }
        if (oceanSample(context, profile, M15TerrainModel.sampleDry(context, x + 48, z)).hasWater()
                || oceanSample(context, profile, M15TerrainModel.sampleDry(context, x - 48, z)).hasWater()
                || oceanSample(context, profile, M15TerrainModel.sampleDry(context, x, z + 48)).hasWater()
                || oceanSample(context, profile, M15TerrainModel.sampleDry(context, x, z - 48)).hasWater()) {
            return false;
        }
        int h1 = M15TerrainModel.surfaceHeightDry(context, x + 24, z);
        int h2 = M15TerrainModel.surfaceHeightDry(context, x - 24, z);
        int h3 = M15TerrainModel.surfaceHeightDry(context, x, z + 24);
        int h4 = M15TerrainModel.surfaceHeightDry(context, x, z - 24);
        int max = Math.max(Math.max(h1, h2), Math.max(h3, h4));
        int min = Math.min(Math.min(h1, h2), Math.min(h3, h4));
        int drop = max - min;
        if (drop < 18) {
            return false;
        }
        return M15Noise.hashToRange(context.seed() ^ WATERFALL_SEED, x >> 4, drop >> 2, z >> 4, 11) == 0;
    }

    private static M16WaterSample chooseBest(M16WaterSample ocean, M16WaterSample river, M16WaterSample lake) {
        if (ocean.hasWater()) {
            return ocean;
        }
        if (river.hasWater()) {
            return river;
        }
        if (lake.hasWater()) {
            return lake;
        }
        return ocean;
    }

    private static M16WaterSample none(M15TerrainSample terrain, double oceanMask, double shoreDistance) {
        return new M16WaterSample(terrain.x(), terrain.z(), terrain.surfaceY(), terrain.surfaceY(), Integer.MIN_VALUE, 0, 0, 0,
                oceanMask, shoreDistance, Double.POSITIVE_INFINITY, 0, M16WaterType.NONE, M16RiverProfile.NONE,
                false, false, false);
    }


    private static BlockState adjustedDryShoreState(M15TerrainSample terrain, M16WaterColumnShape shore, int y, BlockState baseState) {
        if (!shore.dryShoreActive()) {
            return baseState;
        }
        int currentSurfaceY = terrain.surfaceY();
        int adjustedSurfaceY = shore.dryShoreRaise() && shore.dryShoreSurfaceY() > currentSurfaceY
                ? shore.dryShoreSurfaceY()
                : Math.min(currentSurfaceY, shore.dryShoreSurfaceY());
        if (adjustedSurfaceY == currentSurfaceY) {
            return baseState;
        }
        if (y > adjustedSurfaceY) {
            return Blocks.AIR.defaultBlockState();
        }
        if (y == adjustedSurfaceY) {
            return shore.dryShoreTopState();
        }
        if (adjustedSurfaceY > currentSurfaceY && y > currentSurfaceY) {
            return shore.dryShoreSubState();
        }
        if (y >= adjustedSurfaceY - shore.dryShoreSoftDepth()) {
            return shore.dryShoreSubState();
        }
        return baseState;
    }

    private static ShoreTileHint shoreTileHint(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        int tileX = Math.floorDiv(terrain.x(), SHORE_TILE_SIZE);
        int tileZ = Math.floorDiv(terrain.z(), SHORE_TILE_SIZE);
        long key = sampleKey(context, tileX, tileZ) ^ 0x53484F524554494CL;
        LinkedHashMap<Long, ShoreTileHint> cache = SHORE_TILE_HINT_CACHE.get();
        ShoreTileHint cached = cache.get(key);
        if (cached != null) {
            RuntimeProfiler.addCount("water.shore_tile_hint_cache_hits", 1);
            return cached;
        }
        ShoreTileHint computed = computeShoreTileHint(context, profile, tileX, tileZ);
        cache.put(key, computed);
        RuntimeProfiler.addCount("water.shore_tile_hint_uncached", 1);
        return computed;
    }

    private static ShoreTileHint computeShoreTileHint(CubeGenerationContext context, M15WorldgenProfile profile, int tileX, int tileZ) {
        int centerX = tileX * SHORE_TILE_SIZE + SHORE_TILE_SIZE / 2;
        int centerZ = tileZ * SHORE_TILE_SIZE + SHORE_TILE_SIZE / 2;
        boolean mayRiver = false;
        boolean mayOcean = false;
        boolean mayLake = false;

        int[][] probes = {
                {0, 0}, {-8, -8}, {0, -8}, {8, -8}, {-8, 0}, {8, 0}, {-8, 8}, {0, 8}, {8, 8}
        };
        for (int[] probe : probes) {
            M15TerrainSample sample = M15TerrainModel.sampleDry(context, centerX + probe[0], centerZ + probe[1]);
            if (!mayRiver) {
                RiverField field = riverField(context, sample.x(), sample.z(), sample);
                if (field.profile != M16RiverProfile.NONE && Double.isFinite(field.distance)) {
                    int valleyWidth = Math.max(field.width + 12, (int) Math.round(field.width * Math.min(field.valleyScale, 2.05D)) + 12);
                    mayRiver = field.distance <= valleyWidth && sample.surfaceY() <= profile.seaLevel() + 18;
                }
            }
            if (!mayOcean && sample.surfaceY() <= profile.seaLevel() + 44) {
                mayOcean = rawOceanCandidate(context, profile, sample) || rawOceanMask(context, sample) > 0.32D;
            }
            if (mayRiver && mayOcean) {
                break;
            }
        }

        if (!mayOcean) {
            int[] offsets = {-64, -32, 0, 32, 64};
            outer:
            for (int dz : offsets) {
                for (int dx : offsets) {
                    M15TerrainSample sample = M15TerrainModel.sampleDry(context, centerX + dx, centerZ + dz);
                    if (sample.surfaceY() <= profile.seaLevel() + 6 && rawOceanCandidate(context, profile, sample)) {
                        mayOcean = true;
                        break outer;
                    }
                }
            }
        }

        mayLake = mayLakeShoreNearTile(context, profile, centerX, centerZ);
        if (!mayRiver && !mayOcean && !mayLake) {
            RuntimeProfiler.addCount("water.shore_tile_hint_negative", 1);
        }
        return new ShoreTileHint(mayRiver, mayOcean, mayLake);
    }

    private static boolean mayLakeShoreNearTile(CubeGenerationContext context, M15WorldgenProfile profile, int x, int z) {
        M15TerrainSample terrain = M15TerrainModel.sampleDry(context, x, z);
        if (terrain.surfaceY() <= profile.seaLevel() + 2 || terrain.surfaceY() > profile.highMountainStartY() + 128) {
            return false;
        }
        int cellX = Math.floorDiv(x, 256);
        int cellZ = Math.floorDiv(z, 256);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                if (M15Noise.hashToRange(context.seed() ^ LAKE_SEED, cx, 0, cz, 5) != 0) {
                    continue;
                }
                int centerX = cx * 256 + 48 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 11L), cx, 0, cz, 160);
                int centerZ = cz * 256 + 48 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 29L), cx, 0, cz, 160);
                int rx = 14 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 47L), cx, 0, cz, 82);
                int rz = 12 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 61L), cx, 0, cz, 76);
                double nx = (x - centerX) / (double) rx;
                double nz = (z - centerZ) / (double) rz;
                double dist = Math.sqrt(nx * nx + nz * nz);
                double maxRadius = Math.max(rx, rz);
                if (dist > 1.0D && (dist - 1.0D) * maxRadius <= Math.max(112.0D, maxRadius * 1.85D)) {
                    M15TerrainSample centerTerrain = M15TerrainModel.sampleDry(context, centerX, centerZ);
                    int lakeSurface = centerTerrain.surfaceY() - 1;
                    if (lakeSurface > profile.seaLevel() + 3 && lakeSurface <= terrain.surfaceY() + 12) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ShoreShape nearestShoreShape(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain, ShoreTileHint hint) {
        long key = sampleKey(context, terrain.x(), terrain.z());
        LinkedHashMap<Long, ShoreShape> cache = SHORE_SHAPE_CACHE.get();
        ShoreShape cached = cache.get(key);
        if (cached != null) {
            RuntimeProfiler.addCount("water.shore_shape_cache_hits", 1);
            return cached;
        }

        ShoreShape best = hint.mayRiver() ? directRiverValleyShape(context, profile, terrain) : ShoreShape.NONE;
        if (hint.mayOcean() && !best.active()) {
            ShoreShape ocean = directOceanShoreShape(context, profile, terrain);
            if (ocean.active()) {
                best = ocean;
            }
        }
        if (hint.mayLake() && (!best.active() || !best.raise())) {
            ShoreShape lake = directLakeShoreShape(context, profile, terrain);
            if (lake.active() && (!best.active() || betterShoreShape(terrain.surfaceY(), lake, best))) {
                best = lake;
            }
        }
        cache.put(key, best);
        return best;
    }

    private static ShoreShape directOceanShoreShape(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        int current = terrain.surfaceY();
        if (current > profile.seaLevel() + 56 || current < profile.seaLevel() - 28) {
            return ShoreShape.NONE;
        }
        int x = terrain.x();
        int z = terrain.z();
        int[] radii = {4, 8, 16, 32, 48, 64};
        ShoreShape best = ShoreShape.NONE;
        for (int r : radii) {
            int step = r <= 8 ? 8 : 16;
            for (int dz = -r; dz <= r; dz += step) {
                for (int dx = -r; dx <= r; dx += step) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    M15TerrainSample sample = M15TerrainModel.sampleDry(context, x + dx, z + dz);
                    if (!rawOceanCandidate(context, profile, sample) || sample.surfaceY() > profile.seaLevel() + 2) {
                        continue;
                    }
                    double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                    M16WaterSample pseudoOcean = new M16WaterSample(
                            terrain.x(), terrain.z(), terrain.surfaceY(), profile.seaLevel() - 2, profile.seaLevel(),
                            2, 2, 128, 1.0D, distance, Double.POSITIVE_INFINITY, 0,
                            M16WaterType.OCEAN, M16RiverProfile.NONE, false, false, false
                    );
                    ShoreShape candidate = shoreShapeFor(context, profile, terrain, pseudoOcean, distance);
                    if (candidate.active() && (!best.active() || betterShoreShape(current, candidate, best))) {
                        best = candidate;
                    }
                }
            }
            if (best.active()) {
                RuntimeProfiler.addCount("water.direct_ocean_shore_hits", 1);
                return best;
            }
        }
        return best;
    }

    private static ShoreShape directLakeShoreShape(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        if (terrain.surfaceY() <= profile.seaLevel() + 2 || terrain.surfaceY() > profile.highMountainStartY() + 120) {
            return ShoreShape.NONE;
        }
        int x = terrain.x();
        int z = terrain.z();
        int cellX = Math.floorDiv(x, 256);
        int cellZ = Math.floorDiv(z, 256);
        ShoreShape best = ShoreShape.NONE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                if (M15Noise.hashToRange(context.seed() ^ LAKE_SEED, cx, 0, cz, 5) != 0) {
                    continue;
                }
                int centerX = cx * 256 + 48 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 11L), cx, 0, cz, 160);
                int centerZ = cz * 256 + 48 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 29L), cx, 0, cz, 160);
                int rx = 14 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 47L), cx, 0, cz, 82);
                int rz = 12 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 61L), cx, 0, cz, 76);
                double nx = (x - centerX) / (double) rx;
                double nz = (z - centerZ) / (double) rz;
                double ellipseDist = nx * nx + nz * nz;
                double lobes = M15Noise.fbm2D(context.seed() ^ LAKE_SHAPE_SEED, x, z, 48, 3) * 0.18D
                        + M15Noise.fbm2D(context.seed() ^ (LAKE_SHAPE_SEED + 19L), x + centerX, z - centerZ, 21, 2) * 0.08D;
                double edgeScale = Mth.clamp(1.0D + lobes, 0.72D, 1.28D);
                double dist = ellipseDist / (edgeScale * edgeScale);
                double maxRadius = Math.max(rx, rz);
                double approach = Math.max(96.0D, maxRadius * 1.65D);
                if (dist <= 1.0D || (Math.sqrt(dist) - 1.0D) * maxRadius > approach) {
                    continue;
                }
                M15TerrainSample centerTerrain = M15TerrainModel.sampleDry(context, centerX, centerZ);
                int lakeSurface = centerTerrain.surfaceY() - 1;
                if (lakeSurface <= profile.seaLevel() + 3 || lakeSurface > terrain.surfaceY() + 10) {
                    continue;
                }
                if (!lakeBasinLooksValid(context, profile, centerX, centerZ, rx, rz, lakeSurface)) {
                    continue;
                }
                double distance = Math.max(1.0D, (Math.sqrt(dist) - 1.0D) * maxRadius);
                M16WaterSample pseudoLake = new M16WaterSample(
                        terrain.x(), terrain.z(), terrain.surfaceY(), lakeSurface - 3, lakeSurface,
                        3, 4, (int) Math.round(maxRadius), 0.0D, distance, Double.POSITIVE_INFINITY, 0,
                        M16WaterType.LAKE, M16RiverProfile.NONE, false, false, false
                );
                ShoreShape candidate = shoreShapeFor(context, profile, terrain, pseudoLake, distance);
                if (candidate.active() && (!best.active() || betterShoreShape(terrain.surfaceY(), candidate, best))) {
                    best = candidate;
                }
            }
        }
        if (best.active()) {
            RuntimeProfiler.addCount("water.direct_lake_shore_hits", 1);
        }
        return best;
    }

    private static ShoreShape shoreShapeFor(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain, M16WaterSample water, double distance) {
        int waterLevel = water.waterSurfaceY();
        int current = terrain.surfaceY();
        switch (water.waterType()) {
            case OCEAN -> {
                // M16.9: make the immediate beach actually meet the water.  The previous formula always added at
                // least one block of rise, so coastlines often formed a sharp two-block sand wall.  Here the first
                // few blocks are flat at water level, then the land ramps up with an eased slope.
                double approach = 128.0D;
                if (distance <= approach && current <= waterLevel + 120) {
                    double flat = 6.0D + M15Noise.hashToRange(0x42454143484C564CL, terrain.x() >> 4, waterLevel, terrain.z() >> 4, 7);
                    int target;
                    if (distance <= flat) {
                        target = waterLevel;
                    } else if (distance <= flat + 10.0D) {
                        target = waterLevel + 1;
                    } else {
                        double n = Mth.clamp((distance - flat - 10.0D) / Math.max(1.0D, approach - flat - 10.0D), 0.0D, 1.0D);
                        double eased = n * n * (3.0D - 2.0D * n);
                        target = waterLevel + 1 + (int) Math.round(eased * 38.0D);
                    }
                    if (target < current - 1) {
                        return new ShoreShape(true, target, 9, false, SAND, SANDSTONE);
                    }
                    if (distance <= flat + 4.0D && current < waterLevel) {
                        return new ShoreShape(true, waterLevel, 6, true, SAND, SANDSTONE);
                    }
                }
            }
            case LAKE -> {
                double approach = Math.max(96.0D, water.valleyWidth() * 1.65D);
                if (distance <= approach) {
                    boolean low = current <= profile.seaLevel() + 12;
                    boolean sandyPatch = low || M15Noise.hashToRange(context == null ? LAKE_SHORE_SEED : context.seed() ^ LAKE_SHORE_SEED,
                            Math.floorDiv(terrain.x(), 9), waterLevel, Math.floorDiv(terrain.z(), 9), 100) < 38;
                    double flat = 3.0D + M15Noise.hashToRange(context == null ? 0x4C414B4542454143L : context.seed() ^ 0x4C414B4542454143L,
                            terrain.x() >> 4, waterLevel, terrain.z() >> 4, 6);
                    int target;
                    if (distance <= flat) {
                        target = waterLevel;
                    } else if (distance <= flat + 8.0D) {
                        target = waterLevel + 1;
                    } else {
                        double n = Mth.clamp((distance - flat - 8.0D) / Math.max(1.0D, approach - flat - 8.0D), 0.0D, 1.0D);
                        double eased = n * n * (3.0D - 2.0D * n);
                        target = waterLevel + 1 + (int) Math.round(eased * 32.0D);
                    }
                    if (target < current - 1) {
                        return new ShoreShape(true, target, 7, false, sandyPatch ? SAND : DIRT, sandyPatch ? SANDSTONE : DIRT);
                    }
                    if (distance <= flat + 4.0D && current < waterLevel) {
                        return new ShoreShape(true, waterLevel, 5, true, sandyPatch ? SAND : DIRT, sandyPatch ? SANDSTONE : DIRT);
                    }
                }
            }
            case RIVER -> {
                return riverShoreShape(profile, terrain, water, distance);
            }
            default -> {
                return ShoreShape.NONE;
            }
        }
        return ShoreShape.NONE;
    }

    private static ShoreShape directRiverValleyShape(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        RiverField field = riverField(context, terrain.x(), terrain.z(), terrain);
        if (field.profile == M16RiverProfile.NONE || !Double.isFinite(field.distance)) {
            return ShoreShape.NONE;
        }
        double waterRadius = riverWaterRadius(field);
        int valleyWidth = Math.max(field.width + 8, (int) Math.round(field.width * Math.min(field.valleyScale, 1.85D)));
        if (field.distance <= waterRadius || field.distance > valleyWidth) {
            return ShoreShape.NONE;
        }
        int waterLevel = profile.seaLevel();
        if (terrain.surfaceY() > waterLevel + 10) {
            return ShoreShape.NONE;
        }
        double n = Mth.clamp((field.distance - waterRadius) / Math.max(1.0D, valleyWidth - waterRadius), 0.0D, 1.0D);
        int rise = riverBankRise(field.profile, n);
        int target = waterLevel + rise;
        boolean sandy = field.profile == M16RiverProfile.PLAINS_RIVER || field.profile == M16RiverProfile.GREAT_RIVER;
        if (target < terrain.surfaceY() - 1) {
            return new ShoreShape(true, target, 4, false, sandy ? SAND : DIRT, sandy ? SANDSTONE : DIRT);
        }
        if (field.distance <= waterRadius + 5.0D && terrain.surfaceY() < waterLevel) {
            return new ShoreShape(true, waterLevel, 4, true, sandy ? SAND : DIRT, sandy ? SANDSTONE : DIRT);
        }
        return ShoreShape.NONE;
    }

    private static ShoreShape riverShoreShape(M15WorldgenProfile profile, M15TerrainSample terrain, M16WaterSample water, double distance) {
        int current = terrain.surfaceY();
        int waterLevel = water.waterSurfaceY();
        double bankWidth = Math.max(28.0D, water.valleyWidth() * 0.90D);
        if (distance > bankWidth) {
            return ShoreShape.NONE;
        }
        double waterRadius = riverWaterRadius(new RiverField(water.riverProfile(), water.riverWidth(), water.riverDistance(), water.waterDepth(), water.carveDepth(),
                Math.max(1.0D, water.valleyWidth() / Math.max(1.0D, (double) water.riverWidth())), 0.0D, 1.0D, water.greatRiver()));
        double n = Mth.clamp((distance - waterRadius) / Math.max(1.0D, bankWidth - waterRadius), 0.0D, 1.0D);
        int target = waterLevel + riverBankRise(water.riverProfile(), n);
        boolean sandy = water.riverProfile() == M16RiverProfile.PLAINS_RIVER || water.riverProfile() == M16RiverProfile.GREAT_RIVER;
        if (target < current - 1) {
            return new ShoreShape(true, target, 5, false, sandy ? SAND : DIRT, sandy ? SANDSTONE : DIRT);
        }
        if (distance <= Math.max(6.0D, water.riverWidth() * 0.18D) && current < waterLevel) {
            return new ShoreShape(true, waterLevel, 6, true, sandy ? SAND : DIRT, sandy ? SANDSTONE : DIRT);
        }
        return ShoreShape.NONE;
    }

    private static int riverBankRise(M16RiverProfile profile, double n) {
        double eased = n * n * (3.0D - 2.0D * n);
        int maxRise = switch (profile) {
            case GREAT_RIVER -> 7;
            case PLAINS_RIVER -> 6;
            case SMALL_RIVER -> 5;
            case STREAM -> 3;
            default -> 5;
        };
        if (n < 0.18D) {
            return 0;
        }
        return Math.max(1, (int) Math.round(eased * maxRise));
    }

    private static boolean betterShoreShape(int currentSurfaceY, ShoreShape candidate, ShoreShape previous) {
        if (candidate.raise() && candidate.surfaceY() > currentSurfaceY) {
            return !previous.raise() || candidate.surfaceY() > previous.surfaceY();
        }
        if (previous.raise() && previous.surfaceY() > currentSurfaceY) {
            return false;
        }
        return candidate.surfaceY() < previous.surfaceY();
    }

    private static double rawOceanMask(CubeGenerationContext context, M15TerrainSample terrain) {
        double oceanNoise = M15Noise.fbm2D(context.seed() ^ OCEAN_SEED, terrain.x(), terrain.z(), 2048, 4);
        return M15Noise.smoothstep(-0.18D, 0.34D, -(terrain.continentalness() + oceanNoise * 0.25D));
    }

    private static boolean rawOceanCandidate(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        double oceanMask = rawOceanMask(context, terrain);
        return (terrain.surfaceY() <= profile.seaLevel() - 3 && oceanMask > 0.50D)
                || (terrain.surfaceY() <= profile.seaLevel() + 1 && oceanMask > 0.38D);
    }

    private static double oceanShoreDistance(CubeGenerationContext context, M15WorldgenProfile profile, int x, int z) {
        int cellX = Math.floorDiv(x, 8);
        int cellZ = Math.floorDiv(z, 8);
        long key = sampleKey(context, cellX, cellZ);
        LinkedHashMap<Long, Double> cache = OCEAN_SHORE_CACHE.get();
        Double cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // Deep ocean has no nearby beach to resolve, so do not walk the whole ring scanner just to return max range.
        M15TerrainSample center = M15TerrainModel.sampleDry(context, x, z);
        double centerMask = rawOceanMask(context, center);
        if (center.surfaceY() <= profile.seaLevel() - 14 && centerMask > 0.72D) {
            cache.put(key, 160.0D);
            return 160.0D;
        }

        long start = RuntimeProfiler.markStart();
        double result = oceanShoreDistanceUncached(context, profile, x, z);
        RuntimeProfiler.recordSince("water.ocean_shore_distance", start);
        RuntimeProfiler.addCount("water.ocean_shore_scans", 1);
        cache.put(key, result);
        return result;
    }

    private static double oceanShoreDistanceUncached(CubeGenerationContext context, M15WorldgenProfile profile, int x, int z) {
        int[] radii = {4, 8, 16, 32, 48, 64, 96};
        for (int r : radii) {
            int step = r <= 8 ? 4 : r <= 32 ? 8 : 16;
            for (int dz = -r; dz <= r; dz += step) {
                for (int dx = -r; dx <= r; dx += step) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    M15TerrainSample sample = M15TerrainModel.sampleDry(context, x + dx, z + dz);
                    if (!rawOceanCandidate(context, profile, sample) || sample.surfaceY() > profile.seaLevel() + 2) {
                        return Math.sqrt((double) dx * dx + (double) dz * dz);
                    }
                }
            }
        }
        return 160.0D;
    }

    private static boolean nearStrongOceanCell(CubeGenerationContext context, M15WorldgenProfile profile, int x, int z) {
        int[] offsets = {-32, -16, 0, 16, 32};
        for (int dz : offsets) {
            for (int dx : offsets) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                M15TerrainSample sample = M15TerrainModel.sampleDry(context, x + dx, z + dz);
                double oceanNoise = M15Noise.fbm2D(context.seed() ^ OCEAN_SEED, x + dx, z + dz, 2048, 4);
                double oceanMask = M15Noise.smoothstep(-0.18D, 0.34D, -(sample.continentalness() + oceanNoise * 0.25D));
                if (sample.surfaceY() <= profile.seaLevel() - 4 && oceanMask > 0.55D) {
                    return true;
                }
            }
        }
        return false;
    }

    private record ShoreTileHint(boolean mayRiver, boolean mayOcean, boolean mayLake) {
        private boolean mayHaveShore() {
            return mayRiver || mayOcean || mayLake;
        }
    }

    private record ShoreShape(boolean active, int surfaceY, int softDepth, boolean raise, BlockState topState, BlockState subState) {
        private static final ShoreShape NONE = new ShoreShape(false, Integer.MAX_VALUE, 0, false, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
    }

    private static BlockState waterState(M16WaterSample sample, int y) {
        if (sample.waterType() == M16WaterType.WATERFALL) {
            return Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock();
        }
        // M16.5 generated water bodies are stable geometry. The global M16.2 fluid override still changes real
        // water behaviour after player/block interaction, but the generator itself no longer seeds flowing edge
        // states that can escape the reserved river corridor during post-materialize ticks.
        return WATER;
    }

    private static BlockState bedState(M16WaterSample sample, int y) {
        return switch (sample.waterType()) {
            case OCEAN -> sample.waterDepth() <= 3 ? SAND : SANDSTONE;
            case LAKE -> lakeBedState(sample);
            case RIVER -> riverBedState(sample);
            case WATERFALL -> STONE;
            case NONE -> Blocks.AIR.defaultBlockState();
        };
    }

    private static BlockState lakeBedState(M16WaterSample sample) {
        int roll = M15Noise.hashToRange(LAKE_SHAPE_SEED, Math.floorDiv(sample.x(), 5), sample.waterDepth(), Math.floorDiv(sample.z(), 5), 100);
        if (sample.waterDepth() <= 2) {
            return roll < 70 ? SAND : GRAVEL;
        }
        if (roll < 34) {
            return SAND;
        }
        if (roll < 58) {
            return GRAVEL;
        }
        if (roll < 72) {
            return CLAY;
        }
        return SANDSTONE;
    }

    private static BlockState supportState(M16WaterSample sample, int y) {
        return switch (sample.waterType()) {
            case OCEAN, LAKE -> SANDSTONE;
            case RIVER -> sample.riverProfile() == M16RiverProfile.PLAINS_RIVER || sample.riverProfile() == M16RiverProfile.GREAT_RIVER ? SANDSTONE : STONE;
            case WATERFALL -> STONE;
            case NONE -> DIRT;
        };
    }

    private static BlockState riverBedState(M16WaterSample sample) {
        int roll = M15Noise.hashToRange(RIVER_BED_SEED, Math.floorDiv(sample.x(), 4), sample.waterDepth(), Math.floorDiv(sample.z(), 4), 100);
        return switch (sample.riverProfile()) {
            case GREAT_RIVER, PLAINS_RIVER -> roll < 64 ? SAND : roll < 86 ? GRAVEL : CLAY;
            case SMALL_RIVER, STREAM -> roll < 45 ? SAND : roll < 88 ? GRAVEL : CLAY;
            case MOUNTAIN_RIVER, CANYON_RIVER -> roll < 70 ? STONE : GRAVEL;
            case NONE -> GRAVEL;
        };
    }

    private static int supportDepth(M16WaterSample sample) {
        return switch (sample.waterType()) {
            case OCEAN, LAKE -> 6;
            case RIVER -> 5;
            case WATERFALL -> 8;
            case NONE -> 0;
        };
    }

    private static int infillDepth(M16WaterSample sample) {
        return switch (sample.waterType()) {
            case OCEAN -> 24;
            case LAKE -> 32;
            case RIVER -> Math.max(12, Math.min(48, sample.carveDepth() + 6));
            case WATERFALL -> 64;
            case NONE -> 0;
        };
    }

    private static boolean matches(M16WaterSample sample, M16WaterType type) {
        if (type == M16WaterType.WATERFALL) {
            return sample.waterType() == M16WaterType.WATERFALL;
        }
        return sample.waterType() == type;
    }

    private static M16WaterSample consider(CubeGenerationContext context, M16WaterType type, int x, int z, int centerX, int centerZ, M16WaterSample best, int bestDistance) {
        M16WaterSample sample = sample(context, x, z);
        if (!matches(sample, type)) {
            return best;
        }
        int distance = distance(centerX, centerZ, x, z);
        if (best == null || distance < bestDistance) {
            return sample;
        }
        return best;
    }

    private static int distance(int x1, int z1, int x2, int z2) {
        int dx = x1 - x2;
        int dz = z1 - z2;
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    private record RiverField(M16RiverProfile profile, int width, double distance, int baseWaterDepth, int baseCarveDepth, double valleyScale,
                              double signedLine, double distanceScale, boolean greatLine) {
    }

    public record LocateResult(boolean found, M16WaterSample sample, int distanceBlocks) {
        public String oneLine() {
            if (!found || sample == null) {
                return "found=false, distance=" + distanceBlocks;
            }
            return "found=true, distance=" + distanceBlocks + ", " + sample.oneLine();
        }
    }

    private M16WaterModel() {
    }
}
