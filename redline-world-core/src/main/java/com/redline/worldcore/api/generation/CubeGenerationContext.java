package com.redline.worldcore.api.generation;

/** Immutable inputs shared by the cube-first generation pipeline. */
public record CubeGenerationContext(
        CubicDimensionSettings settings,
        long seed
) {
    public CubeGenerationContext {
        if (settings == null) {
            throw new NullPointerException("settings");
        }
    }
}
