package com.ibicza.redlinetech;


import com.ibicza.redlinetech.content.ContentDatabase;
import com.ibicza.redlinetech.datagen.ModDataGenerators;
import com.ibicza.redlinetech.registry.*;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(RedlineTech.MOD_ID)
public final class RedlineTech {
    public static final String MOD_ID = "redline_tech";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RedlineTech(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);

        ModEffects.EFFECTS.register(modEventBus);

        ModLiquids.FLUID_TYPES.register(modEventBus);
        ModLiquids.FLUIDS.register(modEventBus);
        ModLiquids.BLOCKS.register(modEventBus);
        ModLiquids.BUCKET_ITEMS.register(modEventBus);
        ModGases.BLOCKS.register(modEventBus);
        ModDataComponents.DATA_COMPONENTS.register(modEventBus);


        ModCreativeTabs.CREATIVE_TABS.register(modEventBus);
        
        modEventBus.addListener(ModDataGenerators::gatherData);

        LOGGER.info("Loading Redline Tech");
        LOGGER.info("Loaded ore blocks: {}", ModBlocks.ORE_BLOCKS.size());
        LOGGER.info("Loaded material items: {}", ModItems.MATERIAL_ITEMS.size());
        LOGGER.info("Loaded material blocks: {}", ModBlocks.MATERIAL_BLOCKS.size());
        LOGGER.info("Loaded liquids: {}", ContentDatabase.LIQUIDS.size());
        LOGGER.info("Loaded gas blocks: {}", ModGases.GASES.size());
    }
}
