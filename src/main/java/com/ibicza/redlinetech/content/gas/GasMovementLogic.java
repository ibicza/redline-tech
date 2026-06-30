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
    public static void tick(ServerLevel level, BlockPos pos, BlockState state, RedlineGasBlock block, RandomSource random) {
        GasDefinition definition = block.definition();
        RegisteredGas gas = ModGases.getByGasId(definition.id());

        if (gas == null) {
            return;
        }

        BlockState currentState = level.getBlockState(pos);
        if (!currentState.is(block)) {
            return;
        }

        if (definition.flammable() && GasIgnitionLogic.hasIgniterNearby(level, pos)) {
            GasIgnitionLogic.explodeGasCloud(level, pos, block);
            return;
        }

        int amount = currentState.getValue(RedlineGasBlock.AMOUNT);
        amount = tryAtmosphereEscape(level, pos, definition, amount, random);

        if (amount <= 0) {
            level.removeBlock(pos, false);
            return;
        }

        if (definition.isLighterThanAir()) {
            amount = tickLayeredGas(level, pos, block, gas, amount, Direction.UP, random);
        } else if (definition.isHeavierThanAir()) {
            amount = tickLayeredGas(level, pos, block, gas, amount, Direction.DOWN, random);
        } else {
            amount = tickNeutralGas(level, pos, block, gas, amount, random);
        }

        writeSelfAndReschedule(level, pos, block, amount);
    }

    private static int tickLayeredGas(
            ServerLevel level,
            BlockPos pos,
            RedlineGasBlock block,
            RegisteredGas gas,
            int amount,
            Direction preferredVertical,
            RandomSource random
    ) {
        VerticalMoveResult verticalMove = tryMovePreferredVertical(
                level,
                pos,
                block,
                gas,
                amount,
                preferredVertical
        );

        if (verticalMove.moved()) {
            return verticalMove.remainingAmount();
        }

        if (verticalMove.blockedByFullSameGas()) {
            BlockPos preferredPos = pos.relative(preferredVertical);
            BlockState preferredState = level.getBlockState(preferredPos);

            if (preferredState.getBlock() instanceof RedlineGasBlock preferredBlock) {
                level.scheduleTick(preferredPos, preferredBlock, gas.definition().spreadDelayTicks());
            }

            return amount;
        }

        return spreadHorizontally(level, pos, gas, amount, random);
    }

    private static int tickNeutralGas(
            ServerLevel level,
            BlockPos pos,
            RedlineGasBlock block,
            RegisteredGas gas,
            int amount,
            RandomSource random
    ) {
        int remaining = spreadHorizontally(level, pos, gas, amount, random);

        if (remaining <= 1) {
            return remaining;
        }

        List<Direction> verticals = new ArrayList<>(List.of(Direction.UP, Direction.DOWN));
        shuffle(verticals, random);

        for (Direction direction : verticals) {
            if (remaining <= 1) {
                break;
            }

            int transfer = Math.min(remaining - 1, 1);
            int accepted = GasCloudSpawner.tryAddGasUnits(level, pos.relative(direction), gas, transfer);
            remaining -= accepted;
        }

        return remaining;
    }

    private static VerticalMoveResult tryMovePreferredVertical(
            ServerLevel level,
            BlockPos pos,
            RedlineGasBlock block,
            RegisteredGas gas,
            int amount,
            Direction direction
    ) {
        BlockPos targetPos = pos.relative(direction);

        if (!level.isLoaded(targetPos)) {
            return VerticalMoveResult.blockedBySolid(amount);
        }

        BlockState targetState = level.getBlockState(targetPos);

        if (targetState.getBlock() instanceof RedlineGasBlock targetGasBlock) {
            if (!targetGasBlock.definition().id().equals(gas.id())) {
                return VerticalMoveResult.blockedBySolid(amount);
            }

            int targetAmount = targetState.getValue(RedlineGasBlock.AMOUNT);
            int space = gas.definition().maxAmount() - targetAmount;

            if (space <= 0) {
                return VerticalMoveResult.blockedByFullSameGas(amount);
            }

            int accepted = Math.min(space, amount);
            level.setBlock(targetPos, targetState.setValue(RedlineGasBlock.AMOUNT, targetAmount + accepted), 3);
            level.scheduleTick(targetPos, targetGasBlock, gas.definition().spreadDelayTicks());
            return VerticalMoveResult.moved(amount - accepted);
        }

        if (!canReplaceWithGas(targetState)) {
            return VerticalMoveResult.blockedBySolid(amount);
        }

        int accepted = Math.min(gas.definition().maxAmount(), amount);
        BlockState newState = gas.block().get().defaultBlockState()
                .setValue(RedlineGasBlock.AMOUNT, accepted);

        level.setBlock(targetPos, newState, 3);
        level.scheduleTick(targetPos, gas.block().get(), gas.definition().spreadDelayTicks());
        return VerticalMoveResult.moved(amount - accepted);
    }

    private static int spreadHorizontally(
            ServerLevel level,
            BlockPos pos,
            RegisteredGas gas,
            int amount,
            RandomSource random
    ) {
        int remaining = amount;
        List<Direction> directions = randomizedHorizontals(random);

        for (Direction direction : directions) {
            if (remaining <= 1) {
                break;
            }

            int transfer = horizontalTransferAmount(remaining, random);
            int accepted = GasCloudSpawner.tryAddGasUnits(level, pos.relative(direction), gas, transfer);
            remaining -= accepted;
        }

        return remaining;
    }

    private static int horizontalTransferAmount(int remaining, RandomSource random) {
        if (remaining <= 2) {
            return 1;
        }

        if (remaining >= 12) {
            return Math.min(remaining - 1, 3 + random.nextInt(4));
        }

        if (remaining >= 6) {
            return Math.min(remaining - 1, 2 + random.nextInt(3));
        }

        return Math.min(remaining - 1, 1 + random.nextInt(2));
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

    private static void writeSelfAndReschedule(ServerLevel level, BlockPos pos, RedlineGasBlock block, int amount) {
        if (amount <= 0) {
            level.removeBlock(pos, false);
            return;
        }

        BlockState currentState = level.getBlockState(pos);

        if (!currentState.is(block)) {
            return;
        }

        int clampedAmount = Math.max(1, Math.min(block.definition().maxAmount(), amount));
        level.setBlock(pos, currentState.setValue(RedlineGasBlock.AMOUNT, clampedAmount), 3);
        level.scheduleTick(pos, block, block.definition().spreadDelayTicks());
    }

    private static boolean canReplaceWithGas(BlockState state) {
        return state.isAir();
    }

    private static List<Direction> randomizedHorizontals(RandomSource random) {
        List<Direction> directions = new ArrayList<>(List.of(
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST
        ));
        shuffle(directions, random);
        return directions;
    }

    private static void shuffle(List<Direction> directions, RandomSource random) {
        for (int i = directions.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Direction tmp = directions.get(i);
            directions.set(i, directions.get(j));
            directions.set(j, tmp);
        }
    }

    private record VerticalMoveResult(
            boolean moved,
            boolean blockedByFullSameGas,
            int remainingAmount
    ) {
        static VerticalMoveResult moved(int remainingAmount) {
            return new VerticalMoveResult(true, false, remainingAmount);
        }

        static VerticalMoveResult blockedByFullSameGas(int remainingAmount) {
            return new VerticalMoveResult(false, true, remainingAmount);
        }

        static VerticalMoveResult blockedBySolid(int remainingAmount) {
            return new VerticalMoveResult(false, false, remainingAmount);
        }
    }

    private GasMovementLogic() {
    }
}