package com.ibicza.redlinetech.content.gas;

import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import com.ibicza.redlinetech.registry.ModGases;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class GasMovementLogic {
    private static final double AIR_DENSITY_KG_M3 = 1.225D;

    public static void tick(ServerLevel level, BlockPos pos, BlockState state, RedlineGasBlock block, RandomSource random) {
        GasDefinition definition = block.definition();
        RegisteredGas gas = ModGases.getByGasId(definition.id());

        if (gas == null) {
            return;
        }

        if (definition.flammable() && GasIgnitionLogic.hasIgniterNearby(level, pos)) {
            GasIgnitionLogic.explodeGasCloud(level, pos, block);
            return;
        }

        int amount = state.getValue(RedlineGasBlock.AMOUNT);

        amount = tryAtmosphereEscape(level, pos, definition, amount, random);

        if (amount <= 0) {
            level.removeBlock(pos, false);
            return;
        }

        SpreadResult spreadResult = spread(level, pos, definition, gas, amount, random);
        amount = spreadResult.remainingAmount();

        if (amount <= 0) {
            level.removeBlock(pos, false);
            return;
        }

        BlockState currentState = level.getBlockState(pos);

        if (currentState.is(block)) {
            level.setBlock(pos, currentState.setValue(RedlineGasBlock.AMOUNT, amount), 3);
            level.scheduleTick(pos, block, definition.spreadDelayTicks());
        }
    }

    private static int tryAtmosphereEscape(
            ServerLevel level,
            BlockPos pos,
            GasDefinition definition,
            int amount,
            RandomSource random
    ) {
        if (!definition.escapeToAtmosphere()) {
            return amount;
        }

        if (pos.getY() < definition.escapeYMin()) {
            return amount;
        }

        if (!isOpenToAtmosphere(level, pos)) {
            return amount;
        }

        if (random.nextFloat() > definition.escapeChance()) {
            return amount;
        }

        return amount - 1;
    }

    private static boolean isOpenToAtmosphere(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();

        for (int step = 1; step <= 16; step++) {
            cursor.set(pos.getX(), pos.getY() + step, pos.getZ());

            if (!level.isLoaded(cursor)) {
                return true;
            }

            BlockState state = level.getBlockState(cursor);

            if (state.isAir()) {
                continue;
            }

            if (state.getBlock() instanceof RedlineGasBlock) {
                continue;
            }

            return false;
        }

        return true;
    }

    private static SpreadResult spread(
            ServerLevel level,
            BlockPos pos,
            GasDefinition definition,
            RegisteredGas gas,
            int amount,
            RandomSource random
    ) {
        int remaining = amount;
        List<Direction> directions = orderedDirections(definition, random);

        for (Direction direction : directions) {
            if (remaining <= 1) {
                break;
            }

            int transfer = transferAmountFor(direction, definition, remaining, random);

            if (transfer <= 0) {
                continue;
            }

            int accepted = GasCloudSpawner.tryAddGasUnits(level, pos.relative(direction), gas, transfer);
            remaining -= accepted;
        }

        return new SpreadResult(remaining);
    }

    private static int transferAmountFor(
            Direction direction,
            GasDefinition definition,
            int remaining,
            RandomSource random
    ) {
        boolean verticalPriority = isPreferredVerticalDirection(direction, definition);
        int maxTransfer = verticalPriority ? 4 : 2;

        if (remaining >= 12 && verticalPriority) {
            maxTransfer = 6;
        }

        if (remaining <= 2) {
            maxTransfer = 1;
        }

        return Math.min(remaining - 1, 1 + random.nextInt(maxTransfer));
    }

    private static boolean isPreferredVerticalDirection(Direction direction, GasDefinition definition) {
        double relativeDensity = definition.densityKgM3() / AIR_DENSITY_KG_M3;

        if (relativeDensity < 0.85D) {
            return direction == Direction.UP;
        }

        if (relativeDensity > 1.15D) {
            return direction == Direction.DOWN;
        }

        return false;
    }

    private static List<Direction> orderedDirections(GasDefinition definition, RandomSource random) {
        List<Direction> result = new ArrayList<>();
        double relativeDensity = definition.densityKgM3() / AIR_DENSITY_KG_M3;

        if (relativeDensity < 0.85D) {
            result.add(Direction.UP);
            addRandomHorizontal(result, random);
            result.add(Direction.DOWN);
            return result;
        }

        if (relativeDensity > 1.15D) {
            result.add(Direction.DOWN);
            addRandomHorizontal(result, random);
            result.add(Direction.UP);
            return result;
        }

        addRandomHorizontal(result, random);

        if (random.nextBoolean()) {
            result.add(Direction.UP);
            result.add(Direction.DOWN);
        } else {
            result.add(Direction.DOWN);
            result.add(Direction.UP);
        }

        return result;
    }

    private static void addRandomHorizontal(List<Direction> result, RandomSource random) {
        List<Direction> horizontal = new ArrayList<>(List.of(
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST
        ));

        while (!horizontal.isEmpty()) {
            int index = random.nextInt(horizontal.size());
            result.add(horizontal.remove(index));
        }
    }

    private record SpreadResult(int remainingAmount) {
    }

    private GasMovementLogic() {
    }
}
