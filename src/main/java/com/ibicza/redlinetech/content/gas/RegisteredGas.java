package com.ibicza.redlinetech.content.gas;

import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import net.neoforged.neoforge.registries.DeferredBlock;

public record RegisteredGas(
        GasDefinition definition,
        DeferredBlock<RedlineGasBlock> block
) {
    public String id() {
        return definition.id();
    }

    public String blockId() {
        return definition.id() + "_gas";
    }

    public String texturePath() {
        return "block/gas/" + blockId();
    }
}