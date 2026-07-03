package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
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
 */
public final class CubicTestGameplayGuard {
    private static long canceledMobs;
    private static long canceledFallingBlocks;

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
            canceledFallingBlocks++;
            event.setCanceled(true);
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

    public static void resetCounters() {
        canceledMobs = 0L;
        canceledFallingBlocks = 0L;
    }
}
