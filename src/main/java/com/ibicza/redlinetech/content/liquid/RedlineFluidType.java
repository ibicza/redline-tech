package com.ibicza.redlinetech.content.liquid;

import com.ibicza.redlinetech.RedlineTech;
import net.neoforged.neoforge.fluids.FluidType;

public final class RedlineFluidType extends FluidType {
    private final LiquidDefinition definition;

    public RedlineFluidType(LiquidDefinition definition) {
        super(FluidType.Properties.create()
                .descriptionId("fluid_type." + RedlineTech.MOD_ID + "." + definition.id())
                .canConvertToSource(false)
                .canDrown(true)
                .canSwim(true)
                .canPushEntity(true)
                .canHydrate(false)
                .canExtinguish(definition.temperature() < 600)
                .temperature(definition.temperature())
                .viscosity(Math.max(1000, definition.flowDelayTicks() * 100))
                .density(1000)
        );

        this.definition = definition;
    }

    public LiquidDefinition definition() {
        return definition;
    }
}