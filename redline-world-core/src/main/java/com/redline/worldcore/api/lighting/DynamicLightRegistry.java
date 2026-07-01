package com.redline.worldcore.api.lighting;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Resolves vanilla/block-item light emission for dynamic light MVP. */
public final class DynamicLightRegistry {
    public static int resolveItemLight(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return 0;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return Math.max(0, Math.min(15, state.getLightEmission()));
    }

    private DynamicLightRegistry() {
    }
}
