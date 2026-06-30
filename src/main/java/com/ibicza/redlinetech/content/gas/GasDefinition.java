package com.ibicza.redlinetech.content.gas;

import java.util.List;

public record GasDefinition(
        String id,
        String ruName,
        String enName,
        int color,
        int alpha,
        double densityKgM3,
        int spreadDelayTicks,
        int maxAmount,
        GasRenderMode renderMode,
        boolean flammable,
        float explosionPower,
        boolean escapeToAtmosphere,
        int escapeYMin,
        float escapeChance,
        List<GasEffectEntry> effects
) {
    private static final double AIR_DENSITY_KG_M3 = 1.225D;

    /*
     * Порог специально мягкий.
     *
     * Старые 0.85 / 1.15 были слишком грубыми:
     * сероводород 1.36 кг/м3 тяжелее воздуха, но ratio = 1.11,
     * из-за чего он ошибочно считался нейтральным.
     */
    private static final double LIGHT_GAS_RATIO = 0.95D;
    private static final double HEAVY_GAS_RATIO = 1.05D;

    public GasDefinition {
        effects = List.copyOf(effects);
    }

    public double airDensityRatio() {
        return densityKgM3 / AIR_DENSITY_KG_M3;
    }

    public boolean isLighterThanAir() {
        if (renderMode == GasRenderMode.CEILING_LAYER) {
            return true;
        }

        if (renderMode == GasRenderMode.FLOOR_LAYER) {
            return false;
        }

        return airDensityRatio() < LIGHT_GAS_RATIO;
    }

    public boolean isHeavierThanAir() {
        if (renderMode == GasRenderMode.FLOOR_LAYER) {
            return true;
        }

        if (renderMode == GasRenderMode.CEILING_LAYER) {
            return false;
        }

        return airDensityRatio() > HEAVY_GAS_RATIO;
    }

    public boolean isNeutralGas() {
        return !isLighterThanAir() && !isHeavierThanAir();
    }
}