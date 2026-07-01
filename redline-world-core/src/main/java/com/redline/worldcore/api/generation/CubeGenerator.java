package com.redline.worldcore.api.generation;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;

/** Cube-first terrain generator. It receives exactly one 16x16x16 CubePos and returns one generated cube. */
public interface CubeGenerator {
    LevelCube generate(CubeGenerationContext context, CubePos cubePos);
}
