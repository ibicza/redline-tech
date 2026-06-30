package com.ibicza.redlinetech.registry;

import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.gas.GasCapsuleData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, RedlineTech.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GasCapsuleData>> GAS_CAPSULE_DATA =
            DATA_COMPONENTS.register("gas_capsule_data", () ->
                    DataComponentType.<GasCapsuleData>builder()
                            .persistent(GasCapsuleData.CODEC)
                            .build()
            );

    private ModDataComponents() {
    }
}