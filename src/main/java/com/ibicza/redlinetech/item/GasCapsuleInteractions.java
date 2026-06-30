package com.ibicza.redlinetech.item;

import com.ibicza.redlinetech.content.gas.GasCapsuleData;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import com.ibicza.redlinetech.registry.ModGases;
import net.minecraft.core.BlockPos;
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

        // 1. попытка собрать газ
        if (clickedState.getBlock() instanceof RedlineGasBlock gasBlock) {
            return collectGas(level, pos, clickedState, stack);
        }

        // 2. попытка поставить газ
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
        BlockPos targetPos = context.getClickedPos().relative(context.getClickedFace());
        BlockState targetState = level.getBlockState(targetPos);

        int stored = data.amount();
        if (stored <= 0) {
            return InteractionResult.FAIL;
        }

        // если пусто — ставим новый газ
        if (targetState.isAir()) {
            int placed = Math.min(16, stored);
            level.setBlock(
                    targetPos,
                    gas.block().get().defaultBlockState().setValue(RedlineGasBlock.AMOUNT, placed),
                    3
            );

            int left = stored - placed;
            GasCapsuleItem.setData(stack, left <= 0 ? GasCapsuleData.EMPTY : new GasCapsuleData(data.gasId(), left));
            return InteractionResult.SUCCESS;
        }

        // если такой же газ — доливаем
        if (targetState.getBlock() instanceof RedlineGasBlock gasBlock && gasBlock.registeredGas().id().equals(data.gasId())) {
            int current = targetState.getValue(RedlineGasBlock.AMOUNT);
            int free = 16 - current;

            if (free <= 0) {
                return InteractionResult.FAIL;
            }

            int placed = Math.min(free, stored);
            level.setBlock(targetPos, targetState.setValue(RedlineGasBlock.AMOUNT, current + placed), 3);

            int left = stored - placed;
            GasCapsuleItem.setData(stack, left <= 0 ? GasCapsuleData.EMPTY : new GasCapsuleData(data.gasId(), left));
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.FAIL;
    }

    private GasCapsuleInteractions() {
    }
}