package com.ibicza.redlinetech.content.dimension;

public record DimensionEnvironmentDefinition(
        String dimensionId,
        int ambientTemperatureK,
        float pressureKpa,
        boolean evaporationEnabled,
        float evaporationMultiplier,
        boolean gasEscapeEnabled,
        int gasEscapeYMin
) {
    public static DimensionEnvironmentDefinition overworldDefault() {
        return new DimensionEnvironmentDefinition(
                "minecraft:overworld",
                295,
                101.0F,
                true,
                1.0F,
                true,
                200
        );
    }
}
