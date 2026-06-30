package com.ibicza.redlinetech.registry;



import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.ContentDatabase;
import com.ibicza.redlinetech.content.material.MaterialDefinition;
import com.ibicza.redlinetech.content.material.MaterialItemForm;
import com.ibicza.redlinetech.content.material.RegisteredMaterialItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RedlineTech.MOD_ID);

    private static final List<RegisteredMaterialItem> MUTABLE_MATERIAL_ITEMS = new ArrayList<>();
    public static final List<RegisteredMaterialItem> MATERIAL_ITEMS =
            Collections.unmodifiableList(MUTABLE_MATERIAL_ITEMS);

    static {
        ModBlocks.ORE_BLOCKS.forEach(ore -> {
            DeferredItem<BlockItem> item = ITEMS.registerSimpleBlockItem(ore.blockId(), ore.block());
            ore.setItem(item);
        });

        ModBlocks.MATERIAL_BLOCKS.forEach(materialBlock -> {
            DeferredItem<BlockItem> item = ITEMS.registerSimpleBlockItem(
                    materialBlock.blockId(),
                    materialBlock.block()
            );
            materialBlock.setItem(item);
        });

        ContentDatabase.MATERIALS.forEach(ModItems::registerMaterialItems);
    }

    private static void registerMaterialItems(MaterialDefinition material) {
        if (material.hasIngot()) {
            registerMaterialItem(material, MaterialItemForm.INGOT);
        }

        if (material.hasDust()) {
            registerMaterialItem(material, MaterialItemForm.DUST);
        }

        if (material.hasPlate()) {
            registerMaterialItem(material, MaterialItemForm.PLATE);
        }

        if (material.hasWire()) {
            registerMaterialItem(material, MaterialItemForm.WIRE);
        }

        if (material.hasRod()) {
            registerMaterialItem(material, MaterialItemForm.ROD);
        }

        if (material.hasNugget()) {
            registerMaterialItem(material, MaterialItemForm.NUGGET);
        }
    }

    private static void registerMaterialItem(MaterialDefinition material, MaterialItemForm form) {
        String itemId = material.id() + "_" + form.suffix();
        DeferredItem<Item> item = ITEMS.registerSimpleItem(itemId);

        MUTABLE_MATERIAL_ITEMS.add(new RegisteredMaterialItem(material, form, itemId, item));
    }

    private ModItems() {
    }
}
