package com.ibicza.redlinetech.registry;


import com.ibicza.redlinetech.RedlineTech;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RedlineTech.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.redline_tech.main"))
                    .withTabsBefore(CreativeModeTabs.BUILDING_BLOCKS)
                    .icon(() -> {
                        if (!ModBlocks.ORE_BLOCKS.isEmpty()) {
                            return ModBlocks.ORE_BLOCKS.get(0).item().get().getDefaultInstance();
                        }

                        if (!ModItems.MATERIAL_ITEMS.isEmpty()) {
                            return ModItems.MATERIAL_ITEMS.get(0).item().get().getDefaultInstance();
                        }

                        return net.minecraft.world.item.Items.STONE.getDefaultInstance();
                    })
                    .displayItems((parameters, output) -> {
                        ModBlocks.ORE_BLOCKS.forEach(ore -> output.accept(ore.item().get()));
                        ModItems.MATERIAL_ITEMS.forEach(materialItem -> output.accept(materialItem.item().get()));
                        ModBlocks.MATERIAL_BLOCKS.forEach(materialBlock -> output.accept(materialBlock.item().get()));
                    })
                    .build()
            );

    private ModCreativeTabs() {
    }
}
