package com.redline.worldcore.server.entity;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * M12.1 cube-first runtime entity tracker.
 *
 * <p>This is intentionally a read-only index over vanilla entities. It does not replace vanilla ticking, spawning,
 * despawning, saving or client entity packets yet. The goal is to make redline-world-core know which entities live in
 * which 16x16x16 cube, with enough diagnostics to safely build ENTITY_TICKING/BORDER control in later patches.</p>
 */
public final class EntityCubeTracker {
    private static final CubicTestDimensionService CUBIC_TEST = new CubicTestDimensionService();
    private static final EntityCubeTracker CUBIC_TEST_TRACKER = new EntityCubeTracker();
    private static final int DEFAULT_DUMP_LIMIT = 12;

    private final Map<Integer, EntityRef> byEntityId = new HashMap<>();
    private final Map<CubePos, CubeEntitySection> byCube = new HashMap<>();

    private int scannedLastTick;
    private int addedLastTick;
    private int movedLastTick;
    private int removedLastTick;
    private long totalAdded;
    private long totalMoved;
    private long totalRemoved;
    private long lastTickGameTime;
    private long scanMicrosLastTick;
    private long scanMicrosMax;
    private long scanMicrosTotal;
    private long scanSamples;

    private EntityCubeTracker() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Optional<ServerLevel> maybeLevel = CUBIC_TEST.level(event.getServer());
        if (maybeLevel.isEmpty()) {
            CUBIC_TEST_TRACKER.clear();
            return;
        }
        CUBIC_TEST_TRACKER.tick(maybeLevel.get());
    }

    public static EntityTrackingSnapshot snapshot(CubePos playerCube) {
        return CUBIC_TEST_TRACKER.createSnapshot(playerCube);
    }

    public static int trackedEntities() {
        return CUBIC_TEST_TRACKER.byEntityId.size();
    }

    public static int entitySections() {
        return CUBIC_TEST_TRACKER.byCube.size();
    }

    public static List<EntityRef> dumpCurrentCube(CubePos cubePos) {
        return CUBIC_TEST_TRACKER.dumpCube(cubePos, DEFAULT_DUMP_LIMIT);
    }

    public static List<EntityRef> dumpBusiestCube() {
        CubePos cubePos = CUBIC_TEST_TRACKER.busiestCube();
        return cubePos == null ? List.of() : CUBIC_TEST_TRACKER.dumpCube(cubePos, DEFAULT_DUMP_LIMIT);
    }

    public static void reset() {
        CUBIC_TEST_TRACKER.clear();
    }

    private void tick(ServerLevel level) {
        if (!level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            clear();
            return;
        }

        long started = System.nanoTime();
        scannedLastTick = 0;
        addedLastTick = 0;
        movedLastTick = 0;
        removedLastTick = 0;
        lastTickGameTime = level.getGameTime();

        Set<Integer> seenIds = new HashSet<>();
        List<? extends Entity> entities = level.getEntities(EntityTypeTest.forClass(Entity.class), entity -> !entity.isRemoved());
        for (Entity entity : entities) {
            scan(entity, seenIds);
        }

        // Some vanilla paths keep players in a dedicated player list, so explicitly scan them too. Duplicates are ignored.
        for (ServerPlayer player : level.getPlayers(player -> !player.isRemoved())) {
            scan(player, seenIds);
        }

        removeMissing(seenIds);
        scanMicrosLastTick = Math.max(0L, (System.nanoTime() - started) / 1_000L);
        scanMicrosMax = Math.max(scanMicrosMax, scanMicrosLastTick);
        scanMicrosTotal += scanMicrosLastTick;
        scanSamples++;
    }

    private void scan(Entity entity, Set<Integer> seenIds) {
        if (entity.isRemoved()) {
            return;
        }
        if (!seenIds.add(entity.getId())) {
            return;
        }

        scannedLastTick++;
        CubePos cubePos = CubePos.fromBlock(entity.blockPosition());
        Vec3 position = entity.position();
        EntityRef next = new EntityRef(
                entity.getId(),
                entity.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                kind(entity),
                cubePos,
                position.x,
                position.y,
                position.z,
                entity.isAlwaysTicking()
        );

        EntityRef previous = byEntityId.get(entity.getId());
        if (previous == null) {
            add(next);
            addedLastTick++;
            totalAdded++;
            return;
        }

        if (!previous.cubePos().equals(cubePos)) {
            move(previous, next);
            movedLastTick++;
            totalMoved++;
            return;
        }

        byEntityId.put(next.entityId(), next);
        section(cubePos).put(next);
    }

    private void add(EntityRef ref) {
        byEntityId.put(ref.entityId(), ref);
        section(ref.cubePos()).put(ref);
    }

    private void move(EntityRef previous, EntityRef next) {
        CubeEntitySection oldSection = byCube.get(previous.cubePos());
        if (oldSection != null) {
            oldSection.remove(previous.entityId());
            if (oldSection.isEmpty()) {
                byCube.remove(previous.cubePos());
            }
        }
        byEntityId.put(next.entityId(), next);
        section(next.cubePos()).put(next);
    }

    private void removeMissing(Set<Integer> seenIds) {
        List<EntityRef> removed = new ArrayList<>();
        for (EntityRef ref : byEntityId.values()) {
            if (!seenIds.contains(ref.entityId())) {
                removed.add(ref);
            }
        }
        for (EntityRef ref : removed) {
            byEntityId.remove(ref.entityId());
            CubeEntitySection section = byCube.get(ref.cubePos());
            if (section != null) {
                section.remove(ref.entityId());
                if (section.isEmpty()) {
                    byCube.remove(ref.cubePos());
                }
            }
            removedLastTick++;
            totalRemoved++;
        }
    }

    private CubeEntitySection section(CubePos cubePos) {
        return byCube.computeIfAbsent(cubePos, CubeEntitySection::new);
    }

    private EntityTrackingSnapshot createSnapshot(CubePos playerCube) {
        CubePos busiestCube = busiestCube();
        int busiestCount = 0;
        if (busiestCube != null) {
            CubeEntitySection busiestSection = byCube.get(busiestCube);
            busiestCount = busiestSection == null ? 0 : busiestSection.size();
        }

        CubeEntitySection playerSection = playerCube == null ? null : byCube.get(playerCube);
        int playerCubeEntities = playerSection == null ? 0 : playerSection.size();

        int players = 0;
        int mobs = 0;
        int items = 0;
        int projectiles = 0;
        int other = 0;
        for (EntityRef ref : byEntityId.values()) {
            switch (ref.kind()) {
                case "player" -> players++;
                case "mob" -> mobs++;
                case "item" -> items++;
                case "projectile" -> projectiles++;
                default -> other++;
            }
        }

        long averageMicros = scanSamples <= 0L ? 0L : scanMicrosTotal / scanSamples;
        return new EntityTrackingSnapshot(
                byEntityId.size(),
                byCube.size(),
                playerCubeEntities,
                scannedLastTick,
                addedLastTick,
                movedLastTick,
                removedLastTick,
                totalAdded,
                totalMoved,
                totalRemoved,
                lastTickGameTime,
                playerCube,
                busiestCube,
                busiestCount,
                players,
                mobs,
                items,
                projectiles,
                other,
                scanMicrosLastTick,
                averageMicros,
                scanMicrosMax
        );
    }

    private CubePos busiestCube() {
        CubePos busiestCube = null;
        int busiestCount = 0;
        for (CubeEntitySection section : byCube.values()) {
            if (section.size() > busiestCount) {
                busiestCube = section.cubePos();
                busiestCount = section.size();
            }
        }
        return busiestCube;
    }

    private List<EntityRef> dumpCube(CubePos cubePos, int limit) {
        if (cubePos == null || limit <= 0) {
            return List.of();
        }
        CubeEntitySection section = byCube.get(cubePos);
        if (section == null || section.isEmpty()) {
            return List.of();
        }
        return section.entities().stream()
                .sorted(Comparator.comparingInt(EntityRef::entityId))
                .limit(limit)
                .toList();
    }

    private static String kind(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return "player";
        }
        if (entity instanceof Mob) {
            return "mob";
        }
        if (entity instanceof ItemEntity) {
            return "item";
        }
        if (entity instanceof Projectile) {
            return "projectile";
        }
        return "other";
    }

    private void clear() {
        byEntityId.clear();
        byCube.clear();
        scannedLastTick = 0;
        addedLastTick = 0;
        movedLastTick = 0;
        removedLastTick = 0;
        totalAdded = 0L;
        totalMoved = 0L;
        totalRemoved = 0L;
        lastTickGameTime = 0L;
        scanMicrosLastTick = 0L;
        scanMicrosMax = 0L;
        scanMicrosTotal = 0L;
        scanSamples = 0L;
    }
}
