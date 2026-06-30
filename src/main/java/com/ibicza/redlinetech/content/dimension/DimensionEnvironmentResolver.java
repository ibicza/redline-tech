package com.ibicza.redlinetech.content.dimension;

import com.ibicza.redlinetech.content.ContentDatabase;
import net.minecraft.world.level.Level;

public final class DimensionEnvironmentResolver {
    private static final DimensionEnvironmentDefinition DEFAULT = new DimensionEnvironmentDefinition(
            "default",
            295,
            101.0F,
            true,
            1.0F,
            true,
            200
    );

    public static DimensionEnvironmentDefinition get(Level level) {
        String dimensionId = level.dimension().identifier().toString();

        return ContentDatabase.DIMENSION_ENVIRONMENTS_BY_ID.getOrDefault(
                dimensionId,
                DEFAULT
        );
    }

    private DimensionEnvironmentResolver() {
    }
}