package com.ibicza.redlinetech.content.liquid;

import java.util.List;

public record LiquidDefinition(
        String id,
        String ruName,
        String enName,
        int color,
        int alpha,
        int flowDistance,
        int flowDelayTicks,
        int temperature,
        double densityKgM3,
        int boilingPointK,
        LiquidEvaporationMode evaporationMode,
        double gasExpansionScale,
        int maxGasUnitsFromOneBlock,
        String evaporatesToGasId,
        List<LiquidEffectEntry> effects
) {
    public LiquidDefinition {
        effects = List.copyOf(effects);
    }

    public boolean evaporatesToGas() {
        return !evaporatesToGasId.isBlank();
    }

    public boolean isCryogenic() {
        return temperature <= 160;
    }

    public boolean isCold() {
        return temperature <= 273;
    }

    public boolean isHot() {
        return temperature >= 373;
    }

    public boolean isIgnitingHot() {
        return temperature >= 600;
    }
}
