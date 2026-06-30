package com.ibicza.redlinetech.content.material;


import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

public final class RegisteredMaterialBlock {
    private final MaterialDefinition material;
    private final String blockId;
    private final DeferredBlock<Block> block;
    private DeferredItem<BlockItem> item;

    public RegisteredMaterialBlock(
            MaterialDefinition material,
            String blockId,
            DeferredBlock<Block> block
    ) {
        this.material = material;
        this.blockId = blockId;
        this.block = block;
    }

    public MaterialDefinition material() {
        return material;
    }

    public String blockId() {
        return blockId;
    }

    public DeferredBlock<Block> block() {
        return block;
    }

    public DeferredItem<BlockItem> item() {
        return item;
    }

    public void setItem(DeferredItem<BlockItem> item) {
        this.item = item;
    }
}
