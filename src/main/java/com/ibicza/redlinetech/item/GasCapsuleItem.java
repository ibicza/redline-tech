package com.ibicza.redlinetech.item;

import com.ibicza.redlinetech.content.ContentDatabase;
import com.ibicza.redlinetech.content.gas.GasCapsuleData;
import com.ibicza.redlinetech.content.gas.GasDefinition;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import com.ibicza.redlinetech.registry.ModDataComponents;
import com.ibicza.redlinetech.registry.ModGases;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class GasCapsuleItem extends Item {
    public static final int MAX_AMOUNT = 16;

    public GasCapsuleItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static GasCapsuleData getData(ItemStack stack) {
        GasCapsuleData data = stack.get(ModDataComponents.GAS_CAPSULE_DATA.get());
        return data == null ? GasCapsuleData.EMPTY : data;
    }

    public static void setData(ItemStack stack, GasCapsuleData data) {
        if (data == null || data.isEmpty()) {
            stack.remove(ModDataComponents.GAS_CAPSULE_DATA.get());
            return;
        }
        stack.set(ModDataComponents.GAS_CAPSULE_DATA.get(), data);
    }

    public static ItemStack createFilled(Item item, String gasId, int amount) {
        ItemStack stack = new ItemStack(item);
        setData(stack, new GasCapsuleData(gasId, Math.max(0, Math.min(MAX_AMOUNT, amount))));
        return stack;
    }

    @Override
    public Component getName(ItemStack stack) {
        GasCapsuleData data = getData(stack);

        if (data.isEmpty()) {
            return Component.literal("Газовая капсула");
        }

        GasDefinition gas = ContentDatabase.GASES_BY_ID.get(data.gasId());
        if (gas == null) {
            return Component.literal("Газовая капсула");
        }

        return Component.literal("Газовая капсула: " + gas.ruName());
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        GasCapsuleData data = getData(stack);
        return Math.round(13.0F * data.amount() / (float) MAX_AMOUNT);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        GasCapsuleData data = getData(stack);

        if (data.isEmpty()) {
            return 0xFF777777;
        }

        GasDefinition gas = ContentDatabase.GASES_BY_ID.get(data.gasId());
        return gas == null ? 0xFF777777 : gas.color();
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay tooltipDisplay,
            Consumer<Component> tooltip,
            TooltipFlag flag
    ) {
        GasCapsuleData data = getData(stack);

        if (data.isEmpty()) {
            tooltip.accept(Component.literal("Пустая").withStyle(ChatFormatting.GRAY));
            tooltip.accept(Component.literal("Заполненность: 0/16").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        GasDefinition gas = ContentDatabase.GASES_BY_ID.get(data.gasId());
        String gasName = gas == null ? data.gasId() : gas.ruName();

        tooltip.accept(Component.literal("Газ: " + gasName).withStyle(ChatFormatting.AQUA));
        tooltip.accept(Component.literal("Заполненность: " + data.amount() + "/16").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return GasCapsuleInteractions.useOn(context, context.getItemInHand(), this);
    }
}