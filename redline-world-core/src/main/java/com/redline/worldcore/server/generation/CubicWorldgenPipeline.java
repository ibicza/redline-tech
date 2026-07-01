package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.generation.CubeGenerationContext;
import com.redline.worldcore.api.generation.CubeGenerator;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;

/** M7 entry point for cube-first generation. */
public final class CubicWorldgenPipeline {
    private final CubeGenerationContext context;
    private final CubeGenerator generator;

    public CubicWorldgenPipeline(CubicDimensionSettings settings, long seed) {
        this(new CubeGenerationContext(settings, seed), new BasicCubicGenerator());
    }

    public CubicWorldgenPipeline(CubeGenerationContext context, CubeGenerator generator) {
        this.context = context;
        this.generator = generator;
    }

    public LevelCube generate(CubePos cubePos) {
        if (!context.settings().containsCubeY(cubePos.y())) {
            throw new IllegalArgumentException("Cube Y is outside cubic dimension settings: " + cubePos);
        }
        return generator.generate(context, cubePos);
    }

    public CubeGenerationContext context() {
        return context;
    }
}
