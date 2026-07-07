package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Optional;

/**
 * M19.7 tiny cube-native passive spawn bridge for outside-shell test gameplay.
 *
 * <p>Vanilla natural spawning asks ChunkAccess/heightmaps for spawn candidates, so it has no useful view of Y=9000 or
 * Y=-12000 yet.  This is intentionally not final mob spawning; it only proves that passive entities can be created and
 * tracked on top of true cubic blocks outside the temporary vanilla shell.</p>
 */
public final class CubicNativePassiveSpawner {
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Animal> COW = (EntityType<? extends Animal>) BuiltInRegistries.ENTITY_TYPE
            .getOptional(Identifier.fromNamespaceAndPath("minecraft", "cow"))
            .orElseThrow(() -> new IllegalStateException("Missing minecraft:cow entity type"));
    private static final boolean AUTO_PASSIVE_SPAWNER_ENABLED = false;
    private static final int INTERVAL_TICKS = 200;
    private static final int MAX_ANIMALS_NEAR_PLAYER = 8;
    private static long totalAttempts;
    private static long totalSpawned;
    private static int attemptsLastTick;
    private static int spawnedLastTick;

    private CubicNativePassiveSpawner() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        attemptsLastTick = 0;
        spawnedLastTick = 0;
        if (!AUTO_PASSIVE_SPAWNER_ENABLED) {
            return;
        }
        ServerLevel level = event.getServer().getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL);
        if (level == null || level.getGameTime() % INTERVAL_TICKS != 0L) {
            return;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(event.getServer());
        for (ServerPlayer player : level.players()) {
            if (cache.settings().isBlockInsideVanillaShell(player.blockPosition().getY())) {
                continue;
            }
            if (animalsNear(level, player.blockPosition(), 32.0D) >= MAX_ANIMALS_NEAR_PLAYER) {
                continue;
            }
            trySpawnNear(level, cache, player);
        }
        RuntimeProfiler.addCount("gameplay.native_passive_spawn_attempts", attemptsLastTick);
        RuntimeProfiler.addCount("gameplay.native_passive_spawned", spawnedLastTick);
    }

    public static boolean spawnDebugCow(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level) || !level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return false;
        }
        return spawnAt(level, WorldCoreCubeLoading.cubicTestForServer(level.getServer()), player.blockPosition().offset(2, 0, 0), COW);
    }

    public static Snapshot snapshot() {
        return new Snapshot(totalAttempts, totalSpawned, attemptsLastTick, spawnedLastTick);
    }

    private static void trySpawnNear(ServerLevel level, ServerCubeCache cache, ServerPlayer player) {
        BlockPos base = player.blockPosition();
        int seed = (int) (level.getGameTime() + player.getId() * 31L);
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = ((seed + attempt * 11) & 31) - 16;
            int dz = (((seed >> 4) + attempt * 7) & 31) - 16;
            BlockPos candidate = new BlockPos(base.getX() + dx, base.getY(), base.getZ() + dz);
            if (spawnAt(level, cache, candidate, COW)) {
                return;
            }
        }
    }

    private static boolean spawnAt(ServerLevel level, ServerCubeCache cache, BlockPos nearFeet, EntityType<? extends Animal> type) {
        attemptsLastTick++;
        totalAttempts++;
        BlockPos feet = findFeet(level, cache, nearFeet);
        if (feet == null) {
            return false;
        }
        Animal animal = type.create(level, EntitySpawnReason.NATURAL);
        if (animal == null) {
            return false;
        }
        animal.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
        if (!level.addFreshEntity(animal)) {
            return false;
        }
        spawnedLastTick++;
        totalSpawned++;
        return true;
    }

    private static BlockPos findFeet(ServerLevel level, ServerCubeCache cache, BlockPos nearFeet) {
        for (int dy = 2; dy >= -4; dy--) {
            BlockPos feet = nearFeet.offset(0, dy, 0);
            if (!isOutsideShell(level, cache, feet)) {
                continue;
            }
            BlockState below = cache.readOrGenerateBlock(feet.below()).orElseGet(() -> Blocks.AIR.defaultBlockState());
            BlockState at = cache.readOrGenerateBlock(feet).orElseGet(() -> Blocks.AIR.defaultBlockState());
            BlockState head = cache.readOrGenerateBlock(feet.above()).orElseGet(() -> Blocks.AIR.defaultBlockState());
            if (!below.isAir() && at.isAir() && head.isAir()) {
                return feet;
            }
        }
        return null;
    }

    private static boolean isOutsideShell(ServerLevel level, ServerCubeCache cache, BlockPos pos) {
        return level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)
                && cache.settings().containsBlockY(pos.getY())
                && !cache.settings().isBlockInsideVanillaShell(pos.getY());
    }

    private static int animalsNear(ServerLevel level, BlockPos center, double radius) {
        double radiusSqr = radius * radius;
        int count = 0;
        EntityTypeTest<Entity, Animal> animalTest = EntityTypeTest.forClass(Animal.class);
        for (Animal animal : level.getEntities(animalTest, animal -> animal.distanceToSqr(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D) <= radiusSqr)) {
            count++;
        }
        return count;
    }

    public record Snapshot(long totalAttempts, long totalSpawned, int attemptsLastTick, int spawnedLastTick) {
    }
}
