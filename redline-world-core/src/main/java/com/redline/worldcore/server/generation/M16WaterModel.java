package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.generation.CubeGenerationContext;
import net.minecraft.util.Mth;
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
    public static final String VERSION = "M16.6 hydrology landform integration: ocean shelf + lake rim + river valley rewrite v1";

    private static final long OCEAN_SEED = 0x4F4345414E4D4150L;
    private static final long RIVER_SEED = 0x5249564552533031L;
    private static final long GREAT_RIVER_SEED = 0x4752454154524956L;
    private static final long LAKE_SEED = 0x4C414B45534D3136L;
    private static final long WATERFALL_SEED = 0x574154455246414CL;

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    public static M16WaterSample sample(CubeGenerationContext context, M15TerrainSample terrain) {
        M15WorldgenProfile profile = M15TerrainModel.profile(context);
        int x = terrain.x();
        int z = terrain.z();
        int sea = profile.seaLevel();
        int drySurfaceY = terrain.surfaceY();

        M16WaterSample ocean = oceanSample(context, profile, terrain);
        // M16.1 ownership: oceans own their volume first. Rivers may lead into oceans later, but they must not
        // rasterize a separate river/lake/waterfall body inside an ocean cell.
        if (ocean.hasWater()) {
            return ocean;
        }

        M16WaterSample river = riverSample(context, profile, terrain);
        M16WaterSample lake = lakeSample(context, profile, terrain, river);

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

    public static M16WaterSample sample(CubeGenerationContext context, int x, int z) {
        return sample(context, M15TerrainModel.sampleDry(context, x, z));
    }

    public static BlockState overrideState(CubeGenerationContext context, M15TerrainSample terrain, int y, BlockState baseState) {
        M16WaterSample water = sample(context, terrain);
        if (!water.hasWater()) {
            return adjustedDryShoreState(context, terrain, y, baseState);
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
        int baseX = cubeX << 4;
        int baseZ = cubeZ << 4;
        for (int dz = 0; dz <= 16; dz += 8) {
            for (int dx = 0; dx <= 16; dx += 8) {
                M16WaterSample sample = sample(context, baseX + Math.min(dx, 15), baseZ + Math.min(dz, 15));
                if (sample.hasWater() && maxY >= sample.effectiveSurfaceY() && minY <= sample.waterSurfaceY()) {
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
        double shoreN = Mth.clamp(shoreDistance / 128.0D, 0.0D, 1.0D);
        int shelfDepth = 1 + (int) Math.round(shoreN * shoreN * 30.0D);
        int deepDepth = 6 + (int) Math.round(oceanMask * 24.0D)
                + M15Noise.hashToRange(context.seed() ^ OCEAN_SEED, x >> 4, 0, z >> 4, 8);
        int depth = Mth.clamp(Math.min(deepDepth, shelfDepth + (int) Math.round(oceanMask * 8.0D)), 1, 48);
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
                int rx = 18 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 47L), cx, 0, cz, 44);
                int rz = 18 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 61L), cx, 0, cz, 44);
                double nx = (x - centerX) / (double) rx;
                double nz = (z - centerZ) / (double) rz;
                double dist = nx * nx + nz * nz;
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
                int maxUnsupportedDrop = edge > 0.72D ? 2 : edge > 0.45D ? 6 : 14;
                if (lakeSurface - terrain.surfaceY() > maxUnsupportedDrop) {
                    // Do not let a lake spill as a floating slab over an open valley/cliff.  If a basin cannot be
                    // supported by the local terrain, this cell is not owned by the lake.
                    continue;
                }
                double strength = 1.0D - dist;
                int waterDepth = 2 + (int) Math.round(strength * (4 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 79L), cx, 0, cz, 7)));
                int carveDepth = waterDepth + 1 + (int) Math.round(strength * 5.0D);
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

    private static M16WaterSample riverSample(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        int x = terrain.x();
        int z = terrain.z();
        RiverField field = riverField(context, x, z, terrain);
        double waterRadius = riverWaterRadius(field);
        if (field.distance > waterRadius) {
            return none(terrain, 0.0D, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()));
        }

        double t = Mth.clamp(field.distance / Math.max(1.0D, waterRadius), 0.0D, 1.0D);
        // Bowl-shaped river cross-section: deep in the center, shallow on the edges. This avoids vertical canal walls.
        double bowl = Math.max(0.12D, 1.0D - t * t);
        int waterDepth = Math.max(1, (int) Math.round(field.baseWaterDepth * bowl));
        int carveDepth = Math.max(waterDepth + 1, (int) Math.round(field.baseCarveDepth * (0.28D + 0.72D * bowl)));
        int valleyWidth = Math.max(field.width + 10, (int) Math.round(field.width * field.valleyScale));

        int[] center = projectedRiverCenter(context, field, x, z);
        int centerX = center[0];
        int centerZ = center[1];
        int smoothedSurface = smoothedRiverSurface(context, centerX, centerZ);
        int rawWaterSurfaceY = smoothedSurface - Math.max(1, carveDepth - waterDepth);
        boolean straight = isStraightRiverSegment(context, field, centerX, centerZ);
        int waterSurfaceY = quantizeRiverWaterSurface(context, field.profile, centerX, centerZ, rawWaterSurfaceY, straight);
        if (!straight) {
            // A bend must not be a local waterfall. Hold its level close to neighbouring straight runs.
            waterSurfaceY = Math.max(waterSurfaceY, bendSafeWaterSurface(context, field, centerX, centerZ, rawWaterSurfaceY));
        }
        if (field.profile == M16RiverProfile.GREAT_RIVER && terrain.surfaceY() < profile.seaLevel() + 16) {
            waterSurfaceY = Math.min(profile.seaLevel(), waterSurfaceY);
        }
        // Keep river levels tied to the projected centreline.  Do not clamp every edge cell to its dry terrain:
        // that was the main reason rivers had water only on the sides or punctured centres.  If a local cell is
        // lower than the river bed, the terrain integration pass will infill the bed; if it is higher, it is carved.
        waterSurfaceY = Mth.clamp(waterSurfaceY, profile.minY() + 16, profile.highestSurfaceY() + 16);
        int targetBedY = Math.max(profile.minY() + 16, waterSurfaceY - waterDepth);
        int bedY = targetBedY;
        return new M16WaterSample(x, z, terrain.surfaceY(), bedY, waterSurfaceY, waterSurfaceY - bedY, carveDepth, valleyWidth,
                0.0D, field.distance, field.distance, field.width,
                M16WaterType.RIVER, field.profile, field.profile == M16RiverProfile.GREAT_RIVER,
                field.profile == M16RiverProfile.CANYON_RIVER, false);
    }

    private static RiverField riverField(CubeGenerationContext context, int x, int z, M15TerrainSample terrain) {
        double signedGreat = riverLineValue(context, true, x, z);
        double signedNormal = riverLineValue(context, false, x, z);
        double greatLine = Math.abs(signedGreat);
        double normalLine = Math.abs(signedNormal);
        double mountain = M15Noise.smoothstep(0.35D, 0.82D, terrain.ridge() + Math.max(0.0D, terrain.continentalness()) * 0.25D);
        double wet = M15Noise.smoothstep(-0.25D, 0.75D, terrain.humidity());
        int coarseX = Math.floorDiv(x, 512);
        int coarseZ = Math.floorDiv(z, 512);
        boolean great = greatLine < 0.030D && terrain.continentalness() > -0.38D;
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
            width = 64 + M15Noise.hashToRange(context.seed() ^ GREAT_RIVER_SEED, coarseX, 0, coarseZ, 57);
            depth = 12 + M15Noise.hashToRange(context.seed() ^ (GREAT_RIVER_SEED + 31L), coarseX, 0, coarseZ, 21);
            carve = depth + 8 + M15Noise.hashToRange(context.seed() ^ (GREAT_RIVER_SEED + 47L), coarseX, 0, coarseZ, 32);
            distance = greatLine * 1500.0D;
            valleyScale = 2.8D;
            signedLine = signedGreat;
            distanceScale = 1500.0D;
            greatLineOwner = true;
        } else {
            if (normalLine > 0.070D || terrain.continentalness() < -0.52D || wet < 0.20D) {
                return new RiverField(M16RiverProfile.NONE, Integer.MAX_VALUE / 4, Double.POSITIVE_INFINITY, 0, 0, 1.0D,
                        0.0D, 620.0D, false);
            }
            int roll = M15Noise.hashToRange(context.seed() ^ RIVER_SEED, coarseX, 0, coarseZ, 100);
            if (mountain > 0.72D && roll < 34) {
                profile = M16RiverProfile.CANYON_RIVER;
                width = 16 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 13L), coarseX, 0, coarseZ, 35);
                depth = 8 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 17L), coarseX, 0, coarseZ, 13);
                carve = 32 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 19L), coarseX, 0, coarseZ, 96);
                valleyScale = 4.2D;
            } else if (mountain > 0.55D) {
                profile = M16RiverProfile.MOUNTAIN_RIVER;
                width = 8 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 23L), coarseX, 0, coarseZ, 18);
                depth = 4 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 29L), coarseX, 0, coarseZ, 8);
                carve = depth + 4 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 31L), coarseX, 0, coarseZ, 18);
                valleyScale = 2.6D;
            } else if (roll < 12) {
                profile = M16RiverProfile.STREAM;
                width = 3 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 37L), coarseX, 0, coarseZ, 4);
                depth = 1 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 41L), coarseX, 0, coarseZ, 2);
                carve = depth + 1;
                valleyScale = 1.8D;
            } else if (roll < 42) {
                profile = M16RiverProfile.SMALL_RIVER;
                width = 6 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 43L), coarseX, 0, coarseZ, 9);
                depth = 2 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 53L), coarseX, 0, coarseZ, 5);
                carve = depth + 2 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 59L), coarseX, 0, coarseZ, 8);
                valleyScale = 2.2D;
            } else {
                profile = M16RiverProfile.PLAINS_RIVER;
                width = 14 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 61L), coarseX, 0, coarseZ, 24);
                depth = 4 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 67L), coarseX, 0, coarseZ, 11);
                carve = depth + 3 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 71L), coarseX, 0, coarseZ, 16);
                valleyScale = 2.8D;
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
            case GREAT_RIVER -> field.width * 0.50D;
            case CANYON_RIVER -> field.width * 0.43D;
            case MOUNTAIN_RIVER -> field.width * 0.44D;
            case STREAM -> field.width * 0.46D;
            case SMALL_RIVER, PLAINS_RIVER -> field.width * 0.48D;
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


    private static BlockState adjustedDryShoreState(CubeGenerationContext context, M15TerrainSample terrain, int y, BlockState baseState) {
        M15WorldgenProfile profile = M15TerrainModel.profile(context);
        ShoreShape shore = nearestShoreShape(context, profile, terrain);
        if (!shore.active()) {
            return baseState;
        }
        int currentSurfaceY = terrain.surfaceY();
        int adjustedSurfaceY = shore.raise() && shore.surfaceY() > currentSurfaceY
                ? shore.surfaceY()
                : Math.min(currentSurfaceY, shore.surfaceY());
        if (adjustedSurfaceY == currentSurfaceY) {
            return baseState;
        }
        if (y > adjustedSurfaceY) {
            return Blocks.AIR.defaultBlockState();
        }
        if (y == adjustedSurfaceY) {
            return shore.topState();
        }
        if (adjustedSurfaceY > currentSurfaceY && y > currentSurfaceY) {
            return shore.subState();
        }
        if (y >= adjustedSurfaceY - shore.softDepth()) {
            return shore.subState();
        }
        return baseState;
    }

    private static ShoreShape nearestShoreShape(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        ShoreShape best = directRiverValleyShape(context, profile, terrain);
        int x = terrain.x();
        int z = terrain.z();
        int[] radii = {4, 8, 12, 16, 24, 32, 48, 64, 96};
        for (int r : radii) {
            int step = r <= 16 ? 8 : 16;
            for (int dz = -r; dz <= r; dz += step) {
                for (int dx = -r; dx <= r; dx += step) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    M16WaterSample sample = sample(context, x + dx, z + dz);
                    if (!sample.hasWater()) {
                        continue;
                    }
                    double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                    ShoreShape candidate = shoreShapeFor(profile, terrain, sample, distance);
                    if (candidate.active() && (!best.active() || betterShoreShape(terrain.surfaceY(), candidate, best))) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static ShoreShape shoreShapeFor(M15WorldgenProfile profile, M15TerrainSample terrain, M16WaterSample water, double distance) {
        int waterLevel = water.waterSurfaceY();
        int current = terrain.surfaceY();
        switch (water.waterType()) {
            case OCEAN -> {
                // Dry land near the sea becomes: beach -> wet beach/shelf -> normal land.  This is intentionally
                // distance-based so the final coastline is a walkable slope instead of a vertical cut into water.
                double approach = 128.0D;
                if (distance <= approach && current <= waterLevel + 128) {
                    double n = Mth.clamp(distance / approach, 0.0D, 1.0D);
                    int rise = 1 + (int) Math.round(n * n * 54.0D + n * 10.0D);
                    int target = waterLevel + rise;
                    if (target < current - 1) {
                        return new ShoreShape(true, target, 6, false, SAND, SANDSTONE);
                    }
                }
            }
            case LAKE -> {
                double approach = Math.max(96.0D, water.valleyWidth() * 1.65D);
                if (distance <= approach) {
                    double n = Mth.clamp(distance / approach, 0.0D, 1.0D);
                    int rise = 1 + (int) Math.round(n * n * 34.0D + n * 8.0D);
                    int target = waterLevel + rise;
                    boolean low = current <= profile.seaLevel() + 12;
                    if (target < current - 1) {
                        return new ShoreShape(true, target, 6, false, low ? SAND : DIRT, low ? SANDSTONE : DIRT);
                    }
                    if (distance <= approach * 0.42D && current < waterLevel + 1) {
                        return new ShoreShape(true, waterLevel + 1, 5, true, low ? SAND : DIRT, low ? SANDSTONE : DIRT);
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
        int valleyWidth = Math.max(field.width + 12, (int) Math.round(field.width * field.valleyScale));
        if (field.distance <= waterRadius || field.distance > valleyWidth) {
            return ShoreShape.NONE;
        }
        int[] center = projectedRiverCenter(context, field, terrain.x(), terrain.z());
        int centerSurface = smoothedRiverSurface(context, center[0], center[1]);
        int waterDepth = Math.max(1, field.baseWaterDepth);
        int carveDepth = Math.max(waterDepth + 1, field.baseCarveDepth);
        int rawWaterSurfaceY = centerSurface - Math.max(1, carveDepth - waterDepth);
        boolean straight = isStraightRiverSegment(context, field, center[0], center[1]);
        int waterLevel = quantizeRiverWaterSurface(context, field.profile, center[0], center[1], rawWaterSurfaceY, straight);
        double n = Mth.clamp((field.distance - waterRadius) / Math.max(1.0D, valleyWidth - waterRadius), 0.0D, 1.0D);
        int rise = 1 + (int) Math.round(n * n * Math.max(12.0D, field.baseCarveDepth * 0.75D) + n * 6.0D);
        int target = waterLevel + rise;
        boolean sandy = field.profile == M16RiverProfile.PLAINS_RIVER || field.profile == M16RiverProfile.GREAT_RIVER;
        if (target < terrain.surfaceY() - 1) {
            return new ShoreShape(true, target, 6, false, sandy ? SAND : DIRT, sandy ? SANDSTONE : DIRT);
        }
        if (field.distance <= waterRadius + 4.0D && terrain.surfaceY() < waterLevel + 1) {
            return new ShoreShape(true, waterLevel + 1, 5, true, sandy ? SAND : DIRT, sandy ? SANDSTONE : DIRT);
        }
        return ShoreShape.NONE;
    }

    private static ShoreShape riverShoreShape(M15WorldgenProfile profile, M15TerrainSample terrain, M16WaterSample water, double distance) {
        int current = terrain.surfaceY();
        int waterLevel = water.waterSurfaceY();
        double bankWidth = Math.max(36.0D, water.valleyWidth() * 0.95D);
        if (distance > bankWidth) {
            return ShoreShape.NONE;
        }
        double n = Mth.clamp(distance / bankWidth, 0.0D, 1.0D);
        int rise = 1 + (int) Math.round(n * n * 30.0D + n * 6.0D);
        int target = waterLevel + rise;
        boolean sandy = water.riverProfile() == M16RiverProfile.PLAINS_RIVER || water.riverProfile() == M16RiverProfile.GREAT_RIVER;
        if (target < current - 1) {
            return new ShoreShape(true, target, 6, false, sandy ? SAND : DIRT, sandy ? SANDSTONE : DIRT);
        }
        if (distance <= bankWidth * 0.30D && current < waterLevel + 1) {
            return new ShoreShape(true, waterLevel + 1, 5, true, sandy ? SAND : DIRT, sandy ? SANDSTONE : DIRT);
        }
        return ShoreShape.NONE;
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
        int[] radii = {4, 8, 12, 16, 24, 32, 48, 64, 96, 128};
        for (int r : radii) {
            int step = r <= 16 ? 4 : r <= 48 ? 8 : 16;
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
            case OCEAN, LAKE -> sample.waterDepth() <= 3 ? SAND : SANDSTONE;
            case RIVER -> riverBedState(sample);
            case WATERFALL -> STONE;
            case NONE -> Blocks.AIR.defaultBlockState();
        };
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
        return switch (sample.riverProfile()) {
            case GREAT_RIVER, PLAINS_RIVER, SMALL_RIVER, STREAM -> SAND;
            case MOUNTAIN_RIVER, CANYON_RIVER -> STONE;
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
