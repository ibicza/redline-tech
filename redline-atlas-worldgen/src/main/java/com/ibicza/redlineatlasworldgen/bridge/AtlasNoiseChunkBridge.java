package com.ibicza.redlineatlasworldgen.bridge;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public interface AtlasNoiseChunkBridge {
    void redlineAtlasWorldgen$setAtlasDimension(ResourceKey<Level> dimension);
}
