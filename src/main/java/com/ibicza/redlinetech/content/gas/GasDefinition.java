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

    public GasDefinition {
        effects = List.copyOf(effects);
    }

    public double airDensityRatio() {
        return densityKgM3 / AIR_DENSITY_KG_M3;
    }

    public boolean isLighterThanAir() {
        return airDensityRatio() < 0.85D;
    }

    public boolean isHeavierThanAir() {
        return airDensityRatio() > 1.15D;
    }
}
