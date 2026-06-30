package com.ibicza.redlinetech.registry;

import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.ContentDatabase;
import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import com.ibicza.redlinetech.content.gas.GasDefinition;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModGases {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(RedlineTech.MOD_ID);

    private static final List<RegisteredGas> MUTABLE_GASES = new ArrayList<>();
    private static final Map<String, RegisteredGas> MUTABLE_GASES_BY_ID = new LinkedHashMap<>();

    public static final List<RegisteredGas> GASES =
            Collections.unmodifiableList(MUTABLE_GASES);

    public static final Map<String, RegisteredGas> GASES_BY_ID =
            Collections.unmodifiableMap(MUTABLE_GASES_BY_ID);

    static {
        ContentDatabase.GASES.forEach(ModGases::registerGas);
    }

    public static RegisteredGas getByGasId(String gasId) {
        return GASES_BY_ID.get(gasId);
    }

    private static void registerGas(GasDefinition definition) {
        String blockId = definition.id() + "_gas";

        DeferredBlock<RedlineGasBlock> block = BLOCKS.registerBlock(
                blockId,
                properties -> new RedlineGasBlock(definition, properties),
                ModGases::gasBlockProperties
        );

        RegisteredGas registeredGas = new RegisteredGas(definition, block);
        MUTABLE_GASES.add(registeredGas);
        MUTABLE_GASES_BY_ID.put(definition.id(), registeredGas);
    }

    private static BlockBehaviour.Properties gasBlockProperties() {
        return BlockBehaviour.Properties.of()
                .noLootTable()
                .noCollision()
                .noOcclusion()
                .replaceable()
                .destroyTime(-1.0F)
                .explosionResistance(3_600_000.0F)
                .sound(SoundType.EMPTY)
                .isViewBlocking((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false);
    }

    private ModGases() {
    }
}