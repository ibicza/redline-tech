package com.ibicza.redlinetech.content.liquid;

import com.ibicza.redlinetech.content.block.RedlineLiquidBlock;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public record RegisteredLiquid(
        LiquidDefinition definition,
        DeferredHolder<FluidType, RedlineFluidType> fluidType,
        DeferredHolder<Fluid, BaseFlowingFluid.Source> sourceFluid,
        DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowingFluid,
        DeferredBlock<RedlineLiquidBlock> block,
        DeferredItem<BucketItem> bucketItem
) {
    public String id() {
        return definition.id();
    }

    public String flowingId() {
        return "flowing_" + definition.id();
    }

    public String bucketItemId() {
        return definition.id() + "_bucket";
    }

    public String stillTexturePath() {
        return "block/fluid/" + definition.id() + "_still";
    }

    public String flowingTexturePath() {
        return "block/fluid/" + definition.id() + "_flow";
    }
}