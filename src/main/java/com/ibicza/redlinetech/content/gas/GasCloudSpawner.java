package com.ibicza.redlinetech.content.gas;

import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public final class GasCloudSpawner {
    private static final int MAX_SPAWN_RADIUS = 18;
    private static final int MAX_VISITED_POSITIONS = 4096;

    public static int spawnGasUnits(
            ServerLevel level,
            BlockPos origin,
            RegisteredGas gas,
            int gasUnits
    ) {
        if (gasUnits <= 0) {
            return 0;
        }

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin);

        int remaining = gasUnits;
        int placed = 0;

        while (!queue.isEmpty() && remaining > 0 && visited.size() <= MAX_VISITED_POSITIONS) {
            BlockPos current = queue.remove();

            if (!level.isLoaded(current)) {
                continue;
            }

            int accepted = tryAddGasUnits(level, current, gas, remaining);

            if (accepted > 0) {
                remaining -= accepted;
                placed += accepted;
            }

            if (distanceChebyshev(origin, current) >= MAX_SPAWN_RADIUS) {
                continue;
            }

            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);

                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        return placed;
    }

    public static int tryAddGasUnits(
            ServerLevel level,
            BlockPos pos,
            RegisteredGas gas,
            int units
    ) {
        if (units <= 0 || !level.isLoaded(pos)) {
            return 0;
        }

        BlockState state = level.getBlockState(pos);
        int maxAmount = gas.definition().maxAmount();

        if (state.getBlock() instanceof RedlineGasBlock gasBlock) {
            if (!gasBlock.definition().id().equals(gas.definition().id())) {
                return 0;
            }

            int amount = state.getValue(RedlineGasBlock.AMOUNT);
            int space = maxAmount - amount;

            if (space <= 0) {
                return 0;
            }

            int accepted = Math.min(space, units);
            BlockState newState = state.setValue(RedlineGasBlock.AMOUNT, amount + accepted);
            level.setBlock(pos, newState, 3);
            level.scheduleTick(pos, gasBlock, gas.definition().spreadDelayTicks());
            return accepted;
        }

        if (!canReplaceWithGas(state)) {
            return 0;
        }

        int accepted = Math.min(maxAmount, units);
        BlockState newState = gas.block().get().defaultBlockState()
                .setValue(RedlineGasBlock.AMOUNT, accepted);

        level.setBlock(pos, newState, 3);
        level.scheduleTick(pos, gas.block().get(), gas.definition().spreadDelayTicks());
        return accepted;
    }

    private static boolean canReplaceWithGas(BlockState state) {
        return state.isAir();
    }

    private static int distanceChebyshev(BlockPos a, BlockPos b) {
        return Math.max(
                Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())),
                Math.abs(a.getZ() - b.getZ())
        );
    }

    private GasCloudSpawner() {
    }
}
