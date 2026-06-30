package com.ibicza.redlinetech.content.material;


import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;

public final class RegisteredMaterialItem {
    private final MaterialDefinition material;
    private final MaterialItemForm form;
    private final String itemId;
    private final DeferredItem<Item> item;

    public RegisteredMaterialItem(
            MaterialDefinition material,
            MaterialItemForm form,
            String itemId,
            DeferredItem<Item> item
    ) {
        this.material = material;
        this.form = form;
        this.itemId = itemId;
        this.item = item;
    }

    public MaterialDefinition material() {
        return material;
    }

    public MaterialItemForm form() {
        return form;
    }

    public String itemId() {
        return itemId;
    }

    public DeferredItem<Item> item() {
        return item;
    }
}
