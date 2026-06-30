package com.ibicza.redlinetech.registry;

import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.ContentDatabase;
import com.ibicza.redlinetech.content.block.RedlineLiquidBlock;
import com.ibicza.redlinetech.content.liquid.LiquidDefinition;
import com.ibicza.redlinetech.content.liquid.RedlineFluidType;
import com.ibicza.redlinetech.content.liquid.RegisteredLiquid;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModLiquids {
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, RedlineTech.MOD_ID);

    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, RedlineTech.MOD_ID);

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(RedlineTech.MOD_ID);

    public static final DeferredRegister.Items BUCKET_ITEMS =
            DeferredRegister.createItems(RedlineTech.MOD_ID);

    private static final List<RegisteredLiquid> MUTABLE_LIQUIDS = new ArrayList<>();

    public static final List<RegisteredLiquid> LIQUIDS =
            Collections.unmodifiableList(MUTABLE_LIQUIDS);

    static {
        ContentDatabase.LIQUIDS.forEach(ModLiquids::registerLiquid);
    }

    @SuppressWarnings("unchecked")
    private static void registerLiquid(LiquidDefinition definition) {
        String id = definition.id();
        String flowingId = "flowing_" + id;
        String bucketId = id + "_bucket";

        DeferredHolder<FluidType, RedlineFluidType> fluidType =
                FLUID_TYPES.register(id, () -> new RedlineFluidType(definition));

        DeferredHolder<Fluid, BaseFlowingFluid.Source>[] sourceRef = new DeferredHolder[1];
        DeferredHolder<Fluid, BaseFlowingFluid.Flowing>[] flowingRef = new DeferredHolder[1];
        DeferredBlock<RedlineLiquidBlock>[] blockRef = new DeferredBlock[1];
        DeferredItem<BucketItem>[] bucketRef = new DeferredItem[1];

        sourceRef[0] = FLUIDS.register(
                id,
                () -> new BaseFlowingFluid.Source(fluidProperties(
                        definition,
                        fluidType,
                        sourceRef[0],
                        flowingRef[0],
                        blockRef[0],
                        bucketRef[0]
                ))
        );

        flowingRef[0] = FLUIDS.register(
                flowingId,
                () -> new BaseFlowingFluid.Flowing(fluidProperties(
                        definition,
                        fluidType,
                        sourceRef[0],
                        flowingRef[0],
                        blockRef[0],
                        bucketRef[0]
                ))
        );

        blockRef[0] = BLOCKS.registerBlock(
                id,
                properties -> new RedlineLiquidBlock(
                        sourceRef[0].get(),
                        definition,
                        properties
                ),
                ModLiquids::liquidBlockProperties
        );

        bucketRef[0] = BUCKET_ITEMS.registerItem(
                bucketId,
                properties -> new BucketItem(
                        sourceRef[0].get(),
                        properties
                                .craftRemainder(Items.BUCKET)
                                .stacksTo(1)
                )
        );

        MUTABLE_LIQUIDS.add(new RegisteredLiquid(
                definition,
                fluidType,
                sourceRef[0],
                flowingRef[0],
                blockRef[0],
                bucketRef[0]
        ));
    }

    private static BaseFlowingFluid.Properties fluidProperties(
            LiquidDefinition definition,
            DeferredHolder<FluidType, RedlineFluidType> fluidType,
            DeferredHolder<Fluid, BaseFlowingFluid.Source> source,
            DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing,
            DeferredBlock<RedlineLiquidBlock> block,
            DeferredItem<BucketItem> bucket
    ) {
        return new BaseFlowingFluid.Properties(fluidType, source, flowing)
                .block(block)
                .bucket(bucket)
                .slopeFindDistance(definition.flowDistance())
                .levelDecreasePerBlock(1)
                .tickRate(definition.flowDelayTicks())
                .explosionResistance(100.0F);
    }

    private static BlockBehaviour.Properties liquidBlockProperties() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)
                .noLootTable()
                .noCollision()
                .noOcclusion()
                .replaceable()
                .liquid()
                .pushReaction(PushReaction.DESTROY);
    }

    private ModLiquids() {
    }
}