package com.ibicza.redlinetech.client;

import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import com.ibicza.redlinetech.content.liquid.RegisteredLiquid;
import com.ibicza.redlinetech.registry.ModGases;
import com.ibicza.redlinetech.registry.ModLiquids;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.fluid.FluidTintSources;

import java.util.List;

@EventBusSubscriber(
        modid = RedlineTech.MOD_ID,
        value = Dist.CLIENT
)
public final class RedlineClientEvents {
    @SubscribeEvent
    public static void registerFluidModels(RegisterFluidModelsEvent event) {
        for (RegisteredLiquid liquid : ModLiquids.LIQUIDS) {
            Material stillMaterial = new Material(
                    Identifier.fromNamespaceAndPath(
                            RedlineTech.MOD_ID,
                            liquid.stillTexturePath()
                    ),
                    true
            );

            Material flowingMaterial = new Material(
                    Identifier.fromNamespaceAndPath(
                            RedlineTech.MOD_ID,
                            liquid.flowingTexturePath()
                    ),
                    true
            );

            FluidModel.Unbaked model = new FluidModel.Unbaked(
                    stillMaterial,
                    flowingMaterial,
                    null,
                    FluidTintSources.constant(0xFFFFFFFF)
            );

            event.register(
                    model,
                    liquid.sourceFluid(),
                    liquid.flowingFluid()
            );
        }
    }

    @SubscribeEvent
    public static void registerBlockTintSources(RegisterColorHandlersEvent.BlockTintSources event) {
        for (RegisteredGas gas : ModGases.GASES) {
            BlockTintSource tintSource = state -> gasColor(gas);

            event.register(
                    List.of(tintSource),
                    gas.block().get()
            );
        }
    }

    private static int gasColor(RegisteredGas gas) {
        return 0xFF000000 | gas.definition().color();
    }

    private RedlineClientEvents() {
    }
}