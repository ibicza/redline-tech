package com.ibicza.redlinetech.client;

import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.liquid.RegisteredLiquid;
import com.ibicza.redlinetech.registry.ModLiquids;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.fluid.FluidTintSources;

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

    private RedlineClientEvents() {
    }
}