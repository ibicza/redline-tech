package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Temporary M15 guard for the physical vanilla shell used by cubic_test.
 *
 * <p>Until cube-native ticking/spawn rules replace vanilla gameplay systems, natural mobs and falling-block entities in
 * the shell can interact with air gaps at not-yet-materialized cube borders. That causes suffocation spam, falling sand
 * duplication and water-edge updates. The cube backend still owns terrain; this guard only suppresses vanilla gameplay
 * entities that would mutate the temporary mirror.</p>
 *
 * <p>M16 relaxes falling blocks: static worldgen sand may fall after real interaction, but not while the physical shell
 * is materializing or when the destination cube is not materialized yet.</p>
 */
public final class CubicTestGameplayGuard {
    private static long canceledMobs;
    private static long canceledFallingBlocks;
    private static long allowedFallingBlocks;

    private CubicTestGameplayGuard() {
    }

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof ServerPlayer) {
            return;
        }
        if (entity instanceof FallingBlockEntity) {
            if (shouldCancelFallingBlock(entity)) {
                canceledFallingBlocks++;
                event.setCanceled(true);
            } else {
                allowedFallingBlocks++;
            }
            return;
        }
        if (entity instanceof Mob) {
            canceledMobs++;
            event.setCanceled(true);
        }
    }

    public static long canceledMobs() {
        return canceledMobs;
    }

    public static long canceledFallingBlocks() {
        return canceledFallingBlocks;
    }

    public static long allowedFallingBlocks() {
        return allowedFallingBlocks;
    }

    private static boolean shouldCancelFallingBlock(Entity entity) {
        if (CubicClientSyncBridge.materializationInProgress()) {
            return true;
        }
        BlockPos pos = entity.blockPosition();
        CubePos currentCube = CubePos.fromBlock(pos);
        CubePos belowCube = CubePos.fromBlock(pos.below());
        return !CubicClientSyncBridge.isMaterializedForAnyPlayer(currentCube)
                || !CubicClientSyncBridge.isMaterializedForAnyPlayer(belowCube);
    }

    public static void resetCounters() {
        canceledMobs = 0L;
        canceledFallingBlocks = 0L;
        allowedFallingBlocks = 0L;
    }
}
