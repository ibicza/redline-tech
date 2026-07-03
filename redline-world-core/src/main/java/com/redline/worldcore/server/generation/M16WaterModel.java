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
    public static final String VERSION = "M16.1 water ownership + active river support v1";

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
            return baseState;
        }
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
        double oceanNoise = M15Noise.fbm2D(context.seed() ^ OCEAN_SEED, x, z, 2048, 4);
        double oceanMask = M15Noise.smoothstep(-0.18D, 0.34D, -(terrain.continentalness() + oceanNoise * 0.25D));
        boolean ocean = terrain.surfaceY() <= profile.seaLevel() - 3 && oceanMask > 0.50D;
        if (!ocean) {
            return none(terrain, oceanMask, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()));
        }
        int depth = 6 + (int) Math.round(oceanMask * 22.0D) + M15Noise.hashToRange(context.seed() ^ OCEAN_SEED, x >> 4, 0, z >> 4, 10);
        int bedY = Math.min(terrain.surfaceY(), profile.seaLevel() - depth);
        bedY = Math.max(profile.minY() + 16, bedY);
        return new M16WaterSample(x, z, terrain.surfaceY(), bedY, profile.seaLevel(), profile.seaLevel() - bedY, profile.seaLevel() - bedY,
                24 + (int) Math.round(oceanMask * 80.0D), oceanMask, 0.0D, Double.POSITIVE_INFINITY, 0,
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
                if (lakeSurface <= profile.seaLevel() + 3 || lakeSurface >= terrain.surfaceY() + 18) {
                    continue;
                }
                double strength = 1.0D - dist;
                int waterDepth = 2 + (int) Math.round(strength * (4 + M15Noise.hashToRange(context.seed() ^ (LAKE_SEED + 79L), cx, 0, cz, 7)));
                int carveDepth = waterDepth + 1 + (int) Math.round(strength * 5.0D);
                int bedY = Math.max(profile.minY() + 16, lakeSurface - waterDepth);
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

    private static M16WaterSample riverSample(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain) {
        int x = terrain.x();
        int z = terrain.z();
        RiverField field = riverField(context, x, z, terrain);
        if (field.distance > field.width * 0.5D) {
            return none(terrain, 0.0D, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()));
        }
        double centerStrength = 1.0D - field.distance / (field.width * 0.5D);
        int waterDepth = Math.max(1, (int) Math.round(field.baseWaterDepth * (0.65D + 0.35D * centerStrength)));
        int carveDepth = Math.max(waterDepth + 1, (int) Math.round(field.baseCarveDepth * (0.45D + 0.55D * centerStrength)));
        int valleyWidth = Math.max(field.width + 8, (int) Math.round(field.width * field.valleyScale));
        int smoothedSurface = smoothedRiverSurface(context, x, z);
        int rawWaterSurfaceY = smoothedSurface - Math.max(1, carveDepth - waterDepth);
        int waterSurfaceY = quantizeRiverWaterSurface(context, field.profile, x, z, rawWaterSurfaceY);
        if (field.profile == M16RiverProfile.GREAT_RIVER && terrain.surfaceY() < profile.seaLevel() + 16) {
            waterSurfaceY = Math.min(profile.seaLevel(), terrain.surfaceY() - 1);
        }
        waterSurfaceY = Mth.clamp(waterSurfaceY, profile.minY() + 16, terrain.surfaceY() - 1);
        int bedY = Math.max(profile.minY() + 16, waterSurfaceY - waterDepth);
        return new M16WaterSample(x, z, terrain.surfaceY(), bedY, waterSurfaceY, waterSurfaceY - bedY, carveDepth, valleyWidth,
                0.0D, Math.max(0.0D, terrain.surfaceY() - profile.seaLevel()), field.distance, field.width,
                M16WaterType.RIVER, field.profile, field.profile == M16RiverProfile.GREAT_RIVER,
                field.profile == M16RiverProfile.CANYON_RIVER, false);
    }

    private static RiverField riverField(CubeGenerationContext context, int x, int z, M15TerrainSample terrain) {
        double greatLine = Math.abs(M15Noise.fbm2D(context.seed() ^ GREAT_RIVER_SEED, x, z, 2048, 5));
        double normalLine = Math.abs(M15Noise.fbm2D(context.seed() ^ RIVER_SEED, x, z, 768, 4));
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
        if (great) {
            profile = M16RiverProfile.GREAT_RIVER;
            width = 64 + M15Noise.hashToRange(context.seed() ^ GREAT_RIVER_SEED, coarseX, 0, coarseZ, 57);
            depth = 12 + M15Noise.hashToRange(context.seed() ^ (GREAT_RIVER_SEED + 31L), coarseX, 0, coarseZ, 21);
            carve = depth + 8 + M15Noise.hashToRange(context.seed() ^ (GREAT_RIVER_SEED + 47L), coarseX, 0, coarseZ, 32);
            distance = greatLine * 1500.0D;
            valleyScale = 2.4D;
        } else {
            if (normalLine > 0.070D || terrain.continentalness() < -0.52D || wet < 0.20D) {
                return new RiverField(M16RiverProfile.NONE, Integer.MAX_VALUE / 4, Double.POSITIVE_INFINITY, 0, 0, 1.0D);
            }
            int roll = M15Noise.hashToRange(context.seed() ^ RIVER_SEED, coarseX, 0, coarseZ, 100);
            if (mountain > 0.72D && roll < 34) {
                profile = M16RiverProfile.CANYON_RIVER;
                width = 16 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 13L), coarseX, 0, coarseZ, 35);
                depth = 8 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 17L), coarseX, 0, coarseZ, 13);
                carve = 32 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 19L), coarseX, 0, coarseZ, 96);
                valleyScale = 3.2D;
            } else if (mountain > 0.55D) {
                profile = M16RiverProfile.MOUNTAIN_RIVER;
                width = 8 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 23L), coarseX, 0, coarseZ, 18);
                depth = 4 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 29L), coarseX, 0, coarseZ, 8);
                carve = depth + 4 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 31L), coarseX, 0, coarseZ, 18);
                valleyScale = 2.0D;
            } else if (roll < 12) {
                profile = M16RiverProfile.STREAM;
                width = 3 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 37L), coarseX, 0, coarseZ, 4);
                depth = 1 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 41L), coarseX, 0, coarseZ, 2);
                carve = depth + 1;
                valleyScale = 1.4D;
            } else if (roll < 42) {
                profile = M16RiverProfile.SMALL_RIVER;
                width = 6 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 43L), coarseX, 0, coarseZ, 9);
                depth = 2 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 53L), coarseX, 0, coarseZ, 5);
                carve = depth + 2 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 59L), coarseX, 0, coarseZ, 8);
                valleyScale = 1.8D;
            } else {
                profile = M16RiverProfile.PLAINS_RIVER;
                width = 14 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 61L), coarseX, 0, coarseZ, 24);
                depth = 4 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 67L), coarseX, 0, coarseZ, 11);
                carve = depth + 3 + M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 71L), coarseX, 0, coarseZ, 16);
                valleyScale = 2.2D;
            }
            distance = normalLine * 620.0D;
        }
        return new RiverField(profile, width, distance, depth, carve, valleyScale);
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

    private static int quantizeRiverWaterSurface(CubeGenerationContext context, M16RiverProfile profile, int x, int z, int rawY) {
        int segment = switch (profile) {
            case GREAT_RIVER -> 96;
            case PLAINS_RIVER -> 64;
            case SMALL_RIVER -> 48;
            case STREAM, MOUNTAIN_RIVER -> 32;
            case CANYON_RIVER -> 40;
            case NONE -> 32;
        };
        int cellX = Math.floorDiv(x, segment);
        int cellZ = Math.floorDiv(z, segment);
        int localBias = M15Noise.hashToRange(context.seed() ^ (RIVER_SEED + 101L), cellX, rawY >> 3, cellZ, 3) - 1;
        int step = switch (profile) {
            case GREAT_RIVER, PLAINS_RIVER -> 1;
            case SMALL_RIVER, STREAM -> 2;
            case MOUNTAIN_RIVER, CANYON_RIVER -> 3;
            case NONE -> 1;
        };
        return Math.floorDiv(rawY + localBias, step) * step;
    }

    private static boolean isWaterfallCandidate(CubeGenerationContext context, M15WorldgenProfile profile, M15TerrainSample terrain, M16WaterSample river) {
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

    private static BlockState waterState(M16WaterSample sample, int y) {
        if (sample.waterType() == M16WaterType.WATERFALL) {
            return Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock();
        }
        if (sample.waterType() == M16WaterType.RIVER && y == sample.waterSurfaceY()) {
            double edge = sample.riverWidth() <= 0 ? 0.0D : sample.riverDistance() / Math.max(1.0D, sample.riverWidth() * 0.5D);
            int amount = Mth.clamp(8 - (int) Math.round(edge * 3.0D), 4, 8);
            return Fluids.FLOWING_WATER.getFlowing(amount, false).createLegacyBlock();
        }
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
            case OCEAN, LAKE -> 4;
            case RIVER -> 3;
            case WATERFALL -> 6;
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

    private record RiverField(M16RiverProfile profile, int width, double distance, int baseWaterDepth, int baseCarveDepth, double valleyScale) {
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
