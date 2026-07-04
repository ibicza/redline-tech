package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.generation.CubeGenerationContext;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M15 seed-only Java terrain model.
 *
 * <p>This is the reference implementation. Later atlas and native backends must match its public samples or explicitly
 * run in a different configured mode.</p>
 */
public final class M15TerrainModel {
    public static final String VERSION = "M16.11 cached seed-only terrain + optimized static hydrology v1";

    private static final long CONTINENT_SEED = 0x434F4E54494E454EL;
    private static final long EROSION_SEED = 0x45524F53494F4E31L;
    private static final long TEMP_SEED = 0x54454D5045524154L;
    private static final long HUMIDITY_SEED = 0x48554D4944495459L;
    private static final long RIDGE_SEED = 0x52494447454D4150L;
    private static final long DETAIL_SEED = 0x44455441494C3031L;
    private static final long LAVA_SEED = 0x4C41564142454C54L;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    /**
     * M16 returns real sand for beaches/river beds, but generated sand always receives solid support below.
     * The vanilla shell writes without neighbor updates, so sand behaves like vanilla static worldgen sand until
     * a player/block update interacts with it.
     */
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState SNOW = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    private static final int DRY_SAMPLE_CACHE_LIMIT = 65536;

    private static final ThreadLocal<LinkedHashMap<Long, M15TerrainSample>> DRY_SAMPLE_CACHE = ThreadLocal.withInitial(() -> new LinkedHashMap<>(1024, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, M15TerrainSample> eldest) {
            return size() > DRY_SAMPLE_CACHE_LIMIT;
        }
    });

    public static void beginCubeGeneration() {
        // M16.11: keep the bounded ThreadLocal cache warm across nearby cube generations.
        // Keys include seed + dimension settings, so this is safe for the integrated server and avoids
        // recomputing the same dry terrain samples during shore/river neighbor probes.
    }

    public static void endCubeGeneration() {
        // Entries are evicted by DRY_SAMPLE_CACHE_LIMIT instead of clearing after every cube.
    }

    public static M15WorldgenProfile profile(CubeGenerationContext context) {
        return M15WorldgenProfile.from(context.settings());
    }

    public static M15TerrainSample sample(CubeGenerationContext context, int x, int z) {
        return sampleDry(context, x, z);
    }

    public static M15TerrainSample sampleDry(CubeGenerationContext context, int x, int z) {
        long key = drySampleKey(context, x, z);
        LinkedHashMap<Long, M15TerrainSample> cache = DRY_SAMPLE_CACHE.get();
        M15TerrainSample cached = cache.get(key);
        if (cached != null) {
            RuntimeProfiler.addCount("terrain.dry_sample_cache_hits", 1);
            return cached;
        }
        long start = RuntimeProfiler.markStart();
        M15TerrainSample sample = sampleDryUncached(context, x, z);
        RuntimeProfiler.recordSince("terrain.dry_sample_uncached", start);
        RuntimeProfiler.addCount("terrain.dry_sample_uncached_calls", 1);
        cache.put(key, sample);
        return sample;
    }

    private static M15TerrainSample sampleDryUncached(CubeGenerationContext context, int x, int z) {
        M15WorldgenProfile profile = profile(context);
        long seed = context.seed();
        double continentalness = M15Noise.fbm2D(seed ^ CONTINENT_SEED, x, z, 1536, 4);
        double erosion = M15Noise.fbm2D(seed ^ EROSION_SEED, x, z, 768, 3);
        double temperature = M15Noise.fbm2D(seed ^ TEMP_SEED, x, z, 1024, 3);
        double humidity = M15Noise.fbm2D(seed ^ HUMIDITY_SEED, x, z, 1024, 3);
        double ridge = M15Noise.ridge2D(seed ^ RIDGE_SEED, x, z, 512, 4);
        double detail = M15Noise.fbm2D(seed ^ DETAIL_SEED, x, z, 96, 3);

        double landMask = M15Noise.smoothstep(-0.28D, 0.12D, continentalness);
        double oceanMask = 1.0D - landMask;
        double mountainMask = M15Noise.smoothstep(0.20D, 0.72D, continentalness + ridge * 0.45D - erosion * 0.20D);
        double oceanDepth = oceanMask * (profile.seaLevel() - profile.lowestSurfaceY()) * (0.45D + 0.55D * Math.max(0.0D, -continentalness));

        double height = profile.baseLandY();
        height += continentalness * profile.continentAmplitude();
        // M15.1: plains must not be constant Minecraft-amplified hills. Detail mainly roughens mountains.
        double localRelief = detail * profile.hillAmplitude() * (0.25D + 0.75D * mountainMask);
        height += localRelief;
        height += (ridge * ridge) * mountainMask * profile.mountainAmplitude();
        height -= Math.max(0.0D, erosion) * profile.hillAmplitude() * 0.35D;
        height -= oceanDepth;

        int surfaceY = Mth.clamp((int) Math.round(height), profile.lowestSurfaceY(), profile.highestSurfaceY());
        M15SurfaceZone zone = zone(profile, surfaceY, temperature, humidity, continentalness);
        return new M15TerrainSample(x, z, surfaceY, profile.seaLevel(), continentalness, erosion, temperature, humidity, ridge, zone);
    }

    private static long drySampleKey(CubeGenerationContext context, int x, int z) {
        long key = (((long) x) << 32) ^ (z & 0xFFFF_FFFFL);
        key ^= context.seed() * 0x9E3779B97F4A7C15L;
        key ^= ((long) context.settings().minCubeY() << 48);
        key ^= ((long) context.settings().maxCubeY() << 32);
        key ^= ((long) context.settings().seaLevel() << 16);
        return key;
    }

    public static int surfaceHeight(CubeGenerationContext context, int x, int z) {
        M15TerrainSample dry = sampleDry(context, x, z);
        M16WaterSample water = M16WaterModel.sample(context, dry);
        return water.hasWater() ? Math.max(water.waterSurfaceY(), water.effectiveSurfaceY()) : dry.surfaceY();
    }

    public static int surfaceHeightDry(CubeGenerationContext context, int x, int z) {
        return sampleDry(context, x, z).surfaceY();
    }

    public static boolean isSafeDrySpawn(CubeGenerationContext context, int x, int z) {
        M15TerrainSample sample = sampleDry(context, x, z);
        M16WaterSample water = M16WaterModel.sample(context, sample);
        return M16WaterModel.isSafeDrySpawn(water, profile(context))
                && sample.zone() != M15SurfaceZone.OCEAN_FLOOR
                && sample.zone() != M15SurfaceZone.BEACH;
    }

    public static M15TerrainSample findSafeDrySpawn(CubeGenerationContext context, int centerX, int centerZ, int maxRadiusBlocks) {
        int step = CubePos.SIZE;
        M15TerrainSample bestFallback = sampleDry(context, centerX, centerZ);
        if (isSafeDrySpawn(context, centerX, centerZ)) {
            return bestFallback;
        }
        for (int radius = step; radius <= maxRadiusBlocks; radius += step) {
            for (int x = centerX - radius; x <= centerX + radius; x += step) {
                M15TerrainSample north = sampleDry(context, x, centerZ - radius);
                if (isSafeDrySpawn(context, north.x(), north.z())) {
                    return north;
                }
                M15TerrainSample south = sampleDry(context, x, centerZ + radius);
                if (isSafeDrySpawn(context, south.x(), south.z())) {
                    return south;
                }
            }
            for (int z = centerZ - radius + step; z <= centerZ + radius - step; z += step) {
                M15TerrainSample west = sampleDry(context, centerX - radius, z);
                if (isSafeDrySpawn(context, west.x(), west.z())) {
                    return west;
                }
                M15TerrainSample east = sampleDry(context, centerX + radius, z);
                if (isSafeDrySpawn(context, east.x(), east.z())) {
                    return east;
                }
            }
        }
        return bestFallback;
    }

    public static BlockState stateFor(CubeGenerationContext context, int x, int y, int z) {
        M15TerrainSample sample = sampleDry(context, x, z);
        M16WaterColumnShape waterShape = M16WaterModel.columnShape(context, sample);
        return stateFor(context, sample, waterShape, y);
    }

    public static BlockState stateFor(CubeGenerationContext context, M15TerrainSample sample, M16WaterColumnShape waterShape, int y) {
        M15WorldgenProfile profile = profile(context);
        if (y < profile.minY() || y > profile.maxY()) {
            return AIR;
        }
        if (profile.inBedrockLayer(y)) {
            return BEDROCK;
        }

        int x = sample.x();
        int z = sample.z();
        int surfaceY = sample.surfaceY();
        BlockState baseState;
        if (y > surfaceY) {
            baseState = AIR;
        } else if (profile.inDeepLavaBelt(y) && isDeepLavaPocket(context.seed(), profile, x, y, z)) {
            baseState = LAVA;
        } else {
            int surfaceDepth = surfaceDepth(context.seed(), x, z);
            if (y == surfaceY) {
                baseState = topState(sample, profile);
            } else if (y > surfaceY - surfaceDepth) {
                baseState = subsurfaceState(sample, profile, y);
            } else {
                baseState = baseStone(profile, y);
            }
        }
        return M16WaterModel.overrideState(context, sample, waterShape, y, baseState);
    }

    public static boolean mayContainDeepLavaPocket(long seed, M15WorldgenProfile profile, int cubeX, int cubeY, int cubeZ) {
        int minY = cubeY << 4;
        int maxY = minY + 15;
        if (maxY <= profile.bedrockTopY() || minY > profile.lavaBeltTopY()) {
            return false;
        }
        int cellX = Math.floorDiv(cubeX << 4, 24);
        int cellY = Math.floorDiv(minY, 12);
        int cellZ = Math.floorDiv(cubeZ << 4, 24);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (lavaCellActive(seed, cellX + dx, cellY + dy, cellZ + dz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static M15SurfaceZone zone(M15WorldgenProfile profile, int surfaceY, double temperature, double humidity, double continentalness) {
        if (surfaceY < profile.seaLevel() - 3) {
            return M15SurfaceZone.OCEAN_FLOOR;
        }
        if (surfaceY <= profile.seaLevel() + 3) {
            return M15SurfaceZone.BEACH;
        }
        if (surfaceY >= profile.snowLineY() && temperature < 0.45D) {
            return M15SurfaceZone.SNOWY_MOUNTAIN;
        }
        if (surfaceY >= profile.highMountainStartY()) {
            return M15SurfaceZone.ROCKY_MOUNTAIN;
        }
        if (temperature < -0.42D && humidity > -0.40D) {
            return M15SurfaceZone.COLD;
        }
        if (temperature > 0.38D && humidity < -0.18D && continentalness > -0.10D) {
            return M15SurfaceZone.DRY;
        }
        return M15SurfaceZone.TEMPERATE;
    }

    private static BlockState topState(M15TerrainSample sample, M15WorldgenProfile profile) {
        return switch (sample.zone()) {
            case OCEAN_FLOOR, BEACH, DRY -> SAND;
            case COLD, SNOWY_MOUNTAIN -> SNOW;
            case ROCKY_MOUNTAIN -> sample.surfaceY() >= profile.snowLineY() ? SNOW : STONE;
            case TEMPERATE -> GRASS;
        };
    }

    private static BlockState subsurfaceState(M15TerrainSample sample, M15WorldgenProfile profile, int y) {
        return switch (sample.zone()) {
            case OCEAN_FLOOR, BEACH, DRY -> SANDSTONE;
            case COLD, TEMPERATE -> DIRT;
            case ROCKY_MOUNTAIN, SNOWY_MOUNTAIN -> baseStone(profile, y);
        };
    }

    private static BlockState baseStone(M15WorldgenProfile profile, int y) {
        return profile.inDeepslateLayer(y) ? DEEPSLATE : STONE;
    }

    private static int surfaceDepth(long seed, int x, int z) {
        return 3 + M15Noise.hashToRange(seed ^ 0x5355524644455054L, Math.floorDiv(x, 4), 0, Math.floorDiv(z, 4), 3);
    }

    private static boolean isDeepLavaPocket(long seed, M15WorldgenProfile profile, int x, int y, int z) {
        int cellX = Math.floorDiv(x, 24);
        int cellY = Math.floorDiv(y, 12);
        int cellZ = Math.floorDiv(z, 24);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (insideLavaCell(seed, profile, x, y, z, cellX + dx, cellY + dy, cellZ + dz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean insideLavaCell(long seed, M15WorldgenProfile profile, int x, int y, int z, int cellX, int cellY, int cellZ) {
        if (!lavaCellActive(seed, cellX, cellY, cellZ)) {
            return false;
        }
        int centerX = cellX * 24 + 12 + M15Noise.hashToRange(seed ^ LAVA_SEED, cellX, cellY, cellZ, 9) - 4;
        int centerY = cellY * 12 + 6 + M15Noise.hashToRange(seed ^ (LAVA_SEED + 17L), cellX, cellY, cellZ, 5) - 2;
        int centerZ = cellZ * 24 + 12 + M15Noise.hashToRange(seed ^ (LAVA_SEED + 33L), cellX, cellY, cellZ, 9) - 4;
        if (centerY <= profile.bedrockTopY() + 4 || centerY > profile.lavaBeltTopY()) {
            return false;
        }
        int rx = 7 + M15Noise.hashToRange(seed ^ (LAVA_SEED + 49L), cellX, cellY, cellZ, 8);
        int ry = 3 + M15Noise.hashToRange(seed ^ (LAVA_SEED + 65L), cellX, cellY, cellZ, 5);
        int rz = 7 + M15Noise.hashToRange(seed ^ (LAVA_SEED + 81L), cellX, cellY, cellZ, 8);
        double dx = (x - centerX) / (double) rx;
        double dy = (y - centerY) / (double) ry;
        double dz = (z - centerZ) / (double) rz;
        return dx * dx + dy * dy + dz * dz <= 1.0D;
    }

    private static boolean lavaCellActive(long seed, int cellX, int cellY, int cellZ) {
        return M15Noise.hashToRange(seed ^ LAVA_SEED, cellX, cellY, cellZ, 4) == 0;
    }

    private M15TerrainModel() {
    }
}
