package com.ibicza.redlinetech.content.ore;


import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

public final class RegisteredOreBlock {
    private final OreLikeDefinition definition;
    private final OreBlockVariant variant;
    private final String blockId;
    private final DeferredBlock<Block> block;
    private DeferredItem<BlockItem> item;

    public RegisteredOreBlock(
            OreLikeDefinition definition,
            OreBlockVariant variant,
            String blockId,
            DeferredBlock<Block> block
    ) {
        this.definition = definition;
        this.variant = variant;
        this.blockId = blockId;
        this.block = block;
    }

    public OreLikeDefinition definition() {
        return definition;
    }

    public OreBlockVariant variant() {
        return variant;
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