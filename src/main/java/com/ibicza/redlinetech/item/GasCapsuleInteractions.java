package com.ibicza.redlinetech.item;

import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import com.ibicza.redlinetech.content.gas.GasCapsuleData;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import com.ibicza.redlinetech.registry.ModGases;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class GasCapsuleInteractions {
    public static InteractionResult useOn(UseOnContext context, ItemStack stack, GasCapsuleItem item) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(pos);

        if (clickedState.getBlock() instanceof RedlineGasBlock) {
            return collectGas(level, pos, clickedState, stack);
        }

        return placeGas(context, stack);
    }

    private static InteractionResult collectGas(Level level, BlockPos pos, BlockState state, ItemStack stack) {
        GasCapsuleData data = GasCapsuleItem.getData(stack);

        if (!(state.getBlock() instanceof RedlineGasBlock gasBlock)) {
            return InteractionResult.PASS;
        }

        RegisteredGas gas = gasBlock.registeredGas();
        int blockAmount = state.getValue(RedlineGasBlock.AMOUNT);

        if (!data.isEmpty() && !data.gasId().equals(gas.id())) {
            return InteractionResult.FAIL;
        }

        int currentAmount = data.isEmpty() ? 0 : data.amount();
        int free = GasCapsuleItem.MAX_AMOUNT - currentAmount;

        if (free <= 0) {
            return InteractionResult.FAIL;
        }

        int transfer = Math.min(free, blockAmount);
        int remaining = blockAmount - transfer;

        if (remaining <= 0) {
            level.removeBlock(pos, false);
        } else {
            level.setBlock(pos, state.setValue(RedlineGasBlock.AMOUNT, remaining), 3);
        }

        GasCapsuleItem.setData(stack, new GasCapsuleData(gas.id(), currentAmount + transfer));
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult placeGas(UseOnContext context, ItemStack stack) {
        GasCapsuleData data = GasCapsuleItem.getData(stack);
        if (data.isEmpty()) {
            return InteractionResult.FAIL;
        }

        RegisteredGas gas = ModGases.GASES_BY_ID.get(data.gasId());
        if (gas == null) {
            return InteractionResult.FAIL;
        }

        Level level = context.getLevel();
        BlockPos rawTargetPos = context.getClickedPos().relative(context.getClickedFace());
        BlockPos targetPos = resolveStablePlacementPos(level, rawTargetPos, gas);
        BlockState targetState = level.getBlockState(targetPos);

        int stored = data.amount();
        if (stored <= 0) {
            return InteractionResult.FAIL;
        }

        if (targetState.isAir()) {
            int placed = Math.min(gas.definition().maxAmount(), stored);

            level.setBlock(
                    targetPos,
                    gas.block().get().defaultBlockState().setValue(RedlineGasBlock.AMOUNT, placed),
                    3
            );

            level.scheduleTick(targetPos, gas.block().get(), gas.definition().spreadDelayTicks());

            int left = stored - placed;
            GasCapsuleItem.setData(stack, left <= 0 ? GasCapsuleData.EMPTY : new GasCapsuleData(data.gasId(), left));
            return InteractionResult.SUCCESS;
        }

        if (isSameGas(targetState, gas)) {
            int current = targetState.getValue(RedlineGasBlock.AMOUNT);
            int free = gas.definition().maxAmount() - current;

            if (free <= 0) {
                return InteractionResult.FAIL;
            }

            int placed = Math.min(free, stored);
            level.setBlock(targetPos, targetState.setValue(RedlineGasBlock.AMOUNT, current + placed), 3);

            if (targetState.getBlock() instanceof RedlineGasBlock gasBlock) {
                level.scheduleTick(targetPos, gasBlock, gas.definition().spreadDelayTicks());
            }

            int left = stored - placed;
            GasCapsuleItem.setData(stack, left <= 0 ? GasCapsuleData.EMPTY : new GasCapsuleData(data.gasId(), left));
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.FAIL;
    }

    private static BlockPos resolveStablePlacementPos(Level level, BlockPos startPos, RegisteredGas gas) {
        Direction preferredDirection = preferredVerticalDirection(gas);

        if (preferredDirection == null) {
            return startPos;
        }

        BlockPos current = startPos;
        BlockPos best = canReceiveGas(level.getBlockState(current), gas) ? current : startPos;

        for (int step = 0; step < 16; step++) {
            BlockPos next = current.relative(preferredDirection);
            BlockState nextState = level.getBlockState(next);

            if (nextState.isAir()) {
                best = next;
                current = next;
                continue;
            }

            if (isSameGas(nextState, gas)) {
                int amount = nextState.getValue(RedlineGasBlock.AMOUNT);

                if (amount < gas.definition().maxAmount()) {
                    best = next;
                }

                current = next;
                continue;
            }

            break;
        }

        return best;
    }

    private static Direction preferredVerticalDirection(RegisteredGas gas) {
        if (gas.definition().isLighterThanAir()) {
            return Direction.UP;
        }

        if (gas.definition().isHeavierThanAir()) {
            return Direction.DOWN;
        }

        return null;
    }

    private static boolean canReceiveGas(BlockState state, RegisteredGas gas) {
        if (state.isAir()) {
            return true;
        }

        if (!isSameGas(state, gas)) {
            return false;
        }

        return state.getValue(RedlineGasBlock.AMOUNT) < gas.definition().maxAmount();
    }

    private static boolean isSameGas(BlockState state, RegisteredGas gas) {
        if (!(state.getBlock() instanceof RedlineGasBlock gasBlock)) {
            return false;
        }

        return gasBlock.definition().id().equals(gas.definition().id());
    }

    private GasCapsuleInteractions() {
    }
}