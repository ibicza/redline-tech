package com.ibicza.redlinechunkpriority.core;

import com.ibicza.redlinechunkpriority.config.ChunkPriorityConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChunkPriorityPlanner {
    public List<ChunkPriorityTarget> plan(ServerPlayer player, int maxTargets) {
        ChunkPos center = player.chunkPosition();
        Vec2 movement = horizontal(player.getDeltaMovement());
        Vec2 look = horizontal(player.getLookAngle());
        Vec2 primary = movement.length() >= ChunkPriorityConfig.MIN_MOVEMENT_SPEED_BLOCKS_PER_TICK.get()
                ? movement.normalized()
                : look.normalizedOrDefault(0.0D, 1.0D);

        LinkedHashMap<Long, ChunkPriorityTarget> targets = new LinkedHashMap<>();
        int rank = 0;

        rank = add(targets, center.x(), center.z(), ChunkPriorityTier.CURRENT, rank, "player", maxTargets);
        rank = addNear3x3(targets, center, rank, maxTargets);
        rank = addMovementTier(targets, center, movement, rank, maxTargets);
        rank = addLookTier(targets, center, look, rank, maxTargets);
        rank = addSideTier(targets, center, primary, rank, maxTargets);
        rank = addBackTier(targets, center, primary, rank, maxTargets);
        addRestTier(targets, center, rank, maxTargets);

        return new ArrayList<>(targets.values());
    }

    private int addNear3x3(Map<Long, ChunkPriorityTarget> targets, ChunkPos center, int rank, int maxTargets) {
        int[][] order = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int[] offset : order) {
            rank = add(targets, center.x() + offset[0], center.z() + offset[1], ChunkPriorityTier.NEAR_3X3, rank, "3x3", maxTargets);
        }
        return rank;
    }

    private int addMovementTier(Map<Long, ChunkPriorityTarget> targets, ChunkPos center, Vec2 movement, int rank, int maxTargets) {
        if (movement.length() < ChunkPriorityConfig.MIN_MOVEMENT_SPEED_BLOCKS_PER_TICK.get()) {
            return rank;
        }

        Vec2 dir = movement.normalized();
        Vec2 side = dir.leftNormal();
        int distance = ChunkPriorityConfig.MOVEMENT_LINE_DISTANCE_CHUNKS.get();
        int maxOffset = ChunkPriorityConfig.MOVEMENT_MAX_SIDE_OFFSET_CHUNKS.get();
        int offsetStepDistance = ChunkPriorityConfig.MOVEMENT_OFFSET_STEP_DISTANCE.get();

        // First: strict center line by movement, nearest chunks first.
        for (int d = 1; d <= distance; d++) {
            int x = roundChunk(center.x(), dir.x * d);
            int z = roundChunk(center.z(), dir.z * d);
            rank = add(targets, x, z, ChunkPriorityTier.MOVEMENT_LINE, rank, "d=" + d, maxTargets);
        }

        // Then: expand from the line outward, offset 1, then 2, then 3...
        for (int offset = 1; offset <= maxOffset; offset++) {
            int startDistance = Math.max(1, offset * offsetStepDistance);
            for (int d = startDistance; d <= distance; d++) {
                int x1 = roundChunk(center.x(), dir.x * d + side.x * offset);
                int z1 = roundChunk(center.z(), dir.z * d + side.z * offset);
                int x2 = roundChunk(center.x(), dir.x * d - side.x * offset);
                int z2 = roundChunk(center.z(), dir.z * d - side.z * offset);
                rank = add(targets, x1, z1, ChunkPriorityTier.MOVEMENT_OFFSET, rank, "d=" + d + " off=+" + offset, maxTargets);
                rank = add(targets, x2, z2, ChunkPriorityTier.MOVEMENT_OFFSET, rank, "d=" + d + " off=-" + offset, maxTargets);
            }
        }
        return rank;
    }

    private int addLookTier(Map<Long, ChunkPriorityTarget> targets, ChunkPos center, Vec2 look, int rank, int maxTargets) {
        Vec2 dir = look.normalizedOrDefault(0.0D, 1.0D);
        int distance = ChunkPriorityConfig.LOOK_LINE_DISTANCE_CHUNKS.get();
        int halfFov = ChunkPriorityConfig.LOOK_FOV_DEGREES.get() / 2;
        int step = ChunkPriorityConfig.LOOK_ANGLE_STEP_DEGREES.get();

        // First: strict center line of the camera.
        for (int d = 1; d <= distance; d++) {
            int x = roundChunk(center.x(), dir.x * d);
            int z = roundChunk(center.z(), dir.z * d);
            rank = add(targets, x, z, ChunkPriorityTier.LOOK_LINE, rank, "d=" + d, maxTargets);
        }

        // Then: fan from center to FOV edges.
        for (int angle = step; angle <= halfFov; angle += step) {
            Vec2 plus = dir.rotatedDegrees(angle);
            Vec2 minus = dir.rotatedDegrees(-angle);
            for (int d = 1; d <= distance; d++) {
                int x1 = roundChunk(center.x(), plus.x * d);
                int z1 = roundChunk(center.z(), plus.z * d);
                int x2 = roundChunk(center.x(), minus.x * d);
                int z2 = roundChunk(center.z(), minus.z * d);
                rank = add(targets, x1, z1, ChunkPriorityTier.LOOK_FAN, rank, "d=" + d + " a=+" + angle, maxTargets);
                rank = add(targets, x2, z2, ChunkPriorityTier.LOOK_FAN, rank, "d=" + d + " a=-" + angle, maxTargets);
            }
        }
        return rank;
    }

    private int addSideTier(Map<Long, ChunkPriorityTarget> targets, ChunkPos center, Vec2 primary, int rank, int maxTargets) {
        Vec2 dir = primary.normalizedOrDefault(0.0D, 1.0D);
        int maxDistance = ChunkPriorityConfig.SIDE_DISTANCE_CHUNKS.get();
        for (int radius = 2; radius <= maxDistance; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                rank = maybeAddSide(targets, center, dir, dx, -radius, radius, rank, maxTargets);
                rank = maybeAddSide(targets, center, dir, dx, radius, radius, rank, maxTargets);
            }
            for (int dz = -radius + 1; dz <= radius - 1; dz++) {
                rank = maybeAddSide(targets, center, dir, -radius, dz, radius, rank, maxTargets);
                rank = maybeAddSide(targets, center, dir, radius, dz, radius, rank, maxTargets);
            }
        }
        return rank;
    }

    private int addBackTier(Map<Long, ChunkPriorityTarget> targets, ChunkPos center, Vec2 primary, int rank, int maxTargets) {
        Vec2 dir = primary.normalizedOrDefault(0.0D, 1.0D);
        int maxDistance = ChunkPriorityConfig.BACK_DISTANCE_CHUNKS.get();
        for (int radius = 2; radius <= maxDistance; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                rank = maybeAddBack(targets, center, dir, dx, -radius, radius, rank, maxTargets);
                rank = maybeAddBack(targets, center, dir, dx, radius, radius, rank, maxTargets);
            }
            for (int dz = -radius + 1; dz <= radius - 1; dz++) {
                rank = maybeAddBack(targets, center, dir, -radius, dz, radius, rank, maxTargets);
                rank = maybeAddBack(targets, center, dir, radius, dz, radius, rank, maxTargets);
            }
        }
        return rank;
    }

    private int addRestTier(Map<Long, ChunkPriorityTarget> targets, ChunkPos center, int rank, int maxTargets) {
        int maxDistance = ChunkPriorityConfig.REST_DISTANCE_CHUNKS.get();
        for (int radius = 2; radius <= maxDistance; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                rank = add(targets, center.x() + dx, center.z() - radius, ChunkPriorityTier.REST, rank, "r=" + radius, maxTargets);
                rank = add(targets, center.x() + dx, center.z() + radius, ChunkPriorityTier.REST, rank, "r=" + radius, maxTargets);
            }
            for (int dz = -radius + 1; dz <= radius - 1; dz++) {
                rank = add(targets, center.x() - radius, center.z() + dz, ChunkPriorityTier.REST, rank, "r=" + radius, maxTargets);
                rank = add(targets, center.x() + radius, center.z() + dz, ChunkPriorityTier.REST, rank, "r=" + radius, maxTargets);
            }
        }
        return rank;
    }

    private int maybeAddSide(Map<Long, ChunkPriorityTarget> targets, ChunkPos center, Vec2 dir, int dx, int dz, int radius, int rank, int maxTargets) {
        Vec2 toChunk = new Vec2(dx, dz).normalizedOrDefault(0.0D, 0.0D);
        double dot = dir.dot(toChunk);
        if (Math.abs(dot) <= 0.50D) {
            return add(targets, center.x() + dx, center.z() + dz, ChunkPriorityTier.SIDE, rank, "r=" + radius, maxTargets);
        }
        return rank;
    }

    private int maybeAddBack(Map<Long, ChunkPriorityTarget> targets, ChunkPos center, Vec2 dir, int dx, int dz, int radius, int rank, int maxTargets) {
        Vec2 toChunk = new Vec2(dx, dz).normalizedOrDefault(0.0D, 0.0D);
        if (dir.dot(toChunk) < -0.50D) {
            return add(targets, center.x() + dx, center.z() + dz, ChunkPriorityTier.BACK, rank, "r=" + radius, maxTargets);
        }
        return rank;
    }

    private int add(Map<Long, ChunkPriorityTarget> targets, int chunkX, int chunkZ, ChunkPriorityTier tier, int rank, String detail, int maxTargets) {
        if (targets.size() >= maxTargets) {
            return rank;
        }
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        long key = pos.pack();
        if (!targets.containsKey(key)) {
            targets.put(key, new ChunkPriorityTarget(pos, tier, rank, detail));
            return rank + 1;
        }
        return rank;
    }

    private static int roundChunk(int center, double offset) {
        return center + (int) Math.round(offset);
    }

    private static Vec2 horizontal(Vec3 vec) {
        return new Vec2(vec.x, vec.z);
    }

    private record Vec2(double x, double z) {
        double length() {
            return Math.sqrt(x * x + z * z);
        }

        Vec2 normalized() {
            double length = length();
            if (length <= 1.0E-7D) {
                return new Vec2(0.0D, 0.0D);
            }
            return new Vec2(x / length, z / length);
        }

        Vec2 normalizedOrDefault(double defaultX, double defaultZ) {
            double length = length();
            if (length <= 1.0E-7D) {
                return new Vec2(defaultX, defaultZ).normalized();
            }
            return new Vec2(x / length, z / length);
        }

        Vec2 leftNormal() {
            return new Vec2(-z, x);
        }

        Vec2 rotatedDegrees(double degrees) {
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            return new Vec2(x * cos - z * sin, x * sin + z * cos);
        }

        double dot(Vec2 other) {
            return x * other.x + z * other.z;
        }
    }
}
