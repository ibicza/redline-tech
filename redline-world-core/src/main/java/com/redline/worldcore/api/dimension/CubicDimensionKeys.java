package com.redline.worldcore.api.dimension;

import com.redline.worldcore.RedlineWorldCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Common keys for Redline World Core cubic dimensions. */
public final class CubicDimensionKeys {
    public static final Identifier CUBIC_TEST_ID = Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cubic_test");

    /**
     * M4 test dimension key. The actual level is data-driven through
     * data/redline_world_core/dimension/cubic_test.json.
     */
    public static final ResourceKey<Level> CUBIC_TEST_LEVEL = ResourceKey.create(Registries.DIMENSION, CUBIC_TEST_ID);

    public static boolean isCubicTest(Level level) {
        return level != null && level.dimension().equals(CUBIC_TEST_LEVEL);
    }

    private CubicDimensionKeys() {
    }
}
