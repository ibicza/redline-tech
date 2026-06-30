package com.ibicza.redlinetech.registry;


import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.ContentDatabase;
import com.ibicza.redlinetech.content.material.MaterialDefinition;
import com.ibicza.redlinetech.content.material.RegisteredMaterialBlock;
import com.ibicza.redlinetech.content.ore.OreBlockVariant;
import com.ibicza.redlinetech.content.ore.OreLikeDefinition;
import com.ibicza.redlinetech.content.ore.RegisteredOreBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RedlineTech.MOD_ID);

    private static final List<RegisteredOreBlock> MUTABLE_ORE_BLOCKS = new ArrayList<>();
    public static final List<RegisteredOreBlock> ORE_BLOCKS = Collections.unmodifiableList(MUTABLE_ORE_BLOCKS);

    private static final List<RegisteredMaterialBlock> MUTABLE_MATERIAL_BLOCKS = new ArrayList<>();
    public static final List<RegisteredMaterialBlock> MATERIAL_BLOCKS =
            Collections.unmodifiableList(MUTABLE_MATERIAL_BLOCKS);

    static {
        ContentDatabase.ALL_ORES.forEach(ModBlocks::registerOreVariants);
        ContentDatabase.MATERIALS.forEach(ModBlocks::registerMaterialBlockIfNeeded);
    }

    private static void registerOreVariants(OreLikeDefinition definition) {
        if (definition.hasStoneVariant()) {
            registerOreBlock(definition, OreBlockVariant.STONE, definition.id(), definition.hardness());
        }

        if (definition.hasDeepslateVariant()) {
            registerOreBlock(
                    definition,
                    OreBlockVariant.DEEPSLATE,
                    "deepslate_" + definition.id(),
                    definition.hardness() * 1.5F
            );
        }
    }

    private static void registerOreBlock(
            OreLikeDefinition definition,
            OreBlockVariant variant,
            String blockId,
            float hardness
    ) {
        DeferredBlock<Block> block = BLOCKS.registerSimpleBlock(blockId, properties -> properties
                .mapColor(MapColor.STONE)
                .strength(hardness, definition.resistance())
                .requiresCorrectToolForDrops()
                .sound(SoundType.STONE)
        );

        MUTABLE_ORE_BLOCKS.add(new RegisteredOreBlock(definition, variant, blockId, block));
    }

    private static void registerMaterialBlockIfNeeded(MaterialDefinition material) {
        if (!material.hasBlock()) {
            return;
        }

        String blockId = material.id() + "_block";

        DeferredBlock<Block> block = BLOCKS.registerSimpleBlock(blockId, properties -> properties
                .mapColor(MapColor.METAL)
                .strength(5.0F, 6.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.METAL)
        );

        MUTABLE_MATERIAL_BLOCKS.add(new RegisteredMaterialBlock(material, blockId, block));
    }

    private ModBlocks() {
    }
}