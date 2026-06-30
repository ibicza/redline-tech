package com.ibicza.redlinetech.content.gas;

import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public final class GasIgnitionLogic {
    public static final TagKey<Block> GAS_IGNITERS = TagKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(RedlineTech.MOD_ID, "gas_igniters")
    );

    private static final int MAX_EXPLOSION_GAS_BLOCKS = 512;
    private static final float MAX_EXPLOSION_POWER = 12.0F;

    public static boolean hasIgniterNearby(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (isIgniter(level.getBlockState(pos.relative(direction)))) {
                return true;
            }
        }

        return false;
    }

    public static void explodeGasCloud(ServerLevel level, BlockPos origin, RedlineGasBlock gasBlock) {
        GasDefinition definition = gasBlock.definition();

        if (!definition.flammable()) {
            return;
        }

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin);

        int totalUnits = 0;
        int blocks = 0;

        while (!queue.isEmpty() && blocks < MAX_EXPLOSION_GAS_BLOCKS) {
            BlockPos current = queue.remove();

            if (!level.isLoaded(current)) {
                continue;
            }

            BlockState state = level.getBlockState(current);

            if (!(state.getBlock() instanceof RedlineGasBlock currentGasBlock)) {
                continue;
            }

            if (!currentGasBlock.definition().id().equals(definition.id())) {
                continue;
            }

            totalUnits += state.getValue(RedlineGasBlock.AMOUNT);
            blocks++;
            level.removeBlock(current, false);

            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);

                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        if (totalUnits <= 0) {
            return;
        }

        float normalizedBlocks = totalUnits / 16.0F;
        float power = Math.min(
                MAX_EXPLOSION_POWER,
                definition.explosionPower() * (float) Math.sqrt(normalizedBlocks / 16.0F)
        );

        if (power < 1.0F) {
            power = 1.0F;
        }

        level.explode(
                null,
                origin.getX() + 0.5D,
                origin.getY() + 0.5D,
                origin.getZ() + 0.5D,
                power,
                true,
                Level.ExplosionInteraction.BLOCK
        );
    }

    private static boolean isIgniter(BlockState state) {
        return state.is(GAS_IGNITERS)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.LAVA);
    }

    private GasIgnitionLogic() {
    }
}
